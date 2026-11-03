package com.vos.session;

import com.vos.ipc.AuditFile;
import java.io.File;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The session's egress log (§5.8): one JSONL line per HTTP request or WebSocket connection, in
 * {@code files/egress/requests.jsonl}. It records where traffic went and how much, never headers,
 * query strings or bodies. The file rotates to {@code requests.1.jsonl} past 2 MB. Written through
 * {@link AuditFile} (#321): one synchronous write per line, fsync coalesced on the background sync
 * thread, and a torn tail from a power cut starts the next line fresh.
 */
public final class RequestLog {
    /** One finished (or refused) exchange. {@code status} is the HTTP status, or -1 if none. */
    public static final class Entry {
        public final String kind;
        public final String host;
        public final String method;
        public final String path;
        public final long bytesOut;
        public final long bytesIn;
        public final int status;
        public final String turnId;
        public final String outcome;

        public Entry(String kind, String host, String method, String path, long bytesOut, long bytesIn, int status,
                String turnId, String outcome) {
            this.kind = kind;
            this.host = host;
            this.method = method;
            this.path = path;
            this.bytesOut = bytesOut;
            this.bytesIn = bytesIn;
            this.status = status;
            this.turnId = turnId;
            this.outcome = outcome;
        }
    }

    static final long MAX_BYTES = 2L * 1024 * 1024;

    private final File file;
    private final AuditFile log;

    public RequestLog(File dir) {
        this.file = new File(dir, "requests.jsonl");
        this.log = new AuditFile(file, AuditFile.backgroundSyncer(), e -> {
            // a lost line shows up as a gap in the field check, never as a broken request
        });
    }

    public File file() {
        return file;
    }

    public synchronized void write(Entry e) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis());
            o.put("kind", e.kind);
            o.put("host", e.host);
            o.put("method", e.method);
            o.put("path", e.path);
            o.put("bytes_out", e.bytesOut);
            o.put("bytes_in", e.bytesIn);
            o.put("status", e.status);
            o.put("turn_id", e.turnId == null ? JSONObject.NULL : e.turnId);
            o.put("outcome", e.outcome);
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
        append(o.toString());
    }

    private void append(String line) {
        if (file.length() > MAX_BYTES) {
            try {
                log.rotate(new File(file.getParentFile(), "requests.1.jsonl")); // the outgoing file is synced off this thread
            } catch (IOException ignored) {
                // keep appending to the current file; the next write tries again
            }
        }
        try {
            log.append(line);
        } catch (IOException ignored) {
            // The log must never break a request; a lost line shows up as a gap in the field check.
        }
    }
}
