package com.vos.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The audit file after torn writes (#319): what a power cut can leave, and what reads and appends do next. */
public class AuditFileTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file;

    private AuditFile at(String bytes) throws IOException {
        file = tmp.newFile("audit.jsonl");
        Files.write(file.toPath(), bytes.getBytes(StandardCharsets.UTF_8));
        return open(file);
    }

    /** Real fsyncs, run inline: these tests are about the bytes. */
    private static AuditFile open(File f) {
        return new AuditFile(f, Runnable::run, e -> {
            throw new AssertionError(e);
        });
    }

    private String bytes() throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static List<Integer> ns(AuditFile.Read r) {
        List<Integer> out = new ArrayList<>();
        for (JSONObject o : r.records) {
            out.add(o.optInt("n", -1));
        }
        return out;
    }

    private static String nuls(int n) {
        return "\0".repeat(n);
    }

    @Test
    public void eachAppendIsOneLineAndAMissingFileReadsEmpty() throws IOException {
        file = new File(tmp.getRoot(), "audit.jsonl");
        AuditFile f = open(file);
        AuditFile.Read empty = f.read();
        assertEquals(0, empty.records.size());
        assertEquals(0, empty.skipped);
        assertFalse(empty.tornTail);

        assertFalse("a new file has no torn tail", f.append("{\"n\":1}"));
        assertFalse(f.append("{\"n\":2}"));
        assertEquals("{\"n\":1}\n{\"n\":2}\n", bytes());
        AuditFile.Read r = f.read();
        assertEquals(List.of(1, 2), ns(r));
        assertEquals(0, r.skipped);
    }

    @Test
    public void aCleanTailGetsNoExtraNewline() throws IOException {
        AuditFile f = at("{\"n\":1}\n");
        assertFalse(f.append("{\"n\":2}"));
        assertEquals("{\"n\":1}\n{\"n\":2}\n", bytes());
    }

    @Test
    public void aCutOffLastLineIsSkippedAndTheNextRecordStartsAFreshLine() throws IOException {
        AuditFile f = at("{\"n\":1}\n{\"n\":2,\"state\":\"CAP");
        AuditFile.Read before = f.read();
        assertEquals(List.of(1), ns(before));
        assertEquals(1, before.skipped);
        assertTrue(before.tornTail);

        assertTrue("the torn tail is reported", f.append("{\"n\":3}"));
        assertEquals("{\"n\":1}\n{\"n\":2,\"state\":\"CAP\n{\"n\":3}\n", bytes());
        AuditFile.Read after = f.read();
        assertEquals(List.of(1, 3), ns(after));
        assertEquals("the torn line is still counted", 1, after.skipped);
        assertFalse(after.tornTail);

        assertFalse("checked once: the tail is ours now", f.append("{\"n\":4}"));
        assertEquals(List.of(1, 3, 4), ns(f.read()));
    }

    @Test
    public void aWholeLastRecordWithoutItsNewlineIsTornUntilTheNextAppendEndsIt() throws IOException {
        AuditFile f = at("{\"n\":1}\n{\"n\":2}");
        AuditFile.Read before = f.read();
        assertEquals("it may still be being written", List.of(1), ns(before));
        assertTrue(before.tornTail);

        assertTrue(f.append("{\"n\":3}"));
        AuditFile.Read after = f.read();
        assertEquals(List.of(1, 2, 3), ns(after));
        assertEquals(0, after.skipped);
    }

    @Test
    public void aNulRunAtTheTailIsSkippedOnceAndTheNextRecordStartsAFreshLine() throws IOException {
        AuditFile f = at("{\"n\":1}\n" + nuls(1010));
        AuditFile.Read before = f.read();
        assertEquals(List.of(1), ns(before));
        assertEquals(1, before.skipped);
        assertTrue(before.tornTail);

        assertTrue(f.append("{\"n\":2}"));
        assertEquals("{\"n\":1}\n" + nuls(1010) + "\n{\"n\":2}\n", bytes());
        AuditFile.Read after = f.read();
        assertEquals(List.of(1, 2), ns(after));
        assertEquals("one NUL line, one skip, however long the run", 1, after.skipped);
        assertFalse(after.tornTail);
    }

    @Test
    public void nulRunsInTheMiddleAreSkippedAndAWholeRecordAfterThemIsKept() throws IOException {
        // What the emulator left before #319: unsynced records zeroed, and the next boot's first
        // record appended straight after the NULs, on the same line.
        AuditFile f = at("{\"n\":1}\n" + nuls(300) + "{\"n\":2}\n{\"n\":3}\n"
                + nuls(40) + "\n{\"n\":4}\n{\"n\":5,\"st" + nuls(9) + "\n{\"n\":6}\n");
        AuditFile.Read r = f.read();
        assertEquals(List.of(1, 2, 3, 4, 6), ns(r));
        assertEquals("three lines had NULs", 3, r.skipped);
        assertFalse(r.tornTail);
    }

    @Test
    public void blankLinesAreIgnoredAndLinesThatAreNotOneJsonObjectAreSkipped() throws IOException {
        AuditFile f = at("\n  \n{\"n\":1}\nnot json\n[1,2]\n{\"n\":9} trailing\n\"s\"\n{\"n\":2}\r\n");
        AuditFile.Read r = f.read();
        assertEquals(List.of(1, 2), ns(r));
        assertEquals(4, r.skipped);
        assertFalse(r.tornTail);
    }

    /** Records each fsync's target; while {@code hold} is set, a sync waits for it. */
    private static final class Syncs implements AuditFile.Fsync {
        final List<File> done = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch hold;

        @Override
        public void sync(File f) {
            entered.countDown();
            CountDownLatch h = hold;
            if (h != null) {
                try {
                    h.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            done.add(f);
        }

        long of(File f) {
            synchronized (done) {
                return done.stream().filter(f::equals).count();
            }
        }
    }

    @Test(timeout = 10_000)
    public void appendNeverWaitsForASyncAndOneSyncCoversEveryWriteQueuedBehindIt() throws Exception {
        file = tmp.newFile("audit.jsonl");
        Syncs syncs = new Syncs();
        syncs.hold = new CountDownLatch(1);
        ExecutorService syncer = Executors.newSingleThreadExecutor();
        AuditFile f = new AuditFile(file, syncer, e -> fail("sync failed: " + e), syncs);

        f.append("{\"n\":1}");
        assertTrue("the syncer is inside the first fsync, held", syncs.entered.await(5, TimeUnit.SECONDS));
        List<Integer> want = new ArrayList<>(List.of(1));
        for (int n = 2; n <= 50; n++) {
            f.append("{\"n\":" + n + "}"); // returns while the fsync is still held
            want.add(n);
        }
        assertEquals("every line is written before any sync finishes", want, ns(f.read()));
        assertEquals(0, syncs.of(file));

        syncs.hold.countDown();
        syncer.shutdown();
        assertTrue(syncer.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("the held sync, then one more for the 49 lines written during it", 2, syncs.of(file));
    }

    @Test
    public void syncsCoalesceRunOnlyOnTheSyncerAndSyncNewDirectoriesOnce() throws IOException {
        file = new File(new File(tmp.getRoot(), "audit"), "audit.jsonl"); // audit/ does not exist yet
        Queue<Runnable> queued = new ArrayDeque<>();
        Syncs syncs = new Syncs();
        AuditFile f = new AuditFile(file, queued::add, e -> fail("sync failed: " + e), syncs);

        f.append("{\"n\":1}");
        f.append("{\"n\":2}");
        f.append("{\"n\":3}");
        assertEquals("written at once, in order", List.of(1, 2, 3), ns(f.read()));
        assertTrue("nothing synced on the caller's thread", syncs.done.isEmpty());
        assertEquals("one sync queued for three appends", 1, queued.size());

        queued.poll().run();
        assertEquals("the file, then the new entries: audit/ in its parent, the file in audit/",
                List.of(file, tmp.getRoot(), file.getParentFile()), syncs.done);

        f.append("{\"n\":4}");
        assertEquals("the next append queues the next sync", 1, queued.size());
        queued.poll().run();
        assertEquals("the directories are synced once", List.of(file, tmp.getRoot(), file.getParentFile(), file),
                syncs.done);
        assertTrue(queued.isEmpty());
    }

    @Test
    public void aDirectoryDeletedWhileRunningIsRecreatedAndNoRecordAfterItIsLost() throws IOException {
        File dir = new File(tmp.getRoot(), "audit");
        file = new File(dir, "audit.jsonl");
        Queue<Runnable> queued = new ArrayDeque<>();
        Syncs syncs = new Syncs();
        AuditFile f = new AuditFile(file, queued::add, e -> fail("sync failed: " + e), syncs);
        f.append("{\"n\":1}");
        queued.poll().run();
        syncs.done.clear();

        for (File x : dir.listFiles()) { // what an eval's rm -rf files/audit does
            assertTrue(x.delete());
        }
        assertTrue(dir.delete());

        assertFalse("a new file has no torn tail", f.append("{\"n\":2}"));
        assertFalse(f.append("{\"n\":3}"));
        assertEquals(List.of(2, 3), ns(f.read()));
        assertEquals("{\"n\":2}\n{\"n\":3}\n", bytes());
        queued.poll().run();
        assertEquals("the recreated entries are synced again", List.of(file, tmp.getRoot(), dir), syncs.done);
    }
}
