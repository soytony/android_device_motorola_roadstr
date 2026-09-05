/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.motorola.display;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.hardware.display.AmbientLuxProcessor;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Handler;
import android.content.res.Resources;
import android.util.Slog;

/** Thin bridge to Motorola's vendor panel ALS compensation service. */
public final class StockAlsCompensationProcessor implements AmbientLuxProcessor {
    private static final String TAG = "StockAlsCompensation";
    private static final String SERVICE = "motorola.hardware.sensors.ISensorExt/default";
    private static final String DESCRIPTOR = "motorola.hardware.sensors.ISensorExt";
    private static final String DEFAULT_SENSOR = "stk_stk3bfx";
    private static final String DEFAULT_CONFIG = "/vendor/etc/sensors/als_comp_config.xml";
    private static final int INIT_ALS_COMP = 1;
    private static final int UPDATE_SENSOR_LUX = 3;
    private static final int DEINIT_ALS_COMP = 8;

    private IBinder mBinder;
    private String mSensorName = DEFAULT_SENSOR;
    private String mConfigPath = DEFAULT_CONFIG;
    private boolean mInitialized;
    private int mSampleCount;

    @Override
    public void initialize(Context context, SensorManager sensorManager, Handler handler,
            String[] parameters) {
        loadMotoResources(context);
        mBinder = ServiceManager.getService(SERVICE);
        if (mBinder == null) {
            Slog.w(TAG, "SensorExt service unavailable");
        }
    }

    private void loadMotoResources(Context context) {
        try {
            final Resources resources = context.createPackageContext("com.motorola.res",
                    Context.CONTEXT_IGNORE_SECURITY).getResources();
            mSensorName = getMotoString(resources, "config_motoStockAlsSensorName", mSensorName);
            mConfigPath = getMotoString(resources, "config_motoStockAlsCompensationConfig",
                    mConfigPath);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "MotoRes package unavailable; using stock defaults", e);
        }
    }

    private static String getMotoString(Resources resources, String name, String fallback) {
        final int id = resources.getIdentifier(name, "string", "com.motorola.res");
        if (id == 0) return fallback;
        final String value = resources.getString(id);
        return value == null || value.isEmpty() ? fallback : value;
    }

    @Override
    public void start() {
        if (mInitialized || mBinder == null) return;
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(mSensorName);
            data.writeString(mConfigPath);
            if (!mBinder.transact(INIT_ALS_COMP, data, reply, 0)) {
                throw new RemoteException("initAlsComp transaction rejected");
            }
            reply.readException();
            final int result = reply.readInt();
            mInitialized = result >= 0;
            Slog.i(TAG, "initAlsComp sensor=" + mSensorName + " config=" + mConfigPath
                    + " result=" + result);
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to initialize SensorExt ALS compensation", t);
            mInitialized = false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    @Override
    public void stop() {
        if (!mInitialized || mBinder == null) return;
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(mSensorName);
            if (mBinder.transact(DEINIT_ALS_COMP, data, reply, 0)) {
                reply.readException();
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to deinitialize SensorExt ALS compensation", t);
        } finally {
            mInitialized = false;
            reply.recycle();
            data.recycle();
        }
    }

    @Override
    public void onTargetNitsChanged(long timestampMillis, float targetNits) {
        // SensorExt reads DBV/DC/display state directly; target feedback is intentionally absent.
    }

    @Override
    public float process(long timestampMillis, float primaryLux, boolean dozing) {
        if (!mInitialized || dozing || !Float.isFinite(primaryLux) || primaryLux < 0) {
            return Float.NaN;
        }
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(mSensorName);
            data.writeFloat(primaryLux);
            // The second vendor argument is reserved by SensorExt; panel state is read by it.
            data.writeFloat(0f);
            if (!mBinder.transact(UPDATE_SENSOR_LUX, data, reply, 0)) {
                return Float.NaN;
            }
            reply.readException();
            final float compensated = reply.readFloat();
            if (mSampleCount++ < 8) {
                Slog.i(TAG, "ALS sample raw=" + primaryLux + " compensated=" + compensated);
            }
            return Float.isFinite(compensated) && compensated >= 0 ? compensated : Float.NaN;
        } catch (Throwable t) {
            Slog.w(TAG, "SensorExt ALS update failed", t);
            return Float.NaN;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    @Override
    public void reset() {
        // Lifecycle is owned by start()/stop(); no local state is retained between samples.
    }
}
