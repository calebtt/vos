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
    /** {@code SystemClock.uptimeMillis()} when VOS got the summon (the tile click, the assist session showing), for S-07. */
    static final String EXTRA_T_EVENT = "t_event";
    /** Debug builds only (ignored otherwise): mic from files/debug/mic-inject.pcm, speaker to a file. */
    static final String EXTRA_DEBUG_MIC_FILE = "debug_mic_file";
    /** Debug builds only: the live mic, with mic-inject.pcm sent in place of its first frames (#249). */
    static final String EXTRA_DEBUG_MIC_FILE_THEN_LIVE = "debug_mic_file_then_live";
    /** Debug builds only: record from MIC with no AEC or NS (the control for #249's echo test). */
    static final String EXTRA_DEBUG_RAW_MIC = "debug_raw_mic";
    static final String EXTRA_DEBUG_SPEAKER_FILE = "debug_speaker_file";

    /** No turn keeps the mic longer than this, whatever the session does. */
    static final long MAX_TURN_MS = 10 * 60_000L;
    private static final String CHANNEL = "turn";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile TurnService live;
    private static volatile boolean inForeground; // startForeground succeeded for the current turn
    /** S-07 breakdown: uptimeMillis when the summon reached onStartCommand, and when startForeground returned. */
    static volatile long tStartCommand = -1;
    static volatile long tForeground = -1;
    static volatile long tPlane = -1; // the AudioPlane's summon (focus, media, mode, device) returned
    private volatile VoiceTurn turn; // read from Binder threads (callTool)
    private final Runnable cap = () -> finish("max turn length");

    /** Whether a voice turn is open (a typed turn waits for it to end). */
    static boolean voiceTurnOpen() {
        TurnService s = live;
        return s != null && s.turn != null;
    }

    /**
     * A summon may start the microphone service only when it holds the owner-turn slot.
     * {@link OwnerTurnSlot.Claim#BLOCKED} is an open typed turn: starting here would take the
     * indicator (Shell Contract row 13) and write the shared conversation beside that turn.
     */
    static boolean summonMayStartMicrophone(OwnerTurnSlot.Claim claim) {
        return OwnerTurnSlot.opensMicrophone(claim);
    }

    /** Has the microphone service reached the foreground (the assist session hides after this)? */
    static boolean inForeground() {
        return inForeground;
    }

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
            boolean acts = FastTools.changesState(name); // acting only while the device's state may change
            if (acts) {
                app.indicator().actingStarted(turnId); // the actuator acts: the indicator's acting state
            }
            try {
                r = app.fastTools().run(name, argsJson, new FastTools.MediaIntent() {
                    @Override
                    public Runnable ownerPausing() {
                        return app.audioPlane().ownerPausing(turnId);
                    }

                    @Override
                    public Runnable ownerPlaying() {
                        return app.audioPlane().ownerPlaying(turnId);
                    }
                });
            } finally {
                if (acts) {
                    app.indicator().actingEnded(turnId);
                }
            }
            Earcons.play(app, r.status, t.speakerToFile); // never throws: the result and audit line follow
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
            summon(source == null ? "unknown" : source, intent.getLongExtra(EXTRA_T_EVENT, -1),
                    micOf(intent), BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_RAW_MIC, false),
                    BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_SPEAKER_FILE, false));
        } else if (ACTION_STOP.equals(action)) {
            Log.i(VoiceTurn.TAG, "stop received source=" + source + " t_up=" + android.os.SystemClock.uptimeMillis());
            if (turn != null) {
                finish("owner stop: " + (source == null ? "unknown" : source));
            } else {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    /** The turn's mic: always LIVE in a release build. */
    private static VoiceTurn.Mic micOf(Intent intent) {
        if (!BuildConfig.DEBUG) {
            return VoiceTurn.Mic.LIVE;
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_MIC_FILE, false)) {
            return VoiceTurn.Mic.FILE;
        }
        return intent.getBooleanExtra(EXTRA_DEBUG_MIC_FILE_THEN_LIVE, false) ? VoiceTurn.Mic.FILE_THEN_LIVE : VoiceTurn.Mic.LIVE;
    }

    private void summon(String source, long tEvent, VoiceTurn.Mic mic, boolean rawMic, boolean speakerToFile) {
        tStartCommand = android.os.SystemClock.uptimeMillis();
        TypedChat chat = ((ActuatorApp) getApplication()).typedChat();
        // startSummon already holds the slot when it started this service. Claiming again is
        // HELD. BLOCKED means a typed turn is open: do not take the microphone foreground type.
        OwnerTurnSlot.Claim claim = chat.claimVoice();
        if (!summonMayStartMicrophone(claim)) {
            Log.i(VoiceTurn.TAG, "summon refused: a typed turn is open");
            if (turn == null) {
                stopSelf();
            }
            return;
        }
        try {
            // The current state, not IDLE: a second summon during a turn must not reset the title.
            startForeground(NotificationIds.TURN,notification(((ActuatorApp) getApplication()).indicator().state()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            inForeground = true;
            tForeground = android.os.SystemClock.uptimeMillis();
        } catch (RuntimeException e) { // ForegroundServiceStartNotAllowedException, SecurityException
            Log.w(VoiceTurn.TAG, "summon refused: cannot start the microphone service (" + e.getClass().getSimpleName() + ")");
            if (turn == null) {
                chat.releaseVoice();
            }
            stopSelf();
            return;
        }
        if (turn != null) {
            Log.i(VoiceTurn.TAG, "summon ignored: a turn is open");
            return;
        }
        String id = ((ActuatorApp) getApplication()).audioPlane().summon(source);
        tPlane = android.os.SystemClock.uptimeMillis();
        if (id == null) {
            chat.releaseVoice();
            stopTurnService();
            return;
        }
        turn = new VoiceTurn(this, id, source, tEvent, mic, rawMic, speakerToFile, reason -> MAIN.post(() -> finishIf(id, reason)));
        ((ActuatorApp) getApplication()).indicator().turnOpened(id); // only this turn's calls and tools show
        // A call that starts during the turn ends it (§3.3: "call starts" goes to RESTORE).
        ((ActuatorApp) getApplication()).audioPlatform().watchCalls(() -> MAIN.post(() -> finishIf(id, "call started")));
        try {
            turn.start();
        } catch (IOException | RuntimeException e) { // e.g. PeerLink.bind's SecurityException
            // The plane has already paused media and taken the mode: RESTORE gives them back.
            finish("turn did not start: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
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
        if (t != null) {
            app.indicator().turnEnded(t.turnId); // stop clears both states (Shell Contract row 13)
            // After the turn's last indicator posts (queued in decision order): nothing still queued
            // for this turn's title may land on the next turn's notification.
            MAIN.post(TITLE::cancel);
        }
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
                if (t != null) {
                    // After RESTORE, so a typed turn cannot start while this voice turn is still closing.
                    app.typedChat().releaseVoice();
                }
                stopTurnService();
                TileService.requestListeningState(this, new ComponentName(this, SummonTile.class));
            }
        }
    }

    private void stopTurnService() {
        inForeground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** The turn notification's title, paced for Android's notification rate limit (main thread only). */
    private static final TitlePacer TITLE = new TitlePacer(android.os.SystemClock::uptimeMillis,
            (delayMs, task) -> MAIN.postDelayed(task, delayMs), TurnService::postTitle);

    /** The indicator changed (main thread): the turn notification's title carries it, for when no overlay can be drawn. */
    static void indicatorChanged(Indicator.State state) {
        TITLE.changed(state);
    }

    private static void postTitle(Indicator.State state) {
        TurnService s = live;
        if (s != null && s.turn != null && inForeground) {
            s.getSystemService(NotificationManager.class).notify(NotificationIds.TURN,s.notification(state));
        }
    }

    private Notification notification(Indicator.State state) {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, TurnService.class).setAction(ACTION_STOP).putExtra(EXTRA_SOURCE, "notification"),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_vos)
                .setContentTitle(getString(state == Indicator.State.GENERATING ? R.string.indicator_generating
                        : state == Indicator.State.ACTING ? R.string.indicator_acting : R.string.turn_listening))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.turn_stop), stop).build())
                .build();
    }

    /** Summon or stop from any actuator component that has a foreground moment. */
    static void toggle(Context context, String source) {
        toggle(context, source, android.os.SystemClock.uptimeMillis());
    }

    /** As {@link #toggle(Context, String)}, with the {@code uptimeMillis} at which VOS got the summon, for S-07. */
    static void toggle(Context context, String source, long tEvent) {
        Intent i = new Intent(context, TurnService.class).putExtra(EXTRA_SOURCE, source).putExtra(EXTRA_T_EVENT, tEvent);
        if (turnOpen()) {
            context.startService(i.setAction(ACTION_STOP));
        } else {
            startSummon(context, i.setAction(ACTION_SUMMON));
        }
    }

    /**
     * Starts a voice summon, or does nothing when a typed turn holds the slot. The microphone
     * service is not started in that case, so this process never has to call {@code startForeground}
     * for a summon it will refuse.
     */
    static void startSummon(Context context, Intent summon) {
        TypedChat chat = ((ActuatorApp) context.getApplicationContext()).typedChat();
        OwnerTurnSlot.Claim claim = chat.claimVoice();
        if (!summonMayStartMicrophone(claim)) {
            Log.i(VoiceTurn.TAG, "summon refused: a typed turn is open");
            return;
        }
        try {
            context.startForegroundService(summon.setAction(ACTION_SUMMON));
        } catch (RuntimeException e) {
            if (claim == OwnerTurnSlot.Claim.NEW) {
                chat.releaseVoice();
            }
            throw e;
        }
    }
}
