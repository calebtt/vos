package com.vos.actuator;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import com.vos.ipc.IVosSession;
import com.vos.ipc.Ipc;
import com.vos.ipc.PeerLink;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One open voice turn's audio and its link to the session (§3.2): the mic is captured from the
 * summon on into the mic pipe (so words spoken while the session connects are kept), and the
 * session's reply audio is played from the speaker pipe as {@code USAGE_ASSISTANT} on the
 * communication device the AudioPlane chose.
 *
 * <p>Debug builds can replace the mic with a pushed PCM file ({@code files/debug/mic-inject.pcm},
 * played in real time and followed by silence, like a quiet mic) and the speaker with a file
 * ({@code files/debug/speaker-<turnId>.pcm}), so a turn can be tested without speaking or making
 * a sound. Release builds never get those flags (TurnService).
 */
final class VoiceTurn {
    static final String TAG = "VOS_TURN";
    private static final int RATE = Ipc.TURN_SAMPLE_RATE;
    private static final int FRAME = Ipc.TURN_FRAME_BYTES;

    final String turnId;
    private final Context context;
    private final boolean micFromFile;
    private final boolean speakerToFile;
    private final Consumer<String> ender;
    private final long openedAt = SystemClock.elapsedRealtime();
    private final List<AudioEffect> effects = new ArrayList<>();

    private ParcelFileDescriptor micRead;
    private ParcelFileDescriptor micWrite;
    private ParcelFileDescriptor speakerRead;
    private ParcelFileDescriptor speakerWrite;
    private volatile boolean running = true;
    private AudioRecord record;
    private Thread capture;
    private Thread playback;
    private PeerLink link;
    private volatile IVosSession session;
    /** The playing track; set and released by play() under {@link #trackLock}, only paused by close(). */
    private AudioTrack track;
    private final Object trackLock = new Object();

    VoiceTurn(Context context, String turnId, boolean micFromFile, boolean speakerToFile, Consumer<String> ender) {
        this.context = context;
        this.turnId = turnId;
        this.micFromFile = micFromFile;
        this.speakerToFile = speakerToFile;
        this.ender = ender;
    }

    /** Opens the pipes, starts capture and playback, and binds the session. Throws if the mic cannot open. */
    void start() throws IOException {
        ParcelFileDescriptor[] mic = ParcelFileDescriptor.createReliablePipe();
        ParcelFileDescriptor[] speaker = ParcelFileDescriptor.createReliablePipe();
        micRead = mic[0];
        micWrite = mic[1];
        speakerRead = speaker[0];
        speakerWrite = speaker[1];
        OutputStream micOut = new ParcelFileDescriptor.AutoCloseOutputStream(micWrite);
        if (micFromFile) {
            byte[] pcm = Files.readAllBytes(new File(context.getFilesDir(), "debug/mic-inject.pcm").toPath());
            capture = new Thread(() -> fileMic(pcm, micOut), "vos-mic-file");
        } else {
            record = openRecord();
            capture = new Thread(() -> liveMic(micOut), "vos-mic");
        }
        capture.start();
        playback = new Thread(this::play, "vos-speaker");
        playback.start();
        link = new PeerLink(context, Ipc.sessionIntent(), Ipc.sessionHandshake(), new PeerLink.Listener() {
            @Override
            public void onPeerUp(IBinder binder) {
                openInSession(binder);
            }

            @Override
            public void onPeerDown(String why) {
                ender.accept("session down: " + why);
            }
        });
        if (!link.bind()) {
            throw new IOException("session service not found");
        }
    }

