package com.vos.actuator;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Earcons v0 (P3): the actuator, not the model, says whether an act happened. VERIFIED is two rising
 * tones, UNVERIFIED one low tone, FAILED two falling tones; short, quiet, as {@code USAGE_ASSISTANT}
 * on the communication device. When the turn's speaker is a debug file nothing is played and the
 * earcon is only logged, so file-speaker tests stay silent.
 */
final class Earcons {
    private static final int RATE = 16000;

    private Earcons() {
    }

    /** Tracks still playing, so they are not collected mid-tone. */
    private static final java.util.Set<AudioTrack> PLAYING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Plays the earcon for {@code status}; never throws, so the tool's result and audit line always follow. */
    static void play(Context context, FastTools.Status status, boolean silent) {
        if (silent) {
            Log.i(VoiceTurn.TAG, "earcon " + status + " (file speaker: not played)");
            return;
        }
        short[] pcm;
        switch (status) {
            case VERIFIED:
                pcm = tones(new int[] {880, 1320}, 70);
                break;
            case UNVERIFIED:
                pcm = tones(new int[] {440}, 160);
                break;
            default:
                pcm = tones(new int[] {660, 330}, 90);
                break;
        }
        AudioTrack t = null;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            AudioDeviceInfo dev = context.getSystemService(AudioManager.class).getCommunicationDevice();
            if (dev != null) {
                t.setPreferredDevice(dev);
            }
            t.write(pcm, 0, pcm.length);
            t.setNotificationMarkerPosition(pcm.length);
            t.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override
                public void onMarkerReached(AudioTrack track) {
                    done(track);
                }

                @Override
                public void onPeriodicNotification(AudioTrack track) {
                }
            });
            // Nothing else holds the track while it plays, and an unreferenced AudioTrack can be
            // collected (its finalizer releases the native track) mid-tone. Held until it is done.
            PLAYING.add(t);
            AudioTrack held = t;
            MAIN.postDelayed(() -> done(held), pcm.length * 1000L / RATE + 1000); // if the marker never comes
            t.play();
            Log.i(VoiceTurn.TAG, "earcon " + status);
        } catch (RuntimeException e) { // the verdict still goes back; only the tone is lost
            Log.w(VoiceTurn.TAG, "earcon " + status + " not played: " + e.getClass().getSimpleName());
            if (t != null) {
                done(t);
            }
        }
    }

    private static void done(AudioTrack t) {
        if (PLAYING.remove(t)) {
            t.release();
        }
    }

    /** Sine tones in sequence, each {@code ms} long with a 5 ms fade, at a modest level. */
    static short[] tones(int[] hz, int ms) {
        int per = RATE * ms / 1000;
        int fade = RATE * 5 / 1000;
        short[] out = new short[per * hz.length];
        for (int k = 0; k < hz.length; k++) {
            for (int i = 0; i < per; i++) {
                double env = Math.min(1.0, Math.min(i, per - 1 - i) / (double) fade);
                out[k * per + i] = (short) (6000 * env * Math.sin(2 * Math.PI * hz[k] * i / RATE));
            }
        }
        return out;
    }
}
