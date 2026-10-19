package com.vos.spike.s07;

import android.service.quicksettings.TileService;

/** QS tile summon. Coordinator: adb shell cmd statusbar click-tile com.vos.spike.s07/.ProbeTile */
public class ProbeTile extends TileService {
    @Override
    public void onClick() {
        Probe.summon(this, "tile");
    }
}
