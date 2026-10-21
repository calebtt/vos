package com.vos.spike.s07;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microphone foreground service: records ~1.5 s, notes the first non-zero buffer, discards the audio.
 * The {@code variant} extra picks the audio path (#261), so each step of VOS's summon can be timed on
 * its own:
 * <ul>
 *   <li>{@code rec}: VOICE_RECOGNITION, normal mode, no effects (the original S-07 capture);
 *   <li>{@code vc}: VOICE_COMMUNICATION, normal mode, no effects;
 *   <li>{@code vc_fx}: VOICE_COMMUNICATION with AEC and NS, normal mode;
 *   <li>{@code mode_vc_fx}: focus, MODE_IN_COMMUNICATION and the speaker as communication device,
 *       then VOICE_COMMUNICATION with AEC and NS (what VOS's AudioPlane and VoiceTurn do today);
 *   <li>{@code vc_fx_then_mode}: the same capture started first, then focus, mode and device on
 *       another thread while it reads;
 *   <li>{@code parallel}: focus, mode and device on another thread while the capture opens.
 * </ul>
 * Each step's time (ms from the summon) goes on the trial line, with the largest gap between reads
 * and the longest all-zero run after the first sound, which show a dropout when the mode changes
 * under a running capture. Focus, mode and device are given back after every trial.
 */
public class MicFgs extends Service {
    private static final String CHANNEL = "s07";
    private static final int RATE = 16000;
    private static final int READ_SAMPLES = 160; // 10 ms
    private static final long RECORD_MS = 1500;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            finish();
            return START_NOT_STICKY;
        }
        String source = intent.getStringExtra(Probe.EXTRA_SOURCE);
        String variant = intent.getStringExtra(Probe.EXTRA_VARIANT);
        String v = variant == null ? Probe.DEFAULT_VARIANT : variant;
        boolean locked = intent.getBooleanExtra(Probe.EXTRA_LOCKED, false);
        long tEvent = intent.getLongExtra(Probe.EXTRA_T_EVENT, -1);
        long tFgs = goForeground(source, v, locked, tEvent);
        if (tFgs < 0) {
            // startForeground threw (logged). The platform may crash this process for not calling
            // startForeground after startForegroundService; the next summon restarts it.
            finish();
            return START_NOT_STICKY;
        }
        new Thread(() -> capture(source, v, locked, tEvent, tFgs), "s07-capture").start();
        return START_NOT_STICKY;
    }

    private long goForeground(String source, String variant, boolean locked, long tEvent) {
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            return SystemClock.uptimeMillis();
        } catch (RuntimeException e) {
            Probe.log(source, variant, locked, tEvent, -1, -1, false, Probe.describe(e), "");
            return -1;
        }
    }

    private void capture(String source, String variant, boolean locked, long tEvent, long tFgs) {
        Marks marks = new Marks(tEvent);
        Plane plane = new Plane(getSystemService(AudioManager.class), marks);
        List<AudioEffect> effects = new ArrayList<>();
        String err = null;
        AudioRecord rec = null;
        Thread planeThread = null;
        try {
            switch (variant) {
                case "rec":
                    rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, false, effects, marks);
                    break;
                case "vc":
                    rec = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, false, effects, marks);
                    break;
                case "vc_fx":
                    rec = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true, effects, marks);
                    break;
                case "mode_vc_fx":
                    plane.take();
                    rec = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true, effects, marks);
                    break;
                case "vc_fx_then_mode":
                    rec = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true, effects, marks);
                    planeThread = plane.takeAsync();
                    break;
                case "parallel":
                    planeThread = plane.takeAsync();
                    rec = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true, effects, marks);
                    break;
                default:
                    throw new IllegalArgumentException("unknown variant " + variant);
            }
            read(rec, marks);
            if (!marks.has("first")) err = "no_nonzero_sample_in_" + RECORD_MS + "ms";
        } catch (RuntimeException e) {
            err = Probe.describe(e);
        } finally {
            if (planeThread != null) {
                try {
                    planeThread.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (rec != null) {
                AudioDeviceInfo routed = rec.getRoutedDevice();
                marks.put("routed", routed == null ? -1 : routed.getType());
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                    // never started
                }
                rec.release();
            }
            for (AudioEffect e : effects) {
                e.release();
            }
            String planeErr = plane.restore();
            if (err == null && planeErr != null) err = planeErr;
        }
        long first = marks.absolute("first");
        Probe.log(source, variant, locked, tEvent, tFgs, first, err == null, err, marks.toString());
        new Handler(Looper.getMainLooper()).post(this::finish);
    }

    /** Builds the record, adds AEC and NS if asked, and starts it; marks build, aec, ns and start. */
    private static AudioRecord open(int source, boolean fx, List<AudioEffect> effects, Marks marks) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, RATE / 5 * 2))
                .build();
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IllegalStateException("AudioRecord not initialized");
        }
        marks.mark("build");
        if (fx) {
            int session = rec.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                enable(AcousticEchoCanceler.create(session), effects);
            }
            marks.mark("aec");
            if (NoiseSuppressor.isAvailable()) {
                enable(NoiseSuppressor.create(session), effects);
            }
            marks.mark("ns");
        }
        rec.startRecording();
        marks.mark("start");
        return rec;
    }

    private static void enable(AudioEffect e, List<AudioEffect> effects) {
        if (e != null) {
            e.setEnabled(true);
            effects.add(e);
        }
    }

    /**
     * Reads for RECORD_MS: marks first; puts max_gap (between reads), zero_run (after first) and
     * short_ms: how much less audio arrived than the time that passed between the first and last
     * read. A reroute that only delays reads keeps it near zero; one that drops audio raises it.
     */
    private static void read(AudioRecord rec, Marks marks) {
        short[] buf = new short[READ_SAMPLES];
        long end = SystemClock.uptimeMillis() + RECORD_MS;
        long last = -1;
        long firstRead = -1;
        long samplesAfterFirst = 0;
        long maxGap = 0;
        long zeroRun = 0;
        long maxZeroRun = 0;
        boolean heard = false;
        while (SystemClock.uptimeMillis() < end) {
            int n = rec.read(buf, 0, buf.length);
            long now = SystemClock.uptimeMillis();
            if (n < 0) throw new IllegalStateException("AudioRecord.read=" + n);
            boolean nonzero = false;
            for (int k = 0; k < n; k++) {
                if (buf[k] != 0) {
                    nonzero = true;
                    break;
                }
            }
            if (!heard && nonzero) {
                heard = true;
                marks.mark("first");
            }
            if (heard) {
                zeroRun = nonzero ? 0 : zeroRun + n;
                maxZeroRun = Math.max(maxZeroRun, zeroRun);
            }
            if (last >= 0) {
                maxGap = Math.max(maxGap, now - last);
                samplesAfterFirst += n;
            } else {
                firstRead = now;
            }
            last = now;
        }
        marks.put("max_gap_ms", maxGap);
        marks.put("short_ms", firstRead < 0 ? -1 : (last - firstRead) - samplesAfterFirst * 1000 / RATE);
        marks.put("zero_run_ms", maxZeroRun * 1000 / RATE);
    }

    private void finish() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Probe.busy.set(false);
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "S07 probe", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("VOS S07 probe recording")
                .setOngoing(true)
                .build();
    }

    /** Step times (name_ms, ms from the summon) and other values, in the order they happened; thread-safe. */
    private static final class Marks {
        private final long tEvent;
        private final Map<String, Long> values = new LinkedHashMap<>();
        private final Map<String, Long> absolute = new LinkedHashMap<>();

        Marks(long tEvent) {
            this.tEvent = tEvent;
        }

        synchronized void mark(String name) {
            long now = SystemClock.uptimeMillis();
            absolute.put(name, now);
            values.put(name + "_ms", now - tEvent);
        }

        /** A value with its full name (a duration ends in _ms). */
        synchronized void put(String name, long value) {
            values.put(name, value);
        }

        synchronized boolean has(String name) {
            return absolute.containsKey(name);
        }

        synchronized long absolute(String name) {
            Long t = absolute.get(name);
            return t == null ? -1 : t;
        }

        @Override
        public synchronized String toString() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : values.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /**
     * VOS's AudioPlane summon, reduced to what costs time: transient focus for voice communication,
     * MODE_IN_COMMUNICATION, and the built-in speaker as communication device. restore() gives all
     * three back.
     */
    private static final class Plane {
        private final AudioManager am;
        private final Marks marks;
        private volatile AudioFocusRequest focus;
        private volatile int savedMode = -1;
        private volatile boolean deviceSet;
        private volatile String asyncErr; // take() on its own thread threw

        Plane(AudioManager am, Marks marks) {
            this.am = am;
            this.marks = marks;
        }

        void take() {
            AudioFocusRequest f = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
            focus = f;
            am.requestAudioFocus(f);
            marks.mark("focus");
            savedMode = am.getMode();
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            marks.mark("mode");
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    deviceSet = am.setCommunicationDevice(d);
                    break;
                }
            }
            marks.mark("device");
        }

        Thread takeAsync() {
            Thread t = new Thread(() -> {
                try {
                    take();
                } catch (RuntimeException e) {
                    asyncErr = "plane:" + Probe.describe(e);
                }
            }, "s07-plane");
            t.start();
            return t;
        }

        /** Gives back what take() took; returns an error description, or null. */
        String restore() {
            try {
                if (deviceSet) am.clearCommunicationDevice();
                if (savedMode >= 0) am.setMode(savedMode);
                if (focus != null) am.abandonAudioFocusRequest(focus);
                return asyncErr;
            } catch (RuntimeException e) {
                return "restore:" + Probe.describe(e);
            }
        }
    }
}
