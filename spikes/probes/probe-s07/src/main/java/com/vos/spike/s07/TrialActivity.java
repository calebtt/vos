package com.vos.spike.s07;

import android.app.Activity;
import android.os.Bundle;

/**
 * A summon the shell can start, with no Quick Settings change: the activity is the foreground moment
 * the microphone FGS needs, and finishes at once (#261).
 *
 * <pre>am start -n com.vos.spike.s07/.TrialActivity --es variant vc_fx</pre>
 */
public class TrialActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String variant = getIntent().getStringExtra(Probe.EXTRA_VARIANT);
        Probe.summon(this, "activity", variant == null ? Probe.DEFAULT_VARIANT : variant);
        finish();
    }
}
