package com.vos.session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turn transcripts, session-private ({@code files/transcripts/transcripts.jsonl}; §5.8: 30 days,
 * the retention job comes with the task record). One line per owner utterance and per reply. Never
 * written to logcat. Rotates to {@code transcripts.1.jsonl} past 2 MB.
 */
final class TranscriptLog {
    static final long MAX_BYTES = 2L * 1024 * 1024;
    private final File file;

    TranscriptLog(File dir) {
        this.file = new File(dir, "transcripts.jsonl");
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
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        if (file.length() > MAX_BYTES) {
            File old = new File(dir, "transcripts.1.jsonl");
            old.delete();
            file.renameTo(old);
        }
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // a lost transcript line never breaks the turn
        }
    }
}
