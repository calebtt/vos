package com.vos.actuator;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Hands the system a {@link VosAssistSession} for each assist gesture. */
public final class VosAssistSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new VosAssistSession(this);
    }
}
