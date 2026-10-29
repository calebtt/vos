package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

        boolean hands = true; // VOS's accessibility service is connected
        boolean navReacts = true;
        String front = "com.example.mail";
        String home = "com.android.launcher3";
        String afterBack = "com.example.other"; // the app a back lands in
        int nullReads; // the next reads of the active window miss (a transient null)

        @Override
        public boolean globalAction(int action) {
            acts.add("nav " + action);
            if (!hands) {
                return false;
            }
            if (navReacts) {
                front = action == 2 || action == 3 ? home : action == 4 || action == 5 ? FastTools.SYSTEM_UI : afterBack;
            }
            return true;
        }

        @Override public String foregroundPackage() { return !hands || nullReads-- > 0 ? null : front; }
        @Override public String homePackage() { return home; }

        final List<String[]> apps = new ArrayList<>(List.of(
                new String[] {"Maps", "com.google.android.apps.maps", "com.google.android.maps.MapsActivity"},
                new String[] {"Camera", "com.android.camera", "com.android.camera.Camera"},
                new String[] {"Google Camera", "com.google.android.GoogleCamera", "com.android.camera.CameraLauncher"},
                new String[] {"Clock", "com.android.deskclock", "com.android.deskclock.DeskClock"},
                new String[] {"Café Finder", "com.example.cafe", "com.example.cafe.Main"},
                new String[] {"Settings", "com.android.settings", "com.android.settings.Settings"}));
        boolean launchWorks = true;
        boolean launchShows = true;

        @Override public List<String[]> launchableApps() { return apps; }

        @Override
        public boolean launch(String pkg, String activity) {
            acts.add("launch " + pkg);
            if (launchWorks && launchShows) {
                front = pkg;
            }
            return launchWorks;
        }
    }

    private final FakePlatform platform = new FakePlatform();
    private final FastTools tools = new FastTools(platform);
    private final List<String> intents = new ArrayList<>();
    /** Records the owner intents that stand: an undo takes its own entry back out. */
    private final FastTools.MediaIntent media = new FastTools.MediaIntent() {
        @Override public Runnable ownerPausing() { return take("paused"); }
        @Override public Runnable ownerPlaying() { return take("played"); }

        private Runnable take(String intent) {
            intents.add(intent);
            int at = intents.size() - 1;
            return () -> intents.remove(at);
        }
    };

    private JSONObject run(String name, String args) throws Exception {
        return tools.run(name, args, media).json;
    }

    @Test
    public void knowsExactlyTheToolsItRuns() throws Exception {
        assertEquals(java.util.Set.of("time_get", "volume_set", "volume_step", "media_control", "shell_nav", "app_open"),
                FastTools.NAMES);
        for (String n : FastTools.NAMES) {
            assertTrue(n, FastTools.knows(n));
            String detail = run(n, "{}").optString("detail");
            assertFalse(n + " is dispatched (" + detail + ")", detail.equals("unknown tool"));
        }
        assertFalse(FastTools.knows("rm_rf"));
        assertFalse(FastTools.knows(null));
        assertEquals("unknown tool", run("rm_rf", "{}").getString("detail"));
        assertFalse("reading the clock is not acting", FastTools.changesState("time_get"));
        for (String n : new String[] {"volume_set", "volume_step", "media_control"}) {
            assertTrue(n, FastTools.changesState(n));
        }
        assertFalse(FastTools.changesState("rm_rf"));
        assertFalse(FastTools.changesState(null));
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

    private FastTools.Result nav(String args) {
        return tools.run("shell_nav", args, media);
    }

    @Test
    public void shellNavHomeIsVerifiedWhenTheHomeAppIsInFront() throws Exception {
        FastTools.Result r = nav("{\"action\":\"home\"}");
        assertEquals("VERIFIED", r.json.getString("status"));
        assertEquals("com.android.launcher3", r.json.getString("foreground"));
        assertEquals(List.of("nav 2"), platform.acts);
        assertTrue(FastTools.changesState("shell_nav"));
    }

    @Test
    public void shellNavBackAndRecentsNeedAnotherAppActive() throws Exception {
        assertEquals("VERIFIED", nav("{\"action\":\"back\"}").json.getString("status"));
        assertEquals("VERIFIED", nav("{\"action\":\"recents\"}").json.getString("status"));
        assertEquals(List.of("nav 1", "nav 3"), platform.acts);
    }

    @Test
    public void anInAppBackOrAHeadsUpIsNotAVerifiedBack() throws Exception {
        platform.afterBack = platform.front; // the back stayed in the same app
        assertEquals("UNVERIFIED", nav("{\"action\":\"back\"}").json.getString("status"));
        platform.afterBack = FastTools.SYSTEM_UI; // System UI became active (a shade), not the app behind
        assertEquals("UNVERIFIED", nav("{\"action\":\"back\"}").json.getString("status"));
    }

    @Test
    public void aMissedFirstReadIsNotAMove() throws Exception {
        platform.navReacts = false;
        platform.nullReads = 1; // the read before the action misses; the app stays in front
        assertEquals("UNVERIFIED", nav("{\"action\":\"back\"}").json.getString("status"));
        platform.front = platform.home;
        platform.nullReads = 1;
        assertEquals("UNVERIFIED", nav("{\"action\":\"recents\"}").json.getString("status"));
    }

    @Test
    public void recentsFromHomeIsNotVerifiedByStayingHome() throws Exception {
        platform.front = platform.home;
        assertEquals("UNVERIFIED", nav("{\"action\":\"recents\"}").json.getString("status"));
    }

    @Test
    public void shellNavShadesAreVerifiedWhenSystemUiBecomesActive() throws Exception {
        assertEquals("VERIFIED", nav("{\"action\":\"notifications\"}").json.getString("status"));
        platform.front = "com.example.mail"; // the shade closed; quick settings has to open it again
        assertEquals("VERIFIED", nav("{\"action\":\"quick_settings\"}").json.getString("status"));
    }

    @Test
    public void aLockScreenOrAnAlreadyOpenShadeIsNotAVerifiedShade() throws Exception {
        platform.front = FastTools.SYSTEM_UI; // lock screen, or the shade was already open
        assertEquals("UNVERIFIED", nav("{\"action\":\"notifications\"}").json.getString("status"));
        assertEquals("UNVERIFIED", nav("{\"action\":\"quick_settings\"}").json.getString("status"));
    }

    @Test
    public void shellNavWithNothingSeenIsUnverified() throws Exception {
        platform.navReacts = false;
        FastTools.Result r = nav("{\"action\":\"home\"}");
        assertEquals("UNVERIFIED", r.json.getString("status"));
        assertEquals("sent once, not retried", List.of("nav 2"), platform.acts);
        platform.front = "com.android.launcher3"; // already home: a home press is VERIFIED
        assertEquals("VERIFIED", nav("{\"action\":\"home\"}").json.getString("status"));
        assertEquals("but back with no new window is not", "UNVERIFIED", nav("{\"action\":\"back\"}").json.getString("status"));
    }

    @Test
    public void shellNavFailsWhenTheAccessibilityServiceIsOff() throws Exception {
        platform.hands = false;
        FastTools.Result r = nav("{\"action\":\"home\"}");
        assertEquals("FAILED", r.json.getString("status"));
        assertTrue(r.json.getString("detail").contains("accessibility service is off"));
    }

    @Test
    public void shellNavRefusesBadArgumentsWithoutActing() throws Exception {
        assertEquals("FAILED", nav("{\"action\":\"power_off\"}").json.getString("status"));
        assertEquals("FAILED", nav("{\"action\":\"home\",\"times\":3}").json.getString("status"));
        assertEquals("FAILED", nav("{}").json.getString("status"));
        assertTrue(platform.acts.isEmpty());
    }

    private FastTools.Result open(String name) throws Exception {
        return tools.run("app_open", new JSONObject().put("name", name).toString(), media);
    }

    @Test
    public void appOpenStartsTheAppNamedAndSeesItInFront() throws Exception {
        FastTools.Result r = open("maps");
        assertEquals("VERIFIED", r.json.getString("status"));
        assertEquals("Maps", r.json.getString("app"));
        assertEquals(List.of("launch com.google.android.apps.maps"), platform.acts);
        assertTrue(FastTools.changesState("app_open"));
        assertEquals("an exact label wins over one that contains it", "VERIFIED", open("Camera").json.getString("status"));
        assertEquals("launch com.android.camera", platform.acts.get(1));
        assertEquals("accents and punctuation are ignored", "VERIFIED", open("cafe finder!").json.getString("status"));
        assertEquals("a word of the label is enough when only one has it", "VERIFIED", open("finder").json.getString("status"));
    }

    @Test
    public void anAmbiguousNameStartsNothingAndListsTheCandidates() throws Exception {
        platform.apps.add(new String[] {"Camera", "com.example.othercam", "com.example.othercam.Main"});
        FastTools.Result r = open("camera");
        assertEquals("FAILED", r.json.getString("status"));
        assertEquals(2, r.json.getJSONArray("candidates").length());
        assertTrue(platform.acts.isEmpty());
        platform.apps.remove(platform.apps.size() - 1);
        for (int i = 0; i < 8; i++) {
            platform.apps.add(new String[] {"Notes " + i, "com.example.notes" + i, "com.example.Main"});
        }
        FastTools.Result many = open("notes");
        assertEquals(FastTools.MAX_CANDIDATES, many.json.getJSONArray("candidates").length());
        assertTrue(platform.acts.isEmpty());
    }

    @Test
    public void twoMatchingEntriesOfOneAppAreTwoCandidates() throws Exception {
        platform.apps.add(new String[] {"My Notes", "com.example.notes", "com.example.notes.Mine"});
        platform.apps.add(new String[] {"Work Notes", "com.example.notes", "com.example.notes.Work"});
        FastTools.Result r = open("notes");
        assertEquals("FAILED", r.json.getString("status"));
        assertEquals(2, r.json.getJSONArray("candidates").length());
        assertTrue("nothing started", platform.acts.isEmpty());
        assertEquals("the exact label starts that entry", "VERIFIED", open("work notes").json.getString("status"));
    }

    @Test
    public void noMatchOrAFailedStartIsFailed() throws Exception {
        assertEquals("FAILED", open("Spreadsheets").json.getString("status"));
        assertEquals("a part of a word is not a match", "FAILED", open("clo").json.getString("status"));
        assertTrue(platform.acts.isEmpty());
        platform.launchWorks = false;
        FastTools.Result r = open("clock");
        assertEquals("FAILED", r.json.getString("status"));
        assertEquals("the app could not be started", r.json.getString("detail"));
    }

    @Test
    public void startedButNotSeenInFrontIsUnverified() throws Exception {
        platform.launchShows = false; // e.g. the system blocked the start, or the screen cannot be read
        FastTools.Result r = open("settings");
        assertEquals("UNVERIFIED", r.json.getString("status"));
        assertEquals(List.of("launch com.android.settings"), platform.acts);
    }

    @Test
    public void appOpenRefusesBadArguments() throws Exception {
        assertEquals("FAILED", tools.run("app_open", "{}", media).json.getString("status"));
        assertEquals("FAILED", tools.run("app_open", "{\"name\":\"Maps\",\"as_admin\":true}", media).json.getString("status"));
        assertEquals("FAILED", open("x".repeat(200)).json.getString("status"));
        assertEquals("FAILED", open("  !! ").json.getString("status"));
        assertTrue(platform.acts.isEmpty());
    }

    @Test
    public void normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("cafe finder", FastTools.normalize("  Café—Finder! "));
        assertEquals("", FastTools.normalize(null));
    }
}
