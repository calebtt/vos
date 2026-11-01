package com.vos.actuator;

/**
 * The open typed turn as the actuator tracks it (decision 21), kept apart from Android so its stop
 * paths can be tested. The owner can stop it at any point (P5): from the chat window, the typed
 * turn's notification, or "Stop VOS" (the QS tile or the assist gesture).
 *
 * <p>A stop takes effect in the actuator at once: the session's reply is refused, and so is any tool
 * call that has not started. The turn stays open, and holds the owner-turn slot, until the session
 * confirms with endTurn that it has closed its side: its {@code TurnGate} is closed, so nothing more
 * of the turn reaches the shared conversation or the actuator. Only then can a voice turn start. A
 * turn stopped before it was sent to the session ends at once, since the session never hears of it.
 *
 * <p>Not thread-safe by itself. {@link TypedChat} is the lock.
 */
final class TypedTurnState {
    /** What a stop did. */
    enum Stop {
        /** No typed turn is open: the press is not a typed stop. */
        NONE,
        /** The open turn was already stopping. */
        ALREADY,
        /** The session was never sent the turn: end it now. */
        LOCAL,
        /** The session has the turn: close it there (onTurnClosed) and end it on its endTurn. */
        SESSION
    }

    /** What a tool call from the session for {@code turnId} gets. */
    enum Tool {
        /** Not the open typed turn: someone else's call, or nobody's. */
        NOT_THIS_TURN,
        /** The owner stopped this turn: refused, not run. */
        STOPPED,
        RUN
    }

    /** How the turn ended: the reason to record, and whether the owner stopped it. */
    static final class Ended {
        final String reason;
        final boolean byOwner;

        Ended(String reason, boolean byOwner) {
            this.reason = reason;
            this.byOwner = byOwner;
        }
    }

    private String turnId; // the open typed turn, or null
    private boolean inSession; // onTypedTurn was sent for it
    private String stopReason; // the owner's stop, or null

    /** Opens {@code id}. False if a typed turn is already open. */
    boolean open(String id) {
        if (turnId != null) {
            return false;
        }
        turnId = id;
        inSession = false;
        stopReason = null;
        return true;
    }

    /** The open typed turn (stopping or not), or null. It holds the owner-turn slot until it ends. */
    String turnId() {
        return turnId;
    }

    boolean isOpen(String id) {
        return id != null && id.equals(turnId);
    }

    /** The owner stopped the open turn, and the session has not yet confirmed its end. */
    boolean stopping() {
        return turnId != null && stopReason != null;
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

    /** The owner's stop, with the reason to record. */
    Stop stop(String reason) {
        if (turnId == null) {
            return Stop.NONE;
        }
        if (stopReason != null) {
            return Stop.ALREADY;
        }
        stopReason = reason;
        return inSession ? Stop.SESSION : Stop.LOCAL;
    }

    /** The session's reply is shown only for the open turn, and not once the owner has stopped it. */
    boolean acceptsReply(String id) {
        return isOpen(id) && stopReason == null;
    }

    Tool tool(String id) {
        if (!isOpen(id)) {
            return Tool.NOT_THIS_TURN;
        }
        return stopReason == null ? Tool.RUN : Tool.STOPPED;
    }

    /**
     * Ends {@code id}, which ended {@code how} (the session's endTurn, the backstop, the session
     * going down). The owner's stop is the reason recorded if there was one. Null if {@code id} is not
     * the open turn.
     */
    Ended end(String id, String how) {
        if (!isOpen(id)) {
            return null;
        }
        Ended e = stopReason != null ? new Ended(stopReason, true) : new Ended(how, false);
        turnId = null;
        inSession = false;
        stopReason = null;
        return e;
    }
}
