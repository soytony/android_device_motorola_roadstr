/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.motorola.display;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.AmbientLuxProcessor;
import android.os.Handler;
import android.os.SystemProperties;
import android.util.Slog;

import java.util.ArrayDeque;

/**
 * Device-owned ambient-lux policy that compensates display leakage and fuses an optional rear
 * light sensor with the framework-owned primary light-sensor sample.
 *
 * <p>The framework remains the sole owner of the primary ALS listener, brightness mapping and
 * hysteresis. This class receives that primary sample synchronously, observes the rear ALS and
 * posture sensors itself, then returns a single ordinary lux value. All policy constants are
 * loaded from {@code com.motorola.res}, allowing product RROs to tune this implementation without
 * putting vendor behavior in framework resources.</p>
 */
public final class ContextualAmbientLuxProcessor implements AmbientLuxProcessor {
    private static final String TAG = "ContextualAmbientLuxProcessor";
    private static final String MOTO_RES_PACKAGE = "com.motorola.res";
    private static final String DEBUG_PROPERTY = "persist.sys.moto.ambient_lux_debug";
    private static final String DEFAULT_REAR_LIGHT_TYPE = "com.motorola.sensor.alternate_light";
    private static final long SENSOR_PERIOD_US = 100_000;
    private static final float EPSILON = 0.001f;

    private Context mContext;
    private SensorManager mSensorManager;
    private Handler mHandler;
    private Sensor mRearLightSensor;
    private Sensor mGravitySensor;
    private Sensor mAccelerometer;

    private String mRearLightType = DEFAULT_REAR_LIGHT_TYPE;
    private long mRearSampleMaxAgeMs = 2_000;
    private long mPostureSampleMaxAgeMs = 1_500;
    private long mTargetHistoryMaxAgeMs = 8_000;
    private int mTargetHistoryMaxSize = 64;
    private long mDisplayToAlsDelayMs = 150;
    private long mTargetTransitionPenaltyMs = 1_000;
    private float mMaxLeakageLux = 1_200f;
    private float mLeakageRelativeUncertainty = 0.5f;
    private float mFrontMeasurementVariance = 25f;
    private float mRearMeasurementVariance = 25f;
    private float mTargetTransitionVariance = 900f;
    private float mFrontProcessNoise = 80f;
    private float mRearProcessNoise = 80f;
    private float mRearLowLux = 10f;
    private float mRearDownGravityZ = 8.5f;
    private float mStationaryMotionVariance = 0.08f;
    private long mRearOcclusionDwellMs = 3_000;
    private float mFusionGateSigma = 3f;

    private int[] mLeakageNits = {0};
    private int[] mLightThemeLeakageLux = {0};
    private int[] mDarkThemeLeakageLux = {0};

    private final ArrayDeque<TargetState> mTargetHistory = new ArrayDeque<>();
    private final ScalarEstimate mFrontEstimate = new ScalarEstimate();
    private final ScalarEstimate mRearEstimate = new ScalarEstimate();

    private float mRearLux = Float.NaN;
    private long mRearTimestampMs = Long.MIN_VALUE;
    private float mGravityZ = Float.NaN;
    private long mGravityTimestampMs = Long.MIN_VALUE;
    private float mMotionMean;
    private float mMotionVariance = Float.NaN;
    private long mMotionTimestampMs = Long.MIN_VALUE;
    private long mRearOcclusionCandidateStartMs = Long.MIN_VALUE;
    private boolean mDarkTheme;
    private float mLastTargetNits = Float.NaN;

