package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.vos.ipc.AuditFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The transcript log after a torn write (#321): the next line starts fresh and every whole line reads back. */
public class TranscriptLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FIRST = "{\"ts\":1,\"turn_id\":\"t1\",\"role\":\"owner\",\"text\":\"hi\"}\n";

    private static String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void aNulRunAtTheTailIsSkippedAndTheNextLineStartsFresh() throws IOException {
        File dir = tmp.newFolder("transcripts");
        TranscriptLog log = new TranscriptLog(dir);
        Files.write(log.file().toPath(), (FIRST + "\0".repeat(600)).getBytes(StandardCharsets.UTF_8));

        log.write("t2", "vos", "hello");

        assertTrue(text(log.file()).startsWith(FIRST + "\0".repeat(600) + "\n{"));
        AuditFile.Read r = AuditFile.read(log.file());
        assertEquals(2, r.records.size());
        assertEquals("t1", r.records.get(0).optString("turn_id"));
        assertEquals("t2", r.records.get(1).optString("turn_id"));
        assertEquals("hello", r.records.get(1).optString("text"));
        assertEquals("the NUL line", 1, r.skipped);
        assertFalse(r.tornTail);
    }

    @Test
    public void aCutOffLastLineIsSkippedAndTheNextLineStartsFresh() throws IOException {
        File dir = tmp.newFolder("transcripts");
        TranscriptLog log = new TranscriptLog(dir);
        Files.write(log.file().toPath(), (FIRST + "{\"ts\":2,\"turn_id\":\"t1\",\"ro").getBytes(StandardCharsets.UTF_8));

        log.write("t2", "owner", "what time is it");
        log.write("t2", "vos", "noon");

        AuditFile.Read r = AuditFile.read(log.file());
        assertEquals(3, r.records.size());
        assertEquals("what time is it", r.records.get(1).optString("text"));
        assertEquals("noon", r.records.get(2).optString("text"));
        assertEquals("the cut-off line", 1, r.skipped);
        assertFalse(r.tornTail);
    }
}
