package com.vos.spike.s07;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;

/** Assistant summon. Coordinator: adb shell cmd voiceinteraction show */
public class ProbeSession extends VoiceInteractionSession {
    private final Handler handler = new Handler(Looper.getMainLooper());

    ProbeSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        Probe.summon(getContext(), "show");
        super.onShow(args, showFlags);
        // The empty session window would otherwise stay up; the trial itself runs ~1 s in MicFgs.
        handler.postDelayed(this::hide, 1500);
    }
}
