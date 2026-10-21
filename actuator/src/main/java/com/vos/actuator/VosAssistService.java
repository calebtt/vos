package com.vos.actuator;

import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

/**
 * The actuator's VoiceInteractionService, so {@code com.vos.actuator} can hold the assistant role
 * (decision 45, §3.1). The assist gesture shows {@link VosAssistSession}, which summons a turn. Assist
 * context (the screen's structure and a screenshot) is turned off: VOS does not use it yet, and it
 * should never be handed over unasked.
 */
public final class VosAssistService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
        ((ActuatorApp) getApplication()).roleWatch().check("assistant service ready");
    }

    @Override
    public void onShutdown() {
        // The system unbinds the service when the role moves to another app, or to none.
        ((ActuatorApp) getApplication()).roleWatch().check("assistant service shut down");
        super.onShutdown();
    }

    /**
     * The keyguard's voice-assist affordance shows no session of its own: show ours, so it has the
     * gesture's foreground moment and its one summon-or-stop path (not exercised on Phone B, where the
     * assist key on the lock screen arrives through {@link VosAssistSession#onShow}).
     */
    @Override
    public void onLaunchVoiceAssistFromKeyguard() {
        Bundle args = new Bundle();
        args.putBoolean(VosAssistSession.ARG_FROM_KEYGUARD, true);
        showSession(args, 0);
    }
}
