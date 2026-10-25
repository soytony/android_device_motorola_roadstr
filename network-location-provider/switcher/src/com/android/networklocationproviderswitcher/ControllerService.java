// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher;

import android.app.*;
import android.app.Service;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.telephony.*;
import android.util.Log;
import java.util.*;

/** System-user owner. Detection only suggests; explicit commands save next-boot settings. */
public final class ControllerService extends Service {
    static final String CHANNEL = "network_location_suggestions";
    private static final String LEGACY_CHANNEL = "network_location_switches";
    static final int NOTIFICATION = 1;
    private static final String TAG = "NlpSwitcher";
    private HandlerThread thread;
    private Handler worker;
    private SharedPreferences prefs;
    private NotificationManager notifications;
    private SubscriptionManager subscriptions;
    private ConnectivityManager connectivity;
    private SubscriptionManager.OnSubscriptionsChangedListener subscriptionListener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final List<Radio> radios = new ArrayList<>();
    private String candidate = "";
    private long candidateAt;
    private boolean observing;
    private android.database.ContentObserver selectionChanged;
    private final SharedPreferences.OnSharedPreferenceChangeListener stateChanged = (p, key) ->
            getContentResolver().notifyChange(ControlProvider.URI, null, 0, UserHandle.USER_ALL);
    private final Runnable evaluate = this::evaluateCountry;
    private final BroadcastReceiver lifecycle = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            worker.post(() -> {
                if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                    createChannel();
                    publishNotification();
                } else {
                    if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())
                            || Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())) {
                        String pkg = intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart();
                        if (!"com.google.android.gms".equals(pkg)
                                && !"com.baidu.map.location".equals(pkg)) return;
                    }
                    reconcile();
                    getContentResolver().notifyChange(ControlProvider.URI, null, 0, UserHandle.USER_ALL);
                }
            });
        }
    };
    private final class Radio extends TelephonyCallback
            implements TelephonyCallback.ServiceStateListener {
        final TelephonyManager manager;
        Radio(TelephonyManager manager) { this.manager = manager; }
        @Override public void onServiceStateChanged(ServiceState state) { evidenceEvent(); }
    }
    @Override public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) { stopSelf(); return; }
        prefs = getSharedPreferences("controller", MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(stateChanged);
        notifications = getSystemService(NotificationManager.class);
        subscriptions = getSystemService(SubscriptionManager.class);
        connectivity = getSystemService(ConnectivityManager.class);
        thread = new HandlerThread(TAG);
        thread.start();
        worker = new Handler(thread.getLooper());
        createChannel();
        // Retire the old runtime-switching channel's notification.
        notifications.cancelAsUser(null, NOTIFICATION, UserHandle.ALL);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        registerReceiver(lifecycle, filter, null, worker, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(lifecycle, packages, null, worker, Context.RECEIVER_NOT_EXPORTED);
        selectionChanged = new android.database.ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) {
                reconcile();
                getContentResolver().notifyChange(ControlProvider.URI, null, 0, UserHandle.USER_ALL);
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(ControlProvider.SELECTION), false, selectionChanged);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(ControlProvider.BOOT_SELECTION), false, selectionChanged);
        worker.post(() -> { migrate(); reconcile(); updateObservation(); });
    }
    private void createChannel() {
        // Remove the channel used by the superseded runtime-switching design.
        // No notification is posted on it after migration.
        notifications.deleteNotificationChannel(LEGACY_CHANNEL);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        notifications.createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null) return START_NOT_STICKY;
        worker.post(() -> {
            String action = intent == null ? null : intent.getAction();
            if (action == null) {
                // Boot/unlock may arrive after this persistent process first started.
                migrate();
                reconcile();
                if (!observing && prefs.getBoolean("detectionEnabled", true)) updateObservation();
            } else if ("select".equals(action)) {
                String selection = intent.getStringExtra("selection");
                String token = intent.getStringExtra("suggestion");
                if (token != null && !token.equals(prefs.getString("suggestion", ""))) return;
                String expected = intent.getStringExtra("expectedSelection");
                if (expected != null && !expected.equals(selectionOrUnknown())) return;
                if ("google".equals(selection) || "mainland".equals(selection)) saveSelection(selection);
            } else if ("detection".equals(action)) {
                prefs.edit().putBoolean("detectionEnabled", intent.getBooleanExtra("enabled", true))
                        .putString("suggestion", "").apply();
                updateObservation();
                publishNotification();
            } else if ("dismiss".equals(action)) {
                String token = intent.getStringExtra("suggestion");
                if (token != null && token.equals(prefs.getString("suggestion", ""))) {
                    prefs.edit().putString("suggestion", "").apply();
                    publishNotification();
                }
            } else if ("dismissReminder".equals(action)) {
                String token = intent.getStringExtra("reminderToken");
                if (token != null && token.equals(prefs.getString("reminderToken", ""))) {
                    prefs.edit().putBoolean("reminderDismissed", true).apply();
                    publishNotification();
                }
            }
            if (intent != null && intent.hasExtra("commandToken")) {
                prefs.edit().putString("commandToken", intent.getStringExtra("commandToken")).apply();
            }
        });
        return START_STICKY;
    }
    private boolean configuredBaidu() {
        return "mainland".equals(ControlProvider.readSelection(this));
    }
    private String selectionOrUnknown() {
        try { return ControlProvider.readSelection(this); }
        catch (RuntimeException e) { setError("configuration_failed", e); return ""; }
    }
    private void migrate() {
        if (prefs.getInt("schema", 0) >= 4) return;
        // Global selection survives app-data removal; never restore a cached choice over it.
        prefs.edit().putInt("schema", 4).remove("selection").remove("bootSelection")
                .remove("transitionTarget").remove("transitionPrevious")
                .remove("bootMarker").remove("restartBaseline").commit();
    }
    private void setSelection(boolean enabled) {
        if (!Settings.Global.putString(getContentResolver(), ControlProvider.SELECTION,
                enabled ? "mainland" : "google"))
            throw new IllegalStateException("Cannot save provider setting");
    }
    private boolean available(String selection) {
        String pkg = "mainland".equals(selection) ? "com.baidu.map.location" : "com.google.android.gms";
        for (String action : new String[] {"com.android.location.service.v3.NetworkLocationProvider",
                "com.android.location.service.GeocodeProvider"}) {
            if (getPackageManager().queryIntentServices(new Intent(action).setPackage(pkg),
                    PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE).isEmpty()) return false;
        }
        return true;
    }
    private void reconcile() {
        try {
            boolean actual = configuredBaidu();
            String selection = prefs.getString("transitionTarget",
                    actual ? "mainland" : "google");
            if (!available(selection) && prefs.contains("transitionPrevious")) {
                selection = prefs.getString("transitionPrevious", "google");
            }
            if (actual != "mainland".equals(selection)) {
                setSelection("mainland".equals(selection));
            }
            selection = ControlProvider.readSelection(this);
            prefs.edit().remove("transitionTarget")
                    .remove("transitionPrevious").putBoolean("pendingReboot",
                    ControlProvider.pendingReboot(this, selection))
                    .putString("error", available(selection) ? "" : "provider_unavailable").apply();
        } catch (RuntimeException e) { setError("configuration_failed", e); }
        publishNotification();
    }
    private void saveSelection(String selection) {
        if (!available(selection)) {
            setError("provider_unavailable", null);
            return;
        }
        try {
            // Save intent before mutation for process-death recovery.
            String previous = ControlProvider.readSelection(this);
            if (previous.equals(selection)) return;
            prefs.edit().putString("transitionPrevious", previous)
                    .putString("transitionTarget", selection).commit();
            setSelection("mainland".equals(selection));
            if (!selection.equals(ControlProvider.readSelection(this)))
                throw new IllegalStateException("Provider setting readback mismatch");
            prefs.edit().remove("transitionPrevious")
                    .remove("transitionTarget").putString("suggestion", "")
                    .putString("reminderToken", UUID.randomUUID().toString())
                    .putBoolean("reminderDismissed", false)
                    .putString("error", "").putBoolean("pendingReboot",
                    ControlProvider.pendingReboot(this, selection)).commit();
            publishNotification();
        } catch (RuntimeException e) {
            prefs.edit().remove("transitionPrevious").remove("transitionTarget").apply();
            setError("configuration_failed", e);
        }
    }
    private void setError(String key, RuntimeException error) {
        prefs.edit().putString("error", key).apply();
        if (error != null) Log.e(TAG, key, error);
    }
    private void updateObservation() {
        stopObservation();
        candidate = "";
        worker.removeCallbacks(evaluate);
        prefs.edit().putString("evidence", "").apply();
        if (!prefs.getBoolean("detectionEnabled", true)) return;
        observing = true;
        subscriptionListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
            @Override public void onSubscriptionsChanged() { registerRadios(); }
        };
        try {
            subscriptions.addOnSubscriptionsChangedListener(worker::post, subscriptionListener);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { worker.post(() -> evidenceEvent()); }
                @Override public void onLost(Network network) { worker.post(() -> evidenceEvent()); }
            };
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    networkCallback, worker);
            registerRadios();
        } catch (RuntimeException e) {
            Log.w(TAG, "Network evidence unavailable", e);
        }
    }
    private void registerRadios() {
        if (!observing) return;
        clearRadios();
        candidate = "";
        worker.removeCallbacks(evaluate);
        try {
            List<SubscriptionInfo> list = subscriptions.getActiveSubscriptionInfoList();
            if (list != null) for (SubscriptionInfo info : list) {
                Radio radio = new Radio(getSystemService(TelephonyManager.class)
                        .createForSubscriptionId(info.getSubscriptionId()));
                radio.manager.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE,
                        worker::post, radio);
                radios.add(radio);
            }
        } catch (RuntimeException e) { Log.w(TAG, "Telephony evidence unavailable", e); }
        evidenceEvent();
    }
    private String country() {
        boolean outside = false;
        boolean cn = false;
        for (Radio radio : radios) {
            try {
                ServiceState state = radio.manager.getServiceState(
                        TelephonyManager.INCLUDE_LOCATION_DATA_NONE);
                if (state == null || (state.getState() != ServiceState.STATE_IN_SERVICE
                        && state.getDataRegistrationState() != ServiceState.STATE_IN_SERVICE)) continue;
                String operator = state.getOperatorNumeric();
                String iso = operator != null && operator.length() >= 3
                        ? CountryPolicy.countryForMcc(operator.substring(0, 3)) : "";
                if (iso.isEmpty()) iso = radio.manager.getNetworkCountryIso();
                if ("cn".equalsIgnoreCase(iso)) cn = true;
                else if (iso != null && iso.length() == 2) outside = true;
            } catch (RuntimeException e) { Log.d(TAG, "No registered country"); }
        }
        prefs.edit().putBoolean("conflictingNetworks", cn && outside).apply();
        return cn ? "CN" : outside ? "OTHER" : "";
    }
    private void evidenceEvent() {
        if (!observing) return;
        String country = country();
        prefs.edit().putString("evidence", country)
                .putLong("evidenceTime", System.currentTimeMillis()).apply();
        if (!country.equals(candidate)) {
            candidate = country;
            candidateAt = SystemClock.elapsedRealtime();
            worker.removeCallbacks(evaluate);
        }
        if (!country.isEmpty() && !worker.hasCallbacks(evaluate)) {
            worker.postDelayed(evaluate,
                    Math.max(0, 30_000 - (SystemClock.elapsedRealtime() - candidateAt)));
        }
    }
    private void evaluateCountry() {
        if (!observing) return;
        String current = country();
        if (!current.equals(candidate)) { evidenceEvent(); return; }
        if (current.isEmpty()) return;
        String last = prefs.getString("confirmedCountry", "");
        if (!current.equals(last)) {
            prefs.edit().putString("confirmedCountry", current)
                    .putBoolean("entryHandled", false).putString("suggestion", "").apply();
        }
        if ("CN".equals(current) && !prefs.getBoolean("entryHandled", false)) {
            prefs.edit().putBoolean("entryHandled", true).putString("suggestion",
                    "google".equals(selectionOrUnknown())
                    ? UUID.randomUUID().toString() : "").apply();
        }
        publishNotification();
    }
    private void publishNotification() {
        boolean pending = prefs.getBoolean("pendingReboot", false);
        String suggestion = prefs.getString("suggestion", "");
        if ((pending && prefs.getBoolean("reminderDismissed", false))
                || (!pending && (suggestion.isEmpty() || !prefs.getBoolean("detectionEnabled", true)))) {
            notifications.cancel(NOTIFICATION);
            return;
        }
        Intent review = new Intent(this, LocationActivity.class)
                .setAction(pending ? "android.intent.action.MAIN" : "review")
                .putExtra("suggestion", suggestion)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(pending ? R.string.reboot_reminder : R.string.suggestion))
                .setContentIntent(PendingIntent.getActivity(this, pending ? 1 : 2, review,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOnlyAlertOnce(true).setSilent(true).setAutoCancel(!pending);
        if (pending) {
            String token = prefs.getString("reminderToken", "");
            builder.setDeleteIntent(PendingIntent.getService(this, token.hashCode(),
                    new Intent(this, ControllerService.class).setAction("dismissReminder")
                            .setData(Uri.parse("nlpswitcher:reminder/" + token))
                            .putExtra("reminderToken", token),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            PendingIntent dismiss = PendingIntent.getService(this, suggestion.hashCode(),
                    new Intent(this, ControllerService.class).setAction("dismiss")
                            .putExtra("suggestion", suggestion),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setDeleteIntent(dismiss);
            builder.addAction(new Notification.Action.Builder(null, getString(R.string.review),
                    PendingIntent.getActivity(this, 2, review,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
            builder.addAction(new Notification.Action.Builder(null, getString(R.string.not_now),
                    dismiss).build());
        }
        notifications.notify(NOTIFICATION, builder.build());
    }
    private void clearRadios() {
        for (Radio radio : radios) {
            try { radio.manager.unregisterTelephonyCallback(radio); }
            catch (RuntimeException e) { Log.w(TAG, "Unregister callback", e); }
        }
        radios.clear();
    }
    private void stopObservation() {
        observing = false;
        clearRadios();
        if (subscriptionListener != null) subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener);
        subscriptionListener = null;
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) {}
        }
        networkCallback = null;
    }
    @Override public void onDestroy() {
        if (worker != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(stateChanged);
            unregisterReceiver(lifecycle);
            getContentResolver().unregisterContentObserver(selectionChanged);
            worker.post(() -> { stopObservation(); thread.quitSafely(); });
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
