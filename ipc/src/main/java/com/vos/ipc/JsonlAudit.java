package com.vos.ipc;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Append-only audit lines in the app's CE storage ({@code files/audit/audit.jsonl}) and logcat
 * (tag {@code VOS_AUDIT}). The actuator's file is the audit of record; audit v1 replaces this
 * writer with rotation, the owner view and export (§3.8).
 */
public final class JsonlAudit implements BinderGuard.Audit {
    public static final String TAG = "VOS_AUDIT";

    private final File file;

    public JsonlAudit(Context context) {
        this.file = new File(new File(context.getFilesDir(), "audit"), "audit.jsonl");
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

    private synchronized void append(String line) {
        Log.w(TAG, line);
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create " + dir);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "audit write failed", e);
        }
    }
}
