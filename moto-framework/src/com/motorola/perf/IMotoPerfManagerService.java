/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.motorola.perf;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import java.util.List;
import java.util.Map;

/** Interface shape exposed by stock Motorola framework builds. */
public interface IMotoPerfManagerService extends IInterface {
    String DESCRIPTOR = "com.motorola.perf.IMotoPerfManagerService";

    int perfHint(int hintId, int duration) throws RemoteException;
    Map<String, String> getCheckinData(int type) throws RemoteException;
    List<String> getAppOptAbilities() throws RemoteException;
    boolean setAppOptState(String ability, String packageName, Bundle state, int userId)
            throws RemoteException;
    Bundle getAppOptState(String ability, String packageName, int userId)
            throws RemoteException;
    void registerGameOptObserver(String name, int intervalMs, IGameOptObserver observer)
            throws RemoteException;
    void unregisterGameOptObserver(String name, IGameOptObserver observer) throws RemoteException;
    void perfHintStart(int hintId, int duration, Bundle state) throws RemoteException;
    void perfHintEnd(int hintId) throws RemoteException;
    void reportJankStats(int uid, int pid, int tid, int surfaceFlingerTid, int frameIntervalNanos,
            int appFrameCount, int appMissedFrameCount, int surfaceFlingerFrameCount,
            int surfaceFlingerMissedFrameCount) throws RemoteException;

    abstract class Stub extends Binder implements IMotoPerfManagerService {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IMotoPerfManagerService asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IMotoPerfManagerService ? (IMotoPerfManagerService) local : null;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
