package com.vos.session;

import com.vos.ipc.AuditFile;
import java.io.File;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turn transcripts, session-private ({@code files/transcripts/transcripts.jsonl}; §5.8: 30 days,
 * the retention job comes with the task record). One line per owner utterance and per reply. Never
 * written to logcat. Rotates to {@code transcripts.1.jsonl} past 2 MB. Written through
 * {@link AuditFile} (#321): one synchronous write per line, fsync coalesced on the background sync
 * thread, and a torn tail from a power cut starts the next line fresh.
 */
final class TranscriptLog {
    static final long MAX_BYTES = 2L * 1024 * 1024;
    private final File file;
    private final AuditFile log;

    TranscriptLog(File dir) {
        this.file = new File(dir, "transcripts.jsonl");
        this.log = new AuditFile(file, AuditFile.backgroundSyncer(), e -> {
            // a lost transcript line never breaks the turn
        });
    }

    File file() {
        return file;
    }

    synchronized void write(String turnId, String role, String text) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis()).put("turn_id", turnId).put("role", role).put("text", text);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        if (file.length() > MAX_BYTES) {
            try {
                log.rotate(new File(file.getParentFile(), "transcripts.1.jsonl")); // the outgoing file is synced off this thread
            } catch (IOException ignored) {
                // keep appending to the current file; the next write tries again
            }
        }
        try {
            log.append(o.toString());
        } catch (IOException ignored) {
            // a lost transcript line never breaks the turn
        }
    }
}
