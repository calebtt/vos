package com.vos.actuator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one component that decides what is heard, through which device, and who holds the mic
 * (§3.3). This is the M1 skeleton: IDLE → CAPTURE → CONVERSE → RESTORE → IDLE, with one audit
 * line per transition and RESTORE of everything a turn changed. NOTICE, ANNOUNCE (M2 trusted path),
 * APP_ASSIST (M4) and HOLD's offline-only summon are declared but not reachable yet: a summon
 * during a call is refused.
 *
 * <p>Media: at the summon a playing session is paused explicitly through its MediaController and
 * recorded as {@code pausedPkg}; RESTORE resumes it only if this turn paused it and the owner did
 * not ask for a pause during the turn. If the owner did, a player that resumes itself after VOS
 * abandons focus (its transient-loss resume, ~100 ms later) is paused again: RESTORE re-checks at
 * {@link #RECHECK_MS} (the July bug, where an owner's "pause" was undone at turn end).
 */
public final class AudioPlane {
    public enum State { IDLE, CAPTURE, CONVERSE, NOTICE, ANNOUNCE, APP_ASSIST, HOLD, RESTORE }

    /** AudioManager / MediaSession operations, behind a seam for the JVM tests. */
    public interface Platform {
        /** A call (telephony or another app's VoIP) owns the audio mode. */
        boolean callActive();

        int mode();

        void setMode(int mode);

        /** Picks and sets the communication device (a headset if present, else the speaker); returns its type name. */
        String setCommunicationDevice();

        void clearCommunicationDevice();

        /** The input device the capture will use, for the audit line. */
        String inputDevice();

        boolean requestFocus();

        void abandonFocus();

        /** The package of a MediaSession that is playing now, or null (also when media control is not granted). */
        String playingMediaPackage();

        boolean pauseMedia(String pkg);

        boolean playMedia(String pkg);

        boolean isMediaPlaying(String pkg);

        /** Runs {@code task} after {@code delayMs} (RESTORE's media re-checks). */
        void schedule(Runnable task, long delayMs);
    }

    public interface Audit {
        void transition(Map<String, Object> line);
    }

    /** {@code AudioManager.MODE_IN_COMMUNICATION}, kept here so the class stays free of android.* */
    static final int MODE_IN_COMMUNICATION = 3;
    /** {@code AudioManager} modes that mean a call: RINGTONE, IN_CALL, CALL_SCREENING, CALL_REDIRECT, COMMUNICATION_REDIRECT. */
    private static final int[] CALL_MODES = {1, 2, 4, 5, 6};
    static final long[] RECHECK_MS = {600, 2000};

    private final Platform platform;
    private final Audit audit;
    private State state = State.IDLE;
    private String turnId;
    private int savedMode;
    private boolean commDeviceSet;
    private String outDev;
    private String inDev;
    private boolean focusHeld;
    private String pausedPkg;
    private boolean ownerPausedMedia;
    /** Bumped by every summon, so RESTORE's media re-checks never act after a later turn has begun. */
    private long epoch;

    public AudioPlane(Platform platform, Audit audit) {
        this.platform = platform;
        this.audit = audit;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String turnId() {
        return turnId;
    }

    /**
     * A summon: mints a turn and moves IDLE → CAPTURE (pause media, take focus, communication mode
     * and device). Returns the new {@code turnId}, or null if refused (a turn is open, or a call).
     */
    public synchronized String summon(String source) {
        if (state != State.IDLE) {
            refuse("summon", "turn already open", source);
            return null;
        }
        if (platform.callActive()) {
            refuse("summon", "call active (HOLD: offline grammar only, not built yet)", source);
            return null;
        }
        String id = UUID.randomUUID().toString();
        epoch++;
        turnId = id;
        ownerPausedMedia = false;
        pausedPkg = null;
        String playing = platform.playingMediaPackage();
        if (playing != null && platform.pauseMedia(playing)) {
            pausedPkg = playing;
        }
        focusHeld = platform.requestFocus();
        savedMode = platform.mode();
        platform.setMode(MODE_IN_COMMUNICATION);
        outDev = platform.setCommunicationDevice();
        commDeviceSet = outDev != null;
        inDev = platform.inputDevice();
        move(State.CAPTURE, source);
        return id;
    }

    /** The session's voice backend is ready: CAPTURE → CONVERSE. */
    public synchronized boolean ready(String id) {
        if (state != State.CAPTURE || !id.equals(turnId)) {
            refuse("ready", "not in CAPTURE for this turn", id);
            return false;
        }
        move(State.CONVERSE, "vendor ready");
        return true;
    }

    /** The owner asked for a media pause or stop during the turn (fast tools); RESTORE must not resume. */
    public synchronized void ownerPausedMedia(String id) {
        if (id.equals(turnId)) {
            ownerPausedMedia = true;
        }
    }

    /** The owner asked for playback during the turn; media is theirs again, not ours to resume. */
    public synchronized void ownerResumedMedia(String id) {
        if (id.equals(turnId)) {
            ownerPausedMedia = false;
            pausedPkg = null;
        }
    }

    /**
     * Ends the turn: RESTORE (mode, communication device, focus, media) → IDLE. Returns false for an
     * unknown or already-ended turn (audited, nothing changes).
     */
    public synchronized boolean end(String id, String reason) {
        if (turnId == null || !turnId.equals(id) || state == State.IDLE || state == State.RESTORE) {
            refuse("end", "no such open turn", id + " (" + reason + ")");
            return false;
        }
        move(State.RESTORE, reason);
        // Each undo is tried on its own: an audio-server failure in one (the same restart that kills
        // a recording) must not skip the others or leave the plane stuck in RESTORE.
        java.util.List<String> failed = new java.util.ArrayList<>();
        boolean callNow = false;
        String resumed = null;
        try {
            callNow = isCallMode(platform.mode());
        } catch (RuntimeException e) {
            failed.add("mode");
        }
        try {
            if (!callNow) { // a call that started during the turn owns the mode now; leave it
                platform.setMode(savedMode);
            }
        } catch (RuntimeException e) {
            failed.add("setMode");
        }
        try {
            if (commDeviceSet) { // VOS's own request only; a call's route is the call's own
                platform.clearCommunicationDevice();
            }
        } catch (RuntimeException e) {
            failed.add("clearCommunicationDevice");
        }
        try {
            if (focusHeld) {
                platform.abandonFocus();
            }
        } catch (RuntimeException e) {
            failed.add("abandonFocus");
        }
        if (!callNow) { // never start media into a call
            try {
                if (pausedPkg != null && !ownerPausedMedia && platform.playMedia(pausedPkg)) {
                    resumed = pausedPkg;
                }
            } catch (RuntimeException e) {
                failed.add("playMedia");
            }
            if (ownerPausedMedia && pausedPkg != null) {
                String pkg = pausedPkg;
                long armed = epoch;
                for (long ms : RECHECK_MS) {
                    platform.schedule(() -> keepOwnerPause(id, pkg, armed), ms);
                }
            }
        }
        commDeviceSet = false;
        focusHeld = false;
        outDev = null;
        inDev = null;
        String left = callNow && pausedPkg != null ? ", " + pausedPkg + " left paused for the call" : "";
        pausedPkg = null;
        move(State.IDLE, "restored" + (resumed != null ? ", resumed " + resumed : "")
                + (callNow ? ", mode left to the call" : "") + left
                + (failed.isEmpty() ? "" : ", failed: " + String.join(" ", failed)));
        turnId = null;
        return true;
    }

    /**
     * After RESTORE: the owner paused {@code pkg} during turn {@code id}; pause it again if it resumed
     * itself. Ignored once any later summon has happened ({@code armed} is the epoch at RESTORE).
     */
    private synchronized void keepOwnerPause(String id, String pkg, long armed) {
        if (epoch == armed && turnId == null && platform.isMediaPlaying(pkg) && platform.pauseMedia(pkg)) {
            Map<String, Object> l = line("audio.media_repaused", "player resumed itself after the turn; owner had paused it");
            l.put("turnId", id);
            l.put("pausedPkg", pkg);
            audit.transition(l);
        }
    }

    static boolean isCallMode(int mode) {
        for (int m : CALL_MODES) {
            if (m == mode) {
                return true;
            }
        }
        return false;
    }

    private void move(State to, String reason) {
        State from = state;
        state = to;
        Map<String, Object> l = line("audio.transition", reason);
        l.put("from", from.name());
        audit.transition(l);
    }

    private void refuse(String op, String why, String detail) {
        Map<String, Object> l = line("audio.refused", why);
        l.put("op", op);
        l.put("detail", detail);
        audit.transition(l);
    }

    private Map<String, Object> line(String event, String reason) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("event", event);
        l.put("state", state.name());
        l.put("focus", focusHeld ? "GAIN_TRANSIENT" : "none");
        l.put("mode", platform.mode());
        l.put("inDev", inDev);
        l.put("outDev", outDev);
        l.put("pausedPkg", pausedPkg);
        l.put("mixes", List.of());
        l.put("turnId", turnId);
        l.put("reason", reason);
        return l;
    }
}
