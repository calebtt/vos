package com.vos.session;

import java.util.function.Supplier;

/**
 * Whether a typed turn is still open in the session (decision 21). It closes once: at the turn's
 * reply, refusal or failure, or at its deadline, whichever comes first. Whoever closes it makes the
 * turn's last writes while holding it. A write or tool call that comes later finds it closed and does
 * nothing, and a model call in flight is cancelled. So once the actuator hears endTurn, nothing more
 * of the turn reaches the shared conversation or the actuator.
 */
final class TurnGate {
    /** Set before {@link #close} waits for a step in progress, so no new step starts meanwhile. */
    private volatile boolean ending;
    private boolean closed;
    private okhttp3.Call inFlight;

    /** Runs {@code step} unless the gate is closing; the gate cannot close until it returns. */
    synchronized boolean ifOpen(Runnable step) {
        if (ending) {
            return false;
        }
        step.run();
        return true;
    }

    /** {@code step}'s result, or {@code closedResult} without running it if the gate is closing. */
    synchronized <T> T ifOpen(Supplier<T> step, T closedResult) {
        return ending ? closedResult : step.get();
    }

    /**
     * Closes the gate, cancels the model call in flight, and runs {@code last} (the turn's last
     * writes) before anything else can see the gate. A step already running under {@link #ifOpen}
     * finishes first; none starts after this is called. False, with {@code last} not run, if another
     * close got there first.
     */
    boolean close(Runnable last) {
        ending = true;
        synchronized (this) {
            if (closed) {
                return false;
            }
            closed = true;
            if (inFlight != null) {
                inFlight.cancel();
                inFlight = null;
            }
            last.run();
            return true;
        }
    }

    boolean isOpen() {
        return !ending;
    }

    /** {@code call} is about to run. False if the gate is closing: do not run it. */
    synchronized boolean track(okhttp3.Call call) {
        if (ending) {
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
