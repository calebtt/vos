package com.vos.actuator;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * The session's entry point into the actuator (IVosActuator lands in M1). Every transaction is
 * rejected unless the caller is the session package, before any argument is read (§5.3).
 */
public final class ActuatorService extends Service {
    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            String[] callers = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (!CallerGuard.isExactly(callers, CallerGuard.SESSION_PACKAGE)) {
                throw new SecurityException("UNAUTHORIZED");
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
