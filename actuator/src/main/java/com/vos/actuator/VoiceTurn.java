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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
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
 * a sound. {@link Mic#FILE_THEN_LIVE} opens the live mic as in production but sends the pushed PCM
 * in place of its first frames, then the room: a question nobody has to speak, followed by what the
 * mic really hears (#249: VOS's own reply on the speaker). {@code rawMic} (debug) records from
 * {@code MIC} with no AEC or NS, the control for that echo test. Release builds never get those
 * flags (TurnService).
 */
final class VoiceTurn {
    static final String TAG = "VOS_TURN";

    /** Where the turn's mic audio comes from; anything but LIVE is debug-only (TurnService). */
    enum Mic { LIVE, FILE, FILE_THEN_LIVE }

    private static final int RATE = Ipc.TURN_SAMPLE_RATE;
    private static final int FRAME = Ipc.TURN_FRAME_BYTES;

    final String turnId;
    final boolean speakerToFile;
    private final Context context;
    private final Mic mic;
    private final boolean rawMic;
    private final Consumer<String> ender;
    private final long openedAt = SystemClock.elapsedRealtime();

    private ParcelFileDescriptor micRead;
    private ParcelFileDescriptor micWrite;
    private ParcelFileDescriptor speakerRead;
    private ParcelFileDescriptor speakerWrite;
    private final String source;
    private final long tEvent; // uptimeMillis when VOS got the summon (tile click, assist onShow), or -1
    private volatile boolean running = true;
    /** The live capture: opened before the AudioPlane took the mode (#262), or by start(); released by close(). */
    private Capture live;
    private Thread capture;
    private Thread playback;
    private PeerLink link;
    private volatile IVosSession session;
    /** The playing track; set and released by play() under {@link #trackLock}, only paused by close(). */
    private AudioTrack track;
    private final Object trackLock = new Object();
    /** Reply bytes handed to the speaker so far (debug: the mic level line's played_bytes, #249). */
    private final AtomicLong played = new AtomicLong();

    /** {@code opened}: the live capture MicFirst opened for this turn, or null (the turn opens its own). */
    VoiceTurn(Context context, String turnId, String source, long tEvent, Mic mic, boolean rawMic, boolean speakerToFile,
            Capture opened, Consumer<String> ender) {
        this.context = context;
        this.turnId = turnId;
        this.source = source;
        this.tEvent = tEvent;
        this.mic = mic;
        this.rawMic = rawMic;
        this.live = opened; // held from here, so close() releases it even if start() fails
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
        if (this.mic == Mic.FILE) {
            byte[] pcm = injected();
            capture = new Thread(() -> fileMic(pcm, micOut), "vos-mic-file");
        } else {
            byte[] prefix = this.mic == Mic.FILE_THEN_LIVE ? injected() : null;
            if (live == null) { // not opened before the mode (MicFirst fell back to the old order)
                live = Capture.open(rawMic);
            }
            Log.i(TAG, "capture turnId=" + turnId + " " + live.describe());
            capture = new Thread(() -> liveMic(micOut, prefix), "vos-mic");
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
        if (live != null) {
            live.stop(); // unblocks the capture thread's read
            join(capture);
            live.release();
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

    /**
     * A turn's live capture: {@code VOICE_COMMUNICATION} with AEC and NS (debug {@code raw}: {@code MIC}
     * without them), built and started. MicFirst opens it before the AudioPlane takes the call-style
     * mode and device (#262): opened after them it starts about 400 ms later on Phone B, opened first
     * it loses no audio when the mode changes under it (S-07).
     */
    static final class Capture {
        final AudioRecord record;
        final long tRecording; // uptimeMillis when startRecording returned
        private final boolean raw;
        private final List<AudioEffect> effects;

        private Capture(AudioRecord record, boolean raw, List<AudioEffect> effects, long tRecording) {
            this.record = record;
            this.raw = raw;
            this.effects = effects;
            this.tRecording = tRecording;
        }

        /** Builds and starts the capture; throws (with nothing left open) if the mic cannot. */
        static Capture open(boolean raw) throws IOException {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r;
            try {
                r = new AudioRecord.Builder()
                        .setAudioSource(raw ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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
            List<AudioEffect> effects = new ArrayList<>();
            try {
                int session = r.getAudioSessionId();
                if (AcousticEchoCanceler.isAvailable() && !raw) {
                    enable(AcousticEchoCanceler.create(session), effects);
                }
                if (NoiseSuppressor.isAvailable() && !raw) {
                    enable(NoiseSuppressor.create(session), effects);
                }
                r.startRecording();
            } catch (RuntimeException e) { // e.g. startRecording's IllegalStateException
                for (AudioEffect fx : effects) {
                    fx.release();
                }
                r.release();
                throw new IOException("mic did not start: " + e.getClass().getSimpleName(), e);
            }
            return new Capture(r, raw, effects, SystemClock.uptimeMillis());
        }

        private static void enable(AudioEffect e, List<AudioEffect> effects) {
            if (e != null) {
                e.setEnabled(true);
                effects.add(e);
            }
        }

        /** Whether an effect of this class was created and enabled for the capture. */
        private boolean enabled(Class<? extends AudioEffect> type) {
            for (AudioEffect e : effects) {
                if (type.isInstance(e) && e.getEnabled()) {
                    return true;
                }
            }
            return false;
        }

        /** For the capture log line: the source, AEC and NS, and the input device. */
        String describe() {
            AudioDeviceInfo in = record.getRoutedDevice();
            return "source=" + (raw ? "MIC" : "VOICE_COMMUNICATION") + " aec=" + enabled(AcousticEchoCanceler.class)
                    + " ns=" + enabled(NoiseSuppressor.class) + " inDev=" + (in == null ? "none" : in.getType());
        }

        /** Stops recording, so a blocked read returns. */
        void stop() {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
        }

        /** Releases the record and its effects (after stop()). */
        void release() {
            for (AudioEffect e : effects) {
                e.release();
            }
            record.release();
        }
    }

    /** Debug: the pushed PCM ({@code files/debug/mic-inject.pcm}). */
    private byte[] injected() throws IOException {
        return Files.readAllBytes(new File(context.getFilesDir(), "debug/mic-inject.pcm").toPath());
    }


    /** Milliseconds from VOS getting the summon to {@code t}, or -1 if either is unknown. */
    private long since(long t) {
        return tEvent < 0 || t < 0 ? -1 : t - tEvent;
    }

    private static boolean nonZero(byte[] buf, int n) {
        for (int k = 0; k < n; k++) {
            if (buf[k] != 0) {
                return true;
            }
        }
        return false;
    }

    /** The live mic; with a {@code prefix} (debug), its bytes go out in place of the first frames read. */
    private void liveMic(OutputStream out, byte[] prefix) {
        byte[] buf = new byte[FRAME];
        boolean heard = false;
        int prefixOff = prefix == null ? -1 : 0;
        int prefixLen = prefix == null ? 0 : prefix.length & ~1; // whole 16-bit samples only
        double sumSq = 0;
        int samples = 0;
        long lastPlayed = 0;
        try {
            while (running) {
                int n = live.record.read(buf, 0, FRAME, AudioRecord.READ_BLOCKING);
                if (n > 0) {
                    if (!heard && nonZero(buf, n)) { // S-07: VOS getting the summon to the first non-zero mic buffer
                        heard = true;
                        long now = SystemClock.uptimeMillis();
                        Log.i(TAG, "summon source=" + source + " turnId=" + turnId + " first_nonzero_ms="
                                + (tEvent < 0 ? -1 : now - tEvent) + " to_start_command_ms=" + since(TurnService.tStartCommand)
                                + " to_foreground_ms=" + since(TurnService.tForeground) + " to_plane_ms=" + since(TurnService.tPlane)
                                + " to_recording_ms=" + since(live.tRecording));
                    }
                    if (BuildConfig.DEBUG) { // #249: the live mic's level each second, and the reply audio played meanwhile
                        for (int k = 0; k + 1 < n; k += 2) {
                            int v = (short) ((buf[k] & 0xff) | (buf[k + 1] << 8));
                            sumSq += (double) v * v;
                        }
                        samples += n / 2;
                        if (samples >= RATE) {
                            long p = played.get();
                            double rms = Math.sqrt(sumSq / samples);
                            Log.i(TAG, "mic level turnId=" + turnId + " t_ms=" + (SystemClock.elapsedRealtime() - openedAt)
                                    + " dbfs=" + String.format(Locale.ROOT, "%.1f", 20 * Math.log10(Math.max(rms, 1) / 32768.0))
                                    + " played_bytes=" + (p - lastPlayed));
                            lastPlayed = p;
                            sumSq = 0;
                            samples = 0;
                        }
                    }
                    if (prefixOff >= 0) { // the rest of a frame the prefix ends in stays live
                        int k = Math.min(n, prefixLen - prefixOff);
                        System.arraycopy(prefix, prefixOff, buf, 0, k);
                        prefixOff += k;
                        if (prefixOff >= prefixLen) {
                            prefixOff = -1;
                            Log.i(TAG, "turn " + turnId + " mic prefix done, live from here");
                        }
                    }
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
                played.addAndGet(n);
                if (sink != null) {
                    sink.write(buf, 0, n);
                } else {
                    for (int off = 0; off < n && running; ) { // a short write is retried, an error ends the turn
                        int w = playing.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING);
                        if (w < 0) {
                            if (running) {
                                ender.accept("speaker write error " + w);
                            }
                            return;
                        }
                        off += w;
                    }
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
