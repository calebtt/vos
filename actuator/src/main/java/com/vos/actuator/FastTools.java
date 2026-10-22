package com.vos.actuator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The fast tools the actuator runs for an owner turn (§3.2 fixed tool surface), each checked by the
 * actuator's own oracle (P3: "oracle over confession"). A result is VERIFIED only when the oracle
 * saw the effect, UNVERIFIED when the act was done but its effect could not be observed, and FAILED
 * when it was refused or did not happen. Arguments come from the session and are validated here:
 * an unknown tool, an unknown argument or a value out of range is FAILED without acting.
 *
 * <p>v0: {@code time_get}, {@code volume_set} (a percentage), {@code volume_step} (up or down),
 * {@code media_control}. Tool names use underscores (the vendor's function-name rule); they are the
 * plan's {@code time.get}, {@code volume.set}, {@code media.control}. Setting and stepping are two
 * tools because a model given one tool with both forms filled in both (seen on Phone B).
 */
public final class FastTools {
    public enum Status { VERIFIED, UNVERIFIED, FAILED }

    /** AudioManager, media keys and media state, behind a seam for the JVM tests. */
    public interface Platform {
        long nowMillis();

        String zoneId();

        int streamMin(int stream);

        int streamMax(int stream);

        int streamVolume(int stream);

        /** Throws SecurityException when the platform refuses (e.g. ring volume under Do Not Disturb). */
        void setStreamVolume(int stream, int index);

        void mediaKey(int keyCode);

        /**
         * The active media session's state ("playing", "paused", "stopped", "buffering", "other"), "none"
         * if there is no session, or null when media state cannot be observed (notification listener off).
         */
        String mediaState();

        /** The playing item's media id, or null when the session publishes none. */
        String mediaId();

        /** The playing item's title, or null when there is no metadata yet. */
        String mediaTitle();

        void sleep(long ms) throws InterruptedException;
    }

    /** Tells the AudioPlane what the owner asked of media during the turn (RESTORE honours it). */
    public interface MediaIntent {
        /**
         * The owner asked for a pause or stop. Taken before the media key, so a turn that ends while the
         * oracle waits already knows; returns how to take it back if the pause does not stand.
         */
        Runnable ownerPausing();

        /** The owner asked for playback; as {@link #ownerPausing}. */
        Runnable ownerPlaying();
    }

    /** One tool result: its status plus fields for the model and the audit line. */
    public static final class Result {
        public final Status status;
        public final JSONObject json;

        Result(Status status, JSONObject json) {
            this.status = status;
            this.json = json;
        }

        static Result of(Status s, Object... kv) {
            JSONObject o = new JSONObject();
            try {
                o.put("status", s.name());
                for (int i = 0; i + 1 < kv.length; i += 2) {
                    o.put((String) kv[i], kv[i + 1]);
                }
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            return new Result(s, o);
        }
    }

    /** {@code AudioManager} stream constants, kept here so the class stays free of android.* */
    static final Map<String, Integer> STREAMS = Map.of(
            "call", 0, "ring", 2, "media", 3, "alarm", 4, "notification", 5);
    /** {@code KeyEvent} media key codes. */
    static final Map<String, Integer> MEDIA_KEYS = Map.of(
            "play", 126, "pause", 127, "next", 87, "previous", 88, "stop", 86);
    static final long MEDIA_ORACLE_MS = 1500;
    private static final long POLL_MS = 100;

    private final Platform platform;

    public FastTools(Platform platform) {
        this.platform = platform;
    }

    private interface Handler {
        Result run(FastTools tools, JSONObject args, MediaIntent media) throws BadArgs, InterruptedException;
    }

    /** One tool: how to run it, and whether it changes the device's state (the indicator shows acting only then). */
    private static final class Tool {
        final boolean changesState;
        final Handler handler;

        Tool(boolean changesState, Handler handler) {
            this.changesState = changesState;
            this.handler = handler;
        }
    }

    /** The tools {@link #run} runs, and the only names it dispatches; anything else is FAILED without acting. */
    private static final Map<String, Tool> TOOLS = Map.of(
            "time_get", new Tool(false, (t, args, media) -> t.timeGet(args)), // only reads the clock
            "volume_set", new Tool(true, (t, args, media) -> t.volumeSet(args, false)),
            "volume_step", new Tool(true, (t, args, media) -> t.volumeSet(args, true)),
            "media_control", new Tool(true, (t, args, media) -> t.mediaControl(args, media)));
    static final Set<String> NAMES = TOOLS.keySet();

    static boolean knows(String name) {
        return name != null && TOOLS.containsKey(name);
    }

    static boolean changesState(String name) {
        return knows(name) && TOOLS.get(name).changesState;
    }

    /** Runs {@code name} with the session's JSON arguments. Never throws for bad input. */
    public Result run(String name, String argsJson, MediaIntent media) {
        JSONObject args;
        try {
            args = argsJson == null || argsJson.isBlank() ? new JSONObject() : new JSONObject(argsJson);
        } catch (JSONException e) {
            return Result.of(Status.FAILED, "detail", "arguments are not a JSON object");
        }
        if (!knows(name)) {
            return Result.of(Status.FAILED, "detail", "unknown tool");
        }
        try {
            return TOOLS.get(name).handler.run(this, args, media);
        } catch (BadArgs e) {
            return Result.of(Status.FAILED, "detail", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.of(Status.FAILED, "detail", "interrupted");
        }
    }

    private Result timeGet(JSONObject args) throws BadArgs {
        allowOnly(args);
        ZonedDateTime now = Instant.ofEpochMilli(platform.nowMillis()).atZone(ZoneId.of(platform.zoneId()));
        return Result.of(Status.VERIFIED,
                "local_time", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "spoken", now.format(DateTimeFormatter.ofPattern("h:mm a, EEEE MMMM d", Locale.US)),
                "zone", platform.zoneId());
    }

    /** volume_set {stream?, percent} or, with {@code step}, volume_step {stream?, direction}. */
    private Result volumeSet(JSONObject args, boolean step) throws BadArgs {
        allowOnly(args, "stream", step ? "direction" : "percent");
        String streamName = args.optString("stream", "media");
        Integer stream = STREAMS.get(streamName);
        if (stream == null) {
            throw new BadArgs("stream must be one of " + STREAMS.keySet());
        }
        int min = platform.streamMin(stream);
        int max = platform.streamMax(stream);
        int before = platform.streamVolume(stream);
        int target;
        if (!step) {
            Object p = args.opt("percent");
            if (!(p instanceof Integer) || (Integer) p < 0 || (Integer) p > 100) {
                throw new BadArgs("percent must be a whole number from 0 to 100");
            }
            target = min + Math.round(((Integer) p) * (max - min) / 100f);
        } else {
            String direction = args.optString("direction");
            int delta = Math.max(1, Math.round((max - min) / 10f));
            if (direction.equals("up")) {
                target = Math.min(max, before + delta);
            } else if (direction.equals("down")) {
                target = Math.max(min, before - delta);
            } else {
                throw new BadArgs("direction must be up or down");
            }
        }
        try {
            platform.setStreamVolume(stream, target);
        } catch (SecurityException e) {
            return Result.of(Status.FAILED, "stream", streamName, "detail",
                    "the phone refused the change (Do Not Disturb blocks it)");
        }
        int after = platform.streamVolume(stream);
        Status s = after == target ? Status.VERIFIED : Status.UNVERIFIED;
        return Result.of(s, "stream", streamName, "percent_before", percent(before, min, max),
                "percent_requested", step ? args.optString("direction") : args.opt("percent"),
                "percent", percent(after, min, max),
                "detail", s == Status.VERIFIED ? "read back" : "the phone set " + percent(after, min, max) + "% instead");
    }

    /**
     * Media keys, then the oracle. A request that cannot stand (nothing to pause) is FAILED before any
     * key is sent. The owner's intent (pause or play) goes to the AudioPlane before the key, so a turn
     * that ends while the oracle waits already knows it, and is taken back unless it stands: VERIFIED,
     * or UNVERIFIED because media state cannot be observed at all. A visible session that never
     * reached the requested state leaves the AudioPlane as it was, so RESTORE still resumes what the
     * turn paused.
     */
    private Result mediaControl(JSONObject args, MediaIntent media) throws BadArgs, InterruptedException {
        allowOnly(args, "action");
        String action = args.optString("action");
        Integer key = MEDIA_KEYS.get(action);
        if (key == null) {
            throw new BadArgs("action must be one of " + MEDIA_KEYS.keySet());
        }
        String stateBefore = platform.mediaState();
        if ("none".equals(stateBefore) && !action.equals("play")) {
            return Result.of(Status.FAILED, "action", action, "detail", "nothing is playing");
        }
        String idBefore = platform.mediaId();
        String titleBefore = platform.mediaTitle();
        Runnable undo = action.equals("pause") || action.equals("stop") ? media.ownerPausing()
                : action.equals("play") ? media.ownerPlaying() : null;
        boolean stands = false;
        try {
            platform.mediaKey(key);
            Result r = mediaOracle(action, stateBefore, idBefore, titleBefore);
            stands = r.status == Status.VERIFIED || (r.status == Status.UNVERIFIED && stateBefore == null);
            return r;
        } finally {
            if (!stands && undo != null) {
                undo.run();
            }
        }
    }

    private Result mediaOracle(String action, String stateBefore, String idBefore, String titleBefore)
            throws InterruptedException {
        if (stateBefore == null) {
            return Result.of(Status.UNVERIFIED, "action", action, "detail", "media state is not observable");
        }
        boolean skip = action.equals("next") || action.equals("previous");
        if (skip && idBefore == null && titleBefore == null) {
            return Result.of(Status.UNVERIFIED, "action", action, "detail", "the track is not observable");
        }
        String seen = null; // a skip counts once the new track is seen on two polls in a row
        for (long waited = 0; waited <= MEDIA_ORACLE_MS; waited += POLL_MS) {
            String state = platform.mediaState();
            boolean ok;
            if (skip) {
                String now = newTrack(idBefore, titleBefore, platform.mediaId(), platform.mediaTitle());
                ok = now != null && now.equals(seen);
                seen = now;
            } else if (action.equals("play")) {
                ok = "playing".equals(state) || "buffering".equals(state);
            } else { // pause, stop
                ok = "paused".equals(state) || "stopped".equals(state) || "none".equals(state);
            }
            if (ok) {
                return Result.of(Status.VERIFIED, "action", action, "state", state);
            }
            platform.sleep(POLL_MS);
        }
        return Result.of(Status.UNVERIFIED, "action", action, "state", platform.mediaState(),
                "detail", "no change seen within " + MEDIA_ORACLE_MS + " ms");
    }

    /**
     * The identity of a different track now playing, or null if it is the same one (or unknown). Media
     * ids are compared when both sides have one; otherwise only titles, so metadata filling in later
     * (an id, an artist, an album on the same title) is not a skip.
     */
    static String newTrack(String idBefore, String titleBefore, String idNow, String titleNow) {
        if (idBefore != null && idNow != null) {
            return idNow.equals(idBefore) ? null : "id:" + idNow;
        }
        if (titleBefore != null && titleNow != null && !titleNow.equals(titleBefore)) {
            return "t:" + titleNow;
        }
        return null;
    }

    private static int percent(int index, int min, int max) {
        return max == min ? 0 : Math.round((index - min) * 100f / (max - min));
    }

    private static void allowOnly(JSONObject args, String... keys) throws BadArgs {
        Set<String> allowed = Set.of(keys);
        for (Iterator<String> it = args.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!allowed.contains(k)) {
                throw new BadArgs("unexpected argument " + k);
            }
        }
    }

    private static final class BadArgs extends Exception {
        BadArgs(String message) {
            super(message);
        }
    }
}
