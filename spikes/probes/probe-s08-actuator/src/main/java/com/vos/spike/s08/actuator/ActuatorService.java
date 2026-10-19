package com.vos.spike.s08.actuator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bound service. Transaction OPEN(int frames) returns the read end of a reliable pipe, then a mic
 * FGS writes {@code frames} frames: [long seq][long uptimeNanos at AudioRecord.read return][320 x
 * int16 PCM], little-endian. The audio is never stored.
 */
public class ActuatorService extends Service {
    static final String TAG = "VOS_S08";
    /** Must match RunActivity.TX_OPEN in the session probe. */
    static final int TX_OPEN = IBinder.FIRST_CALL_TRANSACTION;
    static final int RATE = 16000;
    static final int SAMPLES = 320; // 20 ms at 16 kHz
    static final int FRAME_BYTES = 16 + SAMPLES * 2;
    private static final String CHANNEL = "s08";

    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch foreground = new CountDownLatch(1);
    private volatile boolean fgsOk;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != TX_OPEN) return false;
            int frames = data.readInt();
            if (!running.compareAndSet(false, true)) {
                reply.writeInt(0);
                reply.writeString("busy");
                return true;
            }
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createReliablePipe();
            } catch (IOException e) {
                running.set(false);
                reply.writeInt(0);
                reply.writeString(describe(e));
                return true;
            }
            reply.writeInt(1);
            reply.writeFileDescriptor(pipe[0].getFileDescriptor()); // dup'd into the reply
            closeQuietly(pipe[0]);
            start(frames, pipe[1]);
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            fgsOk = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "VOS_S08 actuator fgs_failed err=" + describe(e));
        }
        foreground.countDown();
        return START_NOT_STICKY;
    }

    private void start(int frames, ParcelFileDescriptor writeEnd) {
        try {
            startForegroundService(new Intent(this, ActuatorService.class));
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException etc. Capture still runs, so pipe latency is
            // measured; zero_frames in the done line shows whether the mic was silenced.
            Log.w(TAG, "VOS_S08 actuator fgs_start_failed err=" + describe(e));
            foreground.countDown();
        }
        new Thread(() -> capture(frames, writeEnd), "s08-capture").start();
    }

    private void capture(int frames, ParcelFileDescriptor writeEnd) {
        String err = null;
        long written = 0;
        long zeroFrames = 0;
        AudioRecord rec = null;
        try {
            if (!foreground.await(3, TimeUnit.SECONDS)) Log.w(TAG, "VOS_S08 actuator fgs_timeout");
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLES * 2 * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            Log.i(TAG, "VOS_S08 actuator start frames=" + frames + " fgs=" + fgsOk);
            FileDescriptor fd = writeEnd.getFileDescriptor();
            short[] pcm = new short[SAMPLES];
            ByteBuffer frame = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            rec.startRecording();
            for (long seq = 0; seq < frames; seq++) {
                int got = 0;
                while (got < SAMPLES) {
                    int n = rec.read(pcm, got, SAMPLES - got);
                    if (n < 0) throw new IllegalStateException("AudioRecord.read=" + n);
                    got += n;
                }
                long t = SystemClock.uptimeNanos();
                boolean zero = true;
                for (short s : pcm) {
                    if (s != 0) {
                        zero = false;
                        break;
                    }
                }
                if (zero) zeroFrames++;
                frame.clear();
                frame.putLong(seq).putLong(t);
                frame.asShortBuffer().put(pcm);
                writeFully(fd, frame.array(), FRAME_BYTES);
                written++;
            }
        } catch (InterruptedException | IOException | ErrnoException | RuntimeException e) {
            err = describe(e);
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                    // never started
                }
                rec.release();
            }
            try {
                if (err == null) writeEnd.close();
                else writeEnd.closeWithError(err);
            } catch (IOException ignored) {
                // reader gone
            }
        }
        Log.i(TAG, "VOS_S08 actuator done frames=" + written + " zero_frames=" + zeroFrames
                + " fgs=" + fgsOk + " err=" + (err == null ? "none" : err));
        new Handler(Looper.getMainLooper()).post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            running.set(false);
        });
    }

    private static void writeFully(FileDescriptor fd, byte[] b, int len) throws ErrnoException, InterruptedIOException {
        int off = 0;
        while (off < len) off += Os.write(fd, b, off, len - off);
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    static String describe(Throwable e) {
        return (e.getClass().getSimpleName() + ":" + e.getMessage()).replaceAll("\\s+", "_");
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "S08 probe", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("VOS S08 actuator probe recording")
                .setOngoing(true)
                .build();
    }
}
