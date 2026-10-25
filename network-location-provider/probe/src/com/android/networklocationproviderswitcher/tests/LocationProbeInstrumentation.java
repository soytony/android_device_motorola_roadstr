// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.location.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Functional probe uses stock APIs; external dumps verify actual provider bindings. */
public final class LocationProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final Bundle results = new Bundle();
    private Context context;
    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private void geocode(double latitude, double longitude) throws Exception {
        Geocoder geocoder = new Geocoder(context, Locale.ENGLISH);
        CountDownLatch reverseReady = new CountDownLatch(1);
        AtomicReference<String> reverse = new AtomicReference<>();
        geocoder.getFromLocation(latitude, longitude, 1, new Geocoder.GeocodeListener() {
            @Override public void onGeocode(List<Address> addresses) {
                reverse.set(addresses.isEmpty() ? "EMPTY" : "PASS; country="
                        + addresses.get(0).getCountryCode());
                reverseReady.countDown();
            }
            @Override public void onError(String error) {
                reverse.set("ERROR");
                reverseReady.countDown();
            }
        });
        require(reverseReady.await(45, TimeUnit.SECONDS), "Reverse geocoder timeout");
        results.putString("reverseGeocode", reverse.get());
        require(reverse.get().startsWith("PASS"), "Reverse geocoder failed");

        CountDownLatch forwardReady = new CountDownLatch(1);
        AtomicReference<String> forward = new AtomicReference<>();
        geocoder.getFromLocationName("Shanghai", 1, new Geocoder.GeocodeListener() {
            @Override public void onGeocode(List<Address> addresses) {
                forward.set(addresses.isEmpty() ? "EMPTY" : "PASS; country="
                        + addresses.get(0).getCountryCode());
                forwardReady.countDown();
            }
            @Override public void onError(String error) {
                forward.set("ERROR");
                forwardReady.countDown();
            }
        });
        require(forwardReady.await(45, TimeUnit.SECONDS), "Forward geocoder timeout");
        results.putString("forwardGeocode", forward.get());
        require(forward.get().startsWith("PASS"), "Forward geocoder failed");
    }

    @Override public void onStart() {
        context = getTargetContext();
        try {
            String action = arguments.getString("action", "geocoderOnly");
            if ("select".equals(action) || "detection".equals(action) || "status".equals(action)) {
                Bundle state = context.getContentResolver().call(Uri.parse(
                        "content://com.android.networklocationproviderswitcher.control"),
                        action, arguments.getString("value"), null);
                if (!"status".equals(action)) {
                    String token = state.getString("issuedToken");
                    long deadline = SystemClock.elapsedRealtime() + 10_000;
                    do {
                        Thread.sleep(100);
                        state = context.getContentResolver().call(Uri.parse(
                                "content://com.android.networklocationproviderswitcher.control"),
                                "status", null, null);
                    } while (!token.equals(state.getString("commandToken"))
                            && SystemClock.elapsedRealtime() < deadline);
                    require(token.equals(state.getString("commandToken")), "Command not acknowledged");
                }
                results.putBundle("state", state);
            } else if ("directBaiduGeocoder".equals(action)) {
                DirectBaiduGeocoder.run(context, results);
            } else if ("brokerPermission".equals(action)) {
                BrokerSecurityProbe.run(context, results);
            } else if ("flush".equals(action)) {
                LocationManager manager = context.getSystemService(LocationManager.class);
                require(manager.isLocationEnabled(), "Location setting is disabled");
                CountDownLatch flushed = new CountDownLatch(1);
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) { }
                    @Override public void onFlushComplete(int requestCode) {
                        if (requestCode == 42) flushed.countDown();
                    }
                };
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0,
                        context.getMainExecutor(), listener);
                try {
                    // Let LocationManager register the request before asking the provider to flush.
                    Thread.sleep(750);
                    manager.requestFlush(LocationManager.NETWORK_PROVIDER, listener, 42);
                    require(flushed.await(30, TimeUnit.SECONDS), "Provider flush callback timeout");
                    results.putString("flush", "PASS");
                } finally {
                    manager.removeUpdates(listener);
                }
            } else if ("cancelRequest".equals(action)) {
                LocationManager manager = context.getSystemService(LocationManager.class);
                require(manager.isLocationEnabled(), "Location setting is disabled");
                LocationListener listener = location -> { };
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0,
                        context.getMainExecutor(), listener);
                try {
                    results.putString("requestRegistered", "PASS");
                    // Host observes the live registration before this action cancels it.
                    Thread.sleep(8000);
                } finally {
                    manager.removeUpdates(listener);
                }
                results.putString("requestCancelled", "PASS");
            } else if ("geocoderOnly".equals(action)) {
                geocode(31.2304, 121.4737);
            } else if ("freshFix".equals(action) || "freshFixWithGeocodeActive".equals(action)) {
                LocationManager manager = context.getSystemService(LocationManager.class);
                require(manager.isLocationEnabled(), "Location setting is disabled");
                long requestedAt = SystemClock.elapsedRealtimeNanos();
                AtomicReference<Location> fix = new AtomicReference<>();
                CountDownLatch ready = new CountDownLatch(1);
                LocationListener listener = location -> {
                    if (location.getElapsedRealtimeNanos() >= requestedAt) {
                        fix.set(location); ready.countDown();
                    }
                };
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0,
                        context.getMainExecutor(), listener);
                try {
                    require(ready.await(60, TimeUnit.SECONDS), "Fresh network fix timeout");
                    results.putString("networkFix", "PASS; accuracyMeters=" + fix.get().getAccuracy()
                            + "; ageMillis=" + fix.get().getElapsedRealtimeAgeMillis());
                    if ("freshFix".equals(action)) manager.removeUpdates(listener);
                    geocode(fix.get().getLatitude(), fix.get().getLongitude());
                } finally { manager.removeUpdates(listener); }
            } else throw new IllegalArgumentException("Unknown probe action");
            finish(-1, results);
        } catch (Throwable error) {
            results.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(0, results);
        }
    }
}
