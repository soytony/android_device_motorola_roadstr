// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;

/** Signature-protected system-user commands and configuration, never live-binding claims. */
public final class ControlProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://com.android.networklocationproviderswitcher.control");
    static final String SELECTION = "network_location_provider_selection";
    static final String BOOT_SELECTION = "network_location_provider_boot_selection";
    /** Broker-owned applied choice for this boot; never infer it from controller history. */
    static boolean pendingReboot(Context context, String selection) {
        String boot = android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1) + ":";
        String pinned = android.provider.Settings.Global.getString(
                context.getContentResolver(), BOOT_SELECTION);
        return pinned != null && pinned.startsWith(boot)
                && !selection.equals(pinned.substring(boot.length()));
    }
    /** Device-wide setting is the configured-provider authority. */
    static String readSelection(Context context) {
        String value = android.provider.Settings.Global.getString(
                context.getContentResolver(), SELECTION);
        if (value == null) return "google";
        if (!"google".equals(value) && !"mainland".equals(value))
            throw new IllegalStateException("Invalid provider setting");
        return value;
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission(
                "com.android.networklocationproviderswitcher.CONTROL_PROVIDERS", "Provider settings");
        String issued = null;
        if ("select".equals(method) || "detection".equals(method) || "dismiss".equals(method)) {
            if ("select".equals(method) && !"google".equals(arg) && !"mainland".equals(arg))
                throw new IllegalArgumentException("Unknown provider");
            if ("detection".equals(method) && !"true".equals(arg) && !"false".equals(arg))
                throw new IllegalArgumentException("Invalid detection setting");
            issued = java.util.UUID.randomUUID().toString();
            Intent command = new Intent(getContext(), ControllerService.class)
                    .setAction(method).putExtra("commandToken", issued);
            if ("select".equals(method)) command.putExtra("selection", arg);
            else if ("detection".equals(method)) command.putExtra("enabled", Boolean.parseBoolean(arg));
            if (extras != null && extras.containsKey("suggestion"))
                command.putExtra("suggestion", extras.getString("suggestion"));
            if (extras != null && extras.containsKey("expectedSelection"))
                command.putExtra("expectedSelection", extras.getString("expectedSelection"));
            getContext().startService(command);
        } else if (!"status".equals(method)) throw new IllegalArgumentException("Unknown command");
        SharedPreferences p = getContext().getSharedPreferences("controller", 0);
        Bundle state = new Bundle();
        for (String key : new String[] {"error", "evidence",
                "suggestion", "commandToken"}) state.putString(key, p.getString(key, ""));
        try {
            String selection = readSelection(getContext());
            state.putString("selection", selection);
            state.putBoolean("pendingReboot", pendingReboot(getContext(), selection));
        } catch (RuntimeException error) {
            state.putString("selection", "");
            state.putString("error", "configuration_failed");
            state.putBoolean("pendingReboot", false);
        }
        state.putBoolean("detectionEnabled", p.getBoolean("detectionEnabled", true));
        state.putLong("evidenceTime", p.getLong("evidenceTime", 0));
        if (issued != null) state.putString("issuedToken", issued);
        return state;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
