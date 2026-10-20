package com.vos.actuator;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Hands (§3.1): global actions, gestures, set-text and screenshots arrive in M1/M3. */
public final class HandsService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
