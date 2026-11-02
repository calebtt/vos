package com.vos.session;

/**
 * The note VOS adds to the vendor conversation after a barge-in (decision 48, #248): the last words
 * of the cut reply the owner actually heard. xAI accepts {@code conversation.item.truncate} but does
 * not apply it (the #248 spikes), so its history otherwise holds the whole reply as if heard.
 *
 * <p>The heard part is mapped onto the reply's transcript in proportion to the audio: the owner heard
 * {@code heard} of the reply's {@code total} audio bytes, so about that share of its characters.
 * Characters follow speech time more closely than word counts do (a long word takes longer to say).
 * It rounds down to whole words, so a word the owner heard only part of is not quoted, and a word
 * whose unheard part is only punctuation counts as heard. No note when the transcript is empty, when
 * no whole word was heard, or when the whole reply was. The quote is bounded: at most
 * {@link #MAX_WORDS} words and {@link #MAX_CHARS} characters, its ellipsis included. Pure, so it is
 * tested on the JVM.
 */
final class HeardNote {
    static final int MAX_WORDS = 12;
    static final int MAX_CHARS = 80;
    static final String ELLIPSIS = "…";

    /** The note's text, or null; why, for the log line (never the transcript itself). */
    final String note;
    final String outcome;
    /** Whole words of the transcript heard, and in all. */
    final int heardWords;
    final int words;

    private HeardNote(String note, String outcome, int heardWords, int words) {
        this.note = note;
        this.outcome = outcome;
        this.heardWords = heardWords;
        this.words = words;
    }

    static HeardNote of(String transcript, long heard, long total) {
        String text = transcript == null ? "" : transcript.strip().replaceAll("\\s+", " ");
        int words = text.isEmpty() ? 0 : text.split(" ").length;
        if (text.isEmpty()) {
            return new HeardNote(null, "no transcript", 0, 0);
        }
        if (heard <= 0 || total <= 0) {
            return new HeardNote(null, "nothing heard", 0, words);
        }
        if (heard >= total) {
            return new HeardNote(null, "heard it all", words, words);
        }
        String prefix = heardPrefix(text, (int) (text.length() * heard / total));
        int heardWords = prefix.isEmpty() ? 0 : prefix.split(" ").length;
        if (heardWords == 0) {
            return new HeardNote(null, "nothing heard", 0, words);
        }
        if (heardWords == words) {
            return new HeardNote(null, "heard it all", words, words);
        }
        return new HeardNote("The owner interrupted your last reply after: \"" + quote(prefix)
                + "\". They did not hear the rest of it.", "note", heardWords, words);
    }

    /** The whole words of {@code text} (whitespace collapsed) that end at or before {@code pos}. */
    static String heardPrefix(String text, int pos) {
        if (pos >= text.length()) {
            return text;
        }
        int end = text.indexOf(' ', pos); // the end of the word pos falls in
        if (end < 0) {
            end = text.length();
        }
        boolean rest = false; // a letter or digit of that word is still unheard
        for (int k = pos; k < end; k++) {
            rest |= Character.isLetterOrDigit(text.charAt(k));
        }
        if (!rest) {
            return text.substring(0, end).strip();
        }
        int start = text.lastIndexOf(' ', pos); // the word was cut: keep the words before it
        return start < 0 ? "" : text.substring(0, start).strip();
    }

    /**
     * The last words of {@code prefix}: at most MAX_WORDS words and MAX_CHARS characters, the leading
     * ellipsis (when words were left out) included.
     */
    static String quote(String prefix) {
        String[] w = prefix.split(" ");
        int from = Math.max(0, w.length - MAX_WORDS);
        String q = String.join(" ", java.util.Arrays.copyOfRange(w, from, w.length));
        while ((from > 0 ? ELLIPSIS.length() : 0) + q.length() > MAX_CHARS && from < w.length - 1) {
            from++;
            q = String.join(" ", java.util.Arrays.copyOfRange(w, from, w.length));
        }
        if ((from > 0 ? ELLIPSIS.length() : 0) + q.length() > MAX_CHARS) { // one very long word: its end
            q = q.substring(q.length() - (MAX_CHARS - ELLIPSIS.length()));
            from = 1;
        }
        return (from > 0 ? ELLIPSIS : "") + q.replace('"', '\'');
    }
}
