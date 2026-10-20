package com.vos.session;

import android.app.Application;
import com.vos.ipc.AndroidGuardPlatform;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.Grant;
import com.vos.ipc.GrantTable;
import com.vos.ipc.Ipc;
import com.vos.ipc.JsonlAudit;

/** Session process: VoicePlane, Planner, Egress, Keys, ModelRouter live here (§3.1). M0 has none yet. */
public final class SessionApp extends Application {
    private BinderGuard actuatorGuard;

    @Override
    public void onCreate() {
        super.onCreate();
        actuatorGuard = new BinderGuard("SessionService", Ipc.ACTUATOR_PACKAGE,
                AndroidGuardPlatform.ownCertSha256(this), GrantTable.of(Ipc.ACTUATOR_PACKAGE, Grant.ACTUATOR_CORE),
                new AndroidGuardPlatform(getPackageManager()), new JsonlAudit(this));
    }

    private SessionTurns turns;

    /** Voice turns (§3.2). The realtime voice backend arrives with #241; until then only debug builds run a turn (echo). */
    synchronized SessionTurns turns() {
        if (turns == null) {
            turns = new SessionTurns(new ActuatorLink(this), () -> BuildConfig.DEBUG ? new EchoBackend() : null);
        }
        return turns;
    }

    /** Guards SessionService: only the actuator package, signed with this app's cert (§5.3). */
    BinderGuard actuatorGuard() {
        return actuatorGuard;
    }
}
