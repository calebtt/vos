package com.vos.actuator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Debug builds only: summon or stop a turn from the shell, as the QS tile would. The activity is
 * the foreground moment the microphone service needs; it finishes at once.
 *
 * <pre>
 * am start -n com.vos.actuator/.DebugSummonActivity --es op start [--ez mic_file true | --ez mic_file_then_live true] [--ez raw_mic true] [--ez speaker_file true]
 * am start -n com.vos.actuator/.DebugSummonActivity --es op stop
 * am start -n com.vos.actuator/.DebugSummonActivity --es op typed --es text "what time is it"
 * am start -n com.vos.actuator/.DebugSummonActivity --es op reply --es text "and now?"
 * am start -n com.vos.actuator/.DebugSummonActivity --es op volume --ei stream 3 --ei index 7
 * am start -n com.vos.actuator/.DebugSummonActivity --es op setting [--es key agent_mode --es value quiet]
 * </pre>
 * {@code op volume} restores a stream volume after an eval (the shell's own volume command does not
 * apply on Phone B's Android 17). {@code op setting} sets one owner setting as the source
 * {@code debug_shell} (audited like any change), then logs every key and value (tag VOS_SETTINGS).
 */
public final class DebugSummonActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        Intent i = new Intent(this, TurnService.class).putExtra(TurnService.EXTRA_SOURCE, "debug_shell");
        if ("volume".equals(in.getStringExtra("op"))) {
            getSystemService(android.media.AudioManager.class).setStreamVolume(
                    in.getIntExtra("stream", android.media.AudioManager.STREAM_MUSIC), in.getIntExtra("index", 0), 0);
        } else if ("setting".equals(in.getStringExtra("op"))) {
            OwnerSettings s = ((ActuatorApp) getApplication()).ownerSettings();
            if (in.getStringExtra("key") != null) {
                String why = s.set(in.getStringExtra("key"), in.getStringExtra("value"), "debug_shell");
                android.util.Log.i("VOS_SETTINGS", "set " + in.getStringExtra("key") + ": " + (why == null ? "ok" : why));
            }
            android.util.Log.i("VOS_SETTINGS", "settings " + new org.json.JSONObject(s.all()));
        } else if ("typed".equals(in.getStringExtra("op"))) {
            // As if typed in the chat window: the same TypedChat.send the window calls (eval/m1/chat.sh).
            String why = ((ActuatorApp) getApplication()).typedChat().send(in.getStringExtra("text"), "debug_window");
            android.util.Log.i(TypedChat.TAG, "debug typed: " + (why == null ? "sent" : why));
        } else if ("reply".equals(in.getStringExtra("op"))) {
            // As if replied from the chat notification: the receiver's own handler, without the system UI.
            ChatNotification.Reply.handle(this, in.getStringExtra("text"), "debug_notification");
        } else if ("stop".equals(in.getStringExtra("op"))) {
            startService(i.setAction(TurnService.ACTION_STOP));
        } else {
            startForegroundService(i.setAction(TurnService.ACTION_SUMMON)
                    .putExtra(TurnService.EXTRA_DEBUG_MIC_FILE, in.getBooleanExtra("mic_file", false))
                    .putExtra(TurnService.EXTRA_DEBUG_MIC_FILE_THEN_LIVE, in.getBooleanExtra("mic_file_then_live", false))
                    .putExtra(TurnService.EXTRA_DEBUG_RAW_MIC, in.getBooleanExtra("raw_mic", false))
                    .putExtra(TurnService.EXTRA_DEBUG_SPEAKER_FILE, in.getBooleanExtra("speaker_file", false)));
        }
        finish();
    }
}
