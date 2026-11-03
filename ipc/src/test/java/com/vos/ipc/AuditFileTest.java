package com.vos.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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
        return new AuditFile(file);
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
        AuditFile f = new AuditFile(file);
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
}
