package com.vos.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** JsonlAudit's result, its writable state, and what reaches logcat in each build (#340). */
public class JsonlAuditTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SECRET = "the-owner-said-something-private";

    /** Records what JsonlAudit would send to logcat. */
    private static final class Recorder implements JsonlAudit.Logcat {
        final List<String> lines = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void line(String line) {
            lines.add(line);
        }

        @Override
        public void error(String message, Throwable e) {
            errors.add(message + " | " + e);
        }

        String all() {
            return String.join("\n", lines) + "\n" + String.join("\n", errors);
        }
    }

    private final Recorder logcat = new Recorder();

    private JsonlAudit audit(File file, boolean debug) {
        return new JsonlAudit(new AuditChain.Writer(new AuditFile(file, Runnable::run, e -> {
            throw new AssertionError(e);
        })), debug, logcat);
    }

    private static Map<String, Object> line(String event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", event);
        m.put("text", SECRET);
        return m;
    }

    @Test
    public void aReleaseBuildWritesTheFileButPutsNoLineInLogcat() throws Exception {
        File f = new File(tmp.getRoot(), "audit/audit.jsonl");
        JsonlAudit a = audit(f, false);
        assertTrue(a.record(line("tool.call")));
        assertTrue(a.record(line("tool.call")));
        assertEquals(2, Files.readAllLines(f.toPath()).size());
        assertTrue(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains(SECRET));
        assertTrue("no line mirrored", logcat.lines.isEmpty());
        assertTrue(logcat.errors.isEmpty());
        assertTrue(AuditChain.verify(f, a.chainHead()).ok());
    }

    @Test
    public void aDebugBuildMirrorsEachLineAsStored() throws Exception {
        File f = new File(tmp.getRoot(), "audit/audit.jsonl");
        JsonlAudit a = audit(f, true);
        assertTrue(a.record(line("tool.call")));
        a.unauthorized("ActuatorService", 10123, new String[] {"com.example"}, "not the session");
        assertEquals(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8), logcat.lines);
    }

    @Test
    public void aFailedWriteReturnsFalseLogsNoContentAndHealsOnTheNextGoodWrite() throws Exception {
        File dir = new File(tmp.getRoot(), "audit");
        assertTrue(dir.createNewFile()); // a file where the directory should be: every append fails
        File f = new File(dir, "audit.jsonl");
        for (boolean debug : new boolean[] {false, true}) {
            logcat.lines.clear();
            logcat.errors.clear();
            JsonlAudit a = audit(f, debug);
            assertTrue("nothing has failed yet", a.writable());
            assertFalse(a.record(line("tool.call")));
            assertFalse("the act after a failed write is refused", a.writable());
            assertEquals(1, a.failures());
            assertEquals(1, logcat.errors.size());
            assertFalse("no record contents in logcat, debug=" + debug, logcat.all().contains(SECRET));
            assertTrue(logcat.lines.isEmpty());
        }

        assertTrue(dir.delete() && dir.mkdir()); // the storage is back
        JsonlAudit a = audit(f, false);
        assertTrue(a.record(line("tool.call"))); // a fresh audit writes at once
        assertTrue(a.writable());

        JsonlAudit healing = audit(f, false);
        assertTrue(dir.renameTo(new File(tmp.getRoot(), "moved")) && dir.createNewFile());
        assertFalse(healing.record(line("tool.call")));
        assertFalse(healing.writable());
        assertTrue(dir.delete() && new File(tmp.getRoot(), "moved").renameTo(dir));
        assertTrue("the refusal's own line reaches the file", healing.record(line("tool.call")));
        assertTrue("and the audit is writable again", healing.writable());
        assertTrue(AuditChain.verify(f, healing.chainHead()).ok());
    }

    @Test
    public void anUnrecoverableHeadIsNotWritable() throws Exception {
        File f = new File(tmp.getRoot(), "audit.jsonl");
        byte[] junk = new byte[AuditChain.MAX_TAIL + 1]; // no complete record in the last MAX_TAIL bytes
        Arrays.fill(junk, (byte) 'x');
        Files.write(f.toPath(), junk);
        JsonlAudit a = audit(f, false);
        assertNull(a.chainHead());
        assertFalse("no head, no act", a.writable());
        assertFalse(a.record(line("tool.call")));
        assertFalse(a.writable());
        assertFalse(logcat.all().contains(SECRET));
    }
}
