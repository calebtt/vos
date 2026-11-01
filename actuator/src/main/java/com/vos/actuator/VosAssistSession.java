package com.vos.actuator;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionSession;

/**
 * One assist gesture: summons a turn (or stops the open one, voice or typed), like the QS tile. The
 * session's showing is the foreground moment that lets the actuator start its microphone service
 * (S-07); it has no UI and hides as soon as the service is in the foreground, or after a short cap.
 */
final class VosAssistSession extends VoiceInteractionSession {
    /** Set by {@link VosAssistService#onLaunchVoiceAssistFromKeyguard}: the summon's source is assist_keyguard. */
    static final String ARG_FROM_KEYGUARD = "com.vos.actuator.FROM_KEYGUARD";
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
        boolean keyguard = args != null && args.getBoolean(ARG_FROM_KEYGUARD, false);
        boolean stopping = TurnService.toggle(getContext(), keyguard ? "assist_keyguard" : "assist", tEvent);
        if (stopping) { // a stop starts no microphone service: nothing to wait for
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
