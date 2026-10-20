package com.vos.session;

import android.app.Application;
import com.vos.ipc.AndroidGuardPlatform;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.Grant;
import com.vos.ipc.GrantTable;
import com.vos.ipc.Ipc;
import com.vos.ipc.JsonlAudit;

/** Session process: VoicePlane, Planner, Egress, Keys, ModelRouter live here (§3.1). */
public final class SessionApp extends Application {
    static final String TAG = "VOS_SESSION";

    private BinderGuard actuatorGuard;
    private SessionTurns turns;

    @Override
    public void onCreate() {
        super.onCreate();
        actuatorGuard = new BinderGuard("SessionService", Ipc.ACTUATOR_PACKAGE,
                AndroidGuardPlatform.ownCertSha256(this), GrantTable.of(Ipc.ACTUATOR_PACKAGE, Grant.ACTUATOR_CORE),
                new AndroidGuardPlatform(getPackageManager()), new JsonlAudit(this));
        // The realtime voice backend arrives with #241; until then only debug builds can run a turn (echo).
        turns = new SessionTurns(new ActuatorLink(this), () -> BuildConfig.DEBUG ? new EchoBackend() : null);
    }

    SessionTurns turns() {
        return turns;
    }

    /** Guards SessionService: only the actuator package, signed with this app's cert (§5.3). */
    BinderGuard actuatorGuard() {
        return actuatorGuard;
    }
}
