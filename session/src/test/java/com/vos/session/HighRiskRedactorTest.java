package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.vos.session.HighRiskRedactor.Released;
import java.util.List;
import org.junit.Test;

/**
 * Decision 46 (#276): at task end every span matching a released high-risk string under decision 32's
 * normalization (case folded, whitespace and separators removed) becomes a placeholder naming the
 * source app, in single texts and across adjacent turns, and the string never survives.
 */
public class HighRiskRedactorTest {
    private static final Released OTP = new Released("482-913", "Messages");

    @Test
    public void separatorsWhitespaceAndCaseInsideAMatchAreAllIgnored() {
        assertEquals("Your code is a code from Messages, valid 5 min.",
                HighRiskRedactor.redact("Your code is 482 913, valid 5 min.", List.of(OTP)).text());
        for (String form : List.of("482913", "482-913", "482 913", "4-8-2-9-1-3", "482/913", "482_913",
                "4.8.2.9.1.3", "482 \t- 913")) {
            HighRiskRedactor.Result r = HighRiskRedactor.redact("code: " + form + "!", List.of(OTP));
            assertEquals(form, "code: a code from Messages!", r.text());
            assertEquals(form, 1, r.count());
        }
    }

    @Test
    public void anAlphanumericCodeMatchesAnyCase() {
        Released code = new Released("AB-12cd", "Authenticator");
        assertEquals("a code from Authenticator", HighRiskRedactor.redact("ab12cd", List.of(code)).text());
        assertEquals("a code from Authenticator!", HighRiskRedactor.redact("Ab 12 Cd!", List.of(code)).text());
        assertEquals("code a code from Authenticator",
                HighRiskRedactor.redact("code AB12CD", List.of(code)).text());
    }

    @Test
    public void aCodeDoesNotMatchInsideALongerAlphanumericRun() {
        assertEquals(0, HighRiskRedactor.redact("14829135", List.of(OTP)).count());
        assertEquals(0, HighRiskRedactor.redact("4829135", List.of(OTP)).count());
        assertEquals(0, HighRiskRedactor.redact("x482-913", List.of(OTP)).count());
        assertEquals(0, HighRiskRedactor.redact("482-913x", List.of(OTP)).count());
        assertEquals("order 14829135 shipped", HighRiskRedactor.redact("order 14829135 shipped", List.of(OTP)).text());
        // Separators and punctuation on either end keep it a span of its own.
        assertEquals("1 a code from Messages 5", HighRiskRedactor.redact("1 482913 5", List.of(OTP)).text());
        assertEquals("(a code from Messages)", HighRiskRedactor.redact("(482-913)", List.of(OTP)).text());
    }

    @Test
    public void eachReleasedStringBecomesItsOwnPlaceholder() {
        Released mail = new Released("7-x-9", "Mail");
        HighRiskRedactor.Result r = HighRiskRedactor.redact("codes 482 913 and 7X9", List.of(OTP, mail));
        assertEquals("codes a code from Messages and a code from Mail", r.text());
        assertEquals(2, r.count());
    }

    @Test
    public void everyOccurrenceOfAStringIsReplacedAndCounted() {
        HighRiskRedactor.Result r = HighRiskRedactor.redact("OTP 482-913 again 4-8-2-9-1-3 done", List.of(OTP));
        assertEquals("OTP a code from Messages again a code from Messages done", r.text());
        assertEquals(2, r.count());
        assertFalse(HighRiskRedactor.normalize(r.text()).contains("482913"));
    }

    @Test
    public void noMatchAndNoOrBlankReleasedStringsChangeNothing() {
        HighRiskRedactor.Result none = HighRiskRedactor.redact("no codes here 482 913", List.of());
        assertEquals("no codes here 482 913", none.text());
        assertEquals(0, none.count());
        HighRiskRedactor.Result blank = HighRiskRedactor.redact("text 482-913",
                List.of(new Released("   ", "Messages"), new Released("-.-/ ", "Messages")));
        assertEquals("text 482-913", blank.text());
        assertEquals(0, blank.count());
    }

    @Test
    public void aCodeSplitAcrossAdjacentTurnsIsReplacedInBothAndCountedOnce() {
        HighRiskRedactor.TurnsResult r = HighRiskRedactor.redactTurns(
                List.of("Your code is 482", "913 expires soon"), List.of(OTP));
        assertEquals(List.of("Your code is a code from Messages", "a code from Messages expires soon"), r.turns());
        assertEquals(1, r.count());
        // Separators at the join: the split need not be clean.
        HighRiskRedactor.TurnsResult j = HighRiskRedactor.redactTurns(List.of("code 482 ", "-913."), List.of(OTP));
        assertEquals(List.of("code a code from Messages", "a code from Messages."), j.turns());
        assertEquals(1, j.count());
    }

    @Test
    public void wholeMatchesInsideOneTurnOfAListStillRedact() {
        HighRiskRedactor.TurnsResult r = HighRiskRedactor.redactTurns(List.of("here 482-913", "ok then"), List.of(OTP));
        assertEquals(List.of("here a code from Messages", "ok then"), r.turns());
        assertEquals(1, r.count());
    }

    @Test
    public void turnsCountWholeAndSplitCodesTogether() {
        HighRiskRedactor.TurnsResult r = HighRiskRedactor.redactTurns(
                List.of("code 482913 and 4-8-2-9-1-3", "482", "913 now", "nothing"), List.of(OTP));
        assertEquals(List.of("code a code from Messages and a code from Messages", "a code from Messages",
                "a code from Messages now", "nothing"), r.turns());
        assertEquals(3, r.count());
        for (String turn : r.turns()) {
            assertFalse(HighRiskRedactor.normalize(turn).contains("482913"));
        }
    }

    @Test
    public void emptyTurnListsAreANoOpAndThreeTurnSplitsAreNotHandledYet() {
        HighRiskRedactor.TurnsResult empty = HighRiskRedactor.redactTurns(List.of(), List.of(OTP));
        assertEquals(List.of(), empty.turns());
        assertEquals(0, empty.count());
        // The honest residual: a code spread over three turns stays (decision 46's slice is adjacent turns).
        HighRiskRedactor.TurnsResult three = HighRiskRedactor.redactTurns(List.of("482", "of course", "913"),
                List.of(OTP));
        assertEquals(List.of("482", "of course", "913"), three.turns());
        assertEquals(0, three.count());
    }
}
