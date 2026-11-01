package com.vos.actuator;

import com.vos.ipc.Ipc;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The open typed turn as the actuator tracks it (decision 21), kept apart from Android so its stop
 * paths and backstops can be tested. The owner can stop it at any point (P5): from the chat window,
 * the typed turn's notification, or "Stop VOS" (the QS tile or the assist gesture).
 *
 * <p>A stop takes effect in the actuator at once: the session's reply is refused, and so is any tool
 * call that has not started. The turn stays open, and holds the owner-turn slot, until the session
 * confirms with endTurn that it has closed its side (its {@code TurnGate} is closed, so nothing more
 * of the turn reaches the shared conversation or the actuator), or until {@link #STOP_BACKSTOP_MS}
 * after the stop, whichever comes first. A turn stopped before it was sent to the session ends at
 * once, since the session never hears of it.
 *
 * <p>If the stop backstop ends the turn, the session has not confirmed, so the turn is remembered as
 * stopped: its late reply and tool calls are still refused and audited (never run, never routed to a
 * voice turn that opens next), and its late endTurn is audited as such. A turn that is not stopped
 * keeps the general {@link #BACKSTOP_MS}.
 *
 * <p>Times are {@code uptimeMillis}, passed in. Not thread-safe by itself. {@link TypedChat} is the lock.
 */
final class TypedTurnState {
    /**
     * Backstop for a session that never sends endTurn. It must outlast the session's deadline plus
     * that close: a tool call in flight finishes first, and endTurn is then delivered.
     */
    static final long BACKSTOP_MS = Ipc.TYPED_TURN_DEADLINE_MS + 15_000;
    /**
     * After the owner's stop, the slot is freed this long after the stop if the session has not
     * confirmed by then (Caleb's call on #303). It outlasts the session's close of a turn with a tool
     * call in flight (up to 2 s for the link, then up to 2 s for the tool's oracle).
     */
    static final long STOP_BACKSTOP_MS = 5_000;
    static final String BACKSTOP_REASON = "typed turn timed out: no end from the session";
    static final String STOP_BACKSTOP_REASON = "stop backstop: no endTurn from the session after the stop";
    /** How many stopped turns the session has not confirmed are remembered (oldest dropped first). */
    static final int MAX_UNCONFIRMED = 16;

    /** What a stop did. */
    enum Stop {
        /** No typed turn is open: the press is not a typed stop. */
        NONE,
        /** The open turn was already stopping. */
        ALREADY,
        /** The session was never sent the turn: end it now. */
        LOCAL,
        /** The session has the turn: close it there (onTurnClosed) and end it on its endTurn or the stop backstop. */
        SESSION
    }

    /** What a tool call from the session for {@code turnId} gets. */
    enum Tool {
        /** Not a typed turn here: someone else's call, or nobody's. */
        NOT_THIS_TURN,
        /** The owner stopped this turn (it may already have ended on the stop backstop): refused, not run. */
        STOPPED,
        RUN
    }

    /** How the turn ended: the reason to record, whether the owner stopped it, and what ended it. */
    static final class Ended {
        final String reason;
        final boolean byOwner;
        /** What ended it: the session's endTurn, a backstop, the session going down. */
        final String how;

        Ended(String reason, boolean byOwner, String how) {
            this.reason = reason;
            this.byOwner = byOwner;
            this.how = how;
        }
    }

    private String turnId; // the open typed turn, or null
    private boolean inSession; // onTypedTurn was sent for it
    private String stopReason; // the owner's stop, or null
    private long backstopAt; // when a backstop ends the open turn
    /** Stopped turns the stop backstop ended before the session confirmed: their late writes are refused. */
    private final Set<String> unconfirmed = new LinkedHashSet<>();

    /** Opens {@code id} at {@code now}. False if a typed turn is already open. */
    boolean open(String id, long now) {
        if (turnId != null) {
            return false;
        }
        turnId = id;
        inSession = false;
        stopReason = null;
        backstopAt = now + BACKSTOP_MS;
        unconfirmed.remove(id);
        return true;
    }

    /** The open typed turn (stopping or not), or null. It holds the owner-turn slot until it ends. */
    String turnId() {
        return turnId;
    }

    boolean isOpen(String id) {
        return id != null && id.equals(turnId);
    }

    /** The owner stopped the open turn, and it has not ended yet. */
    boolean stopping() {
        return turnId != null && stopReason != null;
    }

    /** When the open turn's backstop ends it: {@link #BACKSTOP_MS} after it opened, or sooner after a stop. */
    long backstopAt() {
        return backstopAt;
    }

    /**
     * Whether to send {@code id} to the session now. True once, for the open turn the owner has not
     * stopped; a second link up (a rebind) does not send it again.
     */
    boolean sendToSession(String id) {
        if (!isOpen(id) || stopReason != null || inSession) {
            return false;
        }
        inSession = true;
        return true;
    }

    /**
     * The owner's stop at {@code now}, with the reason to record. A stop the session must confirm
     * brings the backstop forward to {@link #STOP_BACKSTOP_MS} from now.
     */
    Stop stop(String reason, long now) {
        if (turnId == null) {
            return Stop.NONE;
        }
        if (stopReason != null) {
            return Stop.ALREADY;
        }
        stopReason = reason;
        if (!inSession) {
            return Stop.LOCAL;
        }
        backstopAt = Math.min(backstopAt, now + STOP_BACKSTOP_MS);
        return Stop.SESSION;
    }

    /** The session's reply is shown only for the open turn, and not once the owner has stopped it. */
    boolean acceptsReply(String id) {
        return isOpen(id) && stopReason == null;
    }

    /**
     * The owner stopped {@code id}: it is the open turn and stopping, or the stop backstop ended it and
     * the session has not confirmed. Its reply is refused as stopped.
     */
    boolean stopped(String id) {
        return id != null && (isOpen(id) ? stopReason != null : unconfirmed.contains(id));
    }

    /** True if {@code id} was ended by the stop backstop and the session has not confirmed it yet. */
    boolean unconfirmed(String id) {
        return id != null && unconfirmed.contains(id);
    }

    Tool tool(String id) {
        if (isOpen(id)) {
            return stopReason == null ? Tool.RUN : Tool.STOPPED;
        }
        return unconfirmed(id) ? Tool.STOPPED : Tool.NOT_THIS_TURN;
    }

    /**
     * Ends {@code id}, which ended {@code how} (the session's endTurn, the session going down). The
     * owner's stop is the reason recorded if there was one. Null if {@code id} is not the open turn.
     */
    Ended end(String id, String how) {
        if (!isOpen(id)) {
            return null;
        }
        Ended e = stopReason != null ? new Ended(stopReason, true, how) : new Ended(how, false, how);
        turnId = null;
        inSession = false;
        stopReason = null;
        return e;
    }

    /**
     * The backstop for {@code id} came due at {@code now}. Ends the open turn if its backstop has
     * passed: a stopped one on {@link #STOP_BACKSTOP_REASON}, remembered as unconfirmed so its late
     * writes are still refused; any other on {@link #BACKSTOP_REASON}. Null if {@code id} is not the
     * open turn or its backstop is not yet due (a stale timer).
     */
    Ended backstop(String id, long now) {
        if (!isOpen(id) || now < backstopAt) {
            return null;
        }
        boolean stopped = stopReason != null;
        Ended e = end(id, stopped ? STOP_BACKSTOP_REASON : BACKSTOP_REASON);
        if (stopped) {
            unconfirmed.add(id);
            for (Iterator<String> it = unconfirmed.iterator(); unconfirmed.size() > MAX_UNCONFIRMED; ) {
                it.next();
                it.remove();
            }
        }
        return e;
    }

    /**
     * The session's endTurn for a stopped turn the stop backstop already ended: its confirmation,
     * late. True once, and then {@code id} is forgotten (the session sends nothing after endTurn).
     */
    boolean lateEnd(String id) {
        return id != null && unconfirmed.remove(id);
    }
}
