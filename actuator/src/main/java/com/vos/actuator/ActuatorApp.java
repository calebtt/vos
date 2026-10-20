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
    private AndroidAudioPlatform audioPlatform;

    @Override
    public void onCreate() {
        super.onCreate();
        JsonlAudit audit = new JsonlAudit(this);
        sessionGuard = new BinderGuard("ActuatorService", Ipc.SESSION_PACKAGE,
                AndroidGuardPlatform.ownCertSha256(this), GrantTable.of(Ipc.SESSION_PACKAGE, Grant.SESSION_CORE),
                new AndroidGuardPlatform(getPackageManager()), audit);
        audioPlatform = new AndroidAudioPlatform(this);
        audioPlane = new AudioPlane(audioPlatform, line -> audit.record(line));
    }

    AndroidAudioPlatform audioPlatform() {
        return audioPlatform;
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
