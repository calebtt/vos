package com.vos.actuator;

import static org.junit.Assert.assertEquals;

import android.media.AudioDeviceInfo;
import org.junit.Test;

/** The communication-device preference (the pure part of AndroidAudioPlatform). */
public class AndroidAudioPlatformTest {
    private static final int SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;

    @Test
    public void whateverTheOwnerWearsBeatsTheLoudspeaker() {
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AndroidAudioPlatform.pick(new int[] {SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES}));
        assertEquals(AudioDeviceInfo.TYPE_HEARING_AID,
                AndroidAudioPlatform.pick(new int[] {SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID}));
        assertEquals(AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AndroidAudioPlatform.pick(new int[] {SPEAKER, AudioDeviceInfo.TYPE_BLE_SPEAKER}));
    }

    @Test
    public void aHeadsetWithAMicBeatsOutputOnlyDevices() {
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AndroidAudioPlatform.pick(new int[] {
            SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO}));
    }

    @Test
    public void theSpeakerOnlyWhenNothingElseAndNothingWhenNothingAtAll() {
        assertEquals(SPEAKER, AndroidAudioPlatform.pick(new int[] {SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE}));
        assertEquals(-1, AndroidAudioPlatform.pick(new int[] {}));
    }
}
