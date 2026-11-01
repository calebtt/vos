package com.vos.actuator;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: summons VOS, or stops the open turn, voice or typed (M1-STOP-01, P5). Its
 * click is the foreground moment that lets the actuator start the microphone service (S-07: 22/22 on
 * Phone B). It shows a typed turn as active too, since its tap stops that turn rather than summoning.
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
        boolean voice = TurnService.turnOpen();
        boolean typed = !voice && ((ActuatorApp) getApplication()).typedChat().turnOpen();
        t.setState(voice || typed ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(getString(voice ? R.string.tile_listening : typed ? R.string.tile_answering : R.string.tile_idle));
        t.updateTile();
    }
}
