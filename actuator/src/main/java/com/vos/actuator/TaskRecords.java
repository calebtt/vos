package com.vos.actuator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Task records (decision 23, §3.8, M1-TASKLOG-01), v0: the store the actuator owns. One record per
 * task, one JSONL file per record in actuator CE storage next to the audit log: a header line, then
 * plan, model pick, step, timing and compaction entries in order, then exactly one outcome line,
 * after which the record is closed. The schema id is {@link #SCHEMA}; the field names, sources,
 * outcomes and veto reasons are closed sets here, so adding one is a code change that follows the
 * plan change §3.8 requires. Every text value is scrubbed of key-like strings before it is written.
 * The owner can list, read, export and delete records; records older than {@link #RETENTION_MS}
 * are pruned (§9 Q24). An owner-pinned record never expires. A torn last line, from a write that
 * did not finish, is dropped when the store restarts. Frames (failure screenshots, masking) come
 * with the observation pipeline.
 */
final class TaskRecords {
    static final String SCHEMA = "vos.task_record/0";
    /** §9 Q24's default: records live 90 days. */
    static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000;

    static final Set<String> OUTCOMES = Set.of("pass", "fail", "cancelled", "vetoed", "timeout");
    /** §9 Q23: the closed set of typed veto reasons. */
    static final Set<String> VETO_REASONS = Set.of("needs_confirmation", "owner_absent", "mode_quiet", "mode_paused",
            "blocked_area", "secure_pixels", "permission_denied", "oracle_failed", "rate_limited", "scope_violation",
            "taint_flow");
    /** §3.8's per-step fields (with the 2026-10-17 additions that apply to a step). */
    static final Set<String> STEP_FIELDS = Set.of("foreground", "observation", "model", "prefill_tokens",
            "decode_tokens", "latency_ms", "proposed_action", "veto", "executed_action", "oracle", "inverse",
            "no_undo", "image_count", "text_bytes", "thermal_status", "work_log", "owner_step");
    /**
     * §3.8's compaction line (decision 46). For a high-risk redaction, {@code replaced} is the count,
     * never the string, and {@code saved} says whether the redacted context reached disk.
     */
    static final Set<String> COMPACTION_FIELDS = Set.of("context", "tokens_before", "tokens_after", "budget",
            "dropped_kinds", "sources", "model", "taint", "replaced", "saved");
    private static final Set<String> FIXED_SOURCES = Set.of("owner_voice", "owner_chat", "owner_demo", "agent", "eval");
    private static final Pattern PREFIXED_SOURCE = Pattern.compile("(app_agent|vtb):[A-Za-z0-9._-]{1,128}");
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Key-like strings: vendor key prefixes and bearer tokens. */
    private static final Pattern KEY_LIKE = Pattern.compile(
            "(?i)\\b(?:xai|sk)-[A-Za-z0-9_-]{8,}|\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    static final String SCRUBBED = "[key removed]";

    private final File dir;
    private final LongSupplier clock;
    private final Set<String> open = new HashSet<>();

    /** {@code dir}: the records directory; records left open by an earlier process are closed as cancelled. */
    TaskRecords(File dir, LongSupplier clock) {
        this.dir = dir;
        this.clock = clock;
        for (String id : taskIds()) {
            dropTornTail(id);
            if (!closed(id)) {
                open.add(id);
                close(id, "cancelled", "the actuator restarted before the task ended", null);
            }
        }
    }

    /**
     * Opens the record for {@code taskId}. {@code source}: owner_voice, owner_chat, owner_demo,
     * agent, eval, app_agent:PKG or vtb:PEER. Returns null, or why not (a task has exactly one record).
     */
    synchronized String open(String taskId, String parentTaskId, String turnId, String source, String request) {
        if (!validId(taskId) || (parentTaskId != null && !validId(parentTaskId))) {
            return "bad task id";
        }
        if (source == null || !(FIXED_SOURCES.contains(source) || PREFIXED_SOURCE.matcher(source).matches())) {
            return "bad source";
        }
        if (file(taskId).exists()) {
            return "the task already has a record";
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("schema", SCHEMA);
        h.put("task_id", taskId);
        h.put("parent_task_id", parentTaskId);
        h.put("turnId", turnId); // §3.8 header field
        h.put("source", source);
        h.put("request", request);
        String why = append(taskId, "header", h, true);
        if (why == null) {
            open.add(taskId);
        }
        return why;
    }

    /**
     * Pins or unpins a record (§9 Q24: an owner-pinned task never expires). Works on an open or a
     * closed record. Returns null, or why not.
     */
    synchronized String pin(String taskId, boolean pinned) {
        if (!validId(taskId) || !file(taskId).isFile()) {
            return "no such record";
        }
        try {
            byte[] raw = Files.readAllBytes(file(taskId).toPath());
            int nl = 0;
            while (nl < raw.length && raw[nl] != '\n') {
                nl++;
            }
            JSONObject header = new JSONObject(new String(raw, 0, nl, StandardCharsets.UTF_8));
            header.put("pinned", pinned);
            byte[] head = (header + "\n").getBytes(StandardCharsets.UTF_8);
            int restAt = nl < raw.length ? nl + 1 : raw.length;
            byte[] out = Arrays.copyOf(head, head.length + (raw.length - restAt));
            System.arraycopy(raw, restAt, out, head.length, raw.length - restAt);
            Files.write(file(taskId).toPath(), out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | JSONException e) {
            return "not saved";
        }
        return null;
    }

    /** The Conductor's plan, or a replan with its trigger (veto, oracle failure, owner correction). */
    synchronized String plan(String taskId, String plan, String trigger) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("plan", plan);
        e.put("trigger", trigger);
        return append(taskId, trigger == null ? "plan" : "replan", e, false);
    }

    /** A ModelRouter pick for a task class (§3.2.1), an escalation included. */
    synchronized String modelPick(String taskId, String taskClass, String model, String why) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("task_class", taskClass);
        e.put("model", model);
        e.put("why", why);
        return append(taskId, "model_pick", e, false);
    }

    /**
     * One step: only {@link #STEP_FIELDS}. A {@code veto} is a map {reason, class, actId} with a
     * reason from {@link #VETO_REASONS} (decision 22).
     */
    synchronized String step(String taskId, Map<String, Object> fields) {
        String bad = unknown(fields, STEP_FIELDS);
        if (bad != null) {
            return "no step field " + bad;
        }
        Object veto = fields.get("veto");
        if (veto != null) {
            if (!(veto instanceof Map) || !VETO_REASONS.contains(((Map<?, ?>) veto).get("reason"))
                    || !((Map<?, ?>) veto).keySet().equals(Set.of("reason", "class", "actId"))) {
                return "a veto is {reason, class, actId} with a reason from Q23";
            }
        }
        return append(taskId, "step", fields, false);
    }

    /** Decision 31's voice-turn times (uptime milliseconds; null if not seen). */
    synchronized String timing(String taskId, Long speechEndMs, Long endOfTurnMs, Long firstAudioMs) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("speech_end_ms", speechEndMs);
        e.put("end_of_turn_ms", endOfTurnMs);
        e.put("first_audio_ms", firstAudioMs);
        return append(taskId, "timing", e, false);
    }

    /** One compaction of a context (decision 46): counts and kinds, never the compacted text. */
    synchronized String compaction(String taskId, Map<String, Object> fields) {
        String bad = unknown(fields, COMPACTION_FIELDS);
        return bad != null ? "no compaction field " + bad : append(taskId, "compaction", fields, false);
    }

    /** Ends the record: one of {@link #OUTCOMES}, the final oracle detail, and a plain failure reason. */
    synchronized String close(String taskId, String outcome, String detail, String failureReason) {
        if (!OUTCOMES.contains(outcome)) {
            return "bad outcome";
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("outcome", outcome);
        e.put("detail", detail);
        e.put("failure_reason", failureReason);
        String why = append(taskId, "outcome", e, false);
        if (why == null) {
            open.remove(taskId);
        }
        return why;
    }

    /** Every record's task id, oldest first: the header's {@code ts} (when the record was opened), not the file mtime. */
    synchronized List<String> taskIds() {
        List<String> ids = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (fs != null) {
            Arrays.sort(fs, (a, b) -> {
                int c = Long.compare(created(a), created(b));
                return c != 0 ? c : a.getName().compareTo(b.getName());
            });
            for (File f : fs) {
                ids.add(f.getName().substring(0, f.getName().length() - ".jsonl".length()));
            }
        }
        return ids;
    }

    /** One record as JSONL (the owner view and a one-task export), or null if there is none. */
    synchronized String read(String taskId) {
        if (!validId(taskId) || !file(taskId).isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file(taskId).toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Every record as one JSONL text, oldest first (export all). */
    synchronized String exportAll() {
        StringBuilder sb = new StringBuilder();
        for (String id : taskIds()) {
            String r = read(id);
            if (r != null) {
                sb.append(r);
            }
        }
        return sb.toString();
    }

    /** Deletes one record; true if it existed. */
    synchronized boolean delete(String taskId) {
        open.remove(taskId);
        return validId(taskId) && file(taskId).delete();
    }

    /** Deletes every record; returns how many. */
    synchronized int deleteAll() {
        int n = 0;
        for (String id : taskIds()) {
            n += delete(id) ? 1 : 0;
        }
        return n;
    }

    /** Deletes closed, unpinned records whose header is older than {@link #RETENTION_MS}; returns how many. */
    synchronized int prune() {
        long cutoff = clock.getAsLong() - RETENTION_MS;
        int n = 0;
        for (String id : taskIds()) {
            if (open.contains(id)) {
                continue;
            }
            String r = read(id);
            long ts = Long.MAX_VALUE;
            boolean pinned = false;
            try {
                JSONObject header = r == null ? null : new JSONObject(r.substring(0, r.indexOf('\n')));
                if (header != null) {
                    ts = header.getLong("ts");
                    pinned = header.optBoolean("pinned"); // Q24: an owner-pinned task never expires
                }
            } catch (JSONException | IndexOutOfBoundsException ignored) {
                // unreadable header: kept for the owner to look at or delete
            }
            if (!pinned && ts < cutoff && delete(id)) {
                n++;
            }
        }
        return n;
    }

    /** {@code text} with key-like strings replaced (§3.8: model output is scrubbed for keys). */
    static String scrub(String text) {
        return text == null ? null : KEY_LIKE.matcher(text).replaceAll(SCRUBBED);
    }

    private String append(String taskId, String entry, Map<String, Object> fields, boolean first) {
        if (!validId(taskId)) {
            return "bad task id";
        }
        if (!first && !open.contains(taskId)) {
            return file(taskId).exists() ? "the record is closed" : "no such record";
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ts", clock.getAsLong());
            o.put("entry", entry);
            if (!first) {
                o.put("task_id", taskId);
            }
            for (Map.Entry<String, Object> f : fields.entrySet()) {
                o.put(f.getKey(), json(f.getValue()));
            }
        } catch (JSONException e) {
            return "bad value";
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "cannot create " + dir;
        }
        try {
            Files.write(file(taskId).toPath(), (o + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            return "not saved: " + e.getClass().getSimpleName();
        }
        return null;
    }

    private static Object json(Object v) throws JSONException {
        if (v == null) {
            return JSONObject.NULL;
        }
        if (v instanceof String) {
            return scrub((String) v);
        }
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                o.put(String.valueOf(e.getKey()), json(e.getValue()));
            }
            return o;
        }
        if (v instanceof Collection) {
            JSONArray a = new JSONArray();
            for (Object x : (Collection<?>) v) {
                a.put(json(x));
            }
            return a;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v;
        }
        throw new JSONException("unsupported value " + v.getClass().getSimpleName());
    }

    /** When the record was opened, from the header {@code ts}. Unreadable headers sort last. */
    private static long created(File f) {
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            int nl = 0;
            while (nl < raw.length && raw[nl] != '\n') {
                nl++;
            }
            return new JSONObject(new String(raw, 0, nl, StandardCharsets.UTF_8)).getLong("ts");
        } catch (IOException | JSONException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Drops a last line that a crash cut short, so recovery does not treat a half-written line as
     * the record. A line counts as torn when the file does not end on a newline, or the last line
     * is not JSON. An empty file is removed.
     */
    private void dropTornTail(String taskId) {
        File f = file(taskId);
        byte[] raw;
        try {
            raw = Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return;
        }
        int end = raw.length;
        if (end == 0) {
            f.delete();
            return;
        }
        if (raw[end - 1] != '\n') {
            end = lastNewline(raw, end - 1) + 1;
        }
        while (end > 0) {
            int start = lastNewline(raw, end - 2) + 1;
            try {
                new JSONObject(new String(raw, start, end - 1 - start, StandardCharsets.UTF_8));
                break;
            } catch (JSONException e) {
                end = start;
            }
        }
        if (end == raw.length) {
            return;
        }
        try {
            if (end == 0) {
                Files.deleteIfExists(f.toPath());
            } else {
                Files.write(f.toPath(), Arrays.copyOf(raw, end), StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException ignored) {
            // the next read still sees the torn line; closed() then leaves the record for the owner
        }
    }

    private static int lastNewline(byte[] raw, int from) {
        for (int i = from; i >= 0; i--) {
            if (raw[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private boolean closed(String taskId) {
        String r = read(taskId);
        if (r == null) {
            return true;
        }
        String[] lines = r.split("\n");
        try {
            return "outcome".equals(new JSONObject(lines[lines.length - 1]).optString("entry"));
        } catch (JSONException e) {
            return false;
        }
    }

    private static String unknown(Map<String, Object> fields, Set<String> allowed) {
        for (String k : fields.keySet()) {
            if (!allowed.contains(k)) {
                return k;
            }
        }
        return null;
    }

    private static boolean validId(String id) {
        return id != null && TASK_ID.matcher(id).matches();
    }

    private File file(String taskId) {
        return new File(dir, taskId + ".jsonl");
    }
}
