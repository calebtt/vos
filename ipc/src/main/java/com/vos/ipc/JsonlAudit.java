package com.vos.ipc;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Append-only audit lines in the app's CE storage ({@code files/audit/audit.jsonl}), mirrored to
 * logcat in debug builds only (below). The actuator's file is the audit of record; audit v1 replaces this
 * writer with rotation, the owner view and export (§3.8). Each line is one write on the caller's
 * thread. The fsync runs on the process's one sync thread ({@link AuditFile#backgroundSyncer()}),
 * coalesced, so a line is durable within one sync period. A torn tail left by a power cut is
 * repaired on the next write ({@link AuditFile}, #319). Every line is hash-chained to the one
 * before it ({@link AuditChain}, audit v2, #154): its {@code prev} is the SHA-256 of that line as
 * stored, and the {@code audit.torn_tail} note is a chained record too. The chain's head is
 * recovered when the audit is built, from a bounded read of the file's end, never the whole
 * file. Lines from before the chain are accepted as a legacy prefix ({@link AuditChain} lists the
 * rules).
 *
 * <p>Logcat. A debug build mirrors every line to logcat (tag {@code VOS_AUDIT}), which the eval
 * runners read. A release build never puts a line's contents in logcat: a failed write logs only a
 * count and the exception, whose message names the file, never the record (#340).
 *
 * <p>Fail-closed contract (#340). {@link #record} returns whether the line reached the file; logging
 * may ignore that (best effort). A path that performs an externally visible or irreversible act must
 * not act unless the audit can take its line: it checks {@link #writable()} (the head is known and
 * the last write succeeded) before acting and refuses with a FAILED result otherwise. An
 * irreversible act (none is wired in M1) must also write its intent line first and act only if
 * {@link #record} returned true, because {@link #writable()} cannot see a failure that has not
 * happened yet. The writable state heals: the next line that reaches the file (the refusal's own
 * line, for instance) makes it true again. A record that cannot be linked is never appended
 * ({@link AuditChain.Writer} fails closed).
 */
public final class JsonlAudit implements BinderGuard.Audit {
    public static final String TAG = "VOS_AUDIT";

    /** Where the audit's own logcat output goes: android.util.Log in the app, a recorder in tests. */
    interface Logcat {
        /** A line's contents (debug builds only). */
        void line(String line);

        /** A failure, without any line's contents. */
        void error(String message, Throwable e);
    }

    private static final Logcat ANDROID = new Logcat() {
        @Override
        public void line(String line) {
            Log.w(TAG, line);
        }

        @Override
        public void error(String message, Throwable e) {
            Log.e(TAG, message, e);
        }
    };

    private final AuditChain.Writer chain;
    private final boolean mirror;
    private final Logcat logcat;
    private boolean lastWriteOk = true; // guarded by this
    private long failures; // guarded by this

    public JsonlAudit(Context context) {
        this(new AuditChain.Writer(new AuditFile(
                new File(new File(context.getFilesDir(), "audit"), "audit.jsonl"),
                AuditFile.backgroundSyncer(), e -> Log.e(TAG, "audit sync failed", e))),
                BuildConfig.DEBUG, ANDROID);
    }

    /**
     * {@code mirror}: copy each line's contents to logcat ({@code BuildConfig.DEBUG} in the app, so
     * never in a release build).
     */
    JsonlAudit(AuditChain.Writer chain, boolean mirror, Logcat logcat) {
        this.chain = chain;
        this.mirror = mirror;
        this.logcat = logcat;
    }

    /**
     * Whether the audit can take a line now: the chain's head is known (recovered at startup, or by a
     * later write) and the last write reached the file. False: an act must be refused (the class
     * comment's contract). It does not probe the disk, so a write can still fail after a true.
     */
    public synchronized boolean writable() {
        return lastWriteOk && chain.head() != null;
    }

    /** How many lines this audit failed to write since it was built. */
    public synchronized long failures() {
        return failures;
    }

    /**
     * The hash of the newest line, for {@link AuditChain#verify(java.io.File, String)}: recovered from
     * the file when this audit is built, so it is set from startup on; null only while that recovery
     * fails (then every write fails closed and is logged, never appended unlinked).
     */
    public String chainHead() {
        return chain.head();
    }

    @Override
    public void unauthorized(String service, int uid, String[] packages, String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis());
            o.put("event", "binder.unauthorized");
            o.put("service", service);
            o.put("uid", uid);
            JSONArray p = new JSONArray();
            for (String s : packages) {
                p.put(s);
            }
            o.put("packages", p);
            o.put("reason", reason);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        append(o);
    }

    /**
     * One audit line from a map whose {@code "event"} entry names it (e.g. the AudioPlane's
     * transitions). Values: strings, numbers, booleans, null, or collections of those. Returns
     * whether the line reached the file; a caller about to act irreversibly acts only on true.
     */
    public boolean record(Map<String, Object> fields) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis());
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Collection) {
                    JSONArray a = new JSONArray();
                    for (Object x : (Collection<?>) v) {
                        a.put(x);
                    }
                    v = a;
                }
                o.put(e.getKey(), v == null ? JSONObject.NULL : v);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return append(o);
    }

    private boolean append(JSONObject o) {
        List<String> written;
        try { // the write only: the fsync is on the syncer thread, never here or under a lock
            written = chain.append(o); // the record, then the torn-tail note if one was due
        } catch (IOException e) {
            long n;
            synchronized (this) {
                lastWriteOk = false;
                n = ++failures;
            }
            // Never the record's contents, in any build: the line is lost, and logcat is not the audit.
            logcat.error("audit write failed (" + n + " so far); the line was not written", e);
            return false;
        }
        synchronized (this) {
            lastWriteOk = true;
        }
        if (mirror) {
            for (String line : written) {
                logcat.line(line);
            }
        }
        return true;
    }
}
