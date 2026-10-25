// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher;

import android.content.*;
import android.database.ContentObserver;
import android.os.*;
import android.text.format.DateFormat;
import android.widget.FrameLayout;
import androidx.preference.*;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SelectorWithWidgetPreference;
import android.app.AlertDialog;

/** Launcher remains available independently of the persistent policy process. */
public final class LocationActivity extends CollapsingToolbarBaseActivity {
    private static final int CONTENT = 0x123456;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.app_name);
        FrameLayout frame = new FrameLayout(this);
        frame.setId(CONTENT);
        setContentView(frame);
        if (saved == null) getSupportFragmentManager().beginTransaction()
                .replace(CONTENT, new ProviderSettings()).commit();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }
    public static final class ProviderSettings extends PreferenceFragmentCompat {
        private MainSwitchPreference automatic;
        private SelectorWithWidgetPreference google, mainland;
        private Preference pending, evidence, error;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private String shownSuggestion = "";
        private boolean confirming;
        private boolean resumed;
        private String waitingToken;
        private int checks;
        private final ContentObserver changes = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { refresh(); }
        };
        private Bundle command(String method, String value, Bundle extras) {
            return requireContext().getContentResolver().call(ControlProvider.URI, method, value, extras);
        }
        @Override public void onCreatePreferences(Bundle saved, String key) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            setPreferenceScreen(screen);
            Preference intro = new Preference(requireContext());
            intro.setSummary(R.string.explanation);
            intro.setSelectable(false);
            screen.addPreference(intro);
            automatic = new MainSwitchPreference(requireContext());
            automatic.setPersistent(false);
            automatic.setTitle(R.string.automatic_detection);
            automatic.setSummary(R.string.detection_summary);
            automatic.setOnPreferenceChangeListener((p, value) -> {
                enqueue("detection", value.toString(), null);
                return true;
            });
            screen.addPreference(automatic);
            PreferenceCategory choices = new PreferenceCategory(requireContext());
            choices.setTitle(R.string.provider_choice);
            screen.addPreference(choices);
            google = provider(choices, R.string.provider_google, "google");
            mainland = provider(choices, R.string.provider_baidu, "mainland");
            pending = info(screen, R.string.pending_title);
            pending.setSummary(R.string.reboot_reminder);
            pending.setSelectable(true);
            pending.setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(requireContext()).setTitle(R.string.restart_title)
                        .setMessage(R.string.restart_message)
                        .setPositiveButton(android.R.string.ok, null).show();
                return true;
            });
            evidence = info(screen, R.string.country_evidence);
            Preference diagnostics = new Preference(requireContext());
            diagnostics.setTitle(R.string.test_provider);
            diagnostics.setSummary(R.string.test_provider_summary);
            diagnostics.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(requireContext(), DiagnosticsActivity.class));
                return true;
            });
            screen.addPreference(diagnostics);
            error = info(screen, R.string.configuration_failed);
        }
        private Preference info(PreferenceGroup group, int title) {
            Preference p = new Preference(requireContext());
            p.setTitle(title);
            p.setSelectable(false);
            group.addPreference(p);
            return p;
        }
        private SelectorWithWidgetPreference provider(PreferenceGroup group, int label, String value) {
            SelectorWithWidgetPreference p = new SelectorWithWidgetPreference(requireContext());
            p.setTitle(label);
            p.setPersistent(false);
            p.setOnClickListener(preference -> {
                preference.setChecked(false);
                refresh();
                confirm(value, null);
            });
            group.addPreference(p);
            return p;
        }
        private void confirm(String selection, String suggestion) {
            if (confirming) return;
            Bundle current = command("status", null, null);
            if (current.getString("selection", "").isEmpty()) return;
            if (selection.equals(current.getString("selection"))) return;
            if (suggestion != null && (!current.getBoolean("detectionEnabled")
                    || !suggestion.equals(current.getString("suggestion")))) return;
            confirming = true;
            String expected = current.getString("selection");
            boolean[] accepted = {false};
            new AlertDialog.Builder(requireContext()).setTitle(R.string.confirm_title)
                    .setMessage(getString(R.string.confirm_message, getString(
                            "mainland".equals(selection) ? R.string.provider_baidu : R.string.provider_google)))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save_selection, (dialog, which) -> {
                        accepted[0] = true;
                        Bundle extras = new Bundle();
                        extras.putString("expectedSelection", expected);
                        if (suggestion != null) {
                            extras.putString("suggestion", suggestion);
                        }
                        enqueue("select", selection, extras);
                    }).setOnDismissListener(dialog -> {
                        confirming = false;
                        if (!accepted[0] && suggestion != null && isAdded()) {
                            Bundle extras = new Bundle();
                            extras.putString("suggestion", suggestion);
                            enqueue("dismiss", null, extras);
                        }
                        refresh();
                    }).show();
        }
        private void enqueue(String method, String value, Bundle extras) {
            waitingToken = command(method, value, extras).getString("issuedToken");
            checks = 0;
            handler.removeCallbacks(acknowledge);
            handler.postDelayed(acknowledge, 150);
        }
        private final Runnable acknowledge = new Runnable() {
            @Override public void run() {
                if (!isAdded() || !resumed) return;
                Bundle state = command("status", null, null);
                refresh();
                if (waitingToken != null && !waitingToken.equals(state.getString("commandToken"))
                        && ++checks < 20) handler.postDelayed(this, 150);
                else waitingToken = null;
            }
        };
        private void refresh() {
            if (!isAdded()) return;
            Bundle state = command("status", null, null);
            automatic.setChecked(state.getBoolean("detectionEnabled"));
            google.setChecked("google".equals(state.getString("selection")));
            mainland.setChecked("mainland".equals(state.getString("selection")));
            pending.setVisible(state.getBoolean("pendingReboot"));
            boolean known = !state.getString("selection", "").isEmpty();
            google.setEnabled(known);
            mainland.setEnabled(known);
            String observed = state.getString("evidence");
            String observation = getString("CN".equals(observed) ? R.string.evidence_cn
                    : "OTHER".equals(observed) ? R.string.evidence_other : R.string.unknown);
            long time = state.getLong("evidenceTime");
            if (time > 0 && !observed.isEmpty()) observation += " · "
                    + DateFormat.getDateFormat(requireContext()).format(new java.util.Date(time)) + " "
                    + DateFormat.getTimeFormat(requireContext()).format(new java.util.Date(time));
            evidence.setSummary(observation);
            String issue = state.getString("error", "");
            error.setVisible(!issue.isEmpty());
            error.setSummary("provider_unavailable".equals(issue)
                    ? R.string.provider_unavailable : R.string.configuration_failed);
            Intent intent = requireActivity().getIntent();
            if ("review".equals(intent.getAction())) {
                String token = intent.getStringExtra("suggestion");
                if (token != null && !token.equals(shownSuggestion)) {
                    shownSuggestion = token;
                    confirm("mainland", token);
                }
            }
        }
        @Override public void onResume() {
            super.onResume();
            resumed = true;
            requireContext().getContentResolver().registerContentObserver(
                    ControlProvider.URI, false, changes);
            refresh();
            if (waitingToken != null) handler.post(acknowledge);
        }
        @Override public void onPause() {
            resumed = false;
            requireContext().getContentResolver().unregisterContentObserver(changes);
            handler.removeCallbacksAndMessages(null);
            super.onPause();
        }
    }
}
