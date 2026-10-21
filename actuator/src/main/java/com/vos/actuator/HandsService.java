package com.vos.actuator;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.LinearInterpolator;
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
    private ObjectAnimator spin;

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
            if (generating) {
                spin.start();
            } else {
                spin.cancel();
                chip.setRotation(0);
            }
            logNextFrame(chip, state);
        }
    }

    /** Logs "indicator drawn" just before the chip's next frame (once). */
    private static void logNextFrame(ImageView view, Indicator.State state) {
        ViewTreeObserver vto = view.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                Log.i(TAG, "indicator drawn state=" + state + " t_up=" + SystemClock.uptimeMillis());
                return true;
            }
        });
        view.invalidate();
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
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = size / 2;
        WindowManager wm = getSystemService(WindowManager.class);
        int top = wm.getCurrentWindowMetrics().getWindowInsets()
                .getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
        lp.y = top + size / 4; // just below the status bar and any cutout
        lp.setTitle("VOS indicator"); // what eval/m1/indicator.sh looks for in dumpsys window
        try {
            wm.addView(chip, lp);
        } catch (RuntimeException e) { // WindowManager.BadTokenException and the like: never crash the actuator
            Log.w(TAG, "indicator overlay not added: " + e.getClass().getSimpleName());
            chip = null;
            return false;
        }
        spin = ObjectAnimator.ofFloat(chip, "rotation", 0f, 360f);
        spin.setDuration(1000);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        return true;
    }

    /** Removes the chip's window; false if there was none. */
    private boolean remove() {
        if (chip == null) {
            return false;
        }
        spin.cancel();
        try {
            getSystemService(WindowManager.class).removeView(chip);
        } catch (IllegalArgumentException ignored) {
            // already gone with the service's windows
        }
        chip = null;
        spin = null;
        return true;
    }
}
