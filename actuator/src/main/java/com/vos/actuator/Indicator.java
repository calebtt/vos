package com.vos.actuator;

import java.util.HashSet;
import java.util.Set;

/**
 * The generating / acting indicator's state (Shell Contract row 13), decided by the actuator alone.
 * GENERATING while a model call of the open turn is producing output: the session reports each
 * call's start and end, and those events carry no model output, so nothing a model says can show or
 * hide it. ACTING while the actuator runs a tool for the turn; it wins while both hold. The turn
 * ending (the owner's stop included) clears both. There is no timeout: a stalled call keeps the
 * indicator spinning, which is how the owner tells a slow model from a hung one.
 */
final class Indicator {
    enum State { IDLE, GENERATING, ACTING }

    /** Draws a state; called on every change, from whichever thread caused it. */
    interface View {
        void show(State state);
    }

    /** More open calls than this for one turn are not tracked (the indicator already spins). */
    static final int MAX_CALLS = 16;

    /** A call id from the session: printable ASCII without spaces, at most 64 characters. Anything else is refused. */
    static boolean validCallId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 64 && id.chars().allMatch(c -> c > 0x20 && c < 0x7f);
    }

    private final View view;
    private String turnId; // the turn the actuator has open, or null
    private final Set<String> calls = new HashSet<>();
    private int acting;
    private State shown = State.IDLE;

    Indicator(View view) {
        this.view = view;
    }

    /** The actuator opened {@code id}: only its calls and tool runs count from now on. */
    synchronized void turnOpened(String id) {
        turnId = id;
        calls.clear();
        acting = 0;
        update();
    }

    /** The turn ended, for any reason: both states clear. A stale id (a turn already replaced) is ignored. */
    synchronized void turnEnded(String id) {
        if (turnId != null && turnId.equals(id)) {
            turnId = null;
            calls.clear();
            acting = 0;
            update();
        }
    }

    /** Returns false if ignored: not the open turn, a malformed call id, or too many open calls. */
    synchronized boolean callStarted(String turn, String callId) {
        if (!isOpen(turn) || !validCallId(callId) || calls.size() >= MAX_CALLS) {
            return false;
        }
        calls.add(callId);
        update();
        return true;
    }

    synchronized void callEnded(String turn, String callId) {
        if (isOpen(turn) && calls.remove(callId)) {
            update();
        }
    }

    synchronized void actingStarted(String turn) {
        if (isOpen(turn)) {
            acting++;
            update();
        }
    }

    synchronized void actingEnded(String turn) {
        if (isOpen(turn) && acting > 0) {
            acting--;
            update();
        }
    }

    synchronized State state() {
        return shown;
    }

    private boolean isOpen(String turn) {
        return turnId != null && turnId.equals(turn);
    }

    private void update() {
        State s = acting > 0 ? State.ACTING : !calls.isEmpty() ? State.GENERATING : State.IDLE;
        if (s != shown) {
            shown = s;
            view.show(s);
        }
    }
}
