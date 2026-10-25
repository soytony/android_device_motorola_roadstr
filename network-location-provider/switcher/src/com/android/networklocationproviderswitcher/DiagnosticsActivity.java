// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.preference.*;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import java.text.DateFormat;
import java.util.*;

/** Foreground-only, ordinary-UID diagnostics. Never binds a backend directly. */
public final class DiagnosticsActivity extends CollapsingToolbarBaseActivity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.diag_app_name);
        FrameLayout content = new FrameLayout(this);
        content.setId(0x123457);
        setContentView(content);
        if (saved == null) getSupportFragmentManager().beginTransaction()
                .replace(content.getId(), new ReportFragment()).commit();
    }

    public static final class ReportFragment extends PreferenceFragmentCompat {
        private static final String SELECTION = "network_location_provider_selection";
        private static final String PIN = "network_location_provider_boot_selection";
        private static final long FIX_LIMIT = 60_000, GEOCODE_LIMIT = 45_000;
        private final Handler main = new Handler(Looper.getMainLooper());
        private LocationManager locations;
        private LocationListener listener;
        private Preference start, cancel, share, context, position, forward, reverse, technical;
        private SwitchPreferenceCompat include;
        private boolean active, awaitingPermission, permissionResultPending, foreground;
        private int generation, updates, freshUpdates;
        private long started, wallStarted, firstFix = -1, finished;
        private String configuration, activeChoice, savedChoice, notice = "";
        private Location latest;
        private Outcome fix = new Outcome(), forwardResult = new Outcome(), reverseResult = new Outcome();
        private final ContentObserver observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                if (active && !Objects.equals(configuration, configuration())) {
                    stop(); notice = text(R.string.diag_provider_changed); render();
                }
            }
        };
        private static final class Outcome {
            int status = R.string.diag_not_tested;
            long latency = -1;
            String address, country;
        }
        private String text(int id) { return getString(id); }
        private String seconds(long ms) { return String.format(Locale.getDefault(), "%.1f s", ms / 1000.0); }
        private String number(double value, String unit) {
            return String.format(Locale.getDefault(), "%.2f%s", value, unit);
        }
        private String date(long time) { return DateFormat.getDateTimeInstance().format(new Date(time)); }
        private void line(StringBuilder out, int key, String value) {
            out.append(text(key)).append(": ").append(value).append('\n');
        }
        private String choice(String value) {
            return "google".equals(value) ? text(R.string.diag_google) : "mainland".equals(value)
                    ? text(R.string.diag_mainland) : text(R.string.diag_unknown);
        }
        private String configured() {
            String value = Settings.Global.getString(requireContext().getContentResolver(), SELECTION);
            return value == null ? "google" : value;
        }
        private String pinned() {
            ContentResolver resolver = requireContext().getContentResolver();
            String prefix = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1) + ":";
            String value = Settings.Global.getString(resolver, PIN);
            return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : "";
        }
        private String configuration() { return pinned() + "|" + configured(); }
        @Override public void onCreatePreferences(Bundle saved, String key) {
            locations = requireContext().getSystemService(LocationManager.class);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            setPreferenceScreen(screen);
            Preference intro = new Preference(requireContext());
            intro.setSummary(R.string.diag_intro); intro.setSelectable(false); screen.addPreference(intro);
            start = action(screen, R.string.diag_start, () -> requestTest());
            cancel = action(screen, R.string.diag_cancel, () -> { stop(); render(); });
            context = section(screen, R.string.diag_context);
            position = section(screen, R.string.diag_position);
            forward = section(screen, R.string.diag_forward);
            reverse = section(screen, R.string.diag_reverse);
            technical = section(screen, R.string.diag_technical);
            include = new SwitchPreferenceCompat(requireContext());
            include.setTitle(R.string.diag_include); include.setSummary(R.string.diag_include_summary);
            include.setPersistent(false); include.setChecked(false); screen.addPreference(include);
            share = action(screen, R.string.diag_share, () -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, report(include.isChecked()));
                startActivity(Intent.createChooser(send, text(R.string.diag_share_title)));
            });
            render();
        }
        private Preference action(PreferenceGroup group, int title, Runnable action) {
            Preference p = new Preference(requireContext()); p.setTitle(title);
            p.setOnPreferenceClickListener(unused -> { action.run(); return true; });
            group.addPreference(p); return p;
        }
        private Preference section(PreferenceGroup group, int title) {
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(title); group.addPreference(category);
            Preference p = new Preference(requireContext()) {
                @Override public void onBindViewHolder(PreferenceViewHolder holder) {
                    super.onBindViewHolder(holder);
                    TextView summary = (TextView) holder.findViewById(android.R.id.summary);
                    if (summary != null) { summary.setMaxLines(Integer.MAX_VALUE); summary.setTextIsSelectable(true); }
                }
            };
            p.setSelectable(false); category.addPreference(p); return p;
        }
        @Override public void onStart() {
            super.onStart(); foreground = true;
            ContentResolver resolver = requireContext().getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(SELECTION), false, observer);
            resolver.registerContentObserver(Settings.Global.getUriFor(PIN), false, observer);
            if (permissionResultPending) {
                permissionResultPending = false;
                begin();
            }
            render();
        }
        @Override public void onStop() {
            foreground = false;
            stop(); requireContext().getContentResolver().unregisterContentObserver(observer);
            latest = null;
            forwardResult.address = reverseResult.address = null;
            forwardResult.country = reverseResult.country = null;
            fix = new Outcome(); forwardResult = new Outcome(); reverseResult = new Outcome();
            activeChoice = savedChoice = configuration = null;
            started = wallStarted = finished = 0;
            updates = freshUpdates = 0; firstFix = -1; notice = "";
            include.setChecked(false);
            super.onStop();
        }
        @Override public void onDestroy() {
            stop(); main.removeCallbacksAndMessages(null); latest = null;
            forwardResult.address = null; reverseResult.address = null;
            super.onDestroy();
        }
        private boolean has(String permission) {
            return requireContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        private void requestTest() {
            if (active || awaitingPermission) return;
            if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                awaitingPermission = true; permissionResultPending = false;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 41);
            } else begin();
        }
        @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
            super.onRequestPermissionsResult(request, permissions, grants);
            if (request == 41 && awaitingPermission) {
                awaitingPermission = false;
                if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    if (foreground) begin(); else permissionResultPending = true;
                } else render();
            }
        }
        private void begin() {
            stop();
            active = true; generation++;
            final int run = generation;
            started = SystemClock.elapsedRealtime(); wallStarted = System.currentTimeMillis(); finished = 0;
            updates = freshUpdates = 0; firstFix = -1; latest = null; notice = "";
            include.setChecked(false);
            activeChoice = pinned(); savedChoice = configured(); configuration = configuration();
            fix = new Outcome(); forwardResult = new Outcome(); reverseResult = new Outcome();
            if (!"google".equals(activeChoice) && !"mainland".equals(activeChoice)) {
                fix.status = forwardResult.status = reverseResult.status = R.string.diag_unavailable;
                complete(); render(); return;
            }
            fix.status = R.string.diag_running;
            if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) fix.status = R.string.diag_denied;
            else if (!locations.isLocationEnabled() || !locations.hasProvider(LocationManager.NETWORK_PROVIDER)
                    || !locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) fix.status = R.string.diag_provider_disabled;
            else {
                listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        if (!live(run) || fix.status != R.string.diag_running) return;
                        updates++;
                        latest = new Location(location);
                        boolean fresh = location.getElapsedRealtimeNanos() / 1_000_000 >= started
                                && location.getElapsedRealtimeNanos() <= SystemClock.elapsedRealtimeNanos();
                        if (fresh) freshUpdates++;
                        if (fresh && !location.isMock()) {
                            firstFix = SystemClock.elapsedRealtime() - started;
                            fix.status = R.string.diag_pass; fix.latency = firstFix;
                            removeLocation();
                        }
                        complete(); render();
                    }
                    @Override public void onProviderDisabled(String provider) {
                        if (live(run)) { fix.status = R.string.diag_provider_disabled; removeLocation(); complete(); render(); }
                    }
                };
                try {
                    locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0,
                            requireContext().getMainExecutor(), listener);
                } catch (SecurityException denied) { fix.status = R.string.diag_denied; removeLocation(); }
                catch (RuntimeException unavailable) { fix.status = R.string.diag_unavailable; removeLocation(); }
            }
            startGeocode(run, false, forwardResult);
            startGeocode(run, true, reverseResult);
            main.postDelayed(() -> {
                if (live(run) && fix.status == R.string.diag_running) {
                    fix.status = R.string.diag_timeout; fix.latency = SystemClock.elapsedRealtime() - started;
                    removeLocation(); complete(); render();
                }
            }, FIX_LIMIT);
            complete(); render();
            main.postDelayed(tick, 1000);
        }
        private boolean live(int run) { return isAdded() && foreground && active && generation == run; }
        private void startGeocode(int run, boolean backwards, Outcome result) {
            if (!Geocoder.isPresent()) { result.status = R.string.diag_unavailable; return; }
            result.status = R.string.diag_running;
            final long sent = SystemClock.elapsedRealtime();
            Geocoder.GeocodeListener callback = new Geocoder.GeocodeListener() {
                @Override public void onGeocode(List<Address> addresses) {
                    main.post(() -> {
                        if (!live(run) || result.status != R.string.diag_running) return;
                        result.latency = SystemClock.elapsedRealtime() - sent;
                        if (addresses == null || addresses.isEmpty()) result.status = R.string.diag_empty;
                        else {
                            result.status = R.string.diag_pass;
                            Address address = addresses.get(0);
                            result.country = address.getCountryCode();
                            result.address = address.getMaxAddressLineIndex() >= 0 ? address.getAddressLine(0) : null;
                        }
                        complete(); render();
                    });
                }
                @Override public void onError(String ignored) {
                    main.post(() -> {
                        if (!live(run) || result.status != R.string.diag_running) return;
                        result.status = R.string.diag_error; result.latency = SystemClock.elapsedRealtime() - sent;
                        complete(); render();
                    });
                }
            };
            try {
                Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
                if (backwards) geocoder.getFromLocation(31.2304, 121.4737, 1, callback);
                else geocoder.getFromLocationName("Shanghai", 1, callback);
            } catch (RuntimeException failure) { result.status = R.string.diag_error; }
            main.postDelayed(() -> {
                if (live(run) && result.status == R.string.diag_running) {
                    result.status = R.string.diag_timeout; result.latency = SystemClock.elapsedRealtime() - sent;
                    complete(); render();
                }
            }, GEOCODE_LIMIT);
        }
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!isAdded() || !foreground || !active) return;
                if (!Objects.equals(configuration, configuration())) {
                    stop(); notice = text(R.string.diag_provider_changed);
                }
                render();
                if (active) main.postDelayed(this, 1000);
            }
        };
        private void removeLocation() {
            if (listener != null) {
                locations.removeUpdates(listener); listener = null;
            }
        }
        private void complete() {
            if (active && fix.status != R.string.diag_running && forwardResult.status != R.string.diag_running
                    && reverseResult.status != R.string.diag_running) {
                active = false; finished = SystemClock.elapsedRealtime(); removeLocation();
                main.removeCallbacksAndMessages(null);
            }
        }
        private void stop() {
            if (active) {
                for (Outcome result : new Outcome[]{fix, forwardResult, reverseResult})
                    if (result.status == R.string.diag_running) result.status = R.string.diag_cancelled;
                finished = SystemClock.elapsedRealtime();
            }
            active = false; generation++; removeLocation(); main.removeCallbacksAndMessages(null);
        }
        private String contextReport() {
            StringBuilder out = new StringBuilder();
            line(out, R.string.diag_active, choice(wallStarted == 0 ? pinned() : activeChoice));
            line(out, R.string.diag_saved, choice(wallStarted == 0 ? configured() : savedChoice));
            String pin = wallStarted == 0 ? pinned() : activeChoice;
            String selection = wallStarted == 0 ? configured() : savedChoice;
            line(out, R.string.diag_pending, !pin.equals("google") && !pin.equals("mainland")
                    ? text(R.string.diag_unknown) : text(pin.equals(selection) ? R.string.diag_no : R.string.diag_yes));
            line(out, R.string.diag_binding, text(R.string.diag_unverified));
            line(out, R.string.diag_location_enabled, text(locations.isLocationEnabled() ? R.string.diag_on : R.string.diag_off));
            line(out, R.string.diag_permission, text(has(Manifest.permission.ACCESS_FINE_LOCATION)
                    ? R.string.diag_precise : has(Manifest.permission.ACCESS_COARSE_LOCATION)
                    ? R.string.diag_approximate : R.string.diag_denied));
            ConnectivityManager connectivity = requireContext().getSystemService(ConnectivityManager.class);
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            int transport = capabilities == null ? R.string.diag_none : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ? R.string.diag_vpn : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? R.string.diag_wifi
                    : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? R.string.diag_cellular : R.string.diag_other;
            line(out, R.string.diag_network, text(transport));
            line(out, R.string.diag_validated, text(capabilities != null && capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? R.string.diag_yes : R.string.diag_no));
            if (wallStarted != 0) {
                line(out, R.string.diag_tested_at, date(wallStarted));
                line(out, R.string.diag_elapsed, seconds((active ? SystemClock.elapsedRealtime() : finished) - started));
            }
            if (!notice.isEmpty()) out.append(notice).append('\n');
            return out.toString().trim();
        }
        private String positionReport(boolean precise) {
            StringBuilder out = new StringBuilder();
            line(out, R.string.diag_status, text(fix.status)); line(out, R.string.diag_limit, seconds(FIX_LIMIT));
            line(out, R.string.diag_updates, Integer.toString(updates));
            line(out, R.string.diag_fresh_updates, Integer.toString(freshUpdates));
            if (fix.status == R.string.diag_denied) out.append(text(R.string.diag_permission_hint)).append('\n');
            if (latest != null) {
                line(out, R.string.diag_latitude, precise ? String.format(Locale.ROOT, "%.6f", latest.getLatitude()) : text(R.string.diag_redacted));
                line(out, R.string.diag_longitude, precise ? String.format(Locale.ROOT, "%.6f", latest.getLongitude()) : text(R.string.diag_redacted));
                line(out, R.string.diag_accuracy, latest.hasAccuracy() ? number(latest.getAccuracy(), " m") : text(R.string.diag_not_provided));
                line(out, R.string.diag_fix_time, date(latest.getTime()));
                line(out, R.string.diag_fix_age, seconds(Math.max(0, latest.getElapsedRealtimeAgeMillis())));
                line(out, R.string.diag_first_fix, firstFix >= 0 ? seconds(firstFix) : text(R.string.diag_not_provided));
                boolean fresh = latest.getElapsedRealtimeNanos() / 1_000_000 >= started
                        && latest.getElapsedRealtimeNanos() <= SystemClock.elapsedRealtimeNanos();
                line(out, R.string.diag_freshness, text(fresh ? R.string.diag_fresh : R.string.diag_cached));
                line(out, R.string.diag_mock, text(latest.isMock() ? R.string.diag_yes : R.string.diag_no));
                if (latest.isMock()) out.append(text(R.string.diag_mock_result)).append('\n');
                line(out, R.string.diag_altitude, !precise ? text(R.string.diag_redacted) : latest.hasAltitude()
                        ? number(latest.getAltitude(), " m") : text(R.string.diag_not_provided));
                line(out, R.string.diag_vertical, latest.hasVerticalAccuracy() ? number(latest.getVerticalAccuracyMeters(), " m") : text(R.string.diag_not_provided));
                line(out, R.string.diag_speed, latest.hasSpeed() ? number(latest.getSpeed(), " m/s") : text(R.string.diag_not_provided));
                line(out, R.string.diag_speed_accuracy, latest.hasSpeedAccuracy() ? number(latest.getSpeedAccuracyMetersPerSecond(), " m/s") : text(R.string.diag_not_provided));
                line(out, R.string.diag_bearing, latest.hasBearing() ? number(latest.getBearing(), "°") : text(R.string.diag_not_provided));
                line(out, R.string.diag_bearing_accuracy, latest.hasBearingAccuracy() ? number(latest.getBearingAccuracyDegrees(), "°") : text(R.string.diag_not_provided));
            }
            line(out, R.string.diag_coordinate, text(R.string.diag_wgs84));
            return out.toString().trim();
        }
        private String geocodeReport(Outcome result, boolean backwards, boolean precise) {
            StringBuilder out = new StringBuilder();
            line(out, R.string.diag_status, text(result.status)); line(out, R.string.diag_limit, seconds(GEOCODE_LIMIT));
            line(out, R.string.diag_query, text(backwards ? R.string.diag_synthetic_reverse : R.string.diag_synthetic_forward));
            if (result.latency >= 0) line(out, R.string.diag_latency, seconds(result.latency));
            if (result.country != null) line(out, R.string.diag_country, result.country);
            if (result.address != null) line(out, R.string.diag_address, precise ? result.address : text(R.string.diag_redacted));
            if (result.status == R.string.diag_error) out.append(text(R.string.diag_error_detail));
            return out.toString().trim();
        }
        private String version(String packageName) {
            try {
                android.content.pm.PackageInfo info = requireContext().getPackageManager().getPackageInfo(packageName, 0);
                return info.versionName + " (" + info.getLongVersionCode() + ")";
            } catch (PackageManager.NameNotFoundException missing) { return text(R.string.diag_unavailable); }
        }
        private String technicalReport() {
            StringBuilder out = new StringBuilder();
            line(out, R.string.diag_android_version, Build.VERSION.RELEASE + " / " + Build.VERSION.SDK_INT);
            line(out, R.string.diag_build, Build.DISPLAY);
            line(out, R.string.diag_diagnostic_version, version(requireContext().getPackageName()));
            String choice = wallStarted == 0 ? pinned() : activeChoice;
            String backend = "google".equals(choice) ? "com.google.android.gms" : "mainland".equals(choice) ? "com.baidu.map.location" : null;
            line(out, R.string.diag_backend_version, backend == null ? text(R.string.diag_unknown) : backend + " / " + version(backend));
            return out.toString().trim();
        }
        private String report(boolean precise) {
            return text(R.string.diag_app_name) + "\n\n" + text(R.string.diag_context) + "\n" + contextReport()
                    + "\n\n" + text(R.string.diag_position) + "\n" + positionReport(precise)
                    + "\n\n" + text(R.string.diag_forward) + "\n" + geocodeReport(forwardResult, false, precise)
                    + "\n\n" + text(R.string.diag_reverse) + "\n" + geocodeReport(reverseResult, true, precise)
                    + "\n\n" + text(R.string.diag_technical) + "\n" + technicalReport();
        }
        private void render() {
            if (context == null || !isAdded()) return;
            start.setEnabled(!active && !awaitingPermission); cancel.setEnabled(active);
            share.setEnabled(wallStarted != 0 && !active);
            include.setEnabled(wallStarted != 0 && !active);
            context.setSummary(contextReport()); position.setSummary(positionReport(true));
            forward.setSummary(geocodeReport(forwardResult, false, true));
            reverse.setSummary(geocodeReport(reverseResult, true, true));
            technical.setSummary(technicalReport());
        }
    }
}
