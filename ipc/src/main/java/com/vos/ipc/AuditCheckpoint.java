package com.vos.ipc;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A MAC'd copy of the audit chain's head (#340; docs/design/audit-chain-anchor.md). The chain alone
 * cannot see a cut-off tail or a whole file rewritten from genesis: both still chain. A checkpoint
 * names a head the log had at some moment, under a key the file's editor does not hold; a later file
 * that no longer contains a line hashing to that head was cut or rewritten below it. Pure JVM: the
 * key is behind {@link Key}, which the app binds to an Android Keystore HMAC key (non-exportable,
 * usable only by the actuator's UID) and the tests to a plain key. Not wired into the app yet.
 *
 * <p>Limits (the design doc has more): lines after the newest checkpoint are not covered; an
 * attacker who can run code as the actuator can use the Keystore key and sign what it likes; and an
 * older genuine checkpoint replayed with a log cut back to it passes (no monotonic counter here).
 */
public final class AuditCheckpoint {
    /** The MAC under the checkpoint key. */
    public interface Key {
        byte[] mac(byte[] data) throws GeneralSecurityException;
    }

    /** HMAC-SHA256 under a raw key (tests, host tools). The app uses a Keystore key instead. */
    public static Key hmacSha256(byte[] key) {
        byte[] k = key.clone();
        return data -> {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(k, "HmacSHA256"));
            return m.doFinal(data);
        };
    }

    private static final String DOMAIN = "vos-audit-checkpoint-v1\n";

    /** The chain head this checkpoint names: {@link AuditChain.Writer#head()} when it was taken. */
    public final String head;
    /** When it was taken (epoch ms). */
    public final long ts;
    private final byte[] mac;

    private AuditCheckpoint(String head, long ts, byte[] mac) {
        this.head = head;
        this.ts = ts;
        this.mac = mac;
    }

    /** A checkpoint of {@code head} at {@code ts}, signed with {@code key}. */
    public static AuditCheckpoint sign(String head, long ts, Key key) throws GeneralSecurityException {
        if (head == null || head.length() != 64) {
            throw new IllegalArgumentException("head must be a chain head");
        }
        return new AuditCheckpoint(head, ts, key.mac(signed(head, ts)));
    }

    private static byte[] signed(String head, long ts) {
        return (DOMAIN + head + "\n" + ts).getBytes(StandardCharsets.UTF_8);
    }

    /** One JSON object, for the checkpoint file. */
    public String toJson() {
        try {
            return new JSONObject().put("v", 1).put("head", head).put("ts", ts)
                    .put("mac", AuditChain.hex(mac)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The checkpoint in {@code json}, or null if it is not one. Not yet checked: see {@link #authentic}. */
    public static AuditCheckpoint parse(String json) {
        JSONObject o = json == null ? null : AuditFile.object(json);
        if (o == null || o.optInt("v") != 1) {
            return null;
        }
        String head = o.optString("head", "");
        byte[] mac = unhex(o.optString("mac", ""));
        if (head.length() != 64 || mac == null || !o.has("ts")) {
            return null;
        }
        return new AuditCheckpoint(head, o.optLong("ts"), mac);
    }

    /** Whether {@code key} signed this checkpoint (constant-time compare). */
    public boolean authentic(Key key) throws GeneralSecurityException {
        return MessageDigest.isEqual(mac, key.mac(signed(head, ts)));
    }

    public enum Verdict {
        /** Signed by the key, and the file still holds the line it names (or it names an empty log). */
        OK,
        /** Not signed by the key: the checkpoint itself was forged or edited. */
        FORGED,
        /** Genuine, but no line in the file hashes to its head: the log was cut or rewritten below it. */
        HEAD_MISSING
    }

    /**
     * Checks a log against a checkpoint: {@link Verdict#OK} if {@code key} signed it and some complete
     * line of {@code raw} hashes, as stored, to its head. Run it together with
     * {@link AuditChain#verify}: the chain covers edits and removals between the lines, this covers
     * what the chain cannot, the tail up to the checkpoint and a rewrite from genesis.
     */
    public static Verdict check(byte[] raw, AuditCheckpoint cp, Key key) throws GeneralSecurityException {
        if (!cp.authentic(key)) {
            return Verdict.FORGED;
        }
        if (cp.head.equals(AuditChain.GENESIS)) {
            return Verdict.OK; // taken while the log was empty: nothing to miss
        }
        for (int start = 0; start < raw.length; ) {
            int nl = start;
            while (nl < raw.length && raw[nl] != '\n') {
                nl++;
            }
            if (nl == raw.length) {
                break; // an unfinished last line is not a record
            }
            if (AuditChain.sha256Hex(Arrays.copyOfRange(raw, start, nl + 1)).equals(cp.head)) {
                return Verdict.OK;
            }
            start = nl + 1;
        }
        return Verdict.HEAD_MISSING;
    }

    private static byte[] unhex(String s) {
        if (s.length() % 2 != 0 || s.isEmpty()) {
            return null;
        }
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            b[i] = (byte) (hi << 4 | lo);
        }
        return b;
    }
}
