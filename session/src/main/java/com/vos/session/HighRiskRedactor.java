package com.vos.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision 46 (#276), the first slice: a high-risk string released during a task (an OTP-like code
 * from Messages, decision 32) must not outlive it. At task end every span that matches a released
 * string under decision 32's normalization — case folded, whitespace and separators such as
 * {@code - . space _ /} removed — is replaced with a placeholder naming the source app
 * ("a code from Messages"), and the count of replacements is returned for the task record's
 * compaction line (§3.8: the count, never the string). Pure JVM, no Android or actuator wiring.
 *
 * <p>Matching runs on a normalized copy of the text that keeps, for every normalized character,
 * where it came from in the raw text, so text outside a match is untouched and a match may carry
 * arbitrary separator, whitespace and case differences inside it ("482913", "482 913", "482-913",
 * "4-8-2-9-1-3"). A span only counts when the raw characters on both its ends are not letters or
 * digits, so a code does not match inside a longer alphanumeric run ("14829135" is not a match).
 * A released string with no match is a no-op; the result never contains a released string.
 */
final class HighRiskRedactor {

    /** One released string: its surface text and the app label it was released from (decision 32). */
    record Released(String text, String sourceApp) {
        /** What replaces every match of it: the source app, never the string. */
        String placeholder() {
            String app = sourceApp == null ? "" : sourceApp.strip();
            return app.isEmpty() ? "a released high-risk string" : "a code from " + app;
        }
    }

    /** The new text and the number of spans replaced. */
    record Result(String text, int count) {}

    /** The new turns, same size and order as the input, and the number of codes replaced. A match
     * split across adjacent turns is replaced in both and counted once. */
    record TurnsResult(List<String> turns, int count) {}

    /** The characters normalization removes besides whitespace (decision 32). */
    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == '-' || c == '.' || c == '_' || c == '/';
    }

    /** Decision 32's normalization: case folded, whitespace and separators removed. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isSeparator(c)) {
                b.append(Character.toLowerCase(c));
            }
        }
        return b.toString();
    }

    /**
     * Replaces every span of {@code text} that matches one of {@code released} under decision 32's
     * normalization. Released strings that are empty or blank after normalization are ignored; the
     * codes are applied in list order, and where two codes could match the same span the earlier
     * one wins.
     */
    static Result redact(String text, List<Released> released) {
        String out = text == null ? "" : text;
        int count = 0;
        for (Released r : released) {
            String code = normalize(r.text());
            if (code.isEmpty()) {
                continue;
            }
            int[] made = new int[1];
            out = redactCode(out, code, r.placeholder(), made);
            count += made[0];
        }
        return new Result(out, count);
    }

    /**
     * Redacts a list of turns (e.g. the conversation's owner and VOS lines). First every whole
     * match inside a single turn is replaced; then a code split across the boundary of two adjacent
     * turns ("482" at the end of one, "913" at the start of the next, separators at the join
     * allowed) is replaced in both turns and counted once. A code spread over three or more turns
     * is not handled; it is part of the honest residual.
     */
    static TurnsResult redactTurns(List<String> turns, List<Released> released) {
        List<String> out = new ArrayList<>(turns);
        int count = 0;
        for (int t = 0; t < out.size(); t++) {
            String s = out.get(t);
            for (Released r : released) {
                String code = normalize(r.text());
                if (code.isEmpty()) {
                    continue;
                }
                int[] made = new int[1];
                s = redactCode(s, code, r.placeholder(), made);
                count += made[0];
            }
            out.set(t, s);
        }
        for (int t = 0; t + 1 < out.size(); t++) {
            for (Released r : released) {
                String code = normalize(r.text());
                if (code.isEmpty()) {
                    continue;
                }
                int[] made = new int[1];
                crossOneBoundary(out, t, code, r.placeholder(), made);
                count += made[0];
            }
        }
        return new TurnsResult(List.copyOf(out), count);
    }

    /**
     * Replaces every non-overlapping match of the normalized {@code code} in {@code text} (the code
     * itself must already be normalized and non-empty), left to right; adds this pass's count to
     * {@code made}.
     */
    private static String redactCode(String text, String code, String placeholder, int[] made) {
        Norm n = Norm.of(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        for (int pos = 0, at; (at = n.text.indexOf(code, pos)) >= 0;) {
            int end = at + code.length();
            if (bounded(text, n, at, end)) {
                out.append(text, copied, n.rawStart[at]).append(placeholder);
                copied = n.rawStart[end - 1] + 1;
                made[0]++;
                pos = end;
            } else {
                pos = at + 1; // the boundary failed here; a later occurrence may start inside it
            }
        }
        return copied == 0 ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * Replaces every match of {@code code} that starts in turn {@code t} and ends in turn
     * {@code t + 1}: the tail of one turn and the head of the next, each side contributing at least
     * one normalized character. The two turns are matched as one text joined by a space (a
     * separator, so a split "482 " / "-913" matches too), and each crossing match is written back
     * as the placeholder in both turns, counted once.
     */
    private static void crossOneBoundary(List<String> turns, int t, String code, String placeholder, int[] made) {
        int join = 1; // the space between the two turns
        for (;;) {
            String a = turns.get(t);
            String b = turns.get(t + 1);
            String joined = a + " " + b;
            Norm n = Norm.of(joined);
            int split = Norm.of(a).text.length(); // normalized index where turn t + 1's characters start
            boolean found = false;
            for (int pos = 0, at; (at = n.text.indexOf(code, pos)) >= 0;) {
                int end = at + code.length();
                if (at < split && end > split && bounded(joined, n, at, end)) {
                    int lastB = n.rawStart[end - 1] - (a.length() + join); // index into turn t + 1
                    turns.set(t, a.substring(0, n.rawStart[at]) + placeholder);
                    turns.set(t + 1, placeholder + b.substring(lastB + 1));
                    made[0]++;
                    found = true;
                    break; // the turns changed; re-find in the rebuilt text
                }
                pos = at + 1;
            }
            if (!found) {
                return;
            }
        }
    }

    /** Whether the raw characters on both ends of the match are outside any alphanumeric run. */
    private static boolean bounded(String raw, Norm n, int at, int end) {
        int first = n.rawStart[at];
        int last = n.rawStart[end - 1];
        return (first == 0 || !Character.isLetterOrDigit(raw.charAt(first - 1)))
                && (last + 1 == raw.length() || !Character.isLetterOrDigit(raw.charAt(last + 1)));
    }

    /** The normalized characters of a text and the raw index each one came from. */
    private static final class Norm {
        final String text;
        final int[] rawStart; // one per normalized character

        private Norm(String text, int[] rawStart) {
            this.text = text;
            this.rawStart = rawStart;
        }

        static Norm of(String raw) {
            StringBuilder b = new StringBuilder(raw.length());
            int[] starts = new int[raw.length()];
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (!isSeparator(c)) {
                    b.append(Character.toLowerCase(c));
                    starts[b.length() - 1] = i;
                }
            }
            return new Norm(b.toString(), starts);
        }
    }

    private HighRiskRedactor() {}
}
