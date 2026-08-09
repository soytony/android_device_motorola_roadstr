/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.refreshdefaults;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** Initializes the cross-group display-switching preference when a user has none. */
public final class RefreshRateDefaultsReceiver extends BroadcastReceiver {
    private static final String TAG = "RefreshRateDefaults";
    private static final String MATCH_CONTENT_FRAME_RATE = "match_content_frame_rate";
    private static final int MATCH_CONTENT_FRAMERATE_ALWAYS = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        // This is a user preference. Never overwrite a value chosen by the user or an admin.
        if (Settings.Secure.getString(context.getContentResolver(),
                MATCH_CONTENT_FRAME_RATE) != null) {
            return;
        }

        if (!Settings.Secure.putInt(context.getContentResolver(),
                MATCH_CONTENT_FRAME_RATE, MATCH_CONTENT_FRAMERATE_ALWAYS)) {
            Log.e(TAG, "Unable to initialize cross-group refresh-rate switching");
        }
    }
}
