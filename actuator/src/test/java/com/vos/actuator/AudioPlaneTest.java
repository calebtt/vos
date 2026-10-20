package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AudioPlaneTest {
    private static final int MODE_NORMAL = 0;
    private static final String MUSIC = "com.example.music";

    /** Records every platform call; media state is a single player. */
    private static final class FakePlatform implements AudioPlane.Platform {
        boolean call;
        int mode = MODE_NORMAL;
        String commDevice;
        boolean headset;
        boolean focus;
        String playing; // package whose session is playing, or null
        final List<String> calls = new ArrayList<>();
        final List<Runnable> scheduled = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public boolean callActive() {
            return call;
        }

        @Override
        public int mode() {
            return mode;
        }

        @Override
        public void setMode(int m) {
            calls.add("setMode " + m);
            mode = m;
        }

        @Override
        public String setCommunicationDevice() {
            commDevice = headset ? "BLUETOOTH_SCO" : "BUILTIN_SPEAKER";
            calls.add("setCommunicationDevice " + commDevice);
            return commDevice;
        }

        @Override
        public void clearCommunicationDevice() {
            calls.add("clearCommunicationDevice");
            commDevice = null;
        }

        @Override
        public String inputDevice() {
            return headset ? "BLUETOOTH_SCO" : "BUILTIN_MIC";
        }

        @Override
        public boolean requestFocus() {
            calls.add("requestFocus");
            focus = true;
            return true;
        }

        @Override
        public void abandonFocus() {
            calls.add("abandonFocus");
            focus = false;
        }

        @Override
        public String playingMediaPackage() {
            return playing;
        }

        @Override
        public boolean pauseMedia(String pkg) {
            calls.add("pause " + pkg);
            if (pkg.equals(playing)) {
                playing = null;
            }
            return true;
        }

        @Override
        public boolean playMedia(String pkg) {
            calls.add("play " + pkg);
            playing = pkg;
            return true;
        }

        @Override
        public boolean isMediaPlaying(String pkg) {
            return pkg.equals(playing);
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            scheduled.add(task);
            delays.add(delayMs);
        }

        void runScheduled() {
            List<Runnable> now = new ArrayList<>(scheduled);
            scheduled.clear();
            now.forEach(Runnable::run);
        }
    }

    private final FakePlatform platform = new FakePlatform();
    private final List<Map<String, Object>> audit = new ArrayList<>();
    private final AudioPlane plane = new AudioPlane(platform, audit::add);

    private Map<String, Object> last() {
        return audit.get(audit.size() - 1);
    }

    @Test
    public void aTurnGoesIdleCaptureConverseRestoreIdleWithOneAuditLineEach() {
        String id = plane.summon("qs_tile");
        assertNotNull(id);
        assertEquals(AudioPlane.State.CAPTURE, plane.state());
        assertTrue(plane.ready(id));
        assertEquals(AudioPlane.State.CONVERSE, plane.state());
        assertTrue(plane.end(id, "idle"));
        assertEquals(AudioPlane.State.IDLE, plane.state());

        List<String> states = new ArrayList<>();
        for (Map<String, Object> l : audit) {
            assertEquals("audio.transition", l.get("event"));
            for (String k : List.of("state", "focus", "mode", "inDev", "outDev", "pausedPkg", "mixes", "turnId", "reason")) {
                assertTrue(k + " missing", l.containsKey(k));
            }
            assertEquals(id, l.get("turnId"));
            states.add((String) l.get("state"));
        }
        assertEquals(List.of("CAPTURE", "CONVERSE", "RESTORE", "IDLE"), states);
        assertEquals("qs_tile", audit.get(0).get("reason"));
        assertEquals("GAIN_TRANSIENT", audit.get(0).get("focus"));
        assertEquals(AudioPlane.MODE_IN_COMMUNICATION, audit.get(0).get("mode"));
        assertEquals("BUILTIN_SPEAKER", audit.get(0).get("outDev"));
        assertEquals("BUILTIN_MIC", audit.get(0).get("inDev"));
        assertNull(plane.turnId());
    }

    @Test
    public void restoreUndoesEverythingTheTurnChanged() {
        platform.mode = 7; // whatever the phone was in
        platform.headset = true;
        String id = plane.summon("debug");
        assertEquals(AudioPlane.MODE_IN_COMMUNICATION, platform.mode);
        assertEquals("BLUETOOTH_SCO", platform.commDevice);
        assertTrue(platform.focus);
        plane.end(id, "stop");
        assertEquals(7, platform.mode);
        assertNull(platform.commDevice);
        assertFalse(platform.focus);
        assertEquals(List.of("requestFocus", "setMode 3", "setCommunicationDevice BLUETOOTH_SCO",
                "setMode 7", "clearCommunicationDevice", "abandonFocus"), platform.calls);
    }

    @Test
    public void mediaPausedByTheTurnResumesAtRestore() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        assertNull(platform.playing);
        assertEquals(MUSIC, audit.get(0).get("pausedPkg"));
        plane.end(id, "idle");
        assertEquals(MUSIC, platform.playing);
        assertEquals("restored, resumed " + MUSIC, last().get("reason"));
        assertTrue(platform.scheduled.isEmpty());
    }

    @Test
    public void mediaTheOwnerPausedDuringTheTurnStaysPausedEvenIfThePlayerResumesItself() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        plane.ownerPausedMedia(id); // "pause" said during the turn
        plane.end(id, "idle");
        assertNull(platform.playing);
        assertFalse(platform.calls.contains("play " + MUSIC));
        assertEquals(List.of(600L, 2000L), platform.delays);

        platform.playing = MUSIC; // the player's own resume on focus regain
        platform.runScheduled();
        assertNull(platform.playing);
        assertEquals("audio.media_repaused", last().get("event"));
        assertEquals(id, last().get("turnId"));
    }

    @Test
    public void mediaThatWasNotPlayingIsNotStarted() {
        String id = plane.summon("qs_tile");
        plane.end(id, "idle");
        assertNull(platform.playing);
        assertFalse(platform.calls.stream().anyMatch(c -> c.startsWith("play ") || c.startsWith("pause ")));
    }

    @Test
    public void ownerPlayDuringTheTurnHandsMediaBackToTheOwner() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        plane.ownerResumedMedia(id);
        platform.calls.clear();
        plane.end(id, "idle");
        assertFalse(platform.calls.contains("play " + MUSIC));
    }

    @Test
    public void aSummonDuringACallIsRefusedAndChangesNothing() {
        platform.call = true;
        platform.playing = MUSIC;
        assertNull(plane.summon("qs_tile"));
        assertEquals(AudioPlane.State.IDLE, plane.state());
        assertTrue(platform.calls.isEmpty());
        assertEquals("audio.refused", last().get("event"));
        assertEquals("summon", last().get("op"));
    }

    @Test
    public void aSecondSummonWhileATurnIsOpenIsRefused() {
        String id = plane.summon("qs_tile");
        assertNull(plane.summon("debug"));
        assertEquals(id, plane.turnId());
        assertEquals("audio.refused", last().get("event"));
    }

    @Test
    public void wrongTurnIdsAreRefusedAndEndIsIdempotent() {
        String id = plane.summon("qs_tile");
        assertFalse(plane.ready("other"));
        assertFalse(plane.end("other", "x"));
        assertEquals(AudioPlane.State.CAPTURE, plane.state());
        assertTrue(plane.end(id, "stop"));
        int lines = audit.size();
        assertFalse(plane.end(id, "stop"));
        assertFalse(plane.ready(id));
        assertEquals(lines + 2, audit.size());
        assertEquals("audio.refused", last().get("event"));
    }

    @Test
    public void eachTurnGetsAFreshId() {
        String a = plane.summon("qs_tile");
        plane.end(a, "idle");
        String b = plane.summon("qs_tile");
        assertNotEquals(a, b);
    }

    @Test
    public void aRepauseCheckDoesNotTouchALaterTurn() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        plane.ownerPausedMedia(id);
        plane.end(id, "idle");
        String next = plane.summon("qs_tile");
        platform.playing = MUSIC;
        platform.runScheduled();
        assertEquals(MUSIC, platform.playing);
        assertEquals(next, plane.turnId());
    }
}
