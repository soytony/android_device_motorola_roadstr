/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.motorola.perf;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** Compatibility API required by Motorola's proprietary core-services library. */
public interface IGameOptObserver extends IInterface {
    String DESCRIPTOR = "com.motorola.perf.IGameOptObserver";

    void onGameOptStateChanged(String name, Bundle state) throws RemoteException;

    abstract class Stub extends Binder implements IGameOptObserver {
        private static final int TRANSACTION_onGameOptStateChanged = FIRST_CALL_TRANSACTION;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IGameOptObserver asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IGameOptObserver ? (IGameOptObserver) local : null;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_onGameOptStateChanged) {
                data.enforceInterface(DESCRIPTOR);
                onGameOptStateChanged(data.readString(), data.readTypedObject(Bundle.CREATOR));
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    class Default implements IGameOptObserver {
        @Override
        public void onGameOptStateChanged(String name, Bundle state) {
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }
}
