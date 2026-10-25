// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderbroker;

import android.app.Service;
import android.content.*;
import android.provider.Settings;
import android.location.Address;
import android.location.Location;
import android.location.provider.*;
import android.os.*;
import android.os.OutcomeReceiver;

import java.util.List;
import java.util.ArrayDeque;

/** Boot-stable provider endpoint. Delegates to the provider selected once per boot from Settings.Global. */
public final class ProviderBrokerService extends Service {
    private static final String SELECTION = "network_location_provider_selection";
    private static final String BOOT_SELECTION = "network_location_provider_boot_selection";
    private String selected;
    private static final String BAIDU = "com.baidu.map.location";
    private static final String GOOGLE = "com.google.android.gms";
    private static final ComponentName BAIDU_LOCATION = new ComponentName(BAIDU,
            "com.baidu.map.location.BaiduNetworkLocationService");
    private static final ComponentName GOOGLE_LOCATION = new ComponentName(GOOGLE,
            "com.google.android.location.network.NetworkLocationService");
    private static final ComponentName BAIDU_GEOCODE = new ComponentName(BAIDU,
            "com.baidu.map.location.BaiduNetworkLocationService");
    private static final ComponentName GOOGLE_GEOCODE = new ComponentName(GOOGLE,
            "com.google.android.gms.location.geocode.GeocodeService");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ChildConnection networkConnection;
    private ChildConnection geocodeConnection;
    private NetworkEndpoint network;
    private GeocodeEndpoint geocode;
    private final BroadcastReceiver unlockedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (networkConnection != null) networkConnection.retryIfDisconnected();
            if (geocodeConnection != null) geocodeConnection.retryIfDisconnected();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        String boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1) + ":";
        String pinned = Settings.Global.getString(getContentResolver(), BOOT_SELECTION);
        if (pinned != null && pinned.startsWith(boot)) {
            selected = pinned.substring(boot.length());
        } else {
            selected = Settings.Global.getString(getContentResolver(), SELECTION);
            if (selected == null) selected = "google";
            if (!"google".equals(selected) && !"mainland".equals(selected))
                throw new IllegalStateException("Invalid provider setting");
            if (!Settings.Global.putString(getContentResolver(), BOOT_SELECTION, boot + selected))
                throw new IllegalStateException("Cannot pin boot provider");
        }
        if (!"google".equals(selected) && !"mainland".equals(selected))
            throw new IllegalStateException("Invalid boot provider");
        registerReceiver(unlockedReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // Start both child bindings as soon as broker process starts. Android may
        // bind geocoding later than network location; lazy endpoint creation then
        // leaves geocoder unavailable during early boot.
        network = new NetworkEndpoint(this);
        geocode = new GeocodeEndpoint(this);
    }
    private boolean baiduSelected() { return "mainland".equals(selected); }
    private ComponentName child(boolean geo) {
        if (geo) return baiduSelected() ? BAIDU_GEOCODE : GOOGLE_GEOCODE;
        return baiduSelected() ? BAIDU_LOCATION : GOOGLE_LOCATION;
    }
    @Override public IBinder onBind(Intent intent) {
        if (LocationProviderBase.ACTION_NETWORK_PROVIDER.equals(intent.getAction())) {
            return network.getBinder();
        }
        if (GeocodeProviderBase.ACTION_GEOCODE_PROVIDER.equals(intent.getAction())) {
            return geocode.getBinder();
        }
        return null;
    }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        out.println("bootSelection=" + selected);
        out.println("networkTarget=" + child(false).flattenToShortString());
        out.println("networkConnected=" + (network != null && network.child != null));
        out.println("geocodeTarget=" + child(true).flattenToShortString());
        out.println("geocodeConnected=" + (geocode != null && geocode.child != null));
        if (networkConnection != null) networkConnection.dump(out);
        if (geocodeConnection != null) geocodeConnection.dump(out);
    }
    @Override public void onDestroy() {
        if (network != null && network.child != null) {
            try { network.child.setRequest(ProviderRequest.EMPTY_REQUEST); }
            catch (RemoteException ignored) { }
        }
        unregisterReceiver(unlockedReceiver);
        if (networkConnection != null) networkConnection.close();
        if (geocodeConnection != null) geocodeConnection.close();
        super.onDestroy();
    }

    /** Owns one binding, including unavailable-at-boot and package-update recovery. */
    private abstract class ChildConnection implements ServiceConnection {
        private final Intent intent;
        private final Runnable retry = this::bind;
        private boolean bound;
        private boolean connected;
        private boolean closed;
        private long retryDelay = 1000;

        ChildConnection(String action, ComponentName target) {
            intent = new Intent(action).setComponent(target);
        }

        final void bind() {
            handler.removeCallbacks(retry);
            if (closed || bound) return;
            try {
                bound = bindService(intent, this, BIND_AUTO_CREATE | BIND_NOT_FOREGROUND);
            } catch (SecurityException e) {
                android.util.Log.e("NlpBroker", "Cannot bind " + intent.getComponent(), e);
            }
            if (!bound) {
                android.util.Log.w("NlpBroker", "Binding unavailable: " + intent.getComponent());
                scheduleRetry();
            }
        }

        private void scheduleRetry() {
            handler.removeCallbacks(retry);
            if (closed) return;
            handler.postDelayed(retry, retryDelay);
            retryDelay = Math.min(retryDelay * 2, 60000);
        }

        final void retryIfDisconnected() {
            if (closed || connected) return;
            release();
            retryDelay = 1000;
            bind();
        }

        private void release() {
            if (bound) unbindService(this);
            bound = false;
            connected = false;
            disconnected();
        }

        final void close() {
            closed = true;
            handler.removeCallbacks(retry);
            release();
        }

        @Override public final void onServiceConnected(ComponentName name, IBinder binder) {
            connected = true;
            retryDelay = 1000;
            android.util.Log.i("NlpBroker", "Connected " + name);
            connected(binder);
        }

        @Override public final void onServiceDisconnected(ComponentName name) {
            connected = false;
            disconnected();
            // ActivityManager reconnects a binding after ordinary child process death.
        }

        @Override public final void onBindingDied(ComponentName name) {
            android.util.Log.w("NlpBroker", "Binding died: " + name);
            release();
            scheduleRetry();
        }

        @Override public final void onNullBinding(ComponentName name) {
            android.util.Log.w("NlpBroker", "Null binding: " + name);
            release();
            scheduleRetry();
        }

        final void dump(java.io.PrintWriter out) {
            out.println("binding=" + intent.getComponent().flattenToShortString()
                    + " bound=" + bound + " connected=" + connected);
        }

        abstract void connected(IBinder binder);
        abstract void disconnected();
    }

    private final class NetworkEndpoint extends LocationProviderBase {
        private volatile ILocationProvider child;
        NetworkEndpoint(Context c) {
            super(c, "NetworkLocationProviderBroker", new ProviderProperties.Builder()
                    .setHasNetworkRequirement(true).setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_MEDIUM).build());
            networkConnection = new ChildConnection(LocationProviderBase.ACTION_NETWORK_PROVIDER,
                    child(false)) {
                @Override void connected(IBinder b) {
                    child = ILocationProvider.Stub.asInterface(b);
                    try { child.setLocationProviderManager(new ChildManager());
                        if (lastRequest != null) child.setRequest(lastRequest); }
                    catch (RemoteException e) { setAllowed(false); }
                }
                @Override void disconnected() {
                    child = null;
                    setAllowed(false);
                    synchronized (flushes) {
                        while (!flushes.isEmpty()) flushes.poll().onFlushComplete();
                    }
                }
            };
            setAllowed(false);
            networkConnection.bind();
        }
        private volatile ProviderRequest lastRequest;
        private final ArrayDeque<OnFlushCompleteCallback> flushes = new ArrayDeque<>();
        @Override public void onSetRequest(ProviderRequest request) {
            lastRequest = request; try { if (child != null) child.setRequest(request); }
            catch (RemoteException e) { setAllowed(false); }
        }
        @Override public void onFlush(OnFlushCompleteCallback callback) {
            synchronized (flushes) {
                if (child == null) { callback.onFlushComplete(); return; }
                flushes.add(callback);
                try { child.flush(); }
                catch (RemoteException e) { flushes.remove(callback); callback.onFlushComplete(); }
            }
        }
        @Override public void onSendExtraCommand(String command, Bundle extras) {
            try { if (child != null) child.sendExtraCommand(command, extras); }
            catch (RemoteException ignored) { }
        }
        private final class ChildManager extends ILocationProviderManager.Stub {
            public void onInitialize(boolean a, ProviderProperties p, String t) { setAllowed(a); setProperties(p); }
            public void onSetAllowed(boolean a) { setAllowed(a); }
            public void onSetProperties(ProviderProperties p) { setProperties(p); }
            public void onReportLocation(Location l) { reportLocation(l); }
            public void onReportLocations(List<Location> l) { reportLocations(l); }
            public void onFlushComplete() {
                OnFlushCompleteCallback callback;
                synchronized (flushes) { callback = flushes.poll(); }
                if (callback != null) callback.onFlushComplete();
            }
        }
    }

    private final class GeocodeEndpoint extends GeocodeProviderBase {
        private volatile IGeocodeProvider child;
        GeocodeEndpoint(Context c) {
            super(c, "NetworkLocationProviderBroker");
            geocodeConnection = new ChildConnection(GeocodeProviderBase.ACTION_GEOCODE_PROVIDER,
                    child(true)) {
                @Override void connected(IBinder b) { child = IGeocodeProvider.Stub.asInterface(b); }
                @Override void disconnected() { child = null; }
            };
            geocodeConnection.bind();
        }
        @Override public void onForwardGeocode(ForwardGeocodeRequest r,
                OutcomeReceiver<List<Address>, Throwable> cb) {
            try { if (child == null) { cb.onError(new IllegalStateException("child unavailable")); return; }
                child.forwardGeocode(r, callback(cb)); } catch (RemoteException e) { cb.onError(e); }
        }
        @Override public void onReverseGeocode(ReverseGeocodeRequest r,
                OutcomeReceiver<List<Address>, Throwable> cb) {
            try { if (child == null) { cb.onError(new IllegalStateException("child unavailable")); return; }
                child.reverseGeocode(r, callback(cb)); } catch (RemoteException e) { cb.onError(e); }
        }
        private IGeocodeCallback callback(OutcomeReceiver<List<Address>, Throwable> cb) {
            return new IGeocodeCallback.Stub() {
                public void onError(String e) { cb.onError(new RuntimeException(e)); }
                public void onResults(List<Address> a) { cb.onResult(a); }
            };
        }
    }
}
