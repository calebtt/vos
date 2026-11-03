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
 * repaired on the next write ({@link AuditFile}, #319).
 */
public final class JsonlAudit implements BinderGuard.Audit {
    public static final String TAG = "VOS_AUDIT";

    private final AuditFile file;

    public JsonlAudit(Context context) {
        this.file = new AuditFile(new File(new File(context.getFilesDir(), "audit"), "audit.jsonl"),
                AuditFile.backgroundSyncer(), e -> Log.e(TAG, "audit sync failed", e));
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
        append(o.toString());
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
        append(o.toString());
    }

    private synchronized void append(String line) {
        Log.w(TAG, line);
        try { // the write only: the fsync is on the syncer thread, never here or under this lock
            if (file.append(line)) { // the tail was torn (#319): say so in the trail, after the record
                String note;
                try {
                    note = new JSONObject().put("ts", System.currentTimeMillis()).put("event", "audit.torn_tail")
                            .put("detail", "the file ended in an unfinished line or NULs; the record before this started a fresh line")
                            .toString();
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
                Log.w(TAG, note);
                file.append(note);
            }
        } catch (IOException e) {
            Log.e(TAG, "audit write failed", e);
        }
    }
}
