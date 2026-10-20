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
 * </pre>
 */
public final class DebugSummonActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        Intent i = new Intent(this, TurnService.class).putExtra(TurnService.EXTRA_SOURCE, "debug_shell");
        if ("stop".equals(in.getStringExtra("op"))) {
            startService(i.setAction(TurnService.ACTION_STOP));
        } else {
            startForegroundService(i.setAction(TurnService.ACTION_SUMMON)
                    .putExtra(TurnService.EXTRA_DEBUG_MIC_FILE, in.getBooleanExtra("mic_file", false))
                    .putExtra(TurnService.EXTRA_DEBUG_SPEAKER_FILE, in.getBooleanExtra("speaker_file", false)));
        }
        finish();
    }
}
