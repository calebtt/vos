package com.vos.actuator;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * The recognition service a VoiceInteractionService must name. VOS does not offer speech recognition
 * to other apps: every request is refused, and the mic is never opened here. Only the system can bind
 * it (the manifest guards it with BIND_VOICE_INTERACTION).
 */
public final class VosRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (RemoteException ignored) {
            // the caller is gone
        }
    }

    @Override
    protected void onCancel(Callback listener) {
    }

    @Override
    protected void onStopListening(Callback listener) {
    }
}
