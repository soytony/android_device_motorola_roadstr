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
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.graphics.Rect;
import android.view.CompositionSamplingListener;
import android.view.Display;

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
    private CompositionSamplingListener mRoiSamplingListener;
    private boolean mRearSensorOnChange;

    private String mRearLightType = DEFAULT_REAR_LIGHT_TYPE;
    private long mRearSampleMaxAgeMs = 2_000;
    private long mFrontSampleResetGapMs = 2_000;
    private long mPostureSampleMaxAgeMs = 1_500;
    private long mTargetHistoryMaxAgeMs = 8_000;
    private int mTargetHistoryMaxSize = 64;
    private long mDisplayToAlsDelayMs = 150;
    private long mLeakageHoldAfterTargetDropMs = 1_500;
    private long mTargetTransitionPenaltyMs = 1_000;
    private float mMaxLeakageLux = 1_200f;
    private float mLightThemeLeakageScale = 1f;
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
    private float mRearAssistAbsoluteMarginLux = 30f;
    private float mRearAssistRelativeMargin = 0.25f;
    private float mRearAssistMinimumLux = 20f;
    private long mRearAssistConfirmDwellMs = 1_000;
    private float mRearAssistMinimumConfidence = 0.7f;
    private float mRearAssistGain = 0.5f;
    private float mRearAssistMaxDeltaLux = 300f;
    private float mRearAssistMaxRelativeDelta = 0.5f;
    private float mRearAssistMaxRiseLuxPerSecond = 300f;
    private float mRearAssistMaxFallLuxPerSecond = 300f;
    private float mRearConstraintAbsoluteMarginLux = 45f;
    private float mRearConstraintRelativeMargin = 0.35f;
    private float mRearConstraintMinimumLeakageLux = 30f;
    private float mRearConstraintMaximumFrontLeakageRatio = 0.75f;
    private float mRearConstraintMinimumConfidence = 0.25f;
    private long mRearConstraintConfirmDwellMs = 750;
    private float mRearConstraintGain = 0.65f;
    private float mRearConstraintMaxDeltaLux = 400f;
    private float mRearConstraintMaxRelativeDelta = 0.8f;
    private float mRearConstraintMaxFallLuxPerSecond = 160f;
    private long mRoiSampleMaxAgeMs = 500;
    private float mRoiLeakageScale = 0f;
    private float mRoiLeakageReferenceLuma = 0.5f;
    private boolean mRoiEnabled;
    private int mRoiLeft;
    private int mRoiTop;
    private int mRoiRight;
    private int mRoiBottom;

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
    private long mRearAssistCandidateStartMs = Long.MIN_VALUE;
    private long mRearConstraintCandidateStartMs = Long.MIN_VALUE;
    private float mRoiLuma = Float.NaN;
    private float mRoiFilteredLuma = Float.NaN;
    private long mRoiTimestampMs = Long.MIN_VALUE;
    private boolean mLeakageHoldActive;
    private float mRearConfidence;
    private long mRearConfidenceTimestampMs = Long.MIN_VALUE;
    private float mLastOutputLux = Float.NaN;
    private long mLastOutputTimestampMs = Long.MIN_VALUE;
    private boolean mLastOutputRearAssisted;
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
                mRearSensorOnChange = sensor.getReportingMode() == Sensor.REPORTING_MODE_ON_CHANGE;
                debug("Rear sensor reporting mode=" + sensor.getReportingMode()
                        + " onChange=" + mRearSensorOnChange);
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
        register("rear", mRearListener, mRearLightSensor);
        register("gravity", mGravityListener, mGravitySensor);
        register("accelerometer", mAccelerometerListener, mAccelerometer);
        startRoiSampling();
    }

    @Override
    public void stop() {
        mSensorManager.unregisterListener(mRearListener);
        mSensorManager.unregisterListener(mGravityListener);
        mSensorManager.unregisterListener(mAccelerometerListener);
        stopRoiSampling();
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

        // At startup the first ALS event can arrive before the delayed alignment window has
        // elapsed. Use the latest already-applied target as a conservative fallback; otherwise
        // the first bright-content sample would bypass leakage correction entirely.
        TargetState target = findDelayedTargetState(timestampMillis);
        if (target == null) {
            final TargetState latest = mTargetHistory.peekLast();
            if (latest != null && latest.timestampMillis <= timestampMillis) {
                target = latest;
            }
        }
        mLeakageHoldActive = false;
        final float baseLeakageLux = target == null ? 0 : getLeakageWithTargetDropHold(
                timestampMillis, target);
        // Scale the reference-surface curve for brighter light-theme content. The value is
        // device-tunable and remains bounded by the maximum calibrated leakage.
        final float scaledLeakage = target != null && !target.darkTheme
                ? baseLeakageLux * mLightThemeLeakageScale : baseLeakageLux;
        final float leakageLux = Math.min(mMaxLeakageLux, Math.max(0, scaledLeakage
                + getRoiLeakageAdjustment(timestampMillis, scaledLeakage)));
        final float correctedFrontLux = Math.max(0, primaryLux - leakageLux);
        // Leakage-table error is mostly a systematic calibration error, not independent sensor
        // noise on each callback. Feeding it into the Kalman observation variance would make a
        // bright panel almost impossible to dim after the environment becomes dark. Keep the
        // front state responsive to the corrected observation, then discount its confidence only
        // when combining it with the independent rear ALS.
        mFrontEstimate.update(timestampMillis, correctedFrontLux, mFrontMeasurementVariance,
                mFrontProcessNoise, mFrontSampleResetGapMs);
        // A leakage-table residual is a content-dependent calibration bias, rather than random
        // noise in this front-ALS callback.  It must not inflate the dynamic fusion covariance:
        // at high panel targets that would otherwise let the rear ALS dominate the normal
        // primary-ALS baseline and circumvent the explicit, bounded rear-assistance policy below.
        float frontVariance = mFrontEstimate.varianceAt(timestampMillis, mFrontProcessNoise);
        if (target == null || timestampMillis - target.timestampMillis
                <= mTargetTransitionPenaltyMs) {
            // The leakage table cannot describe transient content or panel response precisely.
            // Let the front observation influence fusion less until that uncertainty settles.
            frontVariance += mTargetTransitionVariance;
        }
        final boolean rearFresh = isRearFresh(timestampMillis);
        if (rearFresh && mRearTimestampMs > mRearEstimate.lastTimestampMillis) {
            mRearEstimate.update(mRearTimestampMs, mRearLux, mRearMeasurementVariance,
                    mRearProcessNoise, mRearSampleMaxAgeMs);
        }
        // A low rear value during rotation is still valid darkness evidence. Require the
        // stationary rear-down dwell before treating it as table/display occlusion.
        final boolean rearOccluded = rearFresh && updateRearOcclusion(timestampMillis);
        final float frontLux = mFrontEstimate.estimate;
        final float frontEstimateVariance = frontVariance;
        if (!rearFresh || !mRearEstimate.isValid() || rearOccluded) {
            mRearAssistCandidateStartMs = Long.MIN_VALUE;
            // Do not carry a partially confirmed downward-leakage decision across a stale,
            // missing, or occluded rear sample. The next valid rear sequence must start fresh.
            mRearConstraintCandidateStartMs = Long.MIN_VALUE;
            // Decay a previous rear-assisted output instead of dropping it on the first stale or
            // occluded sample. The normal front-only path remains unchanged when no rear assist
            // was active.
            final float stableFront = limitRearContribution(timestampMillis, frontLux, frontLux);
            rememberOutput(timestampMillis, stableFront,
                    mLastOutputRearAssisted && stableFront > frontLux + EPSILON);
            log(timestampMillis, primaryLux, target, leakageLux, frontLux, frontEstimateVariance,
                    Float.NaN, Float.NaN, rearFresh, rearOccluded, 0, "front", stableFront);
            return stableFront;
        }

        final float rearLux = mRearEstimate.estimate;
        final float rearVariance = mRearEstimate.varianceAt(timestampMillis, mRearProcessNoise);
        final float rearConfidence = getRearConfidence(timestampMillis, rearLux);
        final float combinedStdDev = (float) Math.sqrt(frontEstimateVariance + rearVariance);
        final boolean estimatesAgree = Math.abs(frontLux - rearLux)
                <= mFusionGateSigma * Math.max(combinedStdDev, EPSILON);
        final float result;
        final String mode;
        // Check the residual-leakage path before the statistical agreement path. A bright
        // display can inflate the front estimate while the rear ALS remains near darkness;
        // the large front variance during a target transition would otherwise classify this
        // real asymmetry as noise and preserve the excessive front-only level.
        final boolean rearConstraintConfirmed = isRearConstraintConfirmed(timestampMillis, frontLux,
                rearLux, rearConfidence, leakageLux);
        if (rearConstraintConfirmed) {
            mRearAssistCandidateStartMs = Long.MIN_VALUE;
            // Once the persistent residual-leakage condition is confirmed, use the rear ALS as
            // the environmental baseline. A confidence-weighted average here would preserve
            // most of the known display leakage when confidence is merely moderate. Confidence
            // has already gated entry and remains available to the bounded fall-rate policy.
            final float constrainedRearLux = rearLux;
            result = limitDownwardConstraint(timestampMillis, frontLux, constrainedRearLux,
                    rearConfidence);
            mode = "rear-constrained";
        } else if (estimatesAgree) {
            mRearAssistCandidateStartMs = Long.MIN_VALUE;
            // Agreement is not evidence that the rear sensor should raise brightness. The rear
            // device-side sensor has a different optical path and can see local light that the
            // display-facing sensor does not. Keep the primary ALS as the baseline; the rear
            // sensor may raise it only through the separately confirmed rear-assisted path.
            result = frontLux;
            mode = "front-agree";
        } else if (isRearAssistConfirmed(timestampMillis, frontLux, rearLux, rearConfidence)) {
            mRearConstraintCandidateStartMs = Long.MIN_VALUE;
            // Rear ALS may expose illumination the display-facing sensor cannot see. Require a
            // material, repeated and credible difference, then cap the rise rate so a posture or
            // local-light transition cannot abruptly take ownership of automatic brightness.
            result = limitUpwardChange(timestampMillis, frontLux, rearLux, rearConfidence);
            mode = "rear-assisted";
        } else {
            // Do not average incompatible local environments. The corrected front path remains
            // the stable baseline; framework hysteresis still decides when to change output.
            result = frontLux;
            mode = "front-gated";
        }
        // A confirmed downward constraint owns its own conservative fall-rate limit. Do not
        // reapply the unrelated decay used when an earlier rear-assisted rise disappears.
        final float stableResult = mode.equals("rear-constrained") ? result
                : limitRearContribution(timestampMillis, frontLux, result);
        final boolean rearAssisted = mode.equals("rear-assisted")
                && stableResult > frontLux + EPSILON;
        rememberOutput(timestampMillis, stableResult, rearAssisted);
        log(timestampMillis, primaryLux, target, leakageLux, frontLux, frontEstimateVariance, rearLux,
                rearVariance, rearFresh, rearOccluded, rearConfidence, mode, stableResult);
        return stableResult;
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
        mRearAssistCandidateStartMs = Long.MIN_VALUE;
        mRearConstraintCandidateStartMs = Long.MIN_VALUE;
        mRoiLuma = Float.NaN;
        mRoiFilteredLuma = Float.NaN;
        mRoiTimestampMs = Long.MIN_VALUE;
        mLeakageHoldActive = false;
        mRearConfidence = 0;
        mRearConfidenceTimestampMs = Long.MIN_VALUE;
        mLastOutputLux = Float.NaN;
        mLastOutputTimestampMs = Long.MIN_VALUE;
        mLastOutputRearAssisted = false;
        // Target history is empty after reset; do not reuse a target from the previous run.
        mLastTargetNits = Float.NaN;
    }

    private void register(String label, SensorEventListener listener, Sensor sensor) {
        if (sensor == null) {
            debug("Sensor unavailable: " + label + " type=" + mRearLightType);
            return;
        }
        final boolean registered = mSensorManager.registerListener(listener, sensor,
                (int) SENSOR_PERIOD_US, mHandler);
        if (!registered) {
            Slog.w(TAG, "Unable to register " + label + " sensor: " + sensor);
        } else {
            debug("Registered " + label + " sensor: " + sensor);
        }
    }

    private static void debug(String message) {
        if (SystemProperties.getBoolean(DEBUG_PROPERTY, false)) {
            Slog.d(TAG, message);
        }
    }

    private void startRoiSampling() {
        if (!mRoiEnabled || mRoiRight <= mRoiLeft || mRoiBottom <= mRoiTop) {
            return;
        }
        try {
            mRoiSamplingListener = new CompositionSamplingListener(command ->
                    mHandler.post(command)) {
                @Override
                public void onSampleCollected(float medianLuma) {
                    if (Float.isFinite(medianLuma)) {
                        mRoiLuma = clamp01(medianLuma);
                        // Smooth composition changes so one animation frame cannot feed a
                        // brightness change back into the ALS loop.
                        if (!Float.isFinite(mRoiFilteredLuma)) {
                            mRoiFilteredLuma = mRoiLuma;
                        } else {
                            mRoiFilteredLuma += 0.2f * (mRoiLuma - mRoiFilteredLuma);
                        }
                        // Composition sampling has no source timestamp. Record the callback on
                        // the same elapsed-realtime basis used for SensorEvent timestamps.
                        mRoiTimestampMs = SystemClock.elapsedRealtime();
                    }
                }
            };
            CompositionSamplingListener.register(mRoiSamplingListener, Display.DEFAULT_DISPLAY,
                    null, new Rect(mRoiLeft, mRoiTop, mRoiRight, mRoiBottom));
        } catch (RuntimeException error) {
            Slog.w(TAG, "Unable to start optional ambient-lux ROI sampling", error);
            stopRoiSampling();
        }
    }

    private void stopRoiSampling() {
        if (mRoiSamplingListener == null) {
            return;
        }
        try {
            mRoiSamplingListener.destroy();
        } catch (RuntimeException error) {
            Slog.w(TAG, "Unable to stop optional ambient-lux ROI sampling", error);
        }
        mRoiSamplingListener = null;
    }

    private boolean isRearFresh(long timestampMillis) {
        if (!Float.isFinite(mRearLux) || mRearLux < 0 || timestampMillis < mRearTimestampMs) {
            return false;
        }
        // On-change sensors do not emit periodic heartbeats, so allow a longer grace period than
        // a continuous sensor. Do not keep an old value forever: after the grace period it must
        // leave both the upward-assist and residual-leakage paths until a new hardware sample
        // arrives. Confidence still decays continuously during this grace period.
        final long maxAge = mRearSensorOnChange
                ? Math.max(mRearSampleMaxAgeMs, mRearSampleMaxAgeMs * 4)
                : mRearSampleMaxAgeMs;
        return timestampMillis - mRearTimestampMs <= maxAge;
    }

    /**
     * An ON_CHANGE rear ALS can remain silent while stable. Its extended freshness is useful for
     * confidence decay, but a stale retained value must not begin a new fusion decision.
     */
    private boolean isRearRecent(long timestampMillis) {
        return Float.isFinite(mRearLux) && timestampMillis >= mRearTimestampMs
                && timestampMillis - mRearTimestampMs <= mRearSampleMaxAgeMs;
    }

    private boolean updateRearOcclusion(long timestampMillis) {
        if (isLikelyRearDownAndStill(timestampMillis)) {
            if (mRearOcclusionCandidateStartMs == Long.MIN_VALUE) {
                mRearOcclusionCandidateStartMs = timestampMillis;
            }
            return timestampMillis - mRearOcclusionCandidateStartMs >= mRearOcclusionDwellMs;
        }
        mRearOcclusionCandidateStartMs = Long.MIN_VALUE;
        return false;
    }

    /** Returns true only when low rear lux coincides with a fresh, stationary rear-down posture. */
    private boolean isLikelyRearDownAndStill(long timestampMillis) {
        return isRearDownAndLow(timestampMillis) && Float.isFinite(mMotionVariance)
                && timestampMillis >= mMotionTimestampMs
                && timestampMillis - mMotionTimestampMs <= mPostureSampleMaxAgeMs
                && mMotionVariance <= mStationaryMotionVariance;
    }

    /** Returns true when a fresh, low rear reading coincides with display-up orientation. */
    private boolean isRearDownAndLow(long timestampMillis) {
        final boolean postureFresh = Float.isFinite(mGravityZ)
                && timestampMillis >= mGravityTimestampMs
                && timestampMillis - mGravityTimestampMs <= mPostureSampleMaxAgeMs;
        return mRearLux <= mRearLowLux && postureFresh && mGravityZ >= mRearDownGravityZ;
    }

    /**
     * Returns a bounded confidence rather than treating every non-occluded rear sample alike.
     * Freshness, pose and recent motion are independent evidence. A low rear sample remains
     * valid until the separate rear-down/stationary dwell establishes likely occlusion.
     */
    private float getRearConfidence(long timestampMillis, float rearLux) {
        final float age = Math.max(0, timestampMillis - mRearTimestampMs);
        // ON_CHANGE sensors intentionally remain silent while illumination is stable. Their
        // last value is valid; do not turn an unchanged reading into confidence=0 merely because
        // the continuous-sensor freshness window elapsed.
        // On-change sensors have no heartbeat, but an old value must not retain full authority
        // forever: posture and illumination may have changed without crossing the sensor's
        // reporting threshold. Keep a grace period, then decay confidence gradually instead of
        // abruptly invalidating the useful last sample.
        final float freshness = mRearSensorOnChange
                ? Math.max(0, 1f - Math.max(0, age - mRearSampleMaxAgeMs)
                        / Math.max(1f, mRearSampleMaxAgeMs * 4f))
                : Math.max(0, 1f - age / Math.max(1f, mRearSampleMaxAgeMs));
        final boolean postureFresh = Float.isFinite(mGravityZ)
                && timestampMillis >= mGravityTimestampMs
                && timestampMillis - mGravityTimestampMs <= mPostureSampleMaxAgeMs;
        // A rear-down pose is evidence of occlusion only for a near-zero rear reading. Penalizing
        // every rear sample in that pose makes the output oscillate during ordinary rotation.
        final float orientation;
        if (!postureFresh || rearLux > mRearLowLux) {
            orientation = !postureFresh ? 0.8f : 1f;
        } else if (mGravityZ <= mRearDownGravityZ) {
            // Upright/hand-held poses can have a materially positive Z component. Do not
            // mistake that ordinary pose for a face-up table placement merely because the rear
            // sensor sees darkness.
            orientation = 1f;
        } else {
            // Fade confidence only beyond the configured rear-down threshold. Occlusion still
            // requires the independent stationary dwell check.
            orientation = clamp01(1f - (mGravityZ - mRearDownGravityZ) / 2f);
        }
        final boolean motionFresh = Float.isFinite(mMotionVariance)
                && timestampMillis >= mMotionTimestampMs
                && timestampMillis - mMotionTimestampMs <= mPostureSampleMaxAgeMs;
        // Motion does not prove hand-held use. It only makes a stationary table-occlusion
        // interpretation less likely, so it modestly restores rear confidence.
        final float motion = motionFresh
                ? 0.55f + 0.45f * clamp01(mMotionVariance
                        / Math.max(0.01f, mStationaryMotionVariance)) : 0.8f;
        // Low lux is not itself evidence of occlusion: it may be the correct ambient value in a
        // dark room. Occlusion is established only by the posture/motion/dwell policy above.
        final float rawConfidence = Math.max(0.05f,
                freshness * (0.35f + 0.65f * orientation) * motion);
        if (mRearConfidenceTimestampMs == Long.MIN_VALUE
                || timestampMillis < mRearConfidenceTimestampMs
                || (!mRearSensorOnChange
                        && timestampMillis - mRearConfidenceTimestampMs > mRearSampleMaxAgeMs)) {
            mRearConfidence = rawConfidence;
        } else {
            // Smooth confidence over about half a second so posture samples cannot cause
            // frame-to-frame changes in the rear contribution.
            final float elapsedSeconds = (timestampMillis - mRearConfidenceTimestampMs) / 1_000f;
            final float alpha = clamp01(elapsedSeconds / 0.5f);
            mRearConfidence += alpha * (rawConfidence - mRearConfidence);
        }
        mRearConfidenceTimestampMs = timestampMillis;
        return clamp01(mRearConfidence);
    }

    private boolean isRearAssistConfirmed(long timestampMillis, float frontLux, float rearLux,
            float rearConfidence) {
        // A low rear reading cannot credibly request an upward brightness change. This gate
        // rejects sensor zero-point noise and dark-room disagreement before the delta policy.
        if (!isRearRecent(timestampMillis) || rearLux < mRearAssistMinimumLux) {
            mRearAssistCandidateStartMs = Long.MIN_VALUE;
            return false;
        }
        final float requiredDelta = Math.max(mRearAssistAbsoluteMarginLux,
                frontLux * mRearAssistRelativeMargin);
        if (rearConfidence >= mRearAssistMinimumConfidence
                && rearLux - frontLux >= requiredDelta) {
            if (mRearAssistCandidateStartMs == Long.MIN_VALUE) {
                mRearAssistCandidateStartMs = timestampMillis;
            }
        } else {
            mRearAssistCandidateStartMs = Long.MIN_VALUE;
        }
        return mRearAssistCandidateStartMs != Long.MIN_VALUE
                && timestampMillis - mRearAssistCandidateStartMs
                        >= mRearAssistConfirmDwellMs;
    }

    /**
     * Confirms that a lower rear reading is likely correcting residual display leakage rather
     * than describing a different local environment. A rear-down/stationary placement has
     * already been excluded by the occlusion guard before this method is reached.
     */
    private boolean isRearConstraintConfirmed(long timestampMillis, float frontLux, float rearLux,
            float rearConfidence, float leakageLux) {
        final float requiredDelta = Math.max(mRearConstraintAbsoluteMarginLux,
                frontLux * mRearConstraintRelativeMargin);
        final boolean plausibleLeakage = leakageLux >= mRearConstraintMinimumLeakageLux;
        // A low rear ALS must not override legitimate illumination arriving at the display-facing
        // side of the handset. Treat it as residual panel leakage only when the corrected front
        // estimate is itself small relative to the calibrated panel-leakage estimate.
        final boolean residualLooksLikeLeakage = leakageLux > EPSILON
                && frontLux <= leakageLux * mRearConstraintMaximumFrontLeakageRatio;
        if (isRearRecent(timestampMillis) && plausibleLeakage && residualLooksLikeLeakage
                && rearConfidence >= mRearConstraintMinimumConfidence
                && frontLux - rearLux >= requiredDelta) {
            if (mRearConstraintCandidateStartMs == Long.MIN_VALUE) {
                mRearConstraintCandidateStartMs = timestampMillis;
            }
        } else {
            mRearConstraintCandidateStartMs = Long.MIN_VALUE;
        }
        return mRearConstraintCandidateStartMs != Long.MIN_VALUE
                && timestampMillis - mRearConstraintCandidateStartMs
                        >= mRearConstraintConfirmDwellMs;
    }

    private float limitUpwardChange(long timestampMillis, float frontLux, float candidateLux,
            float rearConfidence) {
        candidateLux = capRearDerivedLux(frontLux, candidateLux, rearConfidence);
        if (!Float.isFinite(mLastOutputLux) || timestampMillis <= mLastOutputTimestampMs) {
            return candidateLux;
        }
        final float maxRise = mRearAssistMaxRiseLuxPerSecond * (timestampMillis
                - mLastOutputTimestampMs) / 1_000f;
        return Math.min(candidateLux, mLastOutputLux + Math.max(0, maxRise));
    }

    /**
     * Bounds only the part of the result that can have come from the rear sensor. A rear ALS is
     * useful for recovering light hidden from the display-facing sensor, but it must not silently
     * replace the primary ambient estimate. Front-only increases bypass this method.
     */
    private float capRearDerivedLux(float frontLux, float candidateLux, float rearConfidence) {
        if (!Float.isFinite(frontLux) || !Float.isFinite(candidateLux)
                || candidateLux <= frontLux) {
            return candidateLux;
        }
        final float allowedDelta = Math.min(mRearAssistMaxDeltaLux,
                Math.max(mRearAssistAbsoluteMarginLux,
                        frontLux * mRearAssistMaxRelativeDelta));
        // Confidence is evidence quality, not a linear brightness command. Squaring it keeps
        // uncertain rear readings from producing a disproportionate upward assist.
        final float confidence = clamp01(rearConfidence);
        final float confidenceGain = confidence * confidence * mRearAssistGain;
        return frontLux + Math.min(candidateLux - frontLux, allowedDelta * confidenceGain);
    }

    /**
     * Limits a lower rear estimate to a confirmed portion of the front/rear residual. This keeps
     * a credible dark rear ALS useful against display leakage without treating it as an absolute
     * replacement for the display-facing primary sensor.
     */
    private float limitDownwardConstraint(long timestampMillis, float frontLux,
            float candidateLux, float rearConfidence) {
        if (!Float.isFinite(frontLux) || !Float.isFinite(candidateLux)
                || candidateLux >= frontLux) {
            return frontLux;
        }
        // Bound the correction against the actual front/rear residual. Using frontLux here
        // made a low rear reading remove only a small fixed margin when the corrected front
        // estimate was modest, leaving residual display leakage to hold brightness too high.
        final float residualLux = frontLux - candidateLux;
        final float allowedDelta = Math.min(mRearConstraintMaxDeltaLux,
                Math.max(mRearConstraintAbsoluteMarginLux,
                        residualLux * mRearConstraintMaxRelativeDelta));
        // Confidence gates entry into this path. Once the residual-leakage condition survives
        // its dwell window, do not multiply the correction by the same confidence again:
        // doing so made a valid ~0.28 confidence reduce a 400-lux residual by only ~73 lux.
        final float constrained = Math.max(candidateLux, frontLux - allowedDelta
                * mRearConstraintGain);
        if (!Float.isFinite(mLastOutputLux) || timestampMillis <= mLastOutputTimestampMs) {
            return constrained;
        }
        final float maxFall = mRearConstraintMaxFallLuxPerSecond * (timestampMillis
                - mLastOutputTimestampMs) / 1_000f;
        return Math.max(constrained, mLastOutputLux - Math.max(0, maxFall));
    }

    /** Slew only the portion previously contributed by rear ALS; front-only darkening stays fast. */
    private float limitRearContribution(long timestampMillis, float frontLux, float candidateLux) {
        if (!mLastOutputRearAssisted || !Float.isFinite(mLastOutputLux)
                || timestampMillis <= mLastOutputTimestampMs
                || candidateLux >= mLastOutputLux || mLastOutputLux <= frontLux) {
            return candidateLux;
        }
        final float maxFall = mRearAssistMaxFallLuxPerSecond * (timestampMillis
                - mLastOutputTimestampMs) / 1_000f;
        return Math.max(candidateLux, mLastOutputLux - Math.max(0, maxFall));
    }

    private void rememberOutput(long timestampMillis, float outputLux, boolean rearAssisted) {
        if (Float.isFinite(outputLux) && outputLux >= 0) {
            mLastOutputLux = outputLux;
            mLastOutputTimestampMs = timestampMillis;
            mLastOutputRearAssisted = rearAssisted;
        }
    }

    private float getRoiLeakageAdjustment(long timestampMillis, float baseLeakageLux) {
        if (mRoiLeakageScale == 0 || !Float.isFinite(mRoiFilteredLuma)
                || timestampMillis < mRoiTimestampMs
                || timestampMillis - mRoiTimestampMs > mRoiSampleMaxAgeMs) {
            return 0;
        }
        // Region sampling reports composition luma, not physical lux. It only scales a measured
        // device calibration, with zero effect until a product RRO explicitly enables it.
        return baseLeakageLux * mRoiLeakageScale
                * (mRoiFilteredLuma - mRoiLeakageReferenceLuma);
    }

    private static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
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

    /**
     * Keep the previous higher leakage estimate briefly while the panel is ramping down. The
     * accepted brightness target changes before emitted panel light reaches that target; without
     * this hold, the residual screen light is misclassified as ambient light and can sustain a
     * needlessly high automatic-brightness level in a dark room.
     */
    private float getLeakageWithTargetDropHold(long timestampMillis, TargetState target) {
        final int[] table = target.darkTheme ? mDarkThemeLeakageLux : mLightThemeLeakageLux;
        float leakage = interpolateLeakage(target.targetNits, table);
        TargetState previous = null;
        for (TargetState state : mTargetHistory) {
            if (state == target) {
                break;
            }
            previous = state;
        }
        if (previous != null && previous.darkTheme == target.darkTheme
                && previous.targetNits > target.targetNits
                && timestampMillis >= target.timestampMillis
                && timestampMillis - target.timestampMillis <= mLeakageHoldAfterTargetDropMs) {
            leakage = Math.max(leakage, interpolateLeakage(previous.targetNits, table));
            mLeakageHoldActive = true;
        }
        return leakage;
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
            mFrontSampleResetGapMs = getLong(resources,
                    "config_motoAmbientLuxFrontSampleResetGapMs", mFrontSampleResetGapMs);
            mPostureSampleMaxAgeMs = getLong(resources,
                    "config_motoAmbientLuxPostureSampleMaxAgeMs", mPostureSampleMaxAgeMs);
            mTargetHistoryMaxAgeMs = getLong(resources,
                    "config_motoAmbientLuxTargetHistoryMaxAgeMs", mTargetHistoryMaxAgeMs);
            mTargetHistoryMaxSize = (int) getLong(resources,
                    "config_motoAmbientLuxTargetHistoryMaxSize", mTargetHistoryMaxSize);
            mDisplayToAlsDelayMs = getLong(resources,
                    "config_motoAmbientLuxDisplayToAlsDelayMs", mDisplayToAlsDelayMs);
            mLeakageHoldAfterTargetDropMs = getLong(resources,
                    "config_motoAmbientLuxLeakageHoldAfterTargetDropMs",
                    mLeakageHoldAfterTargetDropMs);
            mTargetTransitionPenaltyMs = getLong(resources,
                    "config_motoAmbientLuxTargetTransitionPenaltyMs", mTargetTransitionPenaltyMs);
            mMaxLeakageLux = getFloat(resources, "config_motoAmbientLuxMaxLeakageLux",
                    mMaxLeakageLux);
            mLightThemeLeakageScale = getFloat(resources,
                    "config_motoAmbientLuxLightThemeLeakageScale", mLightThemeLeakageScale);
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
            mRearAssistAbsoluteMarginLux = getFloat(resources,
                    "config_motoAmbientLuxRearAssistAbsoluteMarginLux",
                    mRearAssistAbsoluteMarginLux);
            mRearAssistRelativeMargin = getFloat(resources,
                    "config_motoAmbientLuxRearAssistRelativeMargin", mRearAssistRelativeMargin);
            mRearAssistMinimumLux = getFloat(resources,
                    "config_motoAmbientLuxRearAssistMinimumLux", mRearAssistMinimumLux);
            mRearAssistConfirmDwellMs = getLong(resources,
                    "config_motoAmbientLuxRearAssistConfirmDwellMs", mRearAssistConfirmDwellMs);
            mRearAssistMinimumConfidence = getFloat(resources,
                    "config_motoAmbientLuxRearAssistMinimumConfidence",
                    mRearAssistMinimumConfidence);
            mRearAssistGain = getFloat(resources, "config_motoAmbientLuxRearAssistGain",
                    mRearAssistGain);
            mRearAssistMaxDeltaLux = getFloat(resources,
                    "config_motoAmbientLuxRearAssistMaxDeltaLux", mRearAssistMaxDeltaLux);
            mRearAssistMaxRelativeDelta = getFloat(resources,
                    "config_motoAmbientLuxRearAssistMaxRelativeDelta",
                    mRearAssistMaxRelativeDelta);
            mRearAssistMaxRiseLuxPerSecond = getFloat(resources,
                    "config_motoAmbientLuxRearAssistMaxRiseLuxPerSecond",
                    mRearAssistMaxRiseLuxPerSecond);
            mRearAssistMaxFallLuxPerSecond = getFloat(resources,
                    "config_motoAmbientLuxRearAssistMaxFallLuxPerSecond",
                    mRearAssistMaxFallLuxPerSecond);
            mRearConstraintAbsoluteMarginLux = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintAbsoluteMarginLux",
                    mRearConstraintAbsoluteMarginLux);
            mRearConstraintRelativeMargin = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintRelativeMargin",
                    mRearConstraintRelativeMargin);
            mRearConstraintMinimumLeakageLux = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintMinimumLeakageLux",
                    mRearConstraintMinimumLeakageLux);
            mRearConstraintMaximumFrontLeakageRatio = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintMaximumFrontLeakageRatio",
                    mRearConstraintMaximumFrontLeakageRatio);
            mRearConstraintConfirmDwellMs = getLong(resources,
                    "config_motoAmbientLuxRearConstraintConfirmDwellMs",
                    mRearConstraintConfirmDwellMs);
            mRearConstraintGain = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintGain", mRearConstraintGain);
            mRearConstraintMinimumConfidence = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintMinimumConfidence",
                    mRearConstraintMinimumConfidence);
            mRearConstraintMaxDeltaLux = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintMaxDeltaLux",
                    mRearConstraintMaxDeltaLux);
            mRearConstraintMaxRelativeDelta = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintMaxRelativeDelta",
                    mRearConstraintMaxRelativeDelta);
            mRearConstraintMaxFallLuxPerSecond = getFloat(resources,
                    "config_motoAmbientLuxRearConstraintMaxFallLuxPerSecond",
                    mRearConstraintMaxFallLuxPerSecond);
            mRoiEnabled = getBoolean(resources, "config_motoAmbientLuxRoiEnabled", mRoiEnabled);
            mRoiSampleMaxAgeMs = getLong(resources, "config_motoAmbientLuxRoiSampleMaxAgeMs",
                    mRoiSampleMaxAgeMs);
            mRoiLeakageScale = getFloat(resources, "config_motoAmbientLuxRoiLeakageScale",
                    mRoiLeakageScale);
            mRoiLeakageReferenceLuma = getFloat(resources,
                    "config_motoAmbientLuxRoiLeakageReferenceLuma", mRoiLeakageReferenceLuma);
            mRoiLeft = getInt(resources, "config_motoAmbientLuxRoiLeft", mRoiLeft);
            mRoiTop = getInt(resources, "config_motoAmbientLuxRoiTop", mRoiTop);
            mRoiRight = getInt(resources, "config_motoAmbientLuxRoiRight", mRoiRight);
            mRoiBottom = getInt(resources, "config_motoAmbientLuxRoiBottom", mRoiBottom);
            mLeakageNits = getIntArray(resources, "config_motoAmbientLuxLeakageNits",
                    mLeakageNits);
            mLightThemeLeakageLux = getIntArray(resources,
                    "config_motoAmbientLuxLightThemeLeakageLux", mLightThemeLeakageLux);
            mDarkThemeLeakageLux = getIntArray(resources,
                    "config_motoAmbientLuxDarkThemeLeakageLux", mDarkThemeLeakageLux);
            sanitizeConfiguration();
        } catch (PackageManager.NameNotFoundException ignored) {
            // The provider remains usable with safe defaults when the optional resource APK is
            // absent, which preserves ordinary primary-ALS automatic brightness during recovery.
        }
    }

    /** Keep malformed or unsafe RRO values from changing the provider's runtime contract. */
    private void sanitizeConfiguration() {
        mRearSampleMaxAgeMs = Math.max(100, mRearSampleMaxAgeMs);
        mLightThemeLeakageScale = Math.max(0, mLightThemeLeakageScale);
        mFrontSampleResetGapMs = Math.max(100, mFrontSampleResetGapMs);
        mPostureSampleMaxAgeMs = Math.max(100, mPostureSampleMaxAgeMs);
        mTargetHistoryMaxAgeMs = Math.max(1_000, mTargetHistoryMaxAgeMs);
        mTargetHistoryMaxSize = Math.max(2, mTargetHistoryMaxSize);
        mLeakageHoldAfterTargetDropMs = Math.max(0, mLeakageHoldAfterTargetDropMs);
        mRoiSampleMaxAgeMs = Math.max(100, mRoiSampleMaxAgeMs);
        mRoiLeakageReferenceLuma = clamp01(mRoiLeakageReferenceLuma);
        mFusionGateSigma = Math.max(0.1f, mFusionGateSigma);
        mRearAssistConfirmDwellMs = Math.max(0, mRearAssistConfirmDwellMs);
        mRearAssistMinimumConfidence = clamp01(mRearAssistMinimumConfidence);
        mRearAssistMinimumLux = Math.max(0, mRearAssistMinimumLux);
        mRearAssistGain = clamp01(mRearAssistGain);
        mRearAssistMaxDeltaLux = Math.max(0, mRearAssistMaxDeltaLux);
        mRearAssistMaxRelativeDelta = Math.max(0, mRearAssistMaxRelativeDelta);
        mRearConstraintConfirmDwellMs = Math.max(0, mRearConstraintConfirmDwellMs);
        mRearConstraintGain = clamp01(mRearConstraintGain);
        mRearConstraintMaximumFrontLeakageRatio = Math.max(0,
                mRearConstraintMaximumFrontLeakageRatio);
        mRearConstraintMinimumConfidence = clamp01(mRearConstraintMinimumConfidence);
        mRearConstraintMaxDeltaLux = Math.max(0, mRearConstraintMaxDeltaLux);
        mRearConstraintMaxRelativeDelta = Math.max(0, mRearConstraintMaxRelativeDelta);
        if (!isValidLeakageTable(mLeakageNits, mLightThemeLeakageLux)
                || !isValidLeakageTable(mLeakageNits, mDarkThemeLeakageLux)) {
            // No correction is safer than applying a malformed calibration table.
            mLeakageNits = new int[] {0};
            mLightThemeLeakageLux = new int[] {0};
            mDarkThemeLeakageLux = new int[] {0};
        }
    }

    private static boolean isValidLeakageTable(int[] nits, int[] leakage) {
        if (nits == null || leakage == null || nits.length < 2 || nits.length != leakage.length) {
            return nits != null && leakage != null && nits.length == 1 && leakage.length == 1;
        }
        for (int i = 0; i < nits.length; i++) {
            if (nits[i] < 0 || leakage[i] < 0 || (i > 0 && nits[i] <= nits[i - 1])) {
                return false;
            }
        }
        return true;
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

    private static int getInt(Resources resources, String name, int defaultValue) {
        try {
            return Integer.parseInt(getString(resources, name, Integer.toString(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean getBoolean(Resources resources, String name, boolean defaultValue) {
        final int id = resources.getIdentifier(name, "bool", MOTO_RES_PACKAGE);
        return id != 0 ? resources.getBoolean(id) : defaultValue;
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

    private void log(long timestampMillis, float rawFront, TargetState target, float leakageLux,
            float frontLux,
            float frontVariance, float rearLux, float rearVariance, boolean rearFresh,
            boolean rearOccluded, float rearConfidence, String mode, float outputLux) {
        if (SystemProperties.getBoolean(DEBUG_PROPERTY, false)) {
            Slog.d(TAG, "time=" + timestampMillis + " targetNits="
                    + (target == null ? "NaN" : target.targetNits) + " darkTheme="
                    + (target == null ? "?" : target.darkTheme) + " rawFront=" + rawFront
                    + " leakage=" + leakageLux + " front=" + frontLux + "/" + frontVariance
                    + " rear=" + rearLux + "/" + rearVariance + " rearFresh=" + rearFresh
                    + " rearOccluded=" + rearOccluded + " rearConfidence=" + rearConfidence
                    + " roiLuma=" + mRoiLuma + " filteredRoiLuma=" + mRoiFilteredLuma
                    + " leakageHold=" + mLeakageHoldActive
                    + " rearAssistDwell=" + getCandidateDwell(timestampMillis,
                            mRearAssistCandidateStartMs)
                    + " rearConstraintDwell=" + getCandidateDwell(timestampMillis,
                            mRearConstraintCandidateStartMs)
                    + " output=" + outputLux + " mode=" + mode);
        }
    }

    private static long getCandidateDwell(long timestampMillis, long candidateStartMs) {
        return candidateStartMs == Long.MIN_VALUE ? 0
                : Math.max(0, timestampMillis - candidateStartMs);
    }

    private final SensorEventListener mRearListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length > 0 && Float.isFinite(event.values[0])
                    && event.values[0] >= 0) {
                mRearLux = event.values[0];
                // Keep the hardware sample time. ON_CHANGE semantics are handled by the
                // freshness policy; relabeling the sample at callback arrival would make a
                // delayed/replayed event appear newer than the light it actually measured.
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
