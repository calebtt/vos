package com.vos.session;

import java.util.Set;
import org.json.JSONObject;

/**
 * The vendor's end-of-turn settings for a voice turn (decision 31, §9 Q33, #247): how long a pause
 * ends the owner's turn ({@code silence_duration_ms}) and how much audio before speech the vendor
 * keeps ({@code prefix_padding_ms}). Values come from the {@code chat_voice} policy entry, per owner
 * patience level: {@code "turn_detection": {"standard": {...}, "long": {...}, "extra_long": {...}}}.
 * A value that is absent, or out of range, is left to the vendor's default; an unknown or missing
 * level falls back to standard. Standard is the vendor's own end-of-turn unless the policy sets it.
 */
final class TurnDetection {
    static final String STANDARD = "standard";
    /** Q33's owner settings: standard, long and extra long patience. */
    static final Set<String> PATIENCE = Set.of(STANDARD, "long", "extra_long");
    static final int MAX_MS = 10_000;
    static final TurnDetection VENDOR_DEFAULT = new TurnDetection(STANDARD, null, null);

    final String patience;
    final Integer silenceMs; // null: the vendor's default
    final Integer prefixPaddingMs; // null: the vendor's default

    TurnDetection(String patience, Integer silenceMs, Integer prefixPaddingMs) {
        this.patience = patience;
        this.silenceMs = silenceMs;
        this.prefixPaddingMs = prefixPaddingMs;
    }

    /** The settings {@code entry} gives {@code patience} (null or unknown: standard). */
    static TurnDetection forPatience(ModelPolicy.Entry entry, String patience) {
        String p = patience != null && PATIENCE.contains(patience) ? patience : STANDARD;
        JSONObject all = entry == null ? null : entry.fields.optJSONObject("turn_detection");
        JSONObject level = all == null ? null : all.optJSONObject(p);
        if (level == null && all != null) {
            level = all.optJSONObject(STANDARD);
        }
        return level == null ? new TurnDetection(p, null, null)
                : new TurnDetection(p, ms(level, "silence_duration_ms"), ms(level, "prefix_padding_ms"));
    }

    /** The same with {@code silence_duration_ms} replaced (a debug build's sweep, eval/m1); null or out of range: unchanged. */
    TurnDetection withSilenceMs(Integer ms) {
        return ms == null || ms <= 0 || ms > MAX_MS ? this : new TurnDetection(patience, ms, prefixPaddingMs);
    }

    private static Integer ms(JSONObject o, String key) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) {
            return null; // a string ("300") or anything else is not a value
        }
        // Any Number the JSON parser hands back (Integer, Long, Double, BigDecimal) counts if it is
        // a whole number of ms in range; a fractional value (1.5) is rejected, not rounded.
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || d != Math.rint(d) || d <= 0 || d > MAX_MS) {
            return null;
        }
        return (int) d;
    }

    /** For the turn's log line, next to its speech-end and first-audio times. */
    @Override
    public String toString() {
        return "patience=" + patience
                + " silence_duration_ms=" + (silenceMs == null ? "vendor" : silenceMs)
                + " prefix_padding_ms=" + (prefixPaddingMs == null ? "vendor" : prefixPaddingMs);
    }
}
