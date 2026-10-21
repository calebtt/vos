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
    private AudioPlane audioPlane;
    private FastTools fastTools;
    private JsonlAudit audit;
    private AndroidAudioPlatform audioPlatform;
    private RoleWatch roleWatch;
    private final Indicator indicator = new Indicator(new IndicatorView());

    @Override
    public void onCreate() {
        super.onCreate();
        audit = new JsonlAudit(this);
        sessionGuard = new BinderGuard("ActuatorService", Ipc.SESSION_PACKAGE,
                AndroidGuardPlatform.ownCertSha256(this), GrantTable.of(Ipc.SESSION_PACKAGE, Grant.SESSION_CORE),
                new AndroidGuardPlatform(getPackageManager()), audit);
        audioPlatform = new AndroidAudioPlatform(this);
        audioPlane = new AudioPlane(audioPlatform, line -> audit.record(line));
        fastTools = new FastTools(new AndroidFastToolsPlatform(this));
        roleWatch = new RoleWatch(new AndroidRolePlatform(this), line -> audit.record(line));
        roleWatch.check("actuator start");
    }

    /** The generating / acting indicator (Shell Contract row 13). */
    Indicator indicator() {
        return indicator;
    }

    /** Whether VOS holds the assistant role, and the notice when it does not (decision 45). */
    RoleWatch roleWatch() {
        return roleWatch;
    }

    AndroidAudioPlatform audioPlatform() {
        return audioPlatform;
    }

    FastTools fastTools() {
        return fastTools;
    }

    JsonlAudit audit() {
        return audit;
    }

    /** The one owner of the audio session (§3.3). */
    AudioPlane audioPlane() {
        return audioPlane;
    }

    /** Guards ActuatorService: only the session package, signed with this app's cert (§5.3). */
    BinderGuard sessionGuard() {
        return sessionGuard;
    }
}
