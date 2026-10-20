package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class FastToolsTest {
    private static final int MEDIA = 3;
    private static final int RING = 2;

    /** Volumes per stream, a scripted media player, and a record of every act. */
    private static class FakePlatform implements FastTools.Platform {
        final Map<Integer, Integer> volume = new HashMap<>(Map.of(MEDIA, 7, RING, 4));
        final List<String> acts = new ArrayList<>();
        String state = "playing"; // null = not observable
        String id; // the session's media id, if it publishes one
        String title = "Song A";
        java.util.function.Consumer<FakePlatform> onKey; // scripted reaction, replaces the default
        boolean dnd;
        Integer clampTo; // the phone sets this instead (e.g. a safe-volume cap)
        boolean playerReacts = true;

        @Override public long nowMillis() { return 1792501200000L; } // 2026-10-20T08:00:00-05:00
        @Override public String zoneId() { return "America/Chicago"; }
        @Override public int streamMin(int s) { return 0; }
        @Override public int streamMax(int s) { return s == MEDIA ? 25 : 7; }
        @Override public int streamVolume(int s) { return volume.get(s); }

        @Override
        public void setStreamVolume(int s, int index) {
            acts.add("volume " + s + " " + index);
            if (dnd && s == RING) {
                throw new SecurityException("Not allowed to change Do Not Disturb state");
            }
            volume.put(s, clampTo != null ? clampTo : index);
        }

        @Override
        public void mediaKey(int keyCode) {
            acts.add("key " + keyCode);
            if (onKey != null) {
                onKey.accept(this);
                return;
            }
            if (!playerReacts || state == null) {
                return;
            }
            switch (keyCode) {
                case 127: state = "paused"; break;
                case 126: state = "playing"; break;
                case 86: state = "stopped"; break;
                case 87: title = "Song B"; break;
                default: break;
            }
        }

        @Override public String mediaState() { return state; }
        @Override public String mediaId() { return state == null ? null : id; }
        @Override public String mediaTitle() { return state == null ? null : title; }
        @Override public void sleep(long ms) { }
    }

    private final FakePlatform platform = new FakePlatform();
    private final FastTools tools = new FastTools(platform);
    private final List<String> intents = new ArrayList<>();
    private final FastTools.MediaIntent media = new FastTools.MediaIntent() {
        @Override public void ownerPaused() { intents.add("paused"); }
        @Override public void ownerPlayed() { intents.add("played"); }
    };

    private JSONObject run(String name, String args) throws Exception {
        return tools.run(name, args, media).json;
    }

    @Test
    public void timeIsTheClockAndVerified() throws Exception {
        JSONObject r = run("time_get", "{}");
        assertEquals("VERIFIED", r.getString("status"));
        assertEquals("2026-10-20T08:00:00-05:00", r.getString("local_time"));
        assertEquals("8:00 AM, Tuesday October 20", r.getString("spoken"));
    }

    @Test
    public void volumePercentIsSetAndReadBack() throws Exception {
        JSONObject r = run("volume_set", "{\"stream\":\"media\",\"percent\":30}");
        assertEquals("VERIFIED", r.getString("status"));
        // 30% of a 0..25 range is index 7.5, set as 8, which reads back as 32%: the readback is reported.
        assertEquals(List.of("volume 3 8"), platform.acts);
        assertEquals(30, r.getInt("percent_requested"));
        assertEquals(32, r.getInt("percent"));
        assertEquals(28, r.getInt("percent_before"));
    }

    @Test
    public void volumeStepsMoveByAboutATenthOfTheRange() throws Exception {
        JSONObject up = run("volume_step", "{\"direction\":\"up\"}");
        assertEquals("VERIFIED", up.getString("status"));
        assertEquals(List.of("volume 3 10"), platform.acts); // 7 + round(25/10)=3
        run("volume_step", "{\"stream\":\"ring\",\"direction\":\"down\"}");
        assertEquals("volume 2 3", platform.acts.get(1));
    }

    @Test
    public void aVolumeThePhoneCapsIsUnverifiedWithTheRealLevel() throws Exception {
        platform.clampTo = 15;
        JSONObject r = run("volume_set", "{\"percent\":100}");
        assertEquals("UNVERIFIED", r.getString("status"));
        assertEquals(60, r.getInt("percent"));
    }

    @Test
    public void doNotDisturbRefusalIsFailed() throws Exception {
        platform.dnd = true;
        JSONObject r = run("volume_set", "{\"stream\":\"ring\",\"percent\":50}");
        assertEquals("FAILED", r.getString("status"));
        assertEquals(4, (int) platform.volume.get(RING));
    }

    @Test
    public void badArgumentsAreFailedWithoutActing() throws Exception {
        String[][] bad = {
            {"volume_set", "{\"percent\":30,\"step\":\"up\"}"},
            {"volume_set", "{\"direction\":\"up\"}"},
            {"volume_step", "{\"percent\":30}"},
            {"volume_step", "{\"direction\":\"sideways\"}"},
            {"volume_set", "{}"},
            {"volume_set", "{\"percent\":130}"},
            {"volume_set", "{\"percent\":30.5}"},
            {"volume_set", "{\"percent\":\"30\"}"},
            {"volume_set", "{\"stream\":\"system\",\"percent\":30}"},
            {"volume_set", "{\"percent\":30,\"extra\":1}"},
            {"volume_set", "not json"},
            {"media_control", "{\"action\":\"rewind\"}"},
            {"time_get", "{\"zone\":\"UTC\"}"},
            {"shell_nav", "{\"to\":\"home\"}"},
            {null, "{}"},
        };
        for (String[] b : bad) {
            assertEquals(b[0] + " " + b[1], "FAILED", run(b[0], b[1]).getString("status"));
        }
        assertTrue(platform.acts.isEmpty());
        assertTrue(intents.isEmpty());
    }

    @Test
    public void pauseIsVerifiedByTheSessionStateAndTellsTheAudioPlane() throws Exception {
        JSONObject r = run("media_control", "{\"action\":\"pause\"}");
        assertEquals("VERIFIED", r.getString("status"));
        assertEquals("paused", r.getString("state"));
        assertEquals(List.of("key 127"), platform.acts);
        assertEquals(List.of("paused"), intents);
    }

    @Test
    public void nextIsVerifiedByATrackChange() throws Exception {
        assertEquals("VERIFIED", run("media_control", "{\"action\":\"next\"}").getString("status"));
    }

    @Test
    public void playIsReportedToTheAudioPlane() throws Exception {
        platform.state = "paused";
        assertEquals("VERIFIED", run("media_control", "{\"action\":\"play\"}").getString("status"));
        assertEquals(List.of("played"), intents);
    }

    @Test
    public void aPlayerThatIgnoresTheKeyIsUnverified() throws Exception {
        platform.playerReacts = false;
        JSONObject r = run("media_control", "{\"action\":\"pause\"}");
        assertEquals("UNVERIFIED", r.getString("status"));
        assertEquals("playing", r.getString("state"));
    }

    @Test
    public void unobservableMediaIsUnverifiedNeverVerified() throws Exception {
        platform.state = null;
        JSONObject r = run("media_control", "{\"action\":\"pause\"}");
        assertEquals("UNVERIFIED", r.getString("status"));
        assertEquals(List.of("key 127"), platform.acts);
    }

    @Test
    public void controllingNothingIsFailedBeforeAnyKeyOrIntent() throws Exception {
        platform.state = "none";
        for (String action : new String[] {"pause", "stop", "next"}) {
            assertEquals(action, "FAILED", run("media_control", "{\"action\":\"" + action + "\"}").getString("status"));
        }
        assertTrue("no key may be sent for a refused call", platform.acts.isEmpty());
        assertTrue("a refused call must not tell the AudioPlane the owner paused", intents.isEmpty());
    }

    @Test
    public void aSkipIsNotVerifiedByMetadataFillingInOrAFlicker() throws Exception {
        // No metadata at all before the key: unknown, not a skip.
        platform.title = null;
        platform.onKey = p -> p.title = "Song A";
        assertEquals("UNVERIFIED", run("media_control", "{\"action\":\"next\"}").getString("status"));
        // Same title, and a media id appears and stays (the player ignored next): not a skip.
        platform.title = "Song A";
        platform.id = null;
        platform.onKey = p -> p.id = "spotify:track:1";
        assertEquals("UNVERIFIED", run("media_control", "{\"action\":\"next\"}").getString("status"));
        // With ids on both sides, only the id decides; a changed title on the same id is not a skip.
        platform.id = "spotify:track:1";
        platform.onKey = p -> p.title = "Song A (Remastered)";
        assertEquals("UNVERIFIED", run("media_control", "{\"action\":\"next\"}").getString("status"));
        // A one-poll flicker of the title is not a skip either.
        int[] polls = {0};
        FakePlatform flicker = new FakePlatform() {
            @Override
            public String mediaTitle() {
                polls[0]++;
                return polls[0] == 2 ? "Other" : "Song A";
            }
        };
        assertEquals("UNVERIFIED", new FastTools(flicker).run("media_control", "{\"action\":\"next\"}", media)
                .json.getString("status"));
        assertEquals("id:b", FastTools.newTrack("a", "T", "b", "T"));
        assertEquals("t:U", FastTools.newTrack(null, "T", null, "U"));
    }

    @Test
    public void aPlayOrPauseTheSessionVisiblyIgnoredLeavesTheAudioPlaneAlone() throws Exception {
        platform.state = "paused";
        platform.onKey = p -> { }; // stays paused (VOS still holds focus, or the player ignored the key)
        assertEquals("UNVERIFIED", run("media_control", "{\"action\":\"play\"}").getString("status"));
        platform.state = "playing";
        assertEquals("UNVERIFIED", run("media_control", "{\"action\":\"pause\"}").getString("status"));
        assertTrue("RESTORE must still resume what the turn paused", intents.isEmpty());
        platform.state = null; // unobservable: the attempt stands
        assertEquals("UNVERIFIED", run("media_control", "{\"action\":\"pause\"}").getString("status"));
        assertEquals(List.of("paused"), intents);
    }
}
