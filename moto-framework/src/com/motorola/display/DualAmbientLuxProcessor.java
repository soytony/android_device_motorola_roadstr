/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.motorola.display;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.AmbientLuxProcessor;
import android.os.Handler;

/** Rear-reference and panel-leakage policy for automatic brightness. */
public final class DualAmbientLuxProcessor implements AmbientLuxProcessor {
    private static final String REAR_LIGHT_TYPE = "com.motorola.sensor.alternate_light";
    private static final long DEFAULT_REAR_SAMPLE_MAX_AGE_MS = 2_000;
    private static final float DEFAULT_MAX_LEAKAGE_LUX = 1_200f;
    private static final float DEFAULT_LIGHT_THEME_LEAK_FACTOR = 0.055f;
    private static final float DEFAULT_DARK_THEME_LEAK_FACTOR = 0.025f;
    private static final float DEFAULT_REAR_WEIGHT = 0.85f;

    private SensorManager mSensorManager;
    private Sensor mRearSensor;
    private Handler mHandler;
    private float mRearLux = Float.NaN;
    private long mRearTimestampMs = Long.MIN_VALUE;
    private float mLastPrimaryLux = Float.NaN;
    private float mLastTargetNits = Float.NaN;
    private float mLearnedLeakageLux;
    private long mRearSampleMaxAgeMs = DEFAULT_REAR_SAMPLE_MAX_AGE_MS;
    private float mMaxLeakageLux = DEFAULT_MAX_LEAKAGE_LUX;
    private float mLightThemeLeakFactor = DEFAULT_LIGHT_THEME_LEAK_FACTOR;
    private float mDarkThemeLeakFactor = DEFAULT_DARK_THEME_LEAK_FACTOR;
    private float mRearWeight = DEFAULT_REAR_WEIGHT;

    @Override
    public void initialize(Context context, SensorManager sensorManager, Handler handler,
            String[] parameters) {
        mSensorManager = sensorManager;
        mHandler = handler;
        applyParameters(parameters);
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (REAR_LIGHT_TYPE.equals(sensor.getStringType())) {
                mRearSensor = sensor;
                break;
            }
        }
    }

    @Override
    public void start() {
        reset();
        if (mRearSensor != null) {
            mSensorManager.registerListener(mRearListener, mRearSensor, 100_000, mHandler);
        }
    }

    @Override
    public void stop() {
        mSensorManager.unregisterListener(mRearListener);
        reset();
    }

    @Override
    public float process(long timestampMillis, float primaryLux, float targetNits,
            boolean dozing, boolean darkTheme) {
        if (!Float.isFinite(primaryLux) || primaryLux < 0 || dozing) {
            return Float.NaN;
        }

        final boolean rearUsable = Float.isFinite(mRearLux) && mRearLux >= 0
                && timestampMillis >= mRearTimestampMs
                && timestampMillis - mRearTimestampMs <= mRearSampleMaxAgeMs;
        final float panelDelta = Float.isFinite(mLastTargetNits) && Float.isFinite(targetNits)
                ? targetNits - mLastTargetNits : 0;
        final float primaryDelta = Float.isFinite(mLastPrimaryLux)
                ? primaryLux - mLastPrimaryLux : 0;
        if (rearUsable && panelDelta > 2f && Math.abs(mRearLux) < 10f && primaryDelta > 2f) {
            mLearnedLeakageLux = Math.min(mMaxLeakageLux,
                    mLearnedLeakageLux + primaryDelta * 0.5f);
        } else if (panelDelta < -2f) {
            mLearnedLeakageLux *= 0.7f;
        }
        mLastPrimaryLux = primaryLux;
        mLastTargetNits = targetNits;

        final float staticLeakage = Math.min(mMaxLeakageLux,
                Math.max(0, Float.isFinite(targetNits) ? targetNits
                        * (darkTheme ? mDarkThemeLeakFactor : mLightThemeLeakFactor) : 0));
        final float correctedPrimary = Math.max(0, primaryLux - staticLeakage - mLearnedLeakageLux);
        if (!rearUsable) {
            return correctedPrimary;
        }
        return Math.max(correctedPrimary, mRearLux * mRearWeight);
    }

    @Override
    public void reset() {
        mRearLux = Float.NaN;
        mRearTimestampMs = Long.MIN_VALUE;
        mLastPrimaryLux = Float.NaN;
        mLastTargetNits = Float.NaN;
        mLearnedLeakageLux = 0;
    }

    private void applyParameters(String[] parameters) {
        for (String parameter : parameters) {
            final int separator = parameter.indexOf('=');
            if (separator <= 0 || separator == parameter.length() - 1) {
                continue;
            }
            try {
                final String name = parameter.substring(0, separator);
                final String value = parameter.substring(separator + 1);
                switch (name) {
                    case "rear_sample_max_age_ms":
                        mRearSampleMaxAgeMs = Math.max(0, Long.parseLong(value));
                        break;
                    case "max_leakage_lux":
                        mMaxLeakageLux = Math.max(0, Float.parseFloat(value));
                        break;
                    case "light_theme_leak_factor":
                        mLightThemeLeakFactor = Math.max(0, Float.parseFloat(value));
                        break;
                    case "dark_theme_leak_factor":
                        mDarkThemeLeakFactor = Math.max(0, Float.parseFloat(value));
                        break;
                    case "rear_weight":
                        mRearWeight = Math.max(0, Float.parseFloat(value));
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed optional RRO entries and retain the safe default.
            }
        }
    }

    private final SensorEventListener mRearListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length > 0 && Float.isFinite(event.values[0])
                    && event.values[0] >= 0) {
                mRearLux = event.values[0];
                mRearTimestampMs = event.timestamp / 1_000_000L;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
}
