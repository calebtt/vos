package com.vos.actuator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.util.Log;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the open voice turn as a {@code microphone} foreground service (§3.2). A summon (the QS
 * tile, or the debug activity) starts it while the actuator has a foreground moment, which S-07
 * showed is what Android 14+ needs to start mic capture. The AudioPlane decides whether the turn
 * opens; the service runs its I/O ({@link VoiceTurn}) and ends it on the owner's stop, the
 * session's end, the session dying, an I/O error, or the hard length cap.
 */
public final class TurnService extends Service {
    static final String ACTION_SUMMON = "com.vos.actuator.action.SUMMON";
    static final String ACTION_STOP = "com.vos.actuator.action.STOP";
    static final String EXTRA_SOURCE = "source";
    /** Debug builds only (ignored otherwise): mic from files/debug/mic-inject.pcm, speaker to a file. */
    static final String EXTRA_DEBUG_MIC_FILE = "debug_mic_file";
    static final String EXTRA_DEBUG_SPEAKER_FILE = "debug_speaker_file";

    /** No turn keeps the mic longer than this, whatever the session does. */
    static final long MAX_TURN_MS = 10 * 60_000L;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "turn";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile TurnService live;
    private volatile VoiceTurn turn; // read from Binder threads (callTool)
    private final Runnable cap = () -> finish("max turn length");

    /** Is a turn open now (for the tile)? */
    static boolean turnOpen() {
        TurnService s = live;
        return s != null && s.turn != null;
    }

    /** From ActuatorService (a Binder thread): the session's backend is ready. */
    static void sessionReady(String turnId) {
        MAIN.post(() -> {
            TurnService s = live;
            if (s != null && s.turn != null && s.turn.turnId.equals(turnId)) {
                ((ActuatorApp) s.getApplication()).audioPlane().ready(turnId);
            }
        });
    }

    /**
     * From ActuatorService (a Binder thread): run a fast tool for the open turn. The actuator decides
     * the origin (§5.6): only the turn it opened, while it is conversing, may act. One audit line per
     * call; the earcon is the actuator's verdict (P3).
     */
    static String callTool(Context context, String turnId, String name, String argsJson) {
        ActuatorApp app = (ActuatorApp) context.getApplicationContext();
        TurnService s = live;
        VoiceTurn t = s == null ? null : s.turn;
        long t0 = android.os.SystemClock.elapsedRealtime();
        FastTools.Result r;
        if (t == null || !t.turnId.equals(turnId) || app.audioPlane().state() != AudioPlane.State.CONVERSE) {
            r = FastTools.Result.of(FastTools.Status.FAILED, "detail", "no open turn " + turnId);
        } else {
            r = app.fastTools().run(name, argsJson, new FastTools.MediaIntent() {
                @Override
                public void ownerPaused() {
                    app.audioPlane().ownerPausedMedia(turnId);
                }

                @Override
                public void ownerPlayed() {
                    app.audioPlane().ownerResumedMedia(turnId);
                }
            });
            Earcons.play(app, r.status, t.speakerToFile);
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "tool.call");
        line.put("turnId", turnId);
        line.put("tool", name);
        line.put("args", argsJson == null || argsJson.length() > 200 ? "(omitted)" : argsJson);
        line.put("status", r.status.name());
        line.put("result", r.json.toString());
        line.put("ms", android.os.SystemClock.elapsedRealtime() - t0);
        app.audit().record(line);
        return r.json.toString();
    }

    /** From ActuatorService (a Binder thread): the session ends the turn. */
    static void sessionEnded(String turnId, String reason) {
        MAIN.post(() -> {
            TurnService s = live;
            if (s != null && s.turn != null && s.turn.turnId.equals(turnId)) {
                s.finish("session: " + reason);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.turn_channel),
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public void onDestroy() {
        if (turn != null) {
            finish("service destroyed");
        }
        live = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String source = intent == null ? null : intent.getStringExtra(EXTRA_SOURCE);
        if (ACTION_SUMMON.equals(action)) {
            summon(source == null ? "unknown" : source,
                    BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_MIC_FILE, false),
                    BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_SPEAKER_FILE, false));
        } else if (ACTION_STOP.equals(action)) {
            if (turn != null) {
                finish("owner stop: " + (source == null ? "unknown" : source));
            } else {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void summon(String source, boolean micFromFile, boolean speakerToFile) {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) { // ForegroundServiceStartNotAllowedException, SecurityException
            Log.w(VoiceTurn.TAG, "summon refused: cannot start the microphone service (" + e.getClass().getSimpleName() + ")");
            stopSelf();
            return;
        }
        if (turn != null) {
            Log.i(VoiceTurn.TAG, "summon ignored: a turn is open");
            return;
        }
        String id = ((ActuatorApp) getApplication()).audioPlane().summon(source);
        if (id == null) {
            stopTurnService();
            return;
        }
        turn = new VoiceTurn(this, id, micFromFile, speakerToFile, reason -> MAIN.post(() -> finishIf(id, reason)));
        // A call that starts during the turn ends it (§3.3: "call starts" goes to RESTORE).
        ((ActuatorApp) getApplication()).audioPlatform().watchCalls(() -> MAIN.post(() -> finishIf(id, "call started")));
        try {
            turn.start();
        } catch (IOException e) {
            finish("turn did not start: " + e.getMessage());
            return;
        }
        MAIN.postDelayed(cap, MAX_TURN_MS);
        TileService.requestListeningState(this, new ComponentName(this, SummonTile.class));
    }

    private void finishIf(String turnId, String reason) {
        if (turn != null && turn.turnId.equals(turnId)) {
            finish(reason);
        }
    }

    /** Ends the turn. RESTORE and the service stop run even if closing the turn's I/O throws. */
    private void finish(String reason) {
        VoiceTurn t = turn;
        turn = null;
        MAIN.removeCallbacks(cap);
        ActuatorApp app = (ActuatorApp) getApplication();
        app.audioPlatform().unwatchCalls();
        try {
            if (t != null) {
                t.close(reason);
            }
        } catch (RuntimeException e) {
            Log.w(VoiceTurn.TAG, "closing the turn failed: " + e.getClass().getSimpleName());
        } finally {
            try {
                if (t != null) {
                    app.audioPlane().end(t.turnId, reason);
                }
            } catch (RuntimeException e) { // end() reaches IDLE on its own; this only keeps the main thread alive
                Log.w(VoiceTurn.TAG, "restore failed: " + e.getClass().getSimpleName());
            } finally {
                stopTurnService();
                TileService.requestListeningState(this, new ComponentName(this, SummonTile.class));
            }
        }
    }

    private void stopTurnService() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, TurnService.class).setAction(ACTION_STOP).putExtra(EXTRA_SOURCE, "notification"),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_vos)
                .setContentTitle(getString(R.string.turn_listening))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.turn_stop), stop).build())
                .build();
    }

    /** Summon or stop from any actuator component that has a foreground moment. */
    static void toggle(Context context, String source) {
        Intent i = new Intent(context, TurnService.class).putExtra(EXTRA_SOURCE, source);
        if (turnOpen()) {
            context.startService(i.setAction(ACTION_STOP));
        } else {
            context.startForegroundService(i.setAction(ACTION_SUMMON));
        }
    }
}
