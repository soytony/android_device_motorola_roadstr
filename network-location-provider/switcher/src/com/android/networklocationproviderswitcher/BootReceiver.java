// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            context.startService(new Intent(context, ControllerService.class));
        }
    }
}
