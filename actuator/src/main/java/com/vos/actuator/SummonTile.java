package com.vos.actuator;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: summons VOS, or stops the open turn (M1-STOP-01). Its click is the
 * foreground moment that lets the actuator start the microphone service (S-07: 22/22 on Phone B).
 */
public final class SummonTile extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        TurnService.toggle(this, "qs_tile");
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        boolean open = TurnService.turnOpen();
        t.setState(open ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(getString(open ? R.string.tile_listening : R.string.tile_idle));
        t.updateTile();
    }
}
