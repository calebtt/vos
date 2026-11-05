package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.vos.session.HighRiskRedactor.Released;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Decision 46 (#276): at task end every span matching a released high-risk string under decision 32's
 * normalization (NFKC and case folded, whitespace, Unicode spaces, dashes, commas and separators
 * removed) becomes a placeholder naming the source app, in single texts and across adjacent turns.
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
    public void nfkcExpansionsBeforeOrInsideTheTextDoNotBreakTheMapBack() {
        // U+FB01 (fi ligature) folds to two characters and U+FDFA to eighteen: more normalized
        // characters than raw ones, which overran the raw-sized map before.
        assertEquals("ﬁle: a code from Messages",
                HighRiskRedactor.redact("ﬁle: 482913", List.of(OTP)).text());
        assertEquals("ﬁﬁﬁ a code from Messages ﬁ",
                HighRiskRedactor.redact("ﬁﬁﬁ 482-913 ﬁ", List.of(OTP)).text());
        HighRiskRedactor.Result r = HighRiskRedactor.redact("ﷺﷺ 482 913 ﷺ", List.of(OTP));
        assertEquals("ﷺﷺ a code from Messages ﷺ", r.text());
        assertEquals(1, r.count());
        Released lig = new Released("ﬁx7", "Messages"); // the code itself holds an expansion
        assertEquals("code a code from Messages.", HighRiskRedactor.redact("code FIX7.", List.of(lig)).text());
        assertEquals("code a code from Messages.", HighRiskRedactor.redact("code ﬁx7.", List.of(lig)).text());
        HighRiskRedactor.TurnsResult t = HighRiskRedactor.redactTurns(List.of("ﬁﬁ 482", "913 ﷺ"),
                List.of(OTP));
        assertEquals(1, t.count());
    }

    @Test
    public void unicodeSpacesDashesAndCommasAreSeparatorsToo() {
        // No-break and narrow spaces, an ideographic space, en dash, em dash, fullwidth hyphen,
        // a fullwidth comma and an Arabic comma: all fold away like the ASCII separators.
        for (String form : List.of("482\u00A0913", "482\u202F913", "482\u3000913", "482–913",
                "482—913", "482－913", "482，913", "482\u060C913")) {
            HighRiskRedactor.Result r = HighRiskRedactor.redact("code: " + form + "!", List.of(OTP));
            assertEquals(form, "code: a code from Messages!", r.text());
            assertEquals(form, 1, r.count());
        }
    }

    @Test
    public void nfkcFoldsFullWidthAndCircledFormsOfTheCode() {
        // NFKC folds fullwidth digits and hyphen to ASCII, on both the text and the released string.
        assertEquals("code: a code from Messages!",
                HighRiskRedactor.redact("code: ４８２－９１３!", List.of(OTP)).text());
        assertEquals("code: a code from Messages!",
                HighRiskRedactor.redact("code: 482913!", List.of(new Released("４８２－９１３", "Messages"))).text());
        Released circled = new Released("①②③", "Notes");
        assertEquals("a code from Notes", HighRiskRedactor.redact("123", List.of(circled)).text());
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
    public void aCodeIsBlockedOnlyByASameKindNeighbor() {
        // A digit next to a digit is a longer run: not a match.
        assertEquals(0, HighRiskRedactor.redact("14829135", List.of(OTP)).count());
        assertEquals(0, HighRiskRedactor.redact("4829135", List.of(OTP)).count());
        assertEquals(0, HighRiskRedactor.redact("5482913", List.of(OTP)).count());
        assertEquals("order 14829135 shipped", HighRiskRedactor.redact("order 14829135 shipped", List.of(OTP)).text());
        // A letter next to the digits (or the reverse) is a label, not a longer run: still a match.
        assertEquals("xa code from Messages", HighRiskRedactor.redact("x482-913", List.of(OTP)).text());
        assertEquals("a code from Messagesx", HighRiskRedactor.redact("482-913x", List.of(OTP)).text());
        // Separators and punctuation on either end keep it a span of its own.
        assertEquals("1 a code from Messages 5", HighRiskRedactor.redact("1 482913 5", List.of(OTP)).text());
        assertEquals("(a code from Messages)", HighRiskRedactor.redact("(482-913)", List.of(OTP)).text());
        // For an alphanumeric code the same rule holds per side: letters block letters only.
        Released code = new Released("AB-12cd", "Authenticator");
        assertEquals(0, HighRiskRedactor.redact("xab12cd", List.of(code)).count());
        assertEquals(0, HighRiskRedactor.redact("ab12cda", List.of(code)).count());
        assertEquals("1a code from Authenticator", HighRiskRedactor.redact("1ab12cd", List.of(code)).text());
        assertEquals("a code from Authenticator3", HighRiskRedactor.redact("ab12cd3", List.of(code)).text());
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
    public void longerCodesWinOverShorterOnesThatEatTheirDigits() {
        // The shorter code is first in the list but the longer one must still match: applying the
        // codes longest-first keeps "291" from eating the middle of "482913".
        HighRiskRedactor.Result r = HighRiskRedactor.redact("code 482913 and 291",
                List.of(new Released("291", "Mail"), OTP));
        assertEquals("code a code from Messages and a code from Mail", r.text());
        assertEquals(2, r.count());
    }

    @Test
    public void noMatchAndNoOrBlankReleasedStringsChangeNothing() {
        HighRiskRedactor.Result none = HighRiskRedactor.redact("no codes here 482 913", List.of());
        assertEquals("no codes here 482 913", none.text());
        assertEquals(0, none.count());
        HighRiskRedactor.Result blank = HighRiskRedactor.redact("text 482-913",
                Arrays.asList(new Released("   ", "Messages"), new Released("-.-/ ", "Messages"), null));
        assertEquals("text 482-913", blank.text());
        assertEquals(0, blank.count());
    }

    @Test
    public void theAppLabelIsSanitizedIntoThePlaceholder() {
        // Control characters stripped, whitespace runs collapsed, length capped.
        assertEquals("a code from Evil App",
                new Released("1", "Evil\r\u0000   \t App").placeholder());
        assertEquals("a code from " + "x".repeat(32), new Released("1", "x".repeat(100)).placeholder());
        assertEquals("a released high-risk string", new Released("1", "\u0000\u0001").placeholder());
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
    public void anAlphanumericCodeSplitAcrossAdjacentTurnsIsRedacted() {
        Released code = new Released("AB-12cd", "Authenticator");
        HighRiskRedactor.TurnsResult r = HighRiskRedactor.redactTurns(
                List.of("here is ab", "12cd now"), List.of(code));
        assertEquals(List.of("here is a code from Authenticator", "a code from Authenticator now"), r.turns());
        assertEquals(1, r.count());
    }

    @Test
    public void crossTurnMatchingTerminatesWhenAReplacementCouldRecreateItsOwnMatch() {
        // The released string is the two turns joined, so the crossing match replaces both turns with
        // the placeholder — and an uncapped re-scan of the rebuilt text is a known non-termination
        // trap when the placeholder itself spells the code. The passes terminate and count once.
        Released loop = new Released("a code from messages a code from messages", "Auth");
        HighRiskRedactor.TurnsResult r = HighRiskRedactor.redactTurns(
                List.of("a code from messages", "a code from messages"), List.of(loop));
        assertEquals(1, r.count());
        assertEquals(List.of("a code from Auth", "a code from Auth"), r.turns());
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
    public void nullAndEmptyTurnListsAreANoOpAndThreeTurnSplitsAreNotHandledYet() {
        HighRiskRedactor.TurnsResult nullTurns = HighRiskRedactor.redactTurns(null, List.of(OTP));
        assertEquals(List.of(), nullTurns.turns());
        assertEquals(0, nullTurns.count());
        HighRiskRedactor.TurnsResult nullElement = HighRiskRedactor.redactTurns(Arrays.asList(null, "482", "913"),
                List.of(OTP));
        assertEquals(List.of("", "a code from Messages", "a code from Messages"), nullElement.turns());
        assertEquals(1, nullElement.count());
        HighRiskRedactor.TurnsResult empty = HighRiskRedactor.redactTurns(List.of(), List.of(OTP));
        assertEquals(List.of(), empty.turns());
        assertEquals(0, empty.count());
        // The honest residual: a code spread over three turns stays (decision 46's slice is adjacent turns).
        HighRiskRedactor.TurnsResult three = HighRiskRedactor.redactTurns(List.of("482", "of course", "913"),
                List.of(OTP));
        assertEquals(List.of("482", "of course", "913"), three.turns());
        assertEquals(0, three.count());
    }

    @Test
    public void minusSignAndInvisibleFormatCharactersAreSeparators() {
        for (String form : new String[] {"482\u2212913", "482\u200B913", "482\u00AD913"}) {
            HighRiskRedactor.Result r = HighRiskRedactor.redact("code: " + form + "!", List.of(OTP));
            assertEquals(form, "code: a code from Messages!", r.text());
            assertEquals(form, 1, r.count());
        }
    }

    @Test
    public void appLabelDropsFormatCharactersAndNeverSplitsSurrogates() {
        assertEquals("a code from Messages",
                new Released("1", "Mess\u202Eages").placeholder());
        String label = "a".repeat(31) + "\uD83D\uDE00";
        String p = new Released("1", label).placeholder();
        assertFalse(Character.isHighSurrogate(p.charAt(p.length() - 1)));
    }
}
