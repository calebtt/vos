package com.vos.actuator;

import com.vos.actuator.OfflineRecognizer.Hypothesis;
import com.vos.actuator.OfflineRecognizer.Recognition;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The bundled offline grammar (§3.3 OfflinePlane: stop/pause/volume/yes/no; P5 "say stop"; §5.5 step
 * 6, the catastrophic "yes"). It turns what an {@link OfflineRecognizer} heard into one command, or
 * null for no match. Pure Java, English only; the contract is in
 * {@code docs/design/offline-grammar.md}.
 *
 * <p>The rules lean the safe way in both directions:
 * <ul>
 *   <li><b>Stop is easy.</b> The word "stop" (or "cancel") anywhere in any alternative at
 *       {@link #STOP_MIN_CONFIDENCE} fires, partials included, and wins over every other reading.
 *       A false stop costs a summon; a missed one costs the owner control.
 *   <li><b>Everything else is a whole-utterance match on a final result</b>, read from the top
 *       alternative only, at {@link #COMMAND_MIN_CONFIDENCE}.
 *   <li><b>"yes" is strict.</b> Only the exact word "yes", final, top alternative, at
 *       {@link #YES_MIN_CONFIDENCE}, and no alternative carries a negation. Not "yeah", not
 *       "yes please", not "yes but", not "no yes".
 *   <li><b>Silence never means yes.</b> {@link #confirm} turns silence, a timeout, an error or any
 *       final that is not a strict "yes" into {@link Command#CANCEL}.
 * </ul>
 * The thresholds are provisional until spike S-15 (#25) measures the bound engine's scores.
 */
public final class OfflineGrammar {
    public enum Command { STOP, CANCEL, PAUSE, VOLUME_UP, VOLUME_DOWN, YES, NO }

    /** Provisional (S-15). Low on purpose: stop must be easy to say. */
    static final double STOP_MIN_CONFIDENCE = 0.5;
    /** Provisional (S-15). */
    static final double COMMAND_MIN_CONFIDENCE = 0.7;
    /** Provisional (S-15). A catastrophic act proceeds on this. */
    static final double YES_MIN_CONFIDENCE = 0.9;

    /** Fail-safe words, matched as whole tokens anywhere, in this priority order. */
    private static final List<Map.Entry<String, Command>> FAIL_SAFE = List.of(
            Map.entry("stop", Command.STOP),
            Map.entry("cancel", Command.CANCEL));

    /** Whole-utterance phrases, after {@link #normalize}. "yes" and "no" have no variants on purpose. */
    private static final Map<String, Command> PHRASES = Map.of(
            "yes", Command.YES,
            "no", Command.NO,
            "pause", Command.PAUSE,
            "pause it", Command.PAUSE,
            "pause that", Command.PAUSE,
            "pause music", Command.PAUSE,
            "pause the music", Command.PAUSE,
            "volume up", Command.VOLUME_UP,
            "volume down", Command.VOLUME_DOWN);

    /** Any of these in any alternative means the owner may not have meant "yes". */
    private static final Set<String> NEGATIONS = Set.of("no", "not", "dont", "nope", "never", "wait");

    private OfflineGrammar() { }

    /**
     * The command in {@code r}, or null for no match (also for null, empty, or a partial that holds
     * no fail-safe word).
     */
    public static Command match(Recognition r) {
        if (r == null) {
            return null;
        }
        List<Reading> readings = readings(r);
        for (Map.Entry<String, Command> e : FAIL_SAFE) {
            for (Reading x : readings) {
                if (x.confidence >= STOP_MIN_CONFIDENCE && x.tokens.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        if (!r.isFinal() || readings.isEmpty()) {
            return null;
        }
        Reading top = readings.get(0);
        Command c = PHRASES.get(String.join(" ", top.tokens));
        if (c == null) {
            return null;
        }
        if (c == Command.YES) {
            if (top.confidence < YES_MIN_CONFIDENCE) {
                return null;
            }
            for (Reading x : readings) {
                for (String t : x.tokens) {
                    if (NEGATIONS.contains(t)) {
                        return null;
                    }
                }
            }
            return Command.YES;
        }
        return top.confidence >= COMMAND_MIN_CONFIDENCE ? c : null;
    }

    /**
     * The catastrophic confirmation (§5.5 step 6), fed every recognition in the confirm window and
     * then null when the window closes. Returns {@link Command#YES} only for a strict "yes",
     * {@link Command#CANCEL} for a fail-safe word, any other final, silence or a timeout (null), and
     * null only to keep listening (a partial with no fail-safe word).
     */
    public static Command confirm(Recognition r) {
        if (r == null) {
            return Command.CANCEL; // silence, timeout or engine error: never a yes
        }
        Command c = match(r);
        if (c == Command.YES) {
            return Command.YES;
        }
        if (c != null || r.isFinal()) {
            return Command.CANCEL;
        }
        return null;
    }

    /**
     * Lower case, accents and apostrophes dropped ("don't" is "dont"), every other non-letter or
     * non-digit a space, whitespace collapsed. Returns the tokens.
     */
    static List<String> normalize(String text) {
        if (text == null) {
            return List.of();
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("['’]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return s.isEmpty() ? List.of() : Arrays.asList(s.split(" "));
    }

    /** The non-empty alternatives, highest confidence first (stable for ties: the engine's order). */
    private static List<Reading> readings(Recognition r) {
        List<Reading> out = new ArrayList<>();
        for (Hypothesis h : r.alternatives()) {
            List<String> tokens = normalize(h.text());
            if (!tokens.isEmpty()) {
                out.add(new Reading(tokens, sane(h.confidence())));
            }
        }
        out.sort(Comparator.comparingDouble((Reading x) -> x.confidence).reversed());
        return out;
    }

    /** NaN or a score outside 0..1 counts as no confidence: an engine bug never confirms anything. */
    private static double sane(double c) {
        return c >= 0 && c <= 1 ? c : 0;
    }

    private record Reading(List<String> tokens, double confidence) { }
}
