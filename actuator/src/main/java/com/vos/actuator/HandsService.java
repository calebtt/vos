package com.vos.actuator;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

/**
 * Hands (§3.1): global actions, gestures, set-text and screenshots arrive in M1/M3. It also draws the
 * generating / acting indicator (Shell Contract row 13) as an accessibility overlay, a window type
 * only an enabled accessibility service can add, so an app cannot remove it or draw over it as that
 * type. Main thread only.
 */
public final class HandsService extends AccessibilityService {
    static final String TAG = "VOS_INDICATOR";
    /** The connected service, or null while accessibility is off for VOS. */
    private static volatile HandsService connected;

    private ImageView chip;
    private WindowManager.LayoutParams chipLayout;
    private boolean spinning;
    /** The spin's step: 20 a second is smooth enough for a 32 dp chip and redraws far less than every frame. */
    static final long SPIN_STEP_MS = 50;
    /**
     * The spin, one turn a second. Not an Animator: those follow the system animation scale, and with
     * "Remove animations" on they would never turn, while the indicator must keep moving to tell a
     * slow model from a hung one.
     */
    private final Runnable spinStep = new Runnable() {
        @Override
        public void run() {
            if (chip != null && spinning) {
                chip.setRotation((SystemClock.uptimeMillis() % 1000) * 0.36f);
                chip.postDelayed(this, SPIN_STEP_MS);
            }
        }
    };
    /** The state to log as drawn at the chip's next frame, or null; one listener, so changes while the screen is off do not pile up. */
    private Indicator.State drawPending;
    private final ViewTreeObserver.OnPreDrawListener drawLogger = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (chip != null) {
                chip.getViewTreeObserver().removeOnPreDrawListener(this);
            }
            if (drawPending != null) {
                Log.i(TAG, "indicator drawn state=" + drawPending + " t_up=" + SystemClock.uptimeMillis());
                drawPending = null;
            }
            return true;
        }
    };

    static HandsService connected() {
        return connected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        show(((ActuatorApp) getApplication()).indicator().state()); // a turn may already be open
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Accessibility turned off: the window token goes with the binding, so stop drawing now.
        if (connected == this) {
            connected = null;
        }
        remove();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (connected == this) { // a quick rebind may already have connected a new instance
            connected = null;
        }
        remove();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Draws the indicator's state: nothing when IDLE, else the chip, spinning while GENERATING. The
     * "indicator drawn" line (the eval's timing) is logged when the chip's next frame is about to be
     * drawn, or when its window is removed; a call that changes nothing logs nothing.
     */
    void show(Indicator.State state) {
        if (state == Indicator.State.IDLE) {
            if (remove()) {
                Log.i(TAG, "indicator drawn state=IDLE t_up=" + SystemClock.uptimeMillis());
            }
        } else {
            if (chip == null && !add()) {
                return; // the overlay cannot be drawn now; the notification title still carries the state
            }
            boolean generating = state == Indicator.State.GENERATING;
            chip.setImageResource(generating ? R.drawable.ic_vos_generating : R.drawable.ic_vos_acting);
            chip.setContentDescription(getString(generating ? R.string.indicator_generating : R.string.indicator_acting));
            if (generating && !spinning) {
                spinning = true;
                chip.post(spinStep);
            } else if (!generating) {
                stopSpin();
            }
            logNextFrame(state);
        }
    }

    /** Logs "indicator drawn" just before the chip's next frame, for the latest state only. */
    private void logNextFrame(Indicator.State state) {
        if (drawPending == null) {
            chip.getViewTreeObserver().addOnPreDrawListener(drawLogger);
        }
        drawPending = state;
        chip.invalidate();
    }

    /** Adds the chip window; false (nothing added) if the system refuses, e.g. the service was just unbound. */
    private boolean add() {
        int size = Math.round(32 * getResources().getDisplayMetrics().density);
        int pad = size / 6;
        chip = new ImageView(this);
        chip.setBackgroundResource(R.drawable.bg_indicator);
        chip.setPadding(pad, pad, pad, pad);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        WindowManager wm = getSystemService(WindowManager.class);
        place(wm, lp);
        lp.setTitle("VOS indicator"); // what eval/m1/indicator.sh looks for in dumpsys window
        try {
            wm.addView(chip, lp);
        } catch (RuntimeException e) { // WindowManager.BadTokenException and the like: never crash the actuator
            Log.w(TAG, "indicator overlay not added: " + e.getClass().getSimpleName());
            chip = null;
            return false;
        }
        chipLayout = lp;
        return true;
    }

    /**
     * Just below the status bar and left of a navigation bar or cutout on the right (landscape), as
     * they are now.
     */
    private static void place(WindowManager wm, WindowManager.LayoutParams lp) {
        Insets in = wm.getCurrentWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.statusBars()
                | WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
        lp.x = in.right + lp.width / 2;
        lp.y = in.top + lp.height / 4;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (chip != null) { // rotation moves the status bar, the navigation bar and the cutout
            WindowManager wm = getSystemService(WindowManager.class);
            place(wm, chipLayout);
            try {
                wm.updateViewLayout(chip, chipLayout);
            } catch (IllegalArgumentException ignored) {
                // the window went with the service
            }
        }
    }

    private void stopSpin() {
        spinning = false;
        if (chip != null) {
            chip.removeCallbacks(spinStep);
            chip.setRotation(0);
        }
    }

    /** Removes the chip's window; false if there was none. */
    private boolean remove() {
        if (chip == null) {
            return false;
        }
        stopSpin();
        drawPending = null; // the listener goes with the removed view; the next chip gets its own
        try {
            getSystemService(WindowManager.class).removeView(chip);
        } catch (IllegalArgumentException ignored) {
            // already gone with the service's windows
        }
        chip = null;
        chipLayout = null;
        return true;
    }
}
