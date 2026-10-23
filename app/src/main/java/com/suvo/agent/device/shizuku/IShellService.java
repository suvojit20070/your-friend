package com.suvo.agent.device.shizuku;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Binder interface of the Shizuku user service (written by hand instead of AIDL so it compiles
 * without the aidl tool). Transaction 16777115 is the "destroy" code reserved by Shizuku.
 */
public interface IShellService extends IInterface {

    String DESCRIPTOR = "com.suvo.agent.device.shizuku.IShellService";
    int TRANSACTION_DESTROY = 16777115;
    int TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION + 1;
    int TRANSACTION_INFO = IBinder.FIRST_CALL_TRANSACTION + 2;

    /** Runs {@code sh -c command}; returns exitCode, stdout, stderr, timedOut. */
    Bundle exec(String command, long timeoutMs) throws RemoteException;

    /** uid, pid and SELinux context of the service process. */
    String info() throws RemoteException;

    void destroy() throws RemoteException;

    abstract class Stub extends Binder implements IShellService {

        protected Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IShellService asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IShellService) return (IShellService) local;
            return new Proxy(binder);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case TRANSACTION_EXEC: {
                    data.enforceInterface(DESCRIPTOR);
                    String cmd = data.readString();
                    long timeout = data.readLong();
                    Bundle r = exec(cmd, timeout);
                    reply.writeNoException();
                    reply.writeInt(1);
                    r.writeToParcel(reply, 0);
                    return true;
                }
                case TRANSACTION_INFO: {
                    data.enforceInterface(DESCRIPTOR);
                    String s = info();
                    reply.writeNoException();
                    reply.writeString(s);
                    return true;
                }
                case TRANSACTION_DESTROY: {
                    data.enforceInterface(DESCRIPTOR);
                    destroy();
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    final class Proxy implements IShellService {
        private final IBinder remote;

        Proxy(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public IBinder asBinder() {
            return remote;
        }

        @Override
        public Bundle exec(String command, long timeoutMs) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(command);
                data.writeLong(timeoutMs);
                remote.transact(TRANSACTION_EXEC, data, reply, 0);
                reply.readException();
                if (reply.readInt() == 0) return new Bundle();
                return Bundle.CREATOR.createFromParcel(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public String info() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_INFO, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public void destroy() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_DESTROY, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
