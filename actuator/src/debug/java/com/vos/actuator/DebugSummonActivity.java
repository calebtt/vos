package com.vos.actuator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Debug builds only: summon or stop a turn from the shell, as the QS tile would. The activity is
 * the foreground moment the microphone service needs; it finishes at once.
 *
 * <pre>
 * am start -n com.vos.actuator/.DebugSummonActivity --es op start [--ez mic_file true] [--ez speaker_file true]
 * am start -n com.vos.actuator/.DebugSummonActivity --es op stop
 * am start -n com.vos.actuator/.DebugSummonActivity --es op volume --ei stream 3 --ei index 7
 * </pre>
 * {@code op volume} restores a stream volume after an eval (the shell's own volume command does not
 * apply on Phone B's Android 17).
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
        } else if ("stop".equals(in.getStringExtra("op"))) {
            startService(i.setAction(TurnService.ACTION_STOP));
        } else {
            startForegroundService(i.setAction(TurnService.ACTION_SUMMON)
                    .putExtra(TurnService.EXTRA_DEBUG_MIC_FILE, in.getBooleanExtra("mic_file", false))
                    .putExtra(TurnService.EXTRA_DEBUG_SPEAKER_FILE, in.getBooleanExtra("speaker_file", false)));
        }
        finish();
    }
}