    /**
     * Stops capture first (the mic is released before anything else), closes the pipes, tells the
     * session, and stops playback. Safe to call more than once.
     */
    void close(String reason) {
        if (!running) {
            return;
        }
        running = false;
        long t0 = SystemClock.elapsedRealtime();
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // never started
            }
            join(capture);
            for (AudioEffect e : effects) {
                e.release();
            }
            record.release();
            Log.i(TAG, "AudioRecord.release turnId=" + turnId + " ms=" + (SystemClock.elapsedRealtime() - t0));
        } else {
            join(capture);
        }
        closeQuietly(micWrite);
        synchronized (trackLock) { // silence now; the playback thread stops at its next write
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                } catch (IllegalStateException ignored) {
                    // not playing yet
                }
            }
        }
        IVosSession s = session;
        if (s != null) {
            try {
                s.onTurnClosed(turnId, reason);
            } catch (RemoteException ignored) {
                // the session is gone; its pipes break on their own
            }
        }
        // Playback ends at EOF: our own write end (if the session never took it) closes here, the
        // session's on onTurnClosed or when it dies.
        closeQuietly(speakerWrite);
        closeQuietly(micRead);
        join(playback);
        closeQuietly(speakerRead);
        if (link != null) {
            link.unbind();
        }
    }

    private void openInSession(IBinder binder) {
        IVosSession s = IVosSession.Stub.asInterface(binder);
        try {
            s.onTurnOpened(turnId, micRead, speakerWrite);
            session = s;
            Log.i(TAG, "turn opened in session turnId=" + turnId + " after_ms=" + (SystemClock.elapsedRealtime() - openedAt));
        } catch (RemoteException | SecurityException e) {
            ender.accept("session refused the turn: " + e.getClass().getSimpleName());
            return;
        }
        // The session holds its own copies now (Binder duplicated them).
        closeQuietly(micRead);
        closeQuietly(speakerWrite);
        micRead = null;
        speakerWrite = null;
    }

    private AudioRecord openRecord() throws IOException {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r;
        try {
            r = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, FRAME * 10))
                    .build();
        } catch (SecurityException | UnsupportedOperationException e) {
            throw new IOException("mic unavailable: " + e.getClass().getSimpleName(), e);
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IOException("mic did not initialize");
        }
        int session = r.getAudioSessionId();
        if (AcousticEchoCanceler.isAvailable()) {
            enable(AcousticEchoCanceler.create(session));
        }
        if (NoiseSuppressor.isAvailable()) {
            enable(NoiseSuppressor.create(session));
        }
        r.startRecording();
        return r;
    }

    private void enable(AudioEffect e) {
        if (e != null) {
            e.setEnabled(true);
            effects.add(e);
        }
    }

    private void liveMic(OutputStream out) {
        byte[] buf = new byte[FRAME];
        try {
            while (running) {
                int n = record.read(buf, 0, FRAME, AudioRecord.READ_BLOCKING);
                if (n > 0) {
                    out.write(buf, 0, n);
                } else if (n < 0) { // the record was invalidated (audio server restart, preemption)
                    if (running) {
                        ender.accept("mic read error " + n);
                    }
                    return;
                }
            }
        } catch (IOException e) {
            if (running) {
                ender.accept("mic pipe closed");
            }
        }
    }

    /** Debug: the pushed PCM in real time, then silence, like a quiet mic. */
    private void fileMic(byte[] pcm, OutputStream out) {
        byte[] silence = new byte[FRAME];
        long next = SystemClock.elapsedRealtimeNanos();
        try {
            for (int off = 0; running; off += FRAME) {
                if (off < pcm.length) {
                    out.write(pcm, off, Math.min(FRAME, pcm.length - off));
                } else {
                    out.write(silence);
                }
                next += 20_000_000L;
                long wait = (next - SystemClock.elapsedRealtimeNanos()) / 1_000_000L;
                if (wait > 0) {
                    Thread.sleep(wait);
                }
            }
        } catch (IOException e) {
            if (running) {
                ender.accept("mic pipe closed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void play() {
        byte[] buf = new byte[FRAME * 5];
        AudioTrack playing = null;
        OutputStream sink = null;
        long bytes = 0;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(speakerRead)) {
            int n;
            while ((n = in.read(buf)) > 0 && running) {
                if (bytes == 0) {
                    Log.i(TAG, "first reply audio turnId=" + turnId + " after_ms=" + (SystemClock.elapsedRealtime() - openedAt));
                    if (speakerToFile) {
                        File dir = new File(context.getFilesDir(), "debug");
                        dir.mkdirs();
                        sink = new FileOutputStream(new File(dir, "speaker-" + turnId + ".pcm"));
                    } else {
                        synchronized (trackLock) {
                            if (!running) {
                                break;
                            }
                            playing = openTrack();
                            track = playing;
                            playing.play();
                        }
                    }
                }
                bytes += n;
                if (sink != null) {
                    sink.write(buf, 0, n);
                } else {
                    playing.write(buf, 0, n, AudioTrack.WRITE_BLOCKING);
                }
            }
        } catch (IOException e) {
            // speaker pipe closed by close() or by the session
        } catch (RuntimeException e) { // AudioTrack could not be built or written
            if (running) {
                ender.accept("speaker error: " + e.getClass().getSimpleName());
            }
        } finally {
            synchronized (trackLock) {
                if (playing != null) {
                    try {
                        playing.stop();
                    } catch (IllegalStateException ignored) {
                        // already stopped
                    }
                    playing.release();
                    track = null;
                }
            }
            closeQuietly(sink);
            Log.i(TAG, "speaker done turnId=" + turnId + " bytes=" + bytes);
        }
    }

    private AudioTrack openTrack() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(min, FRAME * 8))
                .build();
        AudioDeviceInfo dev = context.getSystemService(AudioManager.class).getCommunicationDevice();
        if (dev != null) {
            t.setPreferredDevice(dev);
        }
        return t;
    }

    private static void join(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
