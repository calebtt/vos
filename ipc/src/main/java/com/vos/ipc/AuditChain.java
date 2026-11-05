package com.vos.ipc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The audit log's hash chain (audit v2, M2-AUDIT-01, #154; §5.8). Every record carries {@code prev}:
 * the lowercase hex SHA-256 of the previous line exactly as stored, its UTF-8 bytes including the
 * trailing newline. The first record's {@code prev} is {@link #GENESIS}. {@link Writer} sets it on
 * every append; {@link #verify} checks a file. Pure JVM.
 *
 * <p>A gap (a piece {@link AuditFile#read} skips: a line with NULs, a line that is not one JSON
 * object, an unfinished last line, and here a blank line too) is never linked across. It is a break
 * at its own line and at the next record, even if that record's {@code prev} names the record
 * before the gap. After a break the verifier carries on from the next record, so a power cut's gap
 * and a later edit are reported separately.
 *
 * <p>A one-byte change to a record is reported at that record's line. A link that fails says which
 * line changed only together with the next link: if the next record still links, the line before
 * changed; if it does not, this line did. The newest record has no next link, so a change to it is
 * seen only against the writer's {@link Writer#head()} passed to {@link #verify(byte[], String)};
 * without it, a change there goes unseen, or is reported as ambiguous.
 */
public final class AuditChain {
    /** The {@code prev} of the first record: 64 zero hex digits. */
    public static final String GENESIS = "0".repeat(64);

    private AuditChain() {}

    /** Lowercase hex SHA-256 of a stored line's bytes (with its newline). */
    static String sha256Hex(byte[] storedLine) {
        byte[] d;
        try {
            d = MessageDigest.getInstance("SHA-256").digest(storedLine);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder b = new StringBuilder(64);
        for (byte x : d) {
            b.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return b.toString();
    }

    /** The {@code prev} of the record after {@code line} (one stored line, without its newline). */
    static String hashOfLine(String line) {
        return sha256Hex((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Appends chained records to one {@link AuditFile}. The head (the hash of the last line this
     * writer wrote) lives in memory. On the first append, and after a failed one, it is recovered
     * from the file: the last record {@link AuditFile#read} keeps, hashed as stored, or
     * {@link #GENESIS} for a file with none. A record's own {@code prev} key, if it has one, is
     * replaced. When the append finds a torn tail, a chained {@code audit.torn_tail} record follows
     * the record. Thread-safe.
     */
    public static final class Writer {
        private final AuditFile file;
        private String head; // null: recover from the file before the next append

        public Writer(AuditFile file) {
            this.file = file;
        }

        /**
         * Sets {@code o}'s {@code prev}, appends it, and returns the lines written, in order: the
         * record, then the {@code audit.torn_tail} note if the tail was torn.
         */
        public synchronized List<String> append(JSONObject o) throws IOException {
            List<String> written = new ArrayList<>(2);
            if (appendOne(o, written)) {
                JSONObject note = new JSONObject();
                try {
                    note.put("ts", System.currentTimeMillis()).put("event", "audit.torn_tail")
                            .put("detail", "the file ended in an unfinished line or NULs; the record before this "
                                    + "started a fresh line, and the chain breaks at it");
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
                appendOne(note, written);
            }
            return written;
        }

        /** The hash of the last line this writer wrote or recovered, or null before the first append. */
        public synchronized String head() {
            return head;
        }

        private boolean appendOne(JSONObject o, List<String> written) throws IOException {
            if (head == null) {
                List<String> lines = file.read().lines;
                head = lines.isEmpty() ? GENESIS : hashOfLine(lines.get(lines.size() - 1));
            }
            try {
                o.put("prev", head);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            String line = o.toString();
            boolean torn;
            try {
                torn = file.append(line);
            } catch (IOException e) {
                head = null; // whatever reached the file, the next append chains to what is there
                throw e;
            }
            head = hashOfLine(line);
            written.add(line);
            return torn;
        }
    }

    /** One place the chain does not hold. */
    public static final class Break {
        /** The 1-based line in the file the break is attributed to. */
        public final int line;
        public final String reason;

        Break(int line, String reason) {
            this.line = line;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "line " + line + ": " + reason;
        }
    }

    /** What {@link #verify} found. */
    public static final class Result {
        /** The records it saw (lines that are one JSON object), linked or not. */
        public final int records;
        /** Every break, by line; empty when the whole file chains from genesis. */
        public final List<Break> breaks;

        Result(int records, List<Break> breaks) {
            this.records = records;
            this.breaks = Collections.unmodifiableList(breaks);
        }

        public boolean ok() {
            return breaks.isEmpty();
        }

        /** The earliest break, or null. */
        public Break first() {
            return breaks.isEmpty() ? null : breaks.get(0);
        }
    }

    /** {@link #verify(byte[], String)} of a file; a missing file is an empty, unbroken chain. */
    public static Result verify(File file, String head) throws IOException {
        try {
            return verify(Files.readAllBytes(file.toPath()), head);
        } catch (NoSuchFileException e) {
            return verify(new byte[0], head);
        }
    }

    private enum Link { OK, MISMATCH, BAD_PREV, GAP, NONE }

    private static final class Rec {
        final int line;
        final byte[] stored; // the line's bytes with its newline
        final String prev;
        final int gapBefore; // the last gap line since the previous record, or 0

        Rec(int line, byte[] stored, String prev, int gapBefore) {
            this.line = line;
            this.stored = stored;
            this.prev = prev;
            this.gapBefore = gapBefore;
        }
    }

    /**
     * Verifies the chain over a file's bytes. {@code head}, if not null, is the writer's
     * {@link Writer#head()}: the newest record must hash to it, which covers the one line no later
     * record links to.
     */
    public static Result verify(byte[] raw, String head) {
        Map<Integer, String> breaks = new TreeMap<>();
        List<Rec> recs = new ArrayList<>();
        int gap = 0;
        int line = 0;
        for (int start = 0; start < raw.length; ) {
            line++;
            int nl = start;
            while (nl < raw.length && raw[nl] != '\n') {
                nl++;
            }
            boolean unfinished = nl == raw.length;
            int end = unfinished ? raw.length : nl + 1;
            byte[] stored = Arrays.copyOfRange(raw, start, end);
            String reason = null;
            JSONObject o = null;
            if (unfinished) {
                reason = "an unfinished last line (no newline): a gap";
            } else if (indexOfNul(raw, start, nl) >= 0) {
                reason = "NULs in the line (a torn write): a gap";
            } else {
                String text = new String(raw, start, nl - start, StandardCharsets.UTF_8);
                if (text.isBlank()) {
                    reason = "a blank line: a gap";
                } else if ((o = AuditFile.object(text)) == null) {
                    reason = "not one JSON object: a gap";
                }
            }
            if (reason != null) {
                breaks.putIfAbsent(line, reason);
                gap = line;
            } else {
                recs.add(new Rec(line, stored, o.optString("prev", null), gap));
                gap = 0;
            }
            start = end;
        }

        int n = recs.size();
        Link[] links = new Link[n + 1]; // links[i]: into record i; links[n]: the newest record to head
        for (int i = 0; i < n; i++) {
            Rec r = recs.get(i);
            if (r.gapBefore != 0) {
                links[i] = Link.GAP;
            } else if (!isSha256Hex(r.prev)) {
                links[i] = Link.BAD_PREV;
            } else {
                String want = i == 0 ? GENESIS : sha256Hex(recs.get(i - 1).stored);
                links[i] = r.prev.equals(want) ? Link.OK : Link.MISMATCH;
            }
        }
        if (n == 0 || head == null || trailingGap(recs, line)) {
            links[n] = Link.NONE;
        } else {
            links[n] = head.equals(sha256Hex(recs.get(n - 1).stored)) ? Link.OK : Link.MISMATCH;
        }

        boolean[] explained = new boolean[n + 1];
        for (int i = 0; i < n; i++) {
            Rec r = recs.get(i);
            switch (links[i]) {
                case GAP:
                    breaks.putIfAbsent(r.line, i == 0
                            ? "the chain does not start at genesis: the gap at line " + r.gapBefore + " comes first"
                            : "does not link across the gap at line " + r.gapBefore);
                    break;
                case BAD_PREV:
                    breaks.putIfAbsent(r.line, r.prev == null ? "no prev: not a chained record"
                            : "prev is not a lowercase hex SHA-256");
                    break;
                case MISMATCH:
                    if (explained[i]) {
                        break;
                    }
                    if (i == 0) {
                        breaks.putIfAbsent(r.line, "prev is not genesis: this line changed, or the file "
                                + "does not start at the chain's first record");
                        explained[1] = links[1] == Link.MISMATCH;
                    } else if (links[i + 1] == Link.MISMATCH) {
                        breaks.putIfAbsent(r.line, "this line changed: its prev does not match line "
                                + recs.get(i - 1).line + ", and the next link does not match it");
                        explained[i + 1] = true;
                    } else if (links[i + 1] == Link.OK) {
                        breaks.putIfAbsent(recs.get(i - 1).line, "this line changed: line " + r.line
                                + "'s prev no longer matches it");
                    } else if (!breaks.containsKey(recs.get(i - 1).line)) {
                        breaks.put(r.line, "prev does not match line " + recs.get(i - 1).line
                                + ": that line changed, or this line's prev did");
                    }
                    break;
                default:
                    break;
            }
        }
        if (links[n] == Link.MISMATCH && !explained[n]) {
            breaks.putIfAbsent(recs.get(n - 1).line, "this line changed: it does not hash to the writer's head");
        }

        List<Break> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : breaks.entrySet()) {
            out.add(new Break(e.getKey(), e.getValue()));
        }
        return new Result(n, out);
    }

    /** Whether a gap follows the newest record (then the head cannot be checked against it). */
    private static boolean trailingGap(List<Rec> recs, int lastLine) {
        return !recs.isEmpty() && recs.get(recs.size() - 1).line != lastLine;
    }

    private static int indexOfNul(byte[] raw, int from, int to) {
        for (int i = from; i < to; i++) {
            if (raw[i] == 0) {
                return i;
            }
        }
        return -1;
    }
}
