package com.vos.session;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.Grant;
import com.vos.ipc.IVosSession;
import com.vos.ipc.Ipc;

/**
 * The actuator's entry point into the session. Every transaction, including the interface
 * descriptor query, runs the Binder guard before any argument is read (§5.3).
 */
public final class SessionService extends Service {
    private BinderGuard guard;

    private final IVosSession.Stub binder = new IVosSession.Stub() {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            guard.require(Binder.getCallingUid(), Grant.ACTUATOR_CORE);
            return super.onTransact(code, data, reply, flags);
        }

        @Override
        public int protocolVersion() {
            return Ipc.PROTOCOL_VERSION;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        guard = ((SessionApp) getApplication()).actuatorGuard();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
