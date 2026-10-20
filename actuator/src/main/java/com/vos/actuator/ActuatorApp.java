package com.vos.actuator;

import android.app.Application;
import com.vos.ipc.AndroidGuardPlatform;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.Grant;
import com.vos.ipc.GrantTable;
import com.vos.ipc.Ipc;
import com.vos.ipc.JsonlAudit;

/** Actuator process: AudioPlane, Hands, Shell, Oracles, Audit live here (§3.1). M0 has no planes yet. */
public final class ActuatorApp extends Application {
    private BinderGuard sessionGuard;

    @Override
    public void onCreate() {
        super.onCreate();
        sessionGuard = new BinderGuard("ActuatorService", Ipc.SESSION_PACKAGE,
                AndroidGuardPlatform.ownCertSha256(this), GrantTable.of(Ipc.SESSION_PACKAGE, Grant.SESSION_CORE),
                new AndroidGuardPlatform(getPackageManager()), new JsonlAudit(this));
    }

    /** Guards ActuatorService: only the session package, signed with this app's cert (§5.3). */
    BinderGuard sessionGuard() {
        return sessionGuard;
    }
}
