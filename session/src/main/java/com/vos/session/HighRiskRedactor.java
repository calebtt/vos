package com.vos.session;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decision 46 (#276), the first slice: a high-risk string released during a task (an OTP-like code
 * from Messages, decision 32) must not outlive it. At task end every span that matches a released
 * string under decision 32's normalization — NFKC compatibility folded, case folded, whitespace
 * (all Unicode spaces), dashes, commas and the separators {@code - . _ /} removed — is replaced
 * with a placeholder naming the source app ("a code from Messages"), and the count of replacements
 * is returned for the task record's compaction line (§3.8: the count, never the string). Pure JVM,
 * no Android or actuator wiring.
 *
 * <p>Matching runs on a normalized copy of the text that keeps, for every normalized character,
 * where it came from in the raw text, so text outside a match is untouched and a match may carry
 * arbitrary separator, whitespace, case and compatibility differences inside it ("482913",
 * "482 913", "482-913", "4-8-2-9-1-3", "482,913", "482–913"). A span only counts when the raw
 * character on either end is not an alphanumeric of the same kind as the code character next to it
 * (a digit is blocked only by a digit, a letter only by a letter), so a code does not match inside
 * a longer digit run ("14829135" is not a match) or a longer word, while a label of the other kind
 * ("x482-913") still carries the code. A released string with no match is a no-op; every span the
 * redactor does match disappears completely, and the only spellings of a released string that can
 * survive are ones the boundary rule judged part of a longer run.
 */
final class HighRiskRedactor {

    /** Cap on the app label inside a placeholder, so a released string cannot bloat every line. */
    private static final int MAX_APP_LABEL = 32;

    /** One released string: its surface text and the app label it was released from (decision 32). */
    record Released(String text, String sourceApp) {
        /** What replaces every match of it: the source app, never the string. The label is
         * sanitized — control characters stripped, runs of whitespace collapsed to single spaces,
         * length capped — so a hostile app label cannot smuggle separators past the boundary rule
         * or inflate the text. */
        String placeholder() {
            String app = sanitizeApp(sourceApp);
            return app.isEmpty() ? "a released high-risk string" : "a code from " + app;
        }
    }

    /** The new text and the number of spans replaced. */
    record Result(String text, int count) {}

    /** The new turns, same size and order as the input, and the number of codes replaced. A match
     * split across adjacent turns is replaced in both and counted once. */
    record TurnsResult(List<String> turns, int count) {}

