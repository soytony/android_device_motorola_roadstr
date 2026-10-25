// SPDX-License-Identifier: Apache-2.0
package com.android.networklocationproviderswitcher.tests;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;

/** WRITE_SECURE_SETTINGS must not grant access to the system-UID broker Binder. */
final class BrokerSecurityProbe {
    static void run(Context context, Bundle output) {
        if (UserHandle.getAppId(Process.myUid()) == Process.SYSTEM_UID)
            throw new AssertionError("Probe must use an ordinary app UID");
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED)
            throw new AssertionError("Probe must hold WRITE_SECURE_SETTINGS");
        String permission = "com.android.networklocationproviderbroker.BIND_PROVIDER";
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
            throw new AssertionError("Probe must not hold broker binding permission");
        for (String action : new String[] {
                "com.android.location.service.v3.NetworkLocationProvider",
                "com.android.location.service.GeocodeProvider"}) {
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) { }
                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            boolean bound = false;
            try {
                bound = context.bindService(new Intent(action).setComponent(new ComponentName(
                        "com.android.networklocationproviderbroker",
                        "com.android.networklocationproviderbroker.ProviderBrokerService")),
                        connection, Context.BIND_AUTO_CREATE);
                throw new AssertionError("Expected broker binding SecurityException: " + action);
            } catch (SecurityException expected) {
                output.putString(action, "PASS: signature permission enforced");
            } finally {
                if (bound) context.unbindService(connection);
            }
        }
    }
}