    @Override
    public void initialize(Context context, SensorManager sensorManager, Handler handler,
            String[] parameters) {
        mContext = context;
        mSensorManager = sensorManager;
        mHandler = handler;
        loadDeviceResources(context);
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (mRearLightType.equals(sensor.getStringType())) {
                mRearLightSensor = sensor;
                break;
            }
        }
        mGravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mDarkTheme = isDarkTheme();
    }

    @Override
    public void start() {
        reset();
        mDarkTheme = isDarkTheme();
        register(mRearListener, mRearLightSensor);
        register(mGravityListener, mGravitySensor);
        register(mAccelerometerListener, mAccelerometer);
    }

    @Override
    public void stop() {
        mSensorManager.unregisterListener(mRearListener);
        mSensorManager.unregisterListener(mGravityListener);
        mSensorManager.unregisterListener(mAccelerometerListener);
        reset();
    }

    @Override
    public void onTargetNitsChanged(long timestampMillis, float targetNits) {
        if (!Float.isFinite(targetNits) || targetNits < 0) {
            return;
        }
        final boolean darkTheme = isDarkTheme();
        mDarkTheme = darkTheme;
        mLastTargetNits = targetNits;
        final TargetState last = mTargetHistory.peekLast();
        if (last != null && last.targetNits == targetNits && last.darkTheme == darkTheme) {
            return;
        }
        mTargetHistory.addLast(new TargetState(timestampMillis, targetNits, darkTheme));
        pruneTargetHistory(timestampMillis);
    }

    @Override
    public float process(long timestampMillis, float primaryLux, boolean dozing) {
        if (!Float.isFinite(primaryLux) || primaryLux < 0 || dozing) {
            return Float.NaN;
        }

        // Theme changes can occur without a brightness-target change. Preserve the most recent
        // target nits while recording the new calibration-table dimension at this sample time.
        final boolean darkTheme = isDarkTheme();
        if (darkTheme != mDarkTheme && Float.isFinite(mLastTargetNits)) {
            mDarkTheme = darkTheme;
            mTargetHistory.addLast(new TargetState(timestampMillis, mLastTargetNits, darkTheme));
        }
        pruneTargetHistory(timestampMillis);

        final TargetState target = findDelayedTargetState(timestampMillis);
        final float leakageLux = target == null ? 0 : Math.min(mMaxLeakageLux,
                interpolateLeakage(target.targetNits, target.darkTheme
                        ? mDarkThemeLeakageLux : mLightThemeLeakageLux));
        final float correctedFrontLux = Math.max(0, primaryLux - leakageLux);
        float frontVariance = mFrontMeasurementVariance
                + leakageLux * leakageLux * mLeakageRelativeUncertainty
                        * mLeakageRelativeUncertainty;
        if (target == null || timestampMillis - target.timestampMillis
                <= mTargetTransitionPenaltyMs) {
            // The leakage table cannot describe transient content or panel response precisely.
            // Let the front observation influence fusion less until that uncertainty settles.
            frontVariance += mTargetTransitionVariance;
        }
        mFrontEstimate.update(timestampMillis, correctedFrontLux, frontVariance,
                mFrontProcessNoise, mRearSampleMaxAgeMs);

        final boolean rearFresh = isRearFresh(timestampMillis);
        if (rearFresh && mRearTimestampMs > mRearEstimate.lastTimestampMillis) {
            mRearEstimate.update(mRearTimestampMs, mRearLux, mRearMeasurementVariance,
                    mRearProcessNoise, mRearSampleMaxAgeMs);
        }
        final boolean rearOccluded = rearFresh && updateRearOcclusion(timestampMillis);
        final float frontLux = mFrontEstimate.estimate;
        final float frontEstimateVariance = mFrontEstimate.varianceAt(timestampMillis,
                mFrontProcessNoise);
        if (!rearFresh || !mRearEstimate.isValid() || rearOccluded) {
            log(timestampMillis, primaryLux, leakageLux, frontLux, frontEstimateVariance,
                    Float.NaN, Float.NaN, rearFresh, rearOccluded, "front");
            return frontLux;
        }

        final float rearLux = mRearEstimate.estimate;
        final float rearVariance = mRearEstimate.varianceAt(timestampMillis, mRearProcessNoise);
        final float combinedStdDev = (float) Math.sqrt(frontEstimateVariance + rearVariance);
        final boolean estimatesAgree = Math.abs(frontLux - rearLux)
                <= mFusionGateSigma * Math.max(combinedStdDev, EPSILON);
        final float result;
        final String mode;
        if (estimatesAgree) {
            final float frontWeight = 1f / Math.max(frontEstimateVariance, EPSILON);
            final float rearWeight = 1f / Math.max(rearVariance, EPSILON);
            result = (frontWeight * frontLux + rearWeight * rearLux) / (frontWeight + rearWeight);
            mode = "fused";
        } else if (frontEstimateVariance <= rearVariance) {
            // Do not average incompatible local environments. The more certain estimate carries
            // the sample; the standard framework hysteresis still decides when to change output.
            result = frontLux;
            mode = "front-gated";
        } else {
            result = rearLux;
            mode = "rear-gated";
        }
        log(timestampMillis, primaryLux, leakageLux, frontLux, frontEstimateVariance, rearLux,
                rearVariance, rearFresh, rearOccluded, mode);
        return result;
    }

    @Override
    public void reset() {
        mTargetHistory.clear();
        mFrontEstimate.reset();
        mRearEstimate.reset();
        mRearLux = Float.NaN;
        mRearTimestampMs = Long.MIN_VALUE;
        mGravityZ = Float.NaN;
        mGravityTimestampMs = Long.MIN_VALUE;
        mMotionMean = 0;
        mMotionVariance = Float.NaN;
        mMotionTimestampMs = Long.MIN_VALUE;
        mRearOcclusionCandidateStartMs = Long.MIN_VALUE;
        mLastTargetNits = Float.NaN;
    }

    private void register(SensorEventListener listener, Sensor sensor) {
        if (sensor != null) {
            mSensorManager.registerListener(listener, sensor, (int) SENSOR_PERIOD_US, mHandler);
        }
    }

    private boolean isRearFresh(long timestampMillis) {
        return Float.isFinite(mRearLux) && mRearLux >= 0 && timestampMillis >= mRearTimestampMs
                && timestampMillis - mRearTimestampMs <= mRearSampleMaxAgeMs;
    }

    private boolean updateRearOcclusion(long timestampMillis) {
        final boolean postureFresh = Float.isFinite(mGravityZ)
                && timestampMillis >= mGravityTimestampMs
                && timestampMillis - mGravityTimestampMs <= mPostureSampleMaxAgeMs
                && Float.isFinite(mMotionVariance)
                && timestampMillis >= mMotionTimestampMs
                && timestampMillis - mMotionTimestampMs <= mPostureSampleMaxAgeMs;
        final boolean likelyRearDownAndStill = postureFresh && mGravityZ >= mRearDownGravityZ
                && mMotionVariance <= mStationaryMotionVariance;
        if (mRearLux <= mRearLowLux && likelyRearDownAndStill) {
            if (mRearOcclusionCandidateStartMs == Long.MIN_VALUE) {
                mRearOcclusionCandidateStartMs = timestampMillis;
            }
            return timestampMillis - mRearOcclusionCandidateStartMs >= mRearOcclusionDwellMs;
        }
        mRearOcclusionCandidateStartMs = Long.MIN_VALUE;
        return false;
    }

    private TargetState findDelayedTargetState(long timestampMillis) {
        final long targetTime = timestampMillis - mDisplayToAlsDelayMs;
        TargetState result = null;
        for (TargetState state : mTargetHistory) {
            if (state.timestampMillis > targetTime) {
                break;
            }
            result = state;
        }
        return result;
    }

    private void pruneTargetHistory(long timestampMillis) {
        while (mTargetHistory.size() > mTargetHistoryMaxSize) {
            mTargetHistory.removeFirst();
        }
        while (mTargetHistory.size() > 1
                && timestampMillis - mTargetHistory.peekFirst().timestampMillis
                        > mTargetHistoryMaxAgeMs) {
            mTargetHistory.removeFirst();
        }
    }

    private boolean isDarkTheme() {
        return (mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void loadDeviceResources(Context context) {
        try {
            final Resources resources = context.createPackageContext(MOTO_RES_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY).getResources();
            mRearLightType = getString(resources, "config_motoRearLightSensorType", mRearLightType);
            mRearSampleMaxAgeMs = getLong(resources, "config_motoRearLightSampleMaxAgeMs",
                    mRearSampleMaxAgeMs);
            mPostureSampleMaxAgeMs = getLong(resources,
                    "config_motoAmbientLuxPostureSampleMaxAgeMs", mPostureSampleMaxAgeMs);
            mTargetHistoryMaxAgeMs = getLong(resources,
                    "config_motoAmbientLuxTargetHistoryMaxAgeMs", mTargetHistoryMaxAgeMs);
            mTargetHistoryMaxSize = (int) getLong(resources,
                    "config_motoAmbientLuxTargetHistoryMaxSize", mTargetHistoryMaxSize);
            mDisplayToAlsDelayMs = getLong(resources,
                    "config_motoAmbientLuxDisplayToAlsDelayMs", mDisplayToAlsDelayMs);
            mTargetTransitionPenaltyMs = getLong(resources,
                    "config_motoAmbientLuxTargetTransitionPenaltyMs", mTargetTransitionPenaltyMs);
            mMaxLeakageLux = getFloat(resources, "config_motoAmbientLuxMaxLeakageLux",
                    mMaxLeakageLux);
            mLeakageRelativeUncertainty = getFloat(resources,
                    "config_motoAmbientLuxLeakageRelativeUncertainty",
                    mLeakageRelativeUncertainty);
            mFrontMeasurementVariance = getFloat(resources,
                    "config_motoAmbientLuxFrontMeasurementVariance", mFrontMeasurementVariance);
            mRearMeasurementVariance = getFloat(resources,
                    "config_motoAmbientLuxRearMeasurementVariance", mRearMeasurementVariance);
            mTargetTransitionVariance = getFloat(resources,
                    "config_motoAmbientLuxTargetTransitionVariance", mTargetTransitionVariance);
            mFrontProcessNoise = getFloat(resources, "config_motoAmbientLuxFrontProcessNoise",
                    mFrontProcessNoise);
            mRearProcessNoise = getFloat(resources, "config_motoAmbientLuxRearProcessNoise",
                    mRearProcessNoise);
            mRearLowLux = getFloat(resources, "config_motoAmbientLuxRearLowLux", mRearLowLux);
            mRearDownGravityZ = getFloat(resources, "config_motoAmbientLuxRearDownGravityZ",
                    mRearDownGravityZ);
            mStationaryMotionVariance = getFloat(resources,
                    "config_motoAmbientLuxStationaryMotionVariance", mStationaryMotionVariance);
            mRearOcclusionDwellMs = getLong(resources,
                    "config_motoAmbientLuxRearOcclusionDwellMs", mRearOcclusionDwellMs);
            mFusionGateSigma = getFloat(resources, "config_motoAmbientLuxFusionGateSigma",
                    mFusionGateSigma);
            mLeakageNits = getIntArray(resources, "config_motoAmbientLuxLeakageNits",
                    mLeakageNits);
            mLightThemeLeakageLux = getIntArray(resources,
                    "config_motoAmbientLuxLightThemeLeakageLux", mLightThemeLeakageLux);
            mDarkThemeLeakageLux = getIntArray(resources,
                    "config_motoAmbientLuxDarkThemeLeakageLux", mDarkThemeLeakageLux);
        } catch (PackageManager.NameNotFoundException ignored) {
            // The provider remains usable with safe defaults when the optional resource APK is
            // absent, which preserves ordinary primary-ALS automatic brightness during recovery.
        }
    }

    private static String getString(Resources resources, String name, String defaultValue) {
        final int id = resources.getIdentifier(name, "string", MOTO_RES_PACKAGE);
        return id != 0 ? resources.getString(id) : defaultValue;
    }

    private static float getFloat(Resources resources, String name, float defaultValue) {
        try {
            return Math.max(0, Float.parseFloat(getString(resources, name,
                    Float.toString(defaultValue))));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long getLong(Resources resources, String name, long defaultValue) {
        try {
            return Math.max(0, Long.parseLong(getString(resources, name,
                    Long.toString(defaultValue))));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int[] getIntArray(Resources resources, String name, int[] defaultValue) {
        final int id = resources.getIdentifier(name, "array", MOTO_RES_PACKAGE);
        final int[] values = id != 0 ? resources.getIntArray(id) : null;
        return values != null && values.length >= 2 ? values : defaultValue;
    }

    private float interpolateLeakage(float targetNits, int[] leakageLux) {
        if (!Float.isFinite(targetNits) || targetNits <= 0
                || mLeakageNits.length != leakageLux.length || leakageLux.length == 0) {
            return 0;
        }
        if (targetNits <= mLeakageNits[0]) {
            return leakageLux[0];
        }
        for (int i = 1; i < mLeakageNits.length; i++) {
            if (targetNits <= mLeakageNits[i]) {
                final float span = mLeakageNits[i] - mLeakageNits[i - 1];
                if (span <= 0) {
                    return leakageLux[i];
                }
                final float fraction = (targetNits - mLeakageNits[i - 1]) / span;
                return leakageLux[i - 1] + fraction * (leakageLux[i] - leakageLux[i - 1]);
            }
        }
        return leakageLux[leakageLux.length - 1];
    }

    private void log(long timestampMillis, float rawFront, float leakageLux, float frontLux,
            float frontVariance, float rearLux, float rearVariance, boolean rearFresh,
            boolean rearOccluded, String mode) {
        if (SystemProperties.getBoolean(DEBUG_PROPERTY, false)) {
            Slog.d(TAG, "time=" + timestampMillis + " rawFront=" + rawFront
                    + " leakage=" + leakageLux + " front=" + frontLux + "/" + frontVariance
                    + " rear=" + rearLux + "/" + rearVariance + " rearFresh=" + rearFresh
                    + " rearOccluded=" + rearOccluded + " mode=" + mode);
        }
    }

    private final SensorEventListener mRearListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length > 0 && Float.isFinite(event.values[0])
                    && event.values[0] >= 0) {
                mRearLux = event.values[0];
                // Sensor event timestamps and framework primary samples both use elapsed time.
                mRearTimestampMs = event.timestamp / 1_000_000L;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final SensorEventListener mGravityListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length >= 3 && Float.isFinite(event.values[2])) {
                mGravityZ = event.values[2];
                mGravityTimestampMs = event.timestamp / 1_000_000L;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final SensorEventListener mAccelerometerListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length < 3 || !Float.isFinite(event.values[0])
                    || !Float.isFinite(event.values[1]) || !Float.isFinite(event.values[2])) {
                return;
            }
            final float magnitude = (float) Math.sqrt(event.values[0] * event.values[0]
                    + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
            if (!Float.isFinite(mMotionVariance)) {
                mMotionMean = magnitude;
                mMotionVariance = 0;
            } else {
                // A short exponentially weighted variance distinguishes a resting phone from
                // ordinary hand movement without claiming to identify whether it is held.
                final float delta = magnitude - mMotionMean;
                mMotionMean += 0.1f * delta;
                mMotionVariance = 0.9f * mMotionVariance + 0.1f * delta * delta;
            }
            mMotionTimestampMs = event.timestamp / 1_000_000L;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private static final class TargetState {
        final long timestampMillis;
        final float targetNits;
        final boolean darkTheme;

        TargetState(long timestampMillis, float targetNits, boolean darkTheme) {
            this.timestampMillis = timestampMillis;
            this.targetNits = targetNits;
            this.darkTheme = darkTheme;
        }
    }

    /** Scalar estimate whose variance is kept in lux squared. */
    private static final class ScalarEstimate {
        float estimate = Float.NaN;
        float variance = Float.NaN;
        long lastTimestampMillis = Long.MIN_VALUE;

        void update(long timestampMillis, float observation, float measurementVariance,
                float processNoisePerSecond, long resetGapMillis) {
            if (!Float.isFinite(observation)) {
                return;
            }
            final float safeMeasurementVariance = Math.max(measurementVariance, EPSILON);
            if (!isValid() || timestampMillis < lastTimestampMillis
                    || timestampMillis - lastTimestampMillis > resetGapMillis) {
                estimate = observation;
                variance = safeMeasurementVariance;
                lastTimestampMillis = timestampMillis;
                return;
            }
            final float predictedVariance = variance + processNoisePerSecond
                    * (timestampMillis - lastTimestampMillis) / 1_000f;
            final float gain = predictedVariance / (predictedVariance + safeMeasurementVariance);
            estimate += gain * (observation - estimate);
            variance = Math.max(EPSILON, (1f - gain) * predictedVariance);
            lastTimestampMillis = timestampMillis;
        }

        float varianceAt(long timestampMillis, float processNoisePerSecond) {
            if (!isValid()) {
                return Float.NaN;
            }
            return variance + processNoisePerSecond
                    * Math.max(0, timestampMillis - lastTimestampMillis) / 1_000f;
        }

        boolean isValid() {
            return Float.isFinite(estimate) && Float.isFinite(variance);
        }

        void reset() {
            estimate = Float.NaN;
            variance = Float.NaN;
            lastTimestampMillis = Long.MIN_VALUE;
        }
    }
}
