package com.vos.ipc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * The bytes of one JSONL audit file (#319). Each record is one line, written by one synchronous
 * {@code write()} before {@link #append} returns, so a crash of the app loses nothing. The fsync is
 * not on the caller's thread: a background syncer runs it after the write, and one sync covers
 * every write before it. A record is durable within one sync period, not when append returns: a
 * power cut can lose the records written since the last finished sync. At worst it leaves a torn
 * tail (a cut-off line or NULs where unsynced data was), which the next append repairs and
 * {@link #read} skips.
 *
 * <p>There is no hash chain yet (audit v2). When it comes, every piece {@link #read} skips is a
 * gap: the verifier must report the chain broken at the next record it keeps and never link
 * across the gap. A skipped piece is counted in {@link Read#skipped}, never dropped silently, and
 * {@link JsonlAudit} writes an {@code audit.torn_tail} line when it repairs a torn tail.
 */
public final class AuditFile {
    /** One fsync of a file or a directory; injectable for tests. */
    interface Fsync {
        void sync(File fileOrDir) throws IOException;
    }

    private final File file;
    private final Executor syncer;
    private final Consumer<IOException> syncFailed;
    private final Fsync fsync;
    /** Whether a sync is queued and has not started yet; the next append after it starts queues another. */
    private final AtomicBoolean syncPending = new AtomicBoolean();
    /** Directories this instance created entries in (audit/'s parent, audit/), synced once by the next sync, best effort. */
    private final Queue<File> dirsToSync = new ConcurrentLinkedQueue<>();
    /** False until this instance has checked the tail, and again after a write that failed. Guarded by this. */
    private boolean tailKnownClean;

    /**
     * {@code syncer} runs the fsyncs, and should be one background thread; {@code syncFailed}
     * hears a failed file sync (on the syncer's thread).
     */
    public AuditFile(File file, Executor syncer, Consumer<IOException> syncFailed) {
        this(file, syncer, syncFailed, AuditFile::fsync);
    }

    AuditFile(File file, Executor syncer, Consumer<IOException> syncFailed, Fsync fsync) {
        this.file = file;
        this.syncer = syncer;
        this.syncFailed = syncFailed;
        this.fsync = fsync;
    }

    /**
     * Appends {@code json} (one JSON object, no newline) as one write of one complete line on an
     * {@code O_APPEND} stream, then queues a sync on the syncer unless one is already queued.
     * It never syncs on the caller's thread. On the first append, and after a failed one, it
     * creates the directory if it has to and checks the tail. If the directory is deleted while
     * the app runs, the next append recreates it and the file and retries once. If the file's last
     * byte is not a newline (a cut-off record, or a NUL run), the write starts with a newline, so
     * this record starts on a fresh line and the torn piece stays a piece of its own. Returns
     * whether it did that.
     */
    public boolean append(String json) throws IOException {
        boolean torn = write(json);
        if (syncPending.compareAndSet(false, true)) {
            syncer.execute(this::syncNow);
        }
        return torn;
    }

    private synchronized boolean write(String json) throws IOException {
        boolean torn = !tailKnownClean && prepare();
        tailKnownClean = false;
        try {
            writeLine((torn ? "\n" : "") + json);
        } catch (FileNotFoundException e) {
            File dir = file.getParentFile();
            if (dir == null || dir.isDirectory()) {
                throw e;
            }
            // The directory went away while we ran (e.g. rm -rf files/audit): start it and the file
            // again, as on a first append. A new file has no torn tail.
            prepare();
            torn = false;
            writeLine(json);
        }
        tailKnownClean = true;
        return torn;
    }

    /**
     * Creates the directory if it has to, queues the one-time syncs of the entries this creates,
     * and returns whether an existing file's tail is torn.
     */
    private boolean prepare() throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            if (!dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("cannot create " + dir);
            }
            File up = dir.getParentFile();
            if (up != null) {
                dirsToSync.add(up);
            }
        }
        if (!file.exists()) {
            if (dir != null) {
                dirsToSync.add(dir);
            }
            return false;
        }
        return tornTail();
    }

    private void writeLine(String line) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** On the syncer: one fsync of the file covers every write before it, then any new directories. */
    private void syncNow() {
        syncPending.set(false); // before the sync: a write that lands after this queues the next one
        try {
            fsync.sync(file);
        } catch (NoSuchFileException | FileNotFoundException e) {
            // moved aside or deleted since the write: nothing of ours left to sync
        } catch (IOException e) {
            syncFailed.accept(e);
        }
        for (File d; (d = dirsToSync.poll()) != null; ) {
            try {
                fsync.sync(d);
            } catch (IOException e) {
                // best effort: some platforms cannot open a directory to sync it
            }
        }
    }

    private static void fsync(File f) throws IOException {
        if (f.isDirectory()) {
            try (FileChannel c = FileChannel.open(f.toPath(), StandardOpenOption.READ)) {
                c.force(true);
            }
        } else {
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) { // read-only: never creates the file
                r.getFD().sync();
            }
        }
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
