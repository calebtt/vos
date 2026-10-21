package com.vos.actuator;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionSession;

/**
 * One assist gesture: summons a turn (or stops the open one), like the QS tile. The session's showing
 * is the foreground moment that lets the actuator start its microphone service (S-07); it has no UI
 * and hides as soon as the service is in the foreground, or after a short cap.
 */
final class VosAssistSession extends VoiceInteractionSession {
    private static final long HIDE_CAP_MS = 2_000;
    private static final long POLL_MS = 50;
    private final Handler handler = new Handler(Looper.getMainLooper());

    VosAssistSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        long tEvent = SystemClock.uptimeMillis();
        super.onShow(args, showFlags);
        boolean stopping = TurnService.turnOpen();
        TurnService.toggle(getContext(), "assist", tEvent);
        if (stopping) {
            hide();
        } else {
            hideWhenForeground(tEvent);
        }
    }

    private void hideWhenForeground(long tEvent) {
        if (TurnService.inForeground() || SystemClock.uptimeMillis() - tEvent >= HIDE_CAP_MS) {
            hide();
        } else {
            handler.postDelayed(() -> hideWhenForeground(tEvent), POLL_MS);
        }
    }

    @Override
    public void onHide() {
        handler.removeCallbacksAndMessages(null);
        super.onHide();
    }
}
