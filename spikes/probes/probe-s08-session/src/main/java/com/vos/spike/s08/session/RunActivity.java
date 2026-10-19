package com.vos.spike.s08.session;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * Auto-starts on launch (no buttons): binds the actuator, gets the pipe read end, reads
 * {@code frames} frames (intent extra, default 10000), logs the result to VOS_S08 and finishes.
 * The PCM payload is overwritten frame by frame and never stored.
 */
public class RunActivity extends Activity {
    private static final String TAG = "VOS_S08";
    /** Must match ActuatorService.TX_OPEN in the actuator probe. */
    private static final int TX_OPEN = IBinder.FIRST_CALL_TRANSACTION;
    private static final int FRAME_BYTES = 16 + 320 * 2;
    private static final long UNDERRUN_NS = 40_000_000L;

    private int frames;
    private boolean bound;
    private boolean started;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (started) return;
            started = true;
            new Thread(() -> run(service), "s08-session").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "VOS_S08 session actuator_disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView status = new TextView(this);
        status.setText("VOS S08 probe running; see logcat -s VOS_S08");
        setContentView(status);
        frames = Math.max(1, getIntent().getIntExtra("frames", 10000));
        Log.i(TAG, "VOS_S08 session start frames=" + frames);
        Intent i = new Intent().setComponent(new ComponentName(
                "com.vos.spike.s08.actuator", "com.vos.spike.s08.actuator.ActuatorService"));
        try {
            bound = bindService(i, conn, BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES);
        } catch (SecurityException e) {
            Log.e(TAG, "VOS_S08 session bind_failed err=" + describe(e));
        }
        if (!bound) {
            Log.e(TAG, "VOS_S08 session bind_failed");
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    private void run(IBinder actuator) {
        ParcelFileDescriptor pfd;
        try {
            pfd = open(actuator);
        } catch (RemoteException | IOException e) {
            Log.e(TAG, "VOS_S08 session open_failed err=" + describe(e));
            runOnUiThread(this::finish);
            return;
        }

        long[] lat = new long[frames];
        int n = 0;
        int underruns = 0;
        int gaps = 0;
        long prevSeq = -1;
        long prevRead = -1;
        String err = null;
        byte[] buf = new byte[FRAME_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        DataInputStream in = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(pfd));
        try {
            while (n < frames) {
                in.readFully(buf);
                long tRead = SystemClock.uptimeNanos();
                long seq = bb.getLong(0);
                long tCapture = bb.getLong(8);
                boolean gap = prevSeq >= 0 && seq != prevSeq + 1;
                boolean late = prevRead >= 0 && tRead - prevRead > UNDERRUN_NS;
                if (gap) gaps++;
                if (gap || late) underruns++;
                lat[n++] = tRead - tCapture;
                prevSeq = seq;
                prevRead = tRead;
                if (n % 1000 == 0) {
                    Log.i(TAG, "VOS_S08 progress frames=" + n + " last_ms=" + ms(lat[n - 1])
                            + " underruns=" + underruns + " gaps=" + gaps);
                }
            }
        } catch (IOException e) {
            err = describe(e);
            try {
                pfd.checkError(); // surfaces the actuator's closeWithError message
            } catch (IOException writerErr) {
                err = describe(writerErr);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }

        long[] s = Arrays.copyOf(lat, n);
        Arrays.sort(s);
        Log.i(TAG, "VOS_S08 result frames=" + n + " p50_ms=" + ms(pct(s, 50)) + " p95_ms=" + ms(pct(s, 95))
                + " max_ms=" + ms(n > 0 ? s[n - 1] : -1) + " underruns=" + underruns + " gaps=" + gaps
                + " err=" + (err == null ? "none" : err));
        runOnUiThread(this::finish);
    }

    private ParcelFileDescriptor open(IBinder actuator) throws RemoteException, IOException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(frames);
            if (!actuator.transact(TX_OPEN, data, reply, 0)) throw new IOException("unknown transaction");
            if (reply.readInt() != 1) throw new IOException("actuator refused: " + reply.readString());
            ParcelFileDescriptor pfd = reply.readFileDescriptor();
            if (pfd == null) throw new IOException("no fd in reply");
            return pfd;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Nearest-rank percentile of a sorted array; -1 when empty. */
    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) return -1;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static String ms(long ns) {
        return ns < 0 ? "-1" : String.format(Locale.US, "%.3f", ns / 1e6);
    }

    private static String describe(Throwable e) {
        return (e.getClass().getSimpleName() + ":" + e.getMessage()).replaceAll("\\s+", "_");
    }
}
