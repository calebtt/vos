package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.vos.ipc.AuditFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The egress log after a torn write (#321): the next line starts fresh and every whole line reads back. */
public class RequestLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FIRST = "{\"ts\":1,\"kind\":\"ws\",\"host\":\"api.x.ai\",\"turn_id\":\"t1\"}\n";

    private static RequestLog.Entry entry(String turnId) {
        return new RequestLog.Entry("ws", "api.x.ai", "GET", "/v1/realtime", 10, 20, 101, turnId, "ok");
    }

    @Test
    public void aNulRunAtTheTailIsSkippedAndTheNextLineStartsFresh() throws IOException {
        File dir = tmp.newFolder("egress");
        RequestLog log = new RequestLog(dir);
        Files.write(log.file().toPath(), (FIRST + "\0".repeat(1010)).getBytes(StandardCharsets.UTF_8));

        log.write(entry("t2"));

        AuditFile.Read r = AuditFile.read(log.file());
        assertEquals(2, r.records.size());
        assertEquals("t2", r.records.get(1).optString("turn_id"));
        assertEquals("api.x.ai", r.records.get(1).optString("host"));
        assertEquals("the NUL line", 1, r.skipped);
        assertFalse(r.tornTail);
    }

    @Test
    public void aCutOffLastLineIsSkippedAndTheNextLineStartsFresh() throws IOException {
        File dir = tmp.newFolder("egress");
        RequestLog log = new RequestLog(dir);
        Files.write(log.file().toPath(), (FIRST + "{\"ts\":2,\"kind\":\"ht").getBytes(StandardCharsets.UTF_8));

        log.write(entry("t2"));
        log.write(entry("t3"));

        String text = new String(Files.readAllBytes(log.file().toPath()), StandardCharsets.UTF_8);
        assertEquals("one line each, the first after a fresh newline", 5, text.split("\n", -1).length);
        AuditFile.Read r = AuditFile.read(log.file());
        assertEquals(3, r.records.size());
        assertEquals("t3", r.records.get(2).optString("turn_id"));
        assertEquals("the cut-off line", 1, r.skipped);
        assertFalse(r.tornTail);
    }
}
