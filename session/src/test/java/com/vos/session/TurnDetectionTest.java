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
    public void withTheShippedPolicyPatienceChangesTheSilenceAndTheSweepOverridesIt() throws Exception {
        ModelPolicy.Entry e = new ModelPolicy(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/res/raw/model_policy.json")), java.nio.charset.StandardCharsets.UTF_8))
                .entry("xai:grok-voice-3.0");
        int standard = TurnDetection.forPatience(e, "standard").silenceMs;
        int lng = TurnDetection.forPatience(e, "long").silenceMs;
        int extra = TurnDetection.forPatience(e, "extra_long").silenceMs;
        org.junit.Assert.assertTrue("more patience waits longer: " + standard + " < " + lng + " < " + extra,
                standard < lng && lng < extra);
        TurnDetection swept = TurnDetection.forPatience(e, "long").withSilenceMs(900);
        assertEquals("the debug sweep (files/debug/silence_duration_ms) still wins", Integer.valueOf(900), swept.silenceMs);
        assertEquals("patience=long silence_duration_ms=900 prefix_padding_ms=300", swept.toString());
    }

    @Test
    public void theSpeechEndAndFirstAudioLinesCarryTheValuesInUse() {
        TurnDetection td = new TurnDetection("extra_long", 2000, 400);
        assertEquals("turn t-1 speech end patience=extra_long silence_duration_ms=2000 prefix_padding_ms=400",
                XaiRealtimeBackend.speechEndLine("t-1", td));
        String first = XaiRealtimeBackend.firstAudioLine("t-1", 812, td);
        org.junit.Assert.assertTrue("the eval scripts' match still holds",
                first.startsWith("turn t-1 first reply audio speech_end_to_audio_ms=812 "));
        org.junit.Assert.assertTrue(first, first.endsWith("patience=extra_long silence_duration_ms=2000 prefix_padding_ms=400"));
        assertEquals("turn t-2 first reply audio speech_end_to_audio_ms=-1 patience=standard "
                + "silence_duration_ms=vendor prefix_padding_ms=vendor",
                XaiRealtimeBackend.firstAudioLine("t-2", -1, TurnDetection.VENDOR_DEFAULT));
    }

    @Test
    public void anyWholeNumberTypeIsAValueAndAFractionIsNot() throws Exception {
        org.json.JSONObject levels = new org.json.JSONObject()
                .put("standard", new org.json.JSONObject().put("silence_duration_ms", 600L).put("prefix_padding_ms", 300.0))
                .put("long", new org.json.JSONObject().put("silence_duration_ms", new java.math.BigDecimal("1200"))
                        .put("prefix_padding_ms", 250.5))
                .put("extra_long", new org.json.JSONObject().put("silence_duration_ms", 2000.0f));
        ModelPolicy.Entry e = entry(levels.toString());
        TurnDetection s = TurnDetection.forPatience(e, "standard");
        assertEquals(Integer.valueOf(600), s.silenceMs);
        assertEquals("1200.0 or 300.0 parses as a Double: still a whole number", Integer.valueOf(300), s.prefixPaddingMs);
        TurnDetection l = TurnDetection.forPatience(e, "long");
        assertEquals(Integer.valueOf(1200), l.silenceMs);
        assertNull("a fraction is rejected, not rounded", l.prefixPaddingMs);
        assertEquals(Integer.valueOf(2000), TurnDetection.forPatience(e, "extra_long").silenceMs);
        // Straight from JSON text too: a long literal and a decimal one.
        ModelPolicy.Entry text = entry("{\"standard\":{\"silence_duration_ms\":700.0,\"prefix_padding_ms\":2147483648}}");
        assertEquals(Integer.valueOf(700), TurnDetection.forPatience(text, "standard").silenceMs);
        assertNull("out of range, whatever the type", TurnDetection.forPatience(text, "standard").prefixPaddingMs);
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
