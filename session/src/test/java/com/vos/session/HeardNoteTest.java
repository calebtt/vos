package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Decision 48: the words of a cut reply the owner heard, mapped from the audio heard. */
public class HeardNoteTest {
    /** "w01 w02 … wNN": every word three characters, so a word is 4 characters with its space. */
    static String words(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            b.append(i > 1 ? " " : "").append(String.format("w%02d", i));
        }
        return b.toString();
    }

    @Test
    public void theHeardShareOfTheAudioIsThatShareOfTheTranscriptInWholeWords() {
        // 10 words, 39 characters; 35 % of the audio is character 13, inside w04: w01–w03 were heard.
        HeardNote n = HeardNote.of(words(10), 350, 1000);
        assertEquals("note", n.outcome);
        assertEquals(3, n.heardWords);
        assertEquals(10, n.words);
        assertEquals("The owner interrupted your last reply after: \"w01 w02 w03\". They did not hear the rest of it.",
                n.note);
        // Half of it is character 19, the space after w05: w05 was heard whole.
        assertEquals(5, HeardNote.of(words(10), 500, 1000).heardWords);
    }

    @Test
    public void characterLengthNotWordCountFollowsSpeechTime() {
        // The long word takes most of the time: a third of the audio is still inside it.
        HeardNote n = HeardNote.of("I recommend extraordinarily careful driving", 330, 1000);
        assertEquals(2, n.heardWords);
        assertTrue(n.note, n.note.contains("\"I recommend\""));
    }

    @Test
    public void noNoteWhenNothingOrLessThanOneWordWasHeard() {
        assertNull(HeardNote.of(words(10), 0, 1000).note);
        assertEquals("nothing heard", HeardNote.of(words(10), 0, 1000).outcome);
        HeardNote part = HeardNote.of(words(10), 50, 1000); // character 1: inside w01
        assertNull(part.note);
        assertEquals("nothing heard", part.outcome);
        assertEquals("nothing heard", HeardNote.of(words(10), 10, 0).outcome);
    }

    @Test
    public void noNoteWhenTheWholeReplyWasHeard() {
        assertEquals("heard it all", HeardNote.of(words(10), 1000, 1000).outcome);
        assertEquals("heard it all", HeardNote.of(words(10), 5000, 1000).outcome);
        // Only the full stop was still unheard: the last word counts as heard, so nothing was cut.
        HeardNote n = HeardNote.of("Turn left here.", 14, 15);
        assertEquals("heard it all", n.outcome);
        assertNull(n.note);
    }

    @Test
    public void noNoteWithoutATranscript() {
        assertEquals("no transcript", HeardNote.of(null, 400, 1000).outcome);
        assertEquals("no transcript", HeardNote.of("  \n ", 400, 1000).outcome);
    }

    @Test
    public void theQuoteIsTheLastHeardWordsAndIsBounded() {
        HeardNote n = HeardNote.of(words(100), 900, 1000); // 90 words heard
        assertEquals(90, n.heardWords);
        String quote = n.note.substring(n.note.indexOf('"') + 1, n.note.lastIndexOf('"'));
        assertEquals(HeardNote.ELLIPSIS + "w79 w80 w81 w82 w83 w84 w85 w86 w87 w88 w89 w90", quote);
        assertTrue(n.note.length() <= 160);
        // Long words: fewer than MAX_WORDS fit in MAX_CHARS.
        String longWords = ("internationalization ".repeat(30)).strip();
        HeardNote l = HeardNote.of(longWords, 900, 1000);
        String lq = l.note.substring(l.note.indexOf('"') + 1, l.note.lastIndexOf('"'));
        assertTrue(lq, lq.startsWith(HeardNote.ELLIPSIS) && lq.length() <= HeardNote.MAX_CHARS); // "…" included
        assertTrue(lq, lq.endsWith("internationalization"));
        // One word longer than MAX_CHARS: its end.
        String url = "see " + "x".repeat(200) + " ok then";
        HeardNote u = HeardNote.of(url, 205, url.length());
        String uq = u.note.substring(u.note.indexOf('"') + 1, u.note.lastIndexOf('"'));
        assertEquals(HeardNote.ELLIPSIS + "x".repeat(HeardNote.MAX_CHARS - 1), uq);
        assertEquals(HeardNote.MAX_CHARS, uq.length());
    }

    @Test
    public void quotesAndLineBreaksInTheTranscriptCannotBreakTheNote() {
        // 36 characters once the line breaks are collapsed; character 23 is the space after "then".
        HeardNote n = HeardNote.of("He said \"stop\"\nand then\n\nleft quietly", 23, 36);
        assertEquals("note", n.outcome);
        assertEquals("The owner interrupted your last reply after: \"He said 'stop' and then\". They did not hear the "
                + "rest of it.", n.note);
    }
}
