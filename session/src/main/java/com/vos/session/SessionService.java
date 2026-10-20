package com.vos.session;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * The actuator's entry point into the session (IVosSession lands in M1). Every transaction is
 * rejected unless the caller is exactly the actuator package, before any argument is read (§5.3).
 */
public final class SessionService extends Service {
    static final String ACTUATOR_PACKAGE = "com.vos.actuator";

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            String[] callers = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (callers == null || callers.length != 1 || !ACTUATOR_PACKAGE.equals(callers[0])) {
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
