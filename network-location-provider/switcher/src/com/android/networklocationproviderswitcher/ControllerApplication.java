// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher;

import android.app.Application;
import android.content.Intent;
import android.os.UserHandle;

public final class ControllerApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            startService(new Intent(this, ControllerService.class));
        }
    }
}
