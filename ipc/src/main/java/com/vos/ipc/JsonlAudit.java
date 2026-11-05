package com.vos.ipc;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Append-only audit lines in the app's CE storage ({@code files/audit/audit.jsonl}) and logcat
 * (tag {@code VOS_AUDIT}). The actuator's file is the audit of record; audit v1 replaces this
 * writer with rotation, the owner view and export (§3.8). Each line is one write on the caller's
 * thread. The fsync runs on the process's one sync thread ({@link AuditFile#backgroundSyncer()}),
 * coalesced, so a line is durable within one sync period. A torn tail left by a power cut is
 * repaired on the next write ({@link AuditFile}, #319). Every line is hash-chained to the one
 * before it ({@link AuditChain}, audit v2, #154): its {@code prev} is the SHA-256 of that line as
 * stored, and the {@code audit.torn_tail} note is a chained record too. The chain's head is
 * recovered on the first write from a bounded read of the file's end, never the whole file. Lines
 * from before the chain verify as breaks ({@link AuditChain} lists the known limits).
 */
public final class JsonlAudit implements BinderGuard.Audit {
    public static final String TAG = "VOS_AUDIT";

    private final AuditChain.Writer chain;

    public JsonlAudit(Context context) {
        this.chain = new AuditChain.Writer(new AuditFile(
                new File(new File(context.getFilesDir(), "audit"), "audit.jsonl"),
                AuditFile.backgroundSyncer(), e -> Log.e(TAG, "audit sync failed", e)));
    }

    /** The hash of the newest line written, for {@link AuditChain#verify(java.io.File, String)}; null before the first. */
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
     * transitions). Values: strings, numbers, booleans, null, or collections of those.
     */
    public void record(Map<String, Object> fields) {
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
        append(o);
    }

    private void append(JSONObject o) {
        try { // the write only: the fsync is on the syncer thread, never here or under a lock
            for (String line : chain.append(o)) { // the record, then the torn-tail note if one was due
                Log.w(TAG, line);
            }
        } catch (IOException e) {
            Log.w(TAG, o.toString());
            Log.e(TAG, "audit write failed", e);
        }
    }
}
