package com.vos.actuator;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * {@link Indicator.View} on the device: logs the decision, then on the main thread draws the
 * accessibility overlay chip (when VOS's accessibility service is on) and sets the turn
 * notification's title, which carries the state when the overlay cannot be drawn.
 */
final class IndicatorView implements Indicator.View {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void show(Indicator.State state) {
        Log.i(HandsService.TAG, "indicator state=" + state + " t_up=" + SystemClock.uptimeMillis());
        MAIN.post(() -> {
            HandsService hands = HandsService.connected();
            if (hands != null) {
                hands.show(state);
            }
            TurnService.indicatorChanged(state);
        });
    }
}
