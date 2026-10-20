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
        boolean failSetMode; // the audio server died under RESTORE
        boolean failMode;
        boolean failMedia;
        boolean ringOnFocus; // a call starts ringing while summon is taking audio
        boolean voipOnFocus; // a VoIP app takes MODE_IN_COMMUNICATION while summon is taking audio

        @Override
        public boolean callActive() {
            mode(); // as on the device: AudioManager.getMode()
            return call;
        }

        @Override
        public int mode() {
            if (failMode) {
                throw new RuntimeException(new android.os.DeadObjectException());
            }
            return mode;
        }

        @Override
        public void setMode(int m) {
            if (failSetMode) {
                throw new RuntimeException(new android.os.DeadObjectException());
            }
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
            if (ringOnFocus) {
                mode = 1; // MODE_RINGTONE, set by Telecom from its own process
            }
            if (voipOnFocus) {
                mode = AudioPlane.MODE_IN_COMMUNICATION; // another app's VoIP call
            }
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
            if (failMedia) {
                throw new IllegalStateException("media session gone");
            }
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
    public void aRepauseCheckDoesNothingOnceALaterTurnHasRunEvenIfItEnded() {
        // Grok Build's sequence: A pauses, the owner pauses during A, A ends and arms the re-checks;
        // turn B runs and ends while the music is paused; the owner starts the music again.
        platform.playing = MUSIC;
        String a = plane.summon("qs_tile");
        plane.ownerPausedMedia(a);
        plane.end(a, "idle");
        String b = plane.summon("qs_tile");
        plane.end(b, "idle");
        platform.playing = MUSIC; // the owner's own play, after B
        platform.runScheduled();  // A's re-checks fire now
        assertEquals(MUSIC, platform.playing);
        assertFalse("A's re-check must not pause music after turn B",
                audit.stream().anyMatch(l -> "audio.media_repaused".equals(l.get("event"))));
    }

    @Test
    public void aCallThatStartsDuringTheTurnKeepsItsModeAtRestore() {
        platform.mode = 0;
        String id = plane.summon("qs_tile");
        platform.mode = 2; // MODE_IN_CALL: the call took the mode
        platform.calls.clear();
        assertTrue(plane.end(id, "call started"));
        assertEquals(2, platform.mode);
        assertEquals(List.of("clearCommunicationDevice", "abandonFocus"), platform.calls);
        assertEquals("restored, mode left to the call", last().get("reason"));
        assertTrue(AudioPlane.isCallMode(1) && AudioPlane.isCallMode(2) && AudioPlane.isCallMode(4));
        assertFalse(AudioPlane.isCallMode(0) || AudioPlane.isCallMode(3));
    }

    @Test
    public void restoreNeverStartsMediaIntoACallButStillDropsVosOwnRequests() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile"); // pauses the music
        platform.mode = 1; // MODE_RINGTONE: a call is ringing
        platform.calls.clear();
        plane.end(id, "call started");
        assertNull("no play into the call", platform.playing);
        assertTrue(platform.scheduled.isEmpty());
        // VOS's communication-device request and focus are its own; the call routes by its own request.
        assertEquals(List.of("clearCommunicationDevice", "abandonFocus"), platform.calls);
        assertEquals("restored, mode left to the call, " + MUSIC + " left paused for the call", last().get("reason"));
    }

    @Test
    public void aFailingUndoStillReachesIdleAndTheNextSummonWorks() {
        String id = plane.summon("qs_tile");
        platform.failSetMode = true;
        assertTrue(plane.end(id, "mic read error -6"));
        assertEquals(AudioPlane.State.IDLE, plane.state());
        assertNull(plane.turnId());
        assertTrue((String) last().get("reason"), ((String) last().get("reason")).endsWith("failed: setMode"));
        assertTrue(platform.calls.contains("clearCommunicationDevice")); // the other undos still ran
        assertTrue(platform.calls.contains("abandonFocus"));
        platform.failSetMode = false;
        platform.mode = MODE_NORMAL; // the audio server restarted; the mode it reports is NORMAL again
        assertNotNull(plane.summon("qs_tile"));
    }

    @Test
    public void aDeadAudioServerAtRestoreStillReachesIdleAndUndoesWhatItCan() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        platform.failMode = true; // every mode read throws, including the audit lines'
        assertTrue(plane.end(id, "mic read error -6"));
        assertEquals(AudioPlane.State.IDLE, plane.state());
        assertNull(plane.turnId());
        assertEquals(-1, last().get("mode"));
        assertTrue((String) last().get("reason"), ((String) last().get("reason")).contains("failed: mode"));
        // A call may have started: the mode is put back (a call's own mode cannot be taken), the
        // device and focus dropped, and the music left paused rather than started into a call.
        assertEquals(List.of("setMode 0", "clearCommunicationDevice", "abandonFocus"),
                platform.calls.subList(platform.calls.size() - 3, platform.calls.size()));
        assertFalse(platform.focus);
        assertNull(platform.commDevice);
        assertNull(platform.playing);
        assertTrue((String) last().get("reason"), ((String) last().get("reason")).contains(MUSIC + " left paused,"));
        platform.failMode = false;
        assertNotNull(plane.summon("qs_tile"));
    }

    @Test
    public void aSummonThatHitsADeadAudioServerOpensNoTurnAndGivesBackWhatItTook() {
        platform.playing = MUSIC;
        platform.failSetMode = true;
        assertNull(plane.summon("qs_tile"));
        assertEquals(AudioPlane.State.IDLE, plane.state());
        assertNull(plane.turnId());
        assertFalse(platform.focus);
        assertEquals("the music this attempt paused plays again", MUSIC, platform.playing);
        assertEquals("audio.refused", last().get("event"));
        assertTrue((String) last().get("reason"), ((String) last().get("reason")).startsWith("audio error"));
        platform.failSetMode = false;
        assertNotNull(plane.summon("qs_tile"));
    }

    @Test
    public void aSummonWhenTheModeCannotBeReadIsRefusedWithoutThrowing() {
        platform.playing = MUSIC;
        platform.failMode = true; // callActive() reads the mode first
        assertNull(plane.summon("qs_tile"));
        assertEquals(AudioPlane.State.IDLE, plane.state());
        assertNull(plane.turnId());
        assertEquals(MUSIC, platform.playing);
        assertTrue(platform.calls.isEmpty()); // nothing was taken
        assertEquals("audio.refused", last().get("event"));
    }

    @Test
    public void aCallThatStartsDuringSummonOpensNoTurn() {
        platform.playing = MUSIC;
        platform.ringOnFocus = true;
        assertNull(plane.summon("qs_tile"));
        assertEquals(AudioPlane.State.IDLE, plane.state());
        assertNull(plane.turnId());
        assertEquals(1, platform.mode); // the call's mode is left alone
        assertFalse(platform.focus);
        assertNull("the paused music is not started into the call", platform.playing);
        assertFalse(platform.calls.stream().anyMatch(c -> c.startsWith("setMode") || c.startsWith("setCommunicationDevice")));
        assertEquals("call started during summon", last().get("reason"));
    }

    @Test
    public void aPauseTakenBeforeTheKeyHoldsWhenTheTurnEndsDuringTheOracle() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile"); // VOS pauses the music
        Runnable undo = plane.ownerPausing(id); // the owner said pause; the oracle is still waiting
        plane.end(id, "stop"); // the owner taps stop meanwhile
        assertNull("RESTORE does not resume what the owner paused", platform.playing);
        assertEquals(List.of(600L, 2000L), platform.delays); // the re-pause checks are armed
        undo.run(); // the oracle's verdict comes after the turn: nothing to take back
        assertEquals(AudioPlane.State.IDLE, plane.state());
    }

    @Test
    public void anOwnerIntentThatDidNotStandIsTakenBack() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        plane.ownerPlaying(id).run(); // "play", but the player stayed paused
        plane.ownerPausing(id).run(); // "pause", but it did not stand either
        plane.end(id, "idle");
        assertEquals("RESTORE still resumes what the turn paused", MUSIC, platform.playing);
    }

    @Test
    public void aPlayThatDidNotStandResumesWhatTheTurnPausedEvenIfTheTurnEndedMeanwhile() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile"); // VOS pauses the music
        Runnable undo = plane.ownerPlaying(id); // "play"; the oracle is still waiting
        plane.end(id, "stop"); // the owner taps stop meanwhile: RESTORE has nothing to resume
        assertNull(platform.playing);
        undo.run(); // the play did not stand
        assertEquals(MUSIC, platform.playing);
        assertEquals("audio.media_resumed", last().get("event"));
    }

    @Test
    public void aPlayThatDidNotStandAfterAnOwnerPauseKeepsThePausedMusicPausedWhenTheTurnEndedMeanwhile() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile"); // VOS pauses the music
        plane.ownerPausing(id); // "pause" stood
        Runnable undo = plane.ownerPlaying(id); // "play"; the oracle is still waiting
        plane.end(id, "stop"); // RESTORE sees no owner pause and no package: it arms nothing
        assertTrue(platform.scheduled.isEmpty());
        undo.run(); // the play did not stand: the owner's pause is what stands
        assertNull("not resumed", platform.playing);
        assertEquals(List.of(600L, 2000L), platform.delays);
        platform.playing = MUSIC; // the player resumes itself on the focus regain
        platform.runScheduled();
        assertNull("re-paused", platform.playing);
    }

    @Test
    public void aPauseAfterAPlayThatStoodStillGuardsAgainstASelfResume() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile"); // VOS pauses the music
        plane.ownerPlaying(id); // "play" stood: the music is the owner's again
        platform.playing = MUSIC;
        plane.ownerPausing(id); // then "pause" stood too
        platform.playing = null;
        plane.end(id, "idle");
        assertNull(platform.playing);
        assertEquals(List.of(600L, 2000L), platform.delays);
        platform.playing = MUSIC; // the player resumes itself on the focus regain
        platform.runScheduled();
        assertNull("re-paused", platform.playing);
    }

    @Test
    public void aPauseWhileThePlayerIsNotPlayingStillGuardsThePackageThePlayTookBack() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile"); // VOS pauses the music
        plane.ownerPlaying(id); // "play" stood (verified on buffering)
        platform.playing = null; // buffering, or already paused: no session in STATE_PLAYING
        plane.ownerPausing(id); // "pause" stood
        plane.end(id, "idle");
        assertEquals(List.of(600L, 2000L), platform.delays);
        platform.playing = MUSIC; // the player resumes itself
        platform.runScheduled();
        assertNull("re-paused", platform.playing);
    }

    @Test
    public void aPauseOfMusicVosDidNotPauseIsAlsoGuardedAndItsUndoPutsTheTurnBack() {
        String id = plane.summon("qs_tile"); // nothing playing at summon
        platform.playing = MUSIC; // the owner started something during the turn
        plane.ownerPausing(id).run(); // a pause that did not stand: no package is kept
        plane.ownerPausing(id); // one that stood
        platform.playing = null;
        plane.end(id, "idle");
        assertEquals(List.of(600L, 2000L), platform.delays);
        assertFalse("RESTORE never plays what the owner paused", platform.calls.contains("play " + MUSIC));
    }

    @Test
    public void aPlayThatDidNotStandDoesNotResumeIntoACallOrAfterALaterTurn() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        Runnable undo = plane.ownerPlaying(id);
        plane.end(id, "stop");
        platform.mode = 2; // a call now has the audio
        undo.run();
        assertNull(platform.playing);
        platform.mode = MODE_NORMAL;
        platform.playing = MUSIC;
        String a = plane.summon("qs_tile");
        Runnable late = plane.ownerPlaying(a);
        plane.end(a, "stop");
        plane.summon("qs_tile"); // a later turn opened before the verdict
        late.run();
        assertNull(platform.playing);
    }

    @Test
    public void aVoipCallThatStartsDuringSummonOpensNoTurn() {
        platform.playing = MUSIC;
        platform.voipOnFocus = true;
        assertNull(plane.summon("qs_tile"));
        assertEquals(AudioPlane.State.IDLE, plane.state());
        assertFalse(platform.focus);
        assertNull(platform.playing);
        assertFalse(platform.calls.stream().anyMatch(c -> c.startsWith("setMode") || c.startsWith("setCommunicationDevice")));
        assertEquals("call started during summon", last().get("reason"));
    }

    @Test
    public void aFailedSummonKeepsTheLastTurnsRepauseChecks() {
        platform.playing = MUSIC;
        String a = plane.summon("qs_tile");
        plane.ownerPausedMedia(a);
        plane.end(a, "idle"); // arms the re-pause checks
        platform.failSetMode = true;
        assertNull(plane.summon("qs_tile")); // fails, no turn
        platform.failSetMode = false;
        platform.playing = MUSIC; // the player resumes itself after focus is given back
        platform.runScheduled();
        assertNull("turn A's re-pause still applies", platform.playing);
    }

    @Test
    public void aRepauseCheckThatHitsADeadMediaSessionDoesNotThrow() {
        platform.playing = MUSIC;
        String id = plane.summon("qs_tile");
        plane.ownerPausedMedia(id);
        plane.end(id, "idle");
        platform.failMedia = true;
        platform.runScheduled(); // on the main thread in the app: must not throw
        assertEquals(AudioPlane.State.IDLE, plane.state());
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
