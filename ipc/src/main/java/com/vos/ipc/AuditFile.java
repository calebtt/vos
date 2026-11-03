package com.vos.ipc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * The bytes of one JSONL audit file (#319). Each record is one line, durable once {@link #append}
 * returns. The file stays readable after a torn write, when power is cut in the middle of a write
 * and the filesystem keeps a cut-off line or NULs where unsynced data was.
 *
 * <p>There is no hash chain yet (audit v2). When it comes, every piece {@link #read} skips is a
 * gap: the verifier must report the chain broken at the next record it keeps and never link
 * across the gap. A skipped piece is counted in {@link Read#skipped}, never dropped silently, and
 * {@link JsonlAudit} writes an {@code audit.torn_tail} line when it repairs a torn tail.
 */
public final class AuditFile {
    private final File file;
    /** False until this instance has checked the tail, and again after a write that failed. */
    private boolean tailKnownClean;

    public AuditFile(File file) {
        this.file = file;
    }

    /**
     * Appends {@code json} (one JSON object, no newline) as one write of one complete line on an
     * {@code O_APPEND} stream, then syncs it to the device. On the first append, and after a
     * failed one, it checks the tail first. If the file's last byte is not a newline (a cut-off
     * record, or a NUL run), the write starts with a newline, so this record starts on a fresh line
     * and the torn piece stays a piece of its own. Returns whether it did that.
     */
    public synchronized boolean append(String json) throws IOException {
        boolean torn = !tailKnownClean && tornTail();
        byte[] bytes = ((torn ? "\n" : "") + json + "\n").getBytes(StandardCharsets.UTF_8);
        tailKnownClean = false;
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(bytes);
            out.getFD().sync();
        }
        tailKnownClean = true;
        return torn;
    }

    private boolean tornTail() throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            long n = r.length();
            if (n == 0) {
                return false;
            }
            r.seek(n - 1);
            return r.read() != '\n';
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    /** What {@link #read} kept and what it skipped. */
    public static final class Read {
        /** The whole records, in file order. */
        public final List<JSONObject> records;
        /**
         * Pieces that were not one whole record: a line with NULs (counted once, however long
         * the run), a line that is not exactly one JSON object, and an unfinished last line.
         * Blank lines are not counted.
         */
        public final int skipped;
        /** Whether the file ended in an unfinished line (no final newline). It counts in skipped. */
        public final boolean tornTail;

        Read(List<JSONObject> records, int skipped, boolean tornTail) {
            this.records = Collections.unmodifiableList(records);
            this.skipped = skipped;
            this.tornTail = tornTail;
        }
    }

    /**
     * Reads the file tolerantly; a missing file reads as empty. Blank lines are ignored. A line
     * that is not exactly one JSON object is skipped. A line with NULs is skipped too, but a whole
     * record on it outside the NUL runs is kept: that is a record a later boot appended after the
     * hole, and no JSON text has a raw NUL in it. The last line, if it has no newline, is skipped
     * as torn even when it parses, because it may still be being written. After the writer's
     * next {@link #append} has given it a newline, a whole record there reads as kept.
     */
    public Read read() throws IOException {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file.toPath());
        } catch (NoSuchFileException e) {
            return new Read(new ArrayList<>(), 0, false);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        List<JSONObject> records = new ArrayList<>();
        int skipped = 0;
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) { // the unfinished last line
                skipped++;
                return new Read(records, skipped, true);
            }
            String line = text.substring(start, nl);
            start = nl + 1;
            if (line.indexOf('\0') >= 0) {
                skipped++;
                for (String piece : line.split("\0+")) {
                    JSONObject o = piece.isBlank() ? null : object(piece);
                    if (o != null) {
                        records.add(o);
                    }
                }
            } else if (!line.isBlank()) {
                JSONObject o = object(line);
                if (o != null) {
                    records.add(o);
                } else {
                    skipped++;
                }
            }
        }
        return new Read(records, skipped, false);
    }

    /** {@code s} as one JSON object with nothing after it, or null. */
    private static JSONObject object(String s) {
        try {
            JSONTokener t = new JSONTokener(s.strip());
            Object v = t.nextValue();
            return v instanceof JSONObject && !t.more() ? (JSONObject) v : null;
        } catch (JSONException e) {
            return null;
        }
    }
}
