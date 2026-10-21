package com.vos.actuator;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.Grant;
import com.vos.ipc.IVosActuator;
import com.vos.ipc.Ipc;

/**
 * The session's entry point into the actuator. Every transaction, including the interface
 * descriptor query, runs the Binder guard before any argument is read (§5.3). Every method runs it
 * again: an in-process caller gets this Stub from asInterface() and calls the method directly,
 * without onTransact, and outside a transaction the calling UID is this app's own, which the guard
 * refuses.
 */
public final class ActuatorService extends Service {
    private BinderGuard guard;

    private final IVosActuator.Stub binder = new IVosActuator.Stub() {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            guard.require(Binder.getCallingUid(), Grant.SESSION_CORE);
            return super.onTransact(code, data, reply, flags);
        }

        @Override
        public int protocolVersion() {
            guard.require(Binder.getCallingUid(), Grant.SESSION_CORE);
            return Ipc.PROTOCOL_VERSION;
        }

        @Override
        public void turnReady(String turnId) {
            guard.require(Binder.getCallingUid(), Grant.SESSION_CORE);
            TurnService.sessionReady(turnId);
        }

        @Override
        public void endTurn(String turnId, String reason) {
            guard.require(Binder.getCallingUid(), Grant.SESSION_CORE);
            TurnService.sessionEnded(turnId, clip(reason));
        }

        @Override
        public String callTool(String turnId, String name, String argsJson) {
            guard.require(Binder.getCallingUid(), Grant.SESSION_CORE);
            return TurnService.callTool(ActuatorService.this, turnId, clip(name), argsJson);
        }

        @Override
        public void modelCallStarted(String turnId, String callId) {
            guard.require(Binder.getCallingUid(), Grant.SESSION_CORE);
            ((ActuatorApp) getApplication()).indicator().callStarted(clip(turnId), clip(callId));
        }

        @Override
        public void modelCallEnded(String turnId, String callId) {
            guard.require(Binder.getCallingUid(), Grant.SESSION_CORE);
            ((ActuatorApp) getApplication()).indicator().callEnded(clip(turnId), clip(callId));
        }
    };

    /** A reason from the session is data for the audit line: printable and short. */
    static String clip(String reason) {
        if (reason == null) {
            return "";
        }
        String r = reason.replaceAll("[^\\x20-\\x7e]", "?");
        return r.length() > 120 ? r.substring(0, 120) : r;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        guard = ((ActuatorApp) getApplication()).sessionGuard();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
