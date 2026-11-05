package com.vos.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M2-AUDIT-01 (#154) through the host tool: change one byte of an audit line and verification fails
 * at that line; an untouched chain passes.
 */
public class AuditVerifyTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String out;
    private int rc;

    private void run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        rc = AuditVerify.run(args, new PrintStream(o, true), new PrintStream(e, true));
        out = o.toString(StandardCharsets.UTF_8) + e.toString(StandardCharsets.UTF_8);
    }

    /** A 6-record chain; returns the writer's head. */
    private String chain(File f) {
        run("generate", f.getPath(), "6");
        assertEquals(out, 0, rc);
        return out.trim().substring("head ".length());
    }

    /** {@code src} with one byte of line {@code line} (1-based) changed: column {@code col}, or inside prev. */
    private File tampered(File src, int line, int col, boolean inPrev) throws Exception {
        byte[] b = Files.readAllBytes(src.toPath());
        int start = 0;
        for (int l = 1; l < line; l++) {
            while (b[start] != '\n') {
                start++;
            }
            start++;
        }
        int at;
        if (inPrev) {
            String text = new String(b, StandardCharsets.UTF_8);
            at = text.indexOf("\"prev\":\"", start) + "\"prev\":\"".length() + 20; // a hex digit of the hash
            b[at] = (byte) (b[at] == '0' ? '1' : '0'); // still hex: only the link can tell
        } else {
            at = start + col;
            b[at] ^= 0x01;
        }
        File out = new File(src.getParentFile(), "tampered-" + line + "-" + col + "-" + inPrev + ".jsonl");
        Files.write(out.toPath(), b);
        return out;
    }

    @Test
    public void anUntouchedChainPasses() throws Exception {
        File f = new File(tmp.getRoot(), "audit.jsonl");
        String head = chain(f);
        run("verify", "--head", head, f.getPath());
        assertEquals(out, 0, rc);
        assertTrue(out, out.contains(": OK records=6 legacy=0"));
        run("verify", f.getPath());
        assertEquals("without the head too", 0, rc);
    }

    @Test
    public void oneChangedByteFailsAtExactlyThatLine() throws Exception {
        File f = new File(tmp.getRoot(), "audit.jsonl");
        String head = chain(f);
        for (int line = 1; line <= 6; line++) {
            for (int col : new int[] {0, 5, 20, 40}) { // the brace, inside "ts", the event, the turn id
                File t = tampered(f, line, col, false);
                run("verify", "--head", head, t.getPath());
                assertEquals(line + ":" + col + " " + out, 1, rc);
                assertTrue(line + ":" + col + " " + out, out.contains(t + ": FAIL line " + line + ": "));
            }
            File p = tampered(f, line, 0, true);
            run("verify", "--head", head, p.getPath());
            assertEquals("a digit of line " + line + "'s prev hash: " + out, 1, rc);
            assertTrue(line + " prev " + out, out.contains(p + ": FAIL line " + line + ": "));
        }
    }

    @Test
    public void withoutTheHeadALineBeforeTheNewestStillFailsAtThatLine() throws Exception {
        File f = new File(tmp.getRoot(), "audit.jsonl");
        chain(f);
        for (int line = 1; line <= 5; line++) {
            File t = tampered(f, line, 20, false);
            run("verify", t.getPath());
            assertTrue(line + " " + out, out.contains(": FAIL line " + line + ": "));
        }
    }

    @Test
    public void manyFilesAtOnceAndUsageErrors() throws Exception {
        File f = new File(tmp.getRoot(), "audit.jsonl");
        String head = chain(f);
        File t = tampered(f, 3, 20, false);
        run("verify", "--head", head, f.getPath(), t.getPath());
        assertEquals("one file failing fails the run", 1, rc);
        assertTrue(out, out.contains(f + ": OK") && out.contains(t + ": FAIL line 3: "));
        run("verify");
        assertEquals(2, rc);
        run("verify", new File(tmp.getRoot(), "missing.jsonl").getPath());
        assertEquals(2, rc);
        run("generate", f.getPath(), "3");
        assertEquals("never writes over a file", 2, rc);
        run("nonsense");
        assertEquals(2, rc);
    }
}
