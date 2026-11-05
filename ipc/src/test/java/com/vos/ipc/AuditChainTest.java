package com.vos.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The audit hash chain (M2-AUDIT-01, #154): written through the chain writer, verified line by line. */
public class AuditChainTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file;

    private File newFile() {
        file = new File(new File(tmp.getRoot(), "audit"), "audit.jsonl");
        return file;
    }

    /** Writes like JsonlAudit: real AuditFile, fsyncs run inline. */
    private AuditChain.Writer writer() {
        return new AuditChain.Writer(new AuditFile(file, Runnable::run, e -> {
            throw new AssertionError(e);
        }));
    }

    private static JSONObject rec(int n) throws Exception {
        return new JSONObject().put("ts", 1000 + n).put("event", "audio.transition").put("turnId", "t" + n)
                .put("state", "CAPTURE").put("mode", 0);
    }

    private byte[] bytes() throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private static String prevOf(String line) throws Exception {
        return new JSONObject(line).getString("prev");
    }

    private void appendRaw(String s) throws IOException {
        Files.write(file.toPath(), s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    @Test
    public void aWrittenChainVerifiesFromGenesisAndEachPrevIsTheLineBeforeAsStored() throws Exception {
        newFile();
        AuditChain.Writer w = writer();
        for (int n = 1; n <= 5; n++) {
            assertEquals(1, w.append(rec(n)).size());
        }
        List<String> l = lines();
        assertEquals(AuditChain.GENESIS, prevOf(l.get(0)));
        assertEquals("0".repeat(64), AuditChain.GENESIS);
        for (int i = 1; i < l.size(); i++) {
            assertEquals(AuditChain.sha256Hex((l.get(i - 1) + "\n").getBytes(StandardCharsets.UTF_8)),
                    prevOf(l.get(i)));
        }
        assertEquals(AuditChain.hashOfLine(l.get(4)), w.head());
        AuditChain.Result r = AuditChain.verify(file, w.head());
        assertTrue(String.valueOf(r.breaks), r.ok());
        assertEquals(5, r.records);
        assertTrue(AuditChain.verify(file, null).ok());
    }

    @Test
    public void genesisASingleLineAndAnEmptyOrMissingFile() throws Exception {
        newFile();
        assertTrue("a missing file is an empty chain", AuditChain.verify(file, null).ok());
        AuditChain.Writer w = writer();
        assertNull(w.head());
        w.append(rec(1));
        assertEquals(AuditChain.GENESIS, prevOf(lines().get(0)));
        AuditChain.Result r = AuditChain.verify(file, w.head());
        assertTrue(r.ok());
        assertEquals(1, r.records);
        assertTrue(AuditChain.verify(new byte[0], null).ok());
    }

    @Test
    public void aRestartedWriterChainsToTheLastLineAsStored() throws Exception {
        newFile();
        writer().append(rec(1));
        writer().append(rec(2));
        AuditChain.Writer third = writer();
        third.append(rec(3));
        List<String> l = lines();
        assertEquals(AuditChain.hashOfLine(l.get(1)), prevOf(l.get(2)));
        assertTrue(AuditChain.verify(file, third.head()).ok());
    }

    @Test
    public void aCallersOwnPrevIsReplaced() throws Exception {
        newFile();
        AuditChain.Writer w = writer();
        w.append(rec(1).put("prev", "forged"));
        assertEquals(AuditChain.GENESIS, prevOf(lines().get(0)));
        assertTrue(AuditChain.verify(file, w.head()).ok());
    }

    @Test
    public void flippingAnyOneByteOfAnyLineFailsAtThatLine() throws Exception {
        newFile();
        AuditChain.Writer w = writer();
        for (int n = 1; n <= 4; n++) {
            w.append(rec(n));
        }
        byte[] good = bytes();
        String head = w.head();
        int line = 1;
        int checked = 0;
        for (int at = 0; at < good.length; at++) {
            if (good[at] == '\n') {
                line++;
                continue;
            }
            for (int mask : new int[] {0x01, 0x20, 0x80}) {
                byte[] bad = good.clone();
                bad[at] ^= (byte) mask;
                if (bad[at] == '\n') {
                    continue; // a new line is not a change of one line
                }
                AuditChain.Result r = AuditChain.verify(bad, head);
                assertFalse("byte " + at + " ^ " + mask + " went unseen", r.ok());
                assertEquals("byte " + at + " ^ " + mask + ": " + r.breaks, line, r.first().line);
                checked++;
            }
        }
        assertTrue(checked > 400);
    }

    @Test
    public void withoutTheHeadAChangeBeforeTheNewestLineStillFailsAtThatLine() throws Exception {
        newFile();
        AuditChain.Writer w = writer();
        for (int n = 1; n <= 4; n++) {
            w.append(rec(n));
        }
        byte[] good = bytes();
        String s = new String(good, StandardCharsets.UTF_8);
        int second = s.indexOf("\"t2\"");
        byte[] bad = good.clone();
        bad[second + 2] = '9'; // "t2" -> "t9" on line 2
        AuditChain.Result r = AuditChain.verify(bad, null);
        assertEquals(2, r.first().line);
        assertEquals(1, r.breaks.size());
        assertTrue(r.first().reason, r.first().reason.contains("changed"));
    }

    @Test
    public void aTornTailBreaksTheChainAtTheNextRecordAndTheNoteChainsOn() throws Exception {
        newFile();
        AuditChain.Writer w = writer();
        w.append(rec(1));
        w.append(rec(2));
        appendRaw("{\"ts\":3,\"event\":\"audio.tran"); // the power cut mid-write

        AuditChain.Writer after = writer(); // the next boot
        List<String> written = after.append(rec(4));
        assertEquals("the record, then the torn-tail note", 2, written.size());
        assertEquals("audit.torn_tail", new JSONObject(written.get(1)).getString("event"));

        List<String> l = lines();
        assertEquals("the writer chained to the last whole record", AuditChain.hashOfLine(l.get(1)), prevOf(l.get(3)));
        assertEquals("the note chains to the record", AuditChain.hashOfLine(l.get(3)), prevOf(l.get(4)));

        AuditChain.Result r = AuditChain.verify(file, after.head());
        assertFalse(r.ok());
        assertEquals(2, r.breaks.size());
        assertEquals("the torn piece", 3, r.breaks.get(0).line);
        assertEquals("the next record: not linked across the gap", 4, r.breaks.get(1).line);
        assertTrue(r.breaks.get(1).reason, r.breaks.get(1).reason.contains("gap at line 3"));
    }

    @Test
    public void aNulRunIsAGapThatIsNeverBridgedEvenWhenTheNextPrevMatches() throws Exception {
        newFile();
        AuditChain.Writer w = writer();
        w.append(rec(1));
        w.append(rec(2));
        appendRaw("\0".repeat(200));
        AuditChain.Writer after = writer();
        after.append(rec(3));
        List<String> l = lines();
        assertEquals("its prev names the record before the gap", AuditChain.hashOfLine(l.get(1)), prevOf(l.get(3)));
        AuditChain.Result r = AuditChain.verify(file, after.head());
        assertEquals(3, r.first().line);
        assertTrue(r.breaks.get(1).toString(), r.breaks.get(1).line == 4
                && r.breaks.get(1).reason.contains("does not link across the gap"));
        assertEquals("the note after the record links on", 2, r.breaks.size());
    }

    @Test
    public void aSkippedNonJsonLineIsAGapAtItsLineAndAtTheNextRecord() throws Exception {
        newFile();
        AuditChain.Writer w = writer();
        w.append(rec(1));
        appendRaw("not json\n");
        w.append(rec(2)); // same writer: its prev is line 1's hash, across the gap
        AuditChain.Result r = AuditChain.verify(file, w.head());
        assertEquals(2, r.breaks.size());
        assertEquals(2, r.breaks.get(0).line);
        assertEquals(3, r.breaks.get(1).line);
        assertNotNull(AuditFile.read(file));
        assertEquals(1, AuditFile.read(file).skipped);
    }

    @Test
    public void aLegacyLineWithoutPrevIsABreakAndTheChainResumesAfterIt() throws Exception {
        newFile();
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), "{\"ts\":1,\"event\":\"binder.unauthorized\"}\n".getBytes(StandardCharsets.UTF_8));
        AuditChain.Writer w = writer();
        w.append(rec(2));
        w.append(rec(3));
        AuditChain.Result r = AuditChain.verify(file, w.head());
        assertEquals(1, r.breaks.size());
        assertEquals(1, r.first().line);
        assertTrue(r.first().reason, r.first().reason.startsWith("no prev"));
    }
}
