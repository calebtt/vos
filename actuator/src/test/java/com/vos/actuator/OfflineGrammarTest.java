package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.vos.actuator.OfflineGrammar.Command;
import com.vos.actuator.OfflineRecognizer.Hypothesis;
import com.vos.actuator.OfflineRecognizer.Recognition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OfflineGrammarTest {
    private static Recognition fin(String text, double confidence) {
        return new Recognition(List.of(new Hypothesis(text, confidence)), true);
    }

    private static Recognition partial(String text, double confidence) {
        return new Recognition(List.of(new Hypothesis(text, confidence)), false);
    }

    /** Alternatives as text, confidence pairs. */
    private static Recognition alts(boolean isFinal, Object... pairs) {
        List<Hypothesis> hs = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            hs.add(new Hypothesis((String) pairs[i], (Double) pairs[i + 1]));
        }
        return new Recognition(hs, isFinal);
    }

    private static Command match(String text) {
        return OfflineGrammar.match(fin(text, 0.95));
    }

    @Test
    public void eachCommandWord() {
        assertEquals(Command.STOP, match("stop"));
        assertEquals(Command.CANCEL, match("cancel"));
        assertEquals(Command.PAUSE, match("pause"));
        assertEquals(Command.VOLUME_UP, match("volume up"));
        assertEquals(Command.VOLUME_DOWN, match("volume down"));
        assertEquals(Command.YES, match("yes"));
        assertEquals(Command.NO, match("no"));
    }

    /** Stop and cancel match anywhere (fail-safe), so any phrase holding them works; no listed variants. */
    @Test
    public void failSafeWordsInLongerPhrases() {
        for (String s : List.of("stop it", "stop that", "please stop", "stop stop", "stop now", "okay stop", "don't stop")) {
            assertEquals(s, Command.STOP, match(s));
        }
        for (String s : List.of("cancel that", "cancel it", "please cancel")) {
            assertEquals(s, Command.CANCEL, match(s));
        }
        assertEquals(Command.STOP, match("non-stop")); // the hyphen splits it: fail-safe on purpose
    }

    @Test
    public void variantsNotInThePlanAreNotMatched() {
        for (String s : List.of("yeah", "yep", "yup", "sure", "okay", "yes please", "yes yes", "nope", "no thanks",
                "louder", "quieter", "turn it up", "halt", "pause please now",
                "pause it", "pause that", "pause music", "pause the music")) {
            assertNull(s, match(s));
        }
    }

    @Test
    public void normalization() {
        assertEquals(Command.STOP, match("  STOP!!  "));
        assertEquals(Command.STOP, match("Stop."));
        assertEquals(Command.YES, match("Yes."));
        assertEquals(Command.YES, match("\tYES!\n"));
        assertEquals(Command.VOLUME_UP, match("Volume   up"));
        assertEquals(Command.VOLUME_DOWN, match("volume-down"));
        assertEquals(Command.PAUSE, match("Pause."));
        assertEquals(Command.STOP, match("ＳＴＯＰ")); // full-width
        assertEquals(List.of("dont", "stop"), OfflineGrammar.normalize("Don’t   stop!"));
        assertEquals(List.of("dont", "stop"), OfflineGrammar.normalize("Don\u00B4t stop"));
        assertEquals(List.of("dont", "stop"), OfflineGrammar.normalize("Don\u02BCt stop"));
        assertEquals(List.of("dont"), OfflineGrammar.normalize("don`t"));
        assertEquals(List.of("dont"), OfflineGrammar.normalize("don\u2018t"));
        assertEquals(List.of("cafe"), OfflineGrammar.normalize("Café"));
        assertEquals(List.of("volume", "2", "up"), OfflineGrammar.normalize("volume 2 up"));
        assertNull(match("volume 2 up"));
        assertEquals(List.of("stop2"), OfflineGrammar.normalize("stop\u00B2")); // NFKD: superscript two is "2"
        assertNull(match("stop\u00B2"));
        assertEquals(List.of(), OfflineGrammar.normalize(" .,!? "));
        assertEquals(List.of(), OfflineGrammar.normalize(null));
    }

    @Test
    public void stopFiresOnAPartial() {
        assertEquals(Command.STOP, OfflineGrammar.match(partial("stop", 0.6)));
        assertEquals(Command.STOP, OfflineGrammar.match(partial("okay so stop", 0.6)));
        assertEquals(Command.CANCEL, OfflineGrammar.match(partial("cancel", 0.6)));
    }

    @Test
    public void otherCommandsWaitForTheFinal() {
        for (String s : List.of("pause", "volume up", "volume down", "yes", "no")) {
            assertNull(s, OfflineGrammar.match(partial(s, 0.99)));
        }
    }

    @Test
    public void lowConfidenceIsRejected() {
        assertNull(OfflineGrammar.match(fin("stop", 0.49)));
        assertEquals(Command.STOP, OfflineGrammar.match(fin("stop", 0.5)));
        assertNull(OfflineGrammar.match(fin("pause", 0.69)));
        assertEquals(Command.PAUSE, OfflineGrammar.match(fin("pause", 0.7)));
        assertNull(OfflineGrammar.match(fin("no", 0.69)));
        assertEquals(Command.NO, OfflineGrammar.match(fin("no", 0.7)));
        assertNull(OfflineGrammar.match(fin("yes", 0.89)));
        assertEquals(Command.YES, OfflineGrammar.match(fin("yes", 0.9)));
    }

    @Test
    public void insaneConfidenceCountsAsNone() {
        for (double c : new double[] {Double.NaN, -0.1, 1.5, Double.POSITIVE_INFINITY}) {
            assertNull("" + c, OfflineGrammar.match(fin("yes", c)));
            assertNull("" + c, OfflineGrammar.match(fin("stop", c)));
        }
    }

    @Test
    public void embeddedWordsAreNotCommands() {
        for (String s : List.of("yesterday", "yessir", "stopwatch", "nonstop", "stopped", "stopping", "cancelled",
                "pauses", "paused", "know", "now", "nobody", "volume", "up", "down")) {
            assertNull(s, match(s));
        }
    }

    @Test
    public void compoundAndContradictoryUtterancesAreNotYes() {
        for (String s : List.of("yes but", "yes no", "no yes", "no, yes", "yes wait", "not yes", "yes I think",
                "um yes", "yes, don't")) {
            assertNull(s, match(s));
        }
        // A fail-safe word wins over everything, a "yes" included.
        assertEquals(Command.STOP, match("yes stop"));
        assertEquals(Command.CANCEL, match("yes cancel"));
        assertEquals(Command.STOP, match("stop, cancel"));
    }

    @Test
    public void emptyAndSilence() {
        assertNull(OfflineGrammar.match(null));
        assertNull(OfflineGrammar.match(new Recognition(List.of(), true)));
        assertNull(OfflineGrammar.match(new Recognition(null, true)));
        assertNull(OfflineGrammar.match(fin("", 0.99)));
        assertNull(OfflineGrammar.match(fin("   ", 0.99)));
        assertNull(OfflineGrammar.match(fin(null, 0.99)));
        assertNull(OfflineGrammar.match(new Recognition(Arrays.asList((Hypothesis) null), true)));
    }

    @Test
    public void alternativesAreReadHighestConfidenceFirst() {
        // The engine's order does not matter: the best-scored reading is the top one.
        assertEquals(Command.PAUSE, OfflineGrammar.match(alts(true, "paws", 0.4, "pause", 0.8)));
        assertNull(OfflineGrammar.match(alts(true, "pause", 0.75, "pours", 0.8)));
        // Only the top reading counts for a whole-utterance command, even if a lower one would pass.
        assertNull(OfflineGrammar.match(alts(true, "volume", 0.9, "volume up", 0.85)));
        // Ties keep the engine's order.
        assertEquals(Command.VOLUME_UP, OfflineGrammar.match(alts(true, "volume up", 0.8, "volume down", 0.8)));
        assertEquals(Command.VOLUME_DOWN, OfflineGrammar.match(alts(true, "volume down", 0.8, "volume up", 0.8)));
        // A fail-safe word in any alternative at its threshold wins, even below the top.
        assertEquals(Command.STOP, OfflineGrammar.match(alts(true, "pause", 0.9, "stop", 0.55)));
        assertEquals(Command.STOP, OfflineGrammar.match(alts(false, "top", 0.9, "stop", 0.55)));
        assertNull(OfflineGrammar.match(alts(false, "top", 0.9, "stop", 0.4)));
        // Empty alternatives are skipped.
        assertEquals(Command.NO, OfflineGrammar.match(alts(true, "", 0.99, "no", 0.8)));
    }

    @Test
    public void yesNeedsTheTopReadingAndNoNegationInAnyAlternative() {
        assertEquals(Command.YES, OfflineGrammar.match(alts(true, "yes", 0.95, "yesterday", 0.3)));
        assertNull(OfflineGrammar.match(alts(true, "yes", 0.95, "no", 0.1)));
        assertNull(OfflineGrammar.match(alts(true, "yes", 0.95, "not yet", 0.1)));
        assertNull(OfflineGrammar.match(alts(true, "yes", 0.95, "wait", 0.2)));
        assertNull(OfflineGrammar.match(alts(true, "yes", 0.95, "nah", 0.1)));
        assertNull(OfflineGrammar.match(alts(true, "yes", 0.95, "I can't", 0.1)));
        assertNull(OfflineGrammar.match(alts(true, "yeah", 0.95, "yes", 0.92)));
    }

    /** The spoken "yes" is no looser than the typed one (§5.5 step 6b): only a trailing . or ! */
    @Test
    public void yesRawTextMirrorsTheTypedRule() {
        for (String s : List.of("yes", "Yes.", "YES!", " yes ", "\uFF39\uFF25\uFF33")) {
            assertEquals(s, Command.YES, match(s));
        }
        for (String s : List.of("yes?", "\u00BFyes", "'yes'", "\"yes\"", "-yes-", "yes\u2026", "yes...", "yes!!",
                "yes.!", "(yes)", "yes,")) {
            assertNull(s, match(s));
            assertEquals(s, Command.CANCEL, OfflineGrammar.confirm(fin(s, 0.99)));
        }
    }

    @Test
    public void catastrophicYes() {
        assertEquals(Command.YES, OfflineGrammar.confirm(fin("yes", 0.95)));
        assertEquals(Command.YES, OfflineGrammar.confirm(fin("Yes!", 0.9)));
        // Silence, a timeout or an engine error (null) cancels.
        assertEquals(Command.CANCEL, OfflineGrammar.confirm(null));
        assertEquals(Command.CANCEL, OfflineGrammar.confirm(new Recognition(List.of(), true)));
        // Any final that is not a strict yes cancels.
        for (String s : List.of("no", "pause", "yeah", "yesterday", "yes but", "no yes", "volume up", "hello")) {
            assertEquals(s, Command.CANCEL, OfflineGrammar.confirm(fin(s, 0.99)));
        }
        assertEquals(Command.CANCEL, OfflineGrammar.confirm(fin("yes", 0.89)));
        // A partial yes is not a yes yet; a partial stop cancels at once.
        assertNull(OfflineGrammar.confirm(partial("yes", 0.99)));
        assertNull(OfflineGrammar.confirm(partial("hm", 0.99)));
        assertEquals(Command.CANCEL, OfflineGrammar.confirm(partial("stop", 0.6)));
        assertEquals(Command.CANCEL, OfflineGrammar.confirm(partial("cancel", 0.6)));
        assertEquals(Command.CANCEL, OfflineGrammar.confirm(fin("yes stop", 0.99)));
    }

    @Test
    public void recognitionCopiesItsAlternatives() {
        List<Hypothesis> hs = new ArrayList<>(List.of(new Hypothesis("yes", 0.95)));
        Recognition r = new Recognition(hs, true);
        hs.add(new Hypothesis("no", 0.9));
        assertEquals(Command.YES, OfflineGrammar.match(r));
    }
}
