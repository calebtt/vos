package com.vos.session;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.Grant;
import com.vos.ipc.IVosSession;
import com.vos.ipc.Ipc;

/**
 * The actuator's entry point into the session. Every transaction, including the interface
 * descriptor query, runs the Binder guard before any argument is read (§5.3). Every method runs it
 * again: an in-process caller gets this Stub from asInterface() and calls the method directly,
 * without onTransact, and outside a transaction the calling UID is this app's own, which the guard
 * refuses.
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
            guard.require(Binder.getCallingUid(), Grant.ACTUATOR_CORE);
            return Ipc.PROTOCOL_VERSION;
        }

        @Override
        public void onTurnOpened(String turnId, ParcelFileDescriptor mic, ParcelFileDescriptor speaker) {
            guard.require(Binder.getCallingUid(), Grant.ACTUATOR_CORE);
            ((SessionApp) getApplication()).turns().open(turnId, mic, speaker);
        }

        @Override
        public void onTurnClosed(String turnId, String reason) {
            guard.require(Binder.getCallingUid(), Grant.ACTUATOR_CORE);
            ((SessionApp) getApplication()).turnClosed(turnId); // voice or typed (the owner's stop)
        }

        @Override
        public void onTypedTurn(String turnId, String text) {
            guard.require(Binder.getCallingUid(), Grant.ACTUATOR_CORE);
            ((SessionApp) getApplication()).typedTurn(turnId, text);
        }

        @Override
        public String recentThread(int max) {
            guard.require(Binder.getCallingUid(), Grant.ACTUATOR_CORE);
            return ((SessionApp) getApplication()).recentThread(Math.max(0, Math.min(max, 200)));
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