    /** The characters normalization removes besides the ASCII separators (decision 32): every
     * Unicode space (no-break and narrow spaces included), every Unicode dash (en dash, em dash,
     * fullwidth hyphen after NFKC), and commas (Arabic comma; fullwidth comma folds to the ASCII
     * one under NFKC). */
    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c)
                || Character.getType(c) == Character.SPACE_SEPARATOR
                || Character.getType(c) == Character.DASH_PUNCTUATION
                || Character.getType(c) == Character.FORMAT // zero-width space, soft hyphen, bidi marks
                || c == '\u2212' // minus sign
                || c == '-' || c == '.' || c == '_' || c == '/' || c == ',' || c == '\u060C';
    }

    /** NFKC of one character where that can change anything; a surrogate pair passes through
     * whole, so supplementary-plane folds are a known gap. */
    private static String nfkcFold(char c) {
        if (c < '\u00A0' || Character.isSurrogate(c)) {
            return String.valueOf(c);
        }
        return Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
    }

    /** Appends the normalized form of one raw character: separators are dropped entirely, the rest
     * is NFKC folded and case folded. */
    private static void normalizeChar(char c, StringBuilder b) {
        if (isSeparator(c)) {
            return;
        }
        String folded = nfkcFold(c);
        for (int i = 0; i < folded.length(); i++) {
            char f = folded.charAt(i);
            if (!isSeparator(f)) {
                b.append(Character.toLowerCase(f));
            }
        }
    }

    /** Decision 32's normalization: NFKC folded, case folded, whitespace and separators removed. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            normalizeChar(s.charAt(i), b);
        }
        return b.toString();
    }

    /** Control characters removed, whitespace runs collapsed to single spaces, ends trimmed,
     * capped at {@link #MAX_APP_LABEL}. */
    private static String sanitizeApp(String sourceApp) {
        if (sourceApp == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(sourceApp.length());
        boolean pendingSpace = false;
        for (int i = 0; i < sourceApp.length(); i++) {
            char c = sourceApp.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) {
                continue; // a control character may separate words, it never breaks the label apart
            }
            if (Character.isWhitespace(c)) {
                pendingSpace = b.length() > 0;
                continue;
            }
            if (pendingSpace) {
                b.append(' ');
                pendingSpace = false;
            }
            b.append(c);
        }
        String app = b.toString();
        if (app.length() <= MAX_APP_LABEL) {
            return app;
        }
        int end = MAX_APP_LABEL;
        if (Character.isHighSurrogate(app.charAt(end - 1))) {
            end--; // never split a surrogate pair
        }
        return app.substring(0, end).stripTrailing();
    }

    /** The released strings worth applying, longest normalized code first: a shorter code must not
     * consume characters a longer one would have matched. The sort is stable, so equal lengths keep
     * their list order. Null entries and strings empty or blank after normalization are dropped. */
    private static List<Released> codesToApply(List<Released> released) {
        List<Released> codes = new ArrayList<>();
        if (released == null) {
            return codes;
        }
        for (Released r : released) {
            if (r != null && !normalize(r.text()).isEmpty()) {
                codes.add(r);
            }
        }
        codes.sort(Comparator.comparingInt((Released r) -> normalize(r.text()).length()).reversed());
        return codes;
    }

    /**
     * Replaces every span of {@code text} that matches one of {@code released} under decision 32's
     * normalization. Released strings that are empty or blank after normalization (and null
     * entries) are ignored; the codes are applied longest-first, and where two codes could match
     * the same span the longer one wins.
     */
    static Result redact(String text, List<Released> released) {
        String out = text == null ? "" : text;
        int count = 0;
        for (Released r : codesToApply(released)) {
            int[] made = new int[1];
            out = redactCode(out, normalize(r.text()), r.placeholder(), made);
            count += made[0];
        }
        return new Result(out, count);
    }

    /**
     * Redacts a list of turns (e.g. the conversation's owner and VOS lines); a null list is empty
     * and a null turn is empty text. First every whole match inside a single turn is replaced;
     * then a code split across the boundary of two adjacent turns ("482" at the end of one, "913"
     * at the start of the next, separators at the join allowed) is replaced in both turns and
     * counted once. Because a match may end at any raw character before the join, the boundary
     * passes repeat until the turns stop changing; the number of passes is capped, so the step
     * terminates even if a placeholder's own characters could start a new crossing match. A code
     * spread over three or more turns is not handled; it is part of the honest residual.
     */
    static TurnsResult redactTurns(List<String> turns, List<Released> released) {
        List<Released> codes = codesToApply(released);
        List<String> out = new ArrayList<>();
        int count = 0;
        if (turns != null) {
            for (String turn : turns) {
                String s = turn == null ? "" : turn;
                for (Released r : codes) {
                    int[] made = new int[1];
                    s = redactCode(s, normalize(r.text()), r.placeholder(), made);
                    count += made[0];
                }
                out.add(s);
            }
        }
        int passes = 0;
        while (passes++ < out.size() + 2) {
            int before = totalLength(out);
            for (int t = 0; t + 1 < out.size(); t++) {
                for (Released r : codes) {
                    int[] made = new int[1];
                    crossOneBoundary(out, t, normalize(r.text()), r.placeholder(), made);
                    count += made[0];
                }
            }
            if (totalLength(out) == before) {
                break;
            }
        }
        return new TurnsResult(List.copyOf(out), count);
    }

    private static int totalLength(List<String> turns) {
        int total = 0;
        for (String s : turns) {
            total += s.length();
        }
        return total;
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
     * Replaces one match of {@code code} that starts in turn {@code t} and ends in turn
     * {@code t + 1}: the tail of one turn and the head of the next, each side contributing at least
     * one normalized character. The two turns are matched as one text joined by a space (a
     * separator, so a split "482 " / "-913" matches too), and the match is written back as the
     * placeholder in both turns, counted once. A candidate whose raw span overlaps a placeholder
     * already present is skipped: those characters came from an earlier replacement, so a match
     * made of them is an artifact rather than a code. Every crossing match uses the last character
     * of turn t, so one replacement per call is enough for the text as it stands; the caller
     * repeats whole passes until the turns stop changing, capped so it always terminates.
     */
    private static void crossOneBoundary(List<String> turns, int t, String code, String placeholder, int[] made) {
        int join = 1; // the space between the two turns
        String a = turns.get(t);
        String b = turns.get(t + 1);
        String joined = a + " " + b;
        Norm n = Norm.of(joined);
        int split = Norm.of(a).text.length(); // normalized index where turn t + 1's characters start
        for (int pos = 0, at; (at = n.text.indexOf(code, pos)) >= 0; pos = at + 1) {
            int end = at + code.length();
            if (at < split && end > split && bounded(joined, n, at, end)
                    && !overlapsPlaceholder(joined, n, at, end, placeholder)) {
                int lastB = n.rawStart[end - 1] - (a.length() + join); // index into turn t + 1
                turns.set(t, a.substring(0, n.rawStart[at]) + placeholder);
                turns.set(t + 1, placeholder + b.substring(lastB + 1));
                made[0]++;
                return;
            }
        }
    }

    /** Whether the match's raw span overlaps an occurrence of {@code placeholder} in the joined
     * text — placeholder characters are not original content. */
    private static boolean overlapsPlaceholder(String joined, Norm n, int at, int end, String placeholder) {
        int first = n.rawStart[at];
        int last = n.rawStart[end - 1];
        for (int i = joined.indexOf(placeholder); i >= 0; i = joined.indexOf(placeholder, i + 1)) {
            if (i <= last && i + placeholder.length() > first) {
                return true;
            }
        }
        return false;
    }

    /** Whether the raw characters on both ends of the match are outside a run of their own kind: a
     * digit is blocked only by an adjacent digit and a letter only by an adjacent letter, so
     * "14829135" is not a match but "x482-913" carries the code. */
    private static boolean bounded(String raw, Norm n, int at, int end) {
        int first = n.rawStart[at];
        int last = n.rawStart[end - 1];
        return !extendsRun(raw, first - 1, n.text.charAt(at))
                && !extendsRun(raw, last + 1, n.text.charAt(end - 1));
    }

    /** Whether the raw character at {@code rawIndex} is an alphanumeric of the same kind (digit or
     * letter) as {@code codeChar}, extending the match's run on that side. */
    private static boolean extendsRun(String raw, int rawIndex, char codeChar) {
        if (rawIndex < 0 || rawIndex >= raw.length()) {
            return false;
        }
        char neighbor = raw.charAt(rawIndex);
        if (!Character.isLetterOrDigit(neighbor)) {
            return false;
        }
        return Character.isDigit(neighbor) == Character.isDigit(codeChar);
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
                int before = b.length();
                normalizeChar(raw.charAt(i), b);
                for (int k = before; k < b.length(); k++) {
                    starts[k] = i;
                }
            }
            return new Norm(b.toString(), starts);
        }
    }

    private HighRiskRedactor() {}
}
