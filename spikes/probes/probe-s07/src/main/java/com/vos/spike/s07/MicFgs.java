package com.vos.spike.s07;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/** Microphone foreground service: records ~1 s, notes the first non-zero buffer, discards the audio. */
public class MicFgs extends Service {
    private static final String CHANNEL = "s07";
    private static final int RATE = 16000;
    private static final int READ_SAMPLES = 160; // 10 ms
    private static final long RECORD_MS = 1000;

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
        boolean locked = intent.getBooleanExtra(Probe.EXTRA_LOCKED, false);
        long tEvent = intent.getLongExtra(Probe.EXTRA_T_EVENT, -1);
        long tFgs = goForeground(source, locked, tEvent);
        if (tFgs < 0) {
            // startForeground threw (logged). The platform may crash this process for not calling
            // startForeground after startForegroundService; the next summon restarts it.
            finish();
            return START_NOT_STICKY;
        }
        new Thread(() -> capture(source, locked, tEvent, tFgs), "s07-capture").start();
        return START_NOT_STICKY;
    }

    private long goForeground(String source, boolean locked, long tEvent) {
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            return SystemClock.uptimeMillis();
        } catch (RuntimeException e) {
            Probe.log(source, locked, tEvent, -1, -1, false, Probe.describe(e));
            return -1;
        }
    }

    private void capture(String source, boolean locked, long tEvent, long tFgs) {
        long tFirst = -1;
        String err = null;
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            short[] buf = new short[READ_SAMPLES];
            rec.startRecording();
            long end = SystemClock.uptimeMillis() + RECORD_MS;
            while (SystemClock.uptimeMillis() < end) {
                int n = rec.read(buf, 0, buf.length);
                long t = SystemClock.uptimeMillis();
                if (n < 0) throw new IllegalStateException("AudioRecord.read=" + n);
                if (tFirst < 0) {
                    for (int k = 0; k < n; k++) {
                        if (buf[k] != 0) {
                            tFirst = t;
                            break;
                        }
                    }
                }
            }
            if (tFirst < 0) err = "no_nonzero_sample_in_" + RECORD_MS + "ms";
        } catch (RuntimeException e) {
            err = Probe.describe(e);
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                    // never started
                }
                rec.release();
            }
        }
        Probe.log(source, locked, tEvent, tFgs, tFirst, err == null, err);
        new Handler(Looper.getMainLooper()).post(this::finish);
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
}
