package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TurnDetectionTest {
    private static ModelPolicy.Entry entry(String turnDetection) {
        String extra = turnDetection == null ? "" : ",\"turn_detection\":" + turnDetection;
        return new ModelPolicy("{\"registry\":[{\"id\":\"v\",\"kind\":\"realtime_voice\",\"where\":\"cloud:xai\"" + extra
                + "}],\"policy\":{\"chat_voice\":[\"v\"]}}").entry("v");
    }

    private static final String LEVELS = "{\"standard\":{\"silence_duration_ms\":600,\"prefix_padding_ms\":300},"
            + "\"long\":{\"silence_duration_ms\":1200},\"extra_long\":{\"silence_duration_ms\":2000,\"prefix_padding_ms\":400}}";

    @Test
    public void eachPatienceLevelPicksItsOwnValues() {
        ModelPolicy.Entry e = entry(LEVELS);
        TurnDetection s = TurnDetection.forPatience(e, "standard");
        assertEquals(Integer.valueOf(600), s.silenceMs);
        assertEquals(Integer.valueOf(300), s.prefixPaddingMs);
        TurnDetection l = TurnDetection.forPatience(e, "long");
        assertEquals("long", l.patience);
        assertEquals(Integer.valueOf(1200), l.silenceMs);
        assertNull("not set for long: the vendor's default", l.prefixPaddingMs);
        assertEquals(Integer.valueOf(2000), TurnDetection.forPatience(e, "extra_long").silenceMs);
    }

    @Test
    public void anUnknownOrMissingLevelIsStandard() {
        ModelPolicy.Entry e = entry(LEVELS);
        for (String p : new String[] {null, "", "LONG", "forever"}) {
            TurnDetection t = TurnDetection.forPatience(e, p);
            assertEquals(String.valueOf(p), "standard", t.patience);
            assertEquals(Integer.valueOf(600), t.silenceMs);
        }
        TurnDetection l = TurnDetection.forPatience(entry("{\"standard\":{\"silence_duration_ms\":700}}"), "long");
        assertEquals("the level is kept for the log", "long", l.patience);
        assertEquals("but the values fall back to standard", Integer.valueOf(700), l.silenceMs);
    }

    @Test
    public void noSettingsInThePolicyLeaveTheVendorsDefaults() {
        TurnDetection t = TurnDetection.forPatience(entry(null), "long");
        assertNull(t.silenceMs);
        assertNull(t.prefixPaddingMs);
        assertNull(TurnDetection.forPatience(null, "standard").silenceMs);
        assertEquals("patience=long silence_duration_ms=vendor prefix_padding_ms=vendor", t.toString());
    }

    @Test
    public void outOfRangeOrNonIntegerValuesAreLeftToTheVendor() {
        ModelPolicy.Entry e = entry("{\"standard\":{\"silence_duration_ms\":-5,\"prefix_padding_ms\":\"300\"},"
                + "\"long\":{\"silence_duration_ms\":10001},\"extra_long\":{\"silence_duration_ms\":1.5}}");
        for (String p : TurnDetection.PATIENCE) {
            TurnDetection t = TurnDetection.forPatience(e, p);
            assertNull(p, t.silenceMs);
            assertNull(p, t.prefixPaddingMs);
        }
    }

    @Test
    public void theSweepOverrideReplacesOnlyTheSilenceAndOnlyInRange() {
        TurnDetection base = TurnDetection.forPatience(entry(LEVELS), "standard");
        TurnDetection swept = base.withSilenceMs(900);
        assertEquals(Integer.valueOf(900), swept.silenceMs);
        assertEquals(Integer.valueOf(300), swept.prefixPaddingMs);
        assertEquals(base, base.withSilenceMs(null));
        assertEquals(base, base.withSilenceMs(0));
        assertEquals(base, base.withSilenceMs(TurnDetection.MAX_MS + 1));
    }
}
