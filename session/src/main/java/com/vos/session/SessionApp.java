package com.vos.session;

import android.app.Application;
import android.util.Log;
import com.vos.ipc.AndroidGuardPlatform;
import com.vos.ipc.BinderGuard;
import com.vos.ipc.Grant;
import com.vos.ipc.GrantTable;
import com.vos.ipc.Ipc;
import com.vos.ipc.JsonlAudit;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Session process: VoicePlane, Planner, Egress, Keys, ModelRouter live here (§3.1). */
public final class SessionApp extends Application {
    static final String TAG = "VOS_SESSION";

    /** Every vault operation (import, put, delete, has) runs here, one at a time, in call order. */
    private final ExecutorService keyWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "vos-keys"));
    private BinderGuard actuatorGuard;
    private KeyVault keyVault;
    private Egress egress;

    @Override
    public void onCreate() {
        super.onCreate();
        actuatorGuard = new BinderGuard("SessionService", Ipc.ACTUATOR_PACKAGE,
                AndroidGuardPlatform.ownCertSha256(this), GrantTable.of(Ipc.ACTUATOR_PACKAGE, Grant.ACTUATOR_CORE),
                new AndroidGuardPlatform(getPackageManager()), new JsonlAudit(this));
        keyVault = new KeyVault(new File(getFilesDir(), "keys"), new AesGcmSealer(KeystoreKeys::vendorKeyWrapper));
        egress = new Egress(new EgressAllowlist(Egress.HOSTS_V0), new RequestLog(new File(getFilesDir(), "egress")));
        keyWorker.execute(this::importPushedKeys);
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

    KeyVault keyVault() {
        return keyVault;
    }

    /** The app-wide key executor, so an activity recreated mid-save sees the vault after that save. */
    ExecutorService keyWorker() {
        return keyWorker;
    }

    Egress egress() {
        return egress;
    }

    /** Debug builds: seal keys pushed with run-as into the vault (KeyImport). Never logs a value. */
    synchronized void importPushedKeys() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        List<String> imported = KeyImport.run(getFilesDir(), keyVault);
        if (!imported.isEmpty()) {
            String level;
            try {
                level = KeystoreKeys.securityLevel(KeystoreKeys.vendorKeyWrapper());
            } catch (RuntimeException e) {
                level = "unknown";
            }
            Log.i(TAG, "imported keys for " + imported + " (wrapper: " + level + ")");
        }
    }
}
