// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher.tests;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Address;
import android.location.provider.ForwardGeocodeRequest;
import android.location.provider.IGeocodeCallback;
import android.location.provider.IGeocodeProvider;
import android.location.provider.ReverseGeocodeRequest;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only backend check; does not alter RROs or framework supplier bindings. */
final class DirectBaiduGeocoder {
    private static final ComponentName COMPONENT = new ComponentName(
            "com.baidu.map.location", "com.baidu.map.location.BaiduNetworkLocationService");

    private static final class Result extends IGeocodeCallback.Stub {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile String summary;
        @Override public void onError(String error) {
            // Do not retain provider error text, which may include request details.
            summary = "ERROR";
            ready.countDown();
        }
        @Override public void onResults(List<Address> addresses) {
            summary = addresses == null || addresses.isEmpty() ? "EMPTY"
                    : "PASS; count=" + addresses.size()
                    + "; country=" + addresses.get(0).getCountryCode();
            ready.countDown();
        }
        void await(Bundle output, String key) throws Exception {
            if (!ready.await(45, TimeUnit.SECONDS))
                throw new AssertionError(key + " callback timeout");
            output.putString(key, summary);
            if (!summary.startsWith("PASS")) throw new AssertionError(key + " " + summary);
        }
    }

    static void run(Context context, Bundle output) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IBinder> binder = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (COMPONENT.equals(name)) binder.set(service);
                else failure.set("Unexpected service component");
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
            @Override public void onNullBinding(ComponentName name) {
                failure.set("Null binding");
                connected.countDown();
            }
            @Override public void onBindingDied(ComponentName name) {
                failure.set("Binding died");
                connected.countDown();
            }
        };
        boolean bound = context.bindService(new Intent(
                "com.android.location.service.GeocodeProvider").setComponent(COMPONENT),
                connection, Context.BIND_AUTO_CREATE);
        try {
            if (!bound) throw new AssertionError("Baidu service not bound");
            if (!connected.await(20, TimeUnit.SECONDS))
                throw new AssertionError("Baidu service connection timeout");
            if (failure.get() != null) throw new AssertionError(failure.get());
            if (binder.get() == null) throw new AssertionError("Missing Baidu binder");
            output.putString("directComponent", COMPONENT.flattenToString());
            output.putString("directDescriptor", binder.get().getInterfaceDescriptor());
            IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder.get());
            // Synthetic Shanghai request, not the device's location. Preserve real test UID/package.
            Result reverse = new Result();
            provider.reverseGeocode(new ReverseGeocodeRequest.Builder(
                    31.2304, 121.4737, 1, Locale.ENGLISH, Process.myUid(),
                    context.getPackageName()).build(), reverse);
            reverse.await(output, "directReverseGeocode");
            Result forward = new Result();
            provider.forwardGeocode(new ForwardGeocodeRequest.Builder(
                    "Shanghai", 0, 0, 0, 0, 1, Locale.ENGLISH, Process.myUid(),
                    context.getPackageName()).build(), forward);
            forward.await(output, "directForwardGeocode");
        } finally {
            if (bound) context.unbindService(connection);
        }
    }
}
