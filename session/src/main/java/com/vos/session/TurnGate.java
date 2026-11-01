package com.vos.session;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Whether a typed turn is still open in the session (decision 21). It closes once: at the turn's
 * reply, refusal or failure, or at its deadline, whichever comes first. Whoever closes it makes the
 * turn's last writes while holding it. A write or tool call that comes later finds it closed and does
 * nothing, and a model call in flight is cancelled. So once the actuator hears endTurn, nothing more
 * of the turn reaches the shared conversation or the actuator.
 *
 * <p>The first close to start is the one that makes the last writes, even if a later one would get
 * the lock first (#303: a deadline or the owner's stop that started closing while the worker held the
 * gate for a step lost to the worker's own "failed" close once the step returned, so the turn ended
 * with an apology and the wrong reason).
 */
final class TurnGate {
    /**
     * Set once, by the close that wins, before it waits for a step in progress, so no new step starts
     * meanwhile and no other close can win.
     */
    private final AtomicBoolean ending = new AtomicBoolean();
    private okhttp3.Call inFlight;

    /** Runs {@code step} unless the gate is closing; the gate cannot close until it returns. */
    synchronized boolean ifOpen(Runnable step) {
        if (ending.get()) {
            return false;
        }
        step.run();
        return true;
    }

    /** {@code step}'s result, or {@code closedResult} without running it if the gate is closing. */
    synchronized <T> T ifOpen(Supplier<T> step, T closedResult) {
        return ending.get() ? closedResult : step.get();
    }

    /**
     * Closes the gate, cancels the model call in flight, and runs {@code last} (the turn's last
     * writes) before anything else can see the gate. A step already running under {@link #ifOpen}
     * finishes first; none starts after this is called. False at once, with {@code last} not run, if
     * another close started first (it makes the last writes, maybe still waiting for that step).
     */
    boolean close(Runnable last) {
        if (!ending.compareAndSet(false, true)) {
            return false;
        }
        synchronized (this) {
            if (inFlight != null) {
                inFlight.cancel();
                inFlight = null;
            }
            last.run();
            return true;
        }
    }

    boolean isOpen() {
        return !ending.get();
    }

    /** {@code call} is about to run. False if the gate is closing: do not run it. */
    synchronized boolean track(okhttp3.Call call) {
        if (ending.get()) {
            return false;
        }
        inFlight = call;
        return true;
    }

    synchronized void untrack(okhttp3.Call call) {
        if (inFlight == call) {
            inFlight = null;
        }
    }
}
