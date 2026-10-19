package com.vos.spike.s07;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/** One trial per summon: handler time, then a microphone FGS that finds the first non-zero sample. */
final class Probe {
    static final String TAG = "VOS_S07";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_LOCKED = "locked";
    static final String EXTRA_T_EVENT = "t_event";

    /** One trial at a time; a summon during a running trial is logged as ok=false err=busy. */
    static final AtomicBoolean busy = new AtomicBoolean();

    private Probe() {}

    static void summon(Context ctx, String source) {
        long tEvent = SystemClock.uptimeMillis();
        boolean locked = ctx.getSystemService(KeyguardManager.class).isKeyguardLocked();
        if (!busy.compareAndSet(false, true)) {
            log(source, locked, tEvent, -1, -1, false, "busy");
            return;
        }
        Intent i = new Intent(ctx, MicFgs.class)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_LOCKED, locked)
                .putExtra(EXTRA_T_EVENT, tEvent);
        try {
            ctx.startForegroundService(i);
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException, SecurityException, ...
            busy.set(false);
            log(source, locked, tEvent, -1, -1, false, describe(e));
        }
    }

    static void log(String source, boolean locked, long tEvent, long tFgs, long tFirstNonzero, boolean ok, String err) {
        long latency = tFirstNonzero >= 0 ? tFirstNonzero - tEvent : -1;
        Log.i(TAG, "VOS_S07 trial source=" + source + " locked=" + locked + " t_event=" + tEvent
                + " t_fgs=" + tFgs + " t_first_nonzero=" + tFirstNonzero + " latency_ms=" + latency
                + " ok=" + ok + " err=" + (err == null ? "none" : err.replaceAll("\\s+", "_")));
    }

    static String describe(Throwable e) {
        return e.getClass().getSimpleName() + ":" + e.getMessage();
    }
}
