package com.vos.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * What the hash chain detects on its own and what it does not (#340). The "not detected" cases
 * assert today's behaviour on purpose: they are the threat docs/design/audit-chain-anchor.md
 * answers, and the {@link AuditCheckpoint} cases show the anchor closing them (and its own limits).
 */
public class AuditChainLimitsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final AuditCheckpoint.Key KEY = AuditCheckpoint.hmacSha256(
            "test-only checkpoint key, 32 b!!".getBytes(StandardCharsets.UTF_8));
    private static final AuditCheckpoint.Key OTHER_KEY = AuditCheckpoint.hmacSha256(
            "an editor's guess at the key....".getBytes(StandardCharsets.UTF_8));

    private File file;

    /** Writes n chained records like JsonlAudit; returns the writer's head after each one. */
    private List<String> write(File f, int n, String tag) throws Exception {
        AuditChain.Writer w = new AuditChain.Writer(new AuditFile(f, Runnable::run, e -> {
            throw new AssertionError(e);
        }));
        List<String> heads = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            w.append(new JSONObject().put("ts", 1000 + i).put("event", "tool.call").put("tool", tag + i));
            heads.add(w.head());
        }
        return heads;
    }

    private List<String> log(int n) throws Exception {
        file = new File(tmp.getRoot(), "audit.jsonl");
        return write(file, n, "volume_set#");
    }

    private List<String> lines() throws Exception {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private void keep(List<String> lines) throws Exception {
        Files.write(file.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private byte[] bytes() throws Exception {
        return Files.readAllBytes(file.toPath());
    }

    // ---- Detected by the chain alone ----

    @Test
    public void aRemovedMiddleLineIsDetected() throws Exception {
        log(6);
        List<String> l = lines();
        l.remove(2); // line 3
        keep(l);
        AuditChain.Result r = AuditChain.verify(file, null);
        assertFalse(r.ok());
        // The old line 4, now line 3, no longer links, and the link after it still does: the chain
        // reads that as a change to the line before the hole, so it is reported at line 2.
        assertEquals(1, r.breaks.size());
        assertEquals(2, r.first().line);
    }

    @Test
    public void anEditedByteIsDetected() throws Exception {
        log(6);
        byte[] b = bytes();
        int at = new String(b, StandardCharsets.UTF_8).indexOf("volume_set#3") + "volume_set#".length();
        b[at] = '9'; // volume_set#3 -> volume_set#9, same length, still valid JSON
        Files.write(file.toPath(), b);
        AuditChain.Result r = AuditChain.verify(file, null);
        assertFalse(r.ok());
        assertEquals(3, r.first().line);
    }

    // ---- Not detected by the chain alone (today's behaviour; the anchor's threat) ----

    @Test
    public void aTailCutAtALineBoundaryIsNotDetectedByTheChainAlone() throws Exception {
        List<String> heads = log(6);
        List<String> l = lines();
        keep(l.subList(0, 4)); // the last 2 records gone, on a clean line boundary
        assertTrue("no head: the shorter chain is whole", AuditChain.verify(file, null).ok());
        // A restarted writer recovers its head from the cut file, so its own head agrees too.
        String recovered = AuditChain.recoverHead(file);
        assertEquals(heads.get(3), recovered);
        assertTrue("the recovered head is no anchor", AuditChain.verify(file, recovered).ok());
        // Only a head kept from before the cut, out of the editor's reach, sees it.
        assertFalse(AuditChain.verify(file, heads.get(5)).ok());
    }

    @Test
    public void aFullRewriteFromGenesisIsNotDetectedByTheChainAlone() throws Exception {
        List<String> heads = log(6);
        File forged = new File(tmp.getRoot(), "forged.jsonl");
        write(forged, 3, "time_get#"); // a different, innocent-looking history
        Files.move(forged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        assertTrue(AuditChain.verify(file, null).ok());
        assertTrue(AuditChain.verify(file, AuditChain.recoverHead(file)).ok());
        assertFalse("only the old head sees it", AuditChain.verify(file, heads.get(5)).ok());
    }

    // ---- The checkpoint anchor (AuditCheckpoint) ----

    @Test
    public void aCheckpointSeesATailCutBelowIt() throws Exception {
        List<String> heads = log(6);
        AuditCheckpoint cp = AuditCheckpoint.sign(heads.get(4), 2000, KEY); // after record 5
        assertEquals(AuditCheckpoint.Verdict.OK, AuditCheckpoint.check(bytes(), cp, KEY));
        keep(lines().subList(0, 4));
        assertTrue(AuditChain.verify(file, null).ok());
        assertEquals(AuditCheckpoint.Verdict.HEAD_MISSING, AuditCheckpoint.check(bytes(), cp, KEY));
    }

    @Test
    public void aCheckpointSeesAFullRewrite() throws Exception {
        List<String> heads = log(6);
        AuditCheckpoint cp = AuditCheckpoint.sign(heads.get(5), 2000, KEY);
        File forged = new File(tmp.getRoot(), "forged.jsonl");
        List<String> forgedHeads = write(forged, 3, "time_get#");
        Files.move(forged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        assertEquals(AuditCheckpoint.Verdict.HEAD_MISSING, AuditCheckpoint.check(bytes(), cp, KEY));
        // Without the key the editor cannot sign the forged head, and an edited checkpoint fails.
        AuditCheckpoint guess = AuditCheckpoint.sign(forgedHeads.get(2), 2000, OTHER_KEY);
        assertEquals(AuditCheckpoint.Verdict.FORGED, AuditCheckpoint.check(bytes(), guess, KEY));
        String edited = cp.toJson().replace(cp.head, forgedHeads.get(2));
        assertNotEquals(cp.toJson(), edited);
        assertEquals(AuditCheckpoint.Verdict.FORGED,
                AuditCheckpoint.check(bytes(), AuditCheckpoint.parse(edited), KEY));
    }

    @Test
    public void aCheckpointRoundTripsAndRejectsJunk() throws Exception {
        AuditCheckpoint cp = AuditCheckpoint.sign(AuditChain.GENESIS, 5, KEY);
        AuditCheckpoint back = AuditCheckpoint.parse(cp.toJson());
        assertEquals(cp.head, back.head);
        assertEquals(cp.ts, back.ts);
        assertTrue(back.authentic(KEY));
        assertEquals("an empty log's checkpoint holds for any log", AuditCheckpoint.Verdict.OK,
                AuditCheckpoint.check(new byte[0], back, KEY));
        assertNull(AuditCheckpoint.parse(null));
        assertNull(AuditCheckpoint.parse("not json"));
        assertNull(AuditCheckpoint.parse("{\"v\":2}"));

        String head = "ab".repeat(32);
        String mac = "cd".repeat(AuditCheckpoint.MAC_BYTES);
        String good = "{\"v\":1,\"head\":\"" + head + "\",\"ts\":7,\"mac\":\"" + mac + "\"}";
        AuditCheckpoint parsed = AuditCheckpoint.parse(good);
        assertEquals(head, parsed.head);
        assertEquals(7, parsed.ts);
        String[] junk = {
            good.replace("\"v\":1", "\"v\":\"1\""), // v must be the integer 1, not a string
            good.replace("\"v\":1", "\"v\":1.5"),
            good.replace("\"v\":1,", ""),
            good.replace("\"ts\":7", "\"ts\":\"7\""), // ts must be a number
            good.replace("\"ts\":7", "\"ts\":7.5"), // and a whole one
            good.replace("\"ts\":7,", ""),
            good.replace(head, "ab"), // head: 64 lowercase hex digits
            good.replace(head, head.toUpperCase(Locale.ROOT)),
            good.replace(head, "zz" + head.substring(2)),
            good.replace("\"head\":\"" + head + "\"", "\"head\":1"),
            good.replace(mac, "cd"), // mac: exactly 32 bytes
            good.replace(mac, mac + "cd"),
            good.replace(mac, "zz" + mac.substring(2)),
            good.replace(mac, mac.toUpperCase(Locale.ROOT)),
            good.replace(mac, mac.substring(1)),
        };
        for (String j : junk) {
            assertNull(j, AuditCheckpoint.parse(j));
        }
        assertNull(AuditCheckpoint.parse(cp.toJson().replace("\"mac\":\"", "\"mac\":\"zz")));

        for (String bad : new String[] {null, "ab", AuditChain.GENESIS.toUpperCase(Locale.ROOT).replace('0', 'A')}) {
            try {
                AuditCheckpoint.sign(bad, 1, KEY);
                fail("signed a non-head: " + bad);
            } catch (IllegalArgumentException expected) {
                // a checkpoint only ever names a chain head
            }
        }
        try {
            AuditCheckpoint.sign(AuditChain.GENESIS, 1, data -> new byte[16]);
            fail("a 16-byte MAC was accepted");
        } catch (GeneralSecurityException expected) {
            // HMAC-SHA256 only
        }
    }

    /** The anchor's documented limits: asserted so a change to them is a decision, not a surprise. */
    @Test
    public void aCheckpointDoesNotCoverLinesAfterItNorAReplayedOlderCheckpoint() throws Exception {
        List<String> heads = log(6);
        AuditCheckpoint older = AuditCheckpoint.sign(heads.get(1), 1500, KEY); // after record 2
        AuditCheckpoint newer = AuditCheckpoint.sign(heads.get(4), 2000, KEY); // after record 5
        keep(lines().subList(0, 5)); // record 6, after the newest checkpoint, is cut
        assertEquals("lines after the newest checkpoint are not covered", AuditCheckpoint.Verdict.OK,
                AuditCheckpoint.check(bytes(), newer, KEY));
        keep(lines().subList(0, 2)); // cut back to the older checkpoint and replay it
        assertEquals(AuditCheckpoint.Verdict.HEAD_MISSING, AuditCheckpoint.check(bytes(), newer, KEY));
        assertEquals("a replayed older checkpoint passes: no monotonic counter", AuditCheckpoint.Verdict.OK,
                AuditCheckpoint.check(bytes(), older, KEY));
    }
}
