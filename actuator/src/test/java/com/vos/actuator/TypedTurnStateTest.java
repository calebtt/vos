package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The owner can stop a typed turn mid-way (P5, #300). A stop takes effect in the actuator at once
 * (no reply shown, no new tool run), but the turn keeps the owner-turn slot until the session
 * confirms with endTurn that its side is closed, so nothing of the stopped turn lands beside a voice
 * turn that starts next. A turn the session never had ends at once.
 */
public class TypedTurnStateTest {
    private static TypedTurnState inSession(String id) {
        TypedTurnState s = new TypedTurnState();
        assertTrue(s.open(id));
        assertTrue(s.sendToSession(id));
        return s;
    }

    @Test
    public void aStopWithNoTypedTurnOpenIsNotATypedStop() {
        TypedTurnState s = new TypedTurnState();
        assertEquals(TypedTurnState.Stop.NONE, s.stop("owner stop: qs_tile"));
        assertNull(s.turnId());
        assertFalse(s.stopping());
    }

    @Test
    public void aStopAfterTheSessionHasTheTurnClosesItThereAndHoldsTheSlotUntilItsEndTurn() {
        TypedTurnState s = inSession("a");
        assertEquals(TypedTurnState.Stop.SESSION, s.stop("owner stop: window"));
        assertTrue(s.stopping());
        assertEquals("still open: the slot waits for the session's endTurn", "a", s.turnId());
        assertTrue(s.isOpen("a"));
        assertFalse("the reply is not shown after the stop", s.acceptsReply("a"));
        assertEquals("a tool call that had not started is refused", TypedTurnState.Tool.STOPPED, s.tool("a"));
    }

    @Test
    public void aStopBeforeTheSessionWasReachedEndsItLocallyAndTheTurnIsNeverSent() {
        TypedTurnState s = new TypedTurnState();
        assertTrue(s.open("a"));
        assertEquals(TypedTurnState.Stop.LOCAL, s.stop("owner stop: notification"));
        assertFalse("a link that comes up later does not send it", s.sendToSession("a"));
        TypedTurnState.Ended e = s.end("a", "stopped before the session had it");
        assertEquals("owner stop: notification", e.reason);
        assertTrue(e.byOwner);
        assertNull(s.turnId());
    }

    @Test
    public void aSecondStopDoesNothingMore() {
        TypedTurnState s = inSession("a");
        assertEquals(TypedTurnState.Stop.SESSION, s.stop("owner stop: window"));
        assertEquals(TypedTurnState.Stop.ALREADY, s.stop("owner stop: qs_tile"));
        assertEquals("the first stop is the one recorded", "owner stop: window", s.end("a", "session: closed").reason);
    }

    @Test
    public void theOwnersStopIsTheReasonRecordedHoweverTheTurnThenEnds() {
        for (String how : new String[] {"session: closed by the actuator", "session: typed reply",
                "typed turn timed out: no end from the session", "session down: binder died"}) {
            TypedTurnState s = inSession("a");
            s.stop("owner stop: assist");
            TypedTurnState.Ended e = s.end("a", how);
            assertEquals(how, "owner stop: assist", e.reason);
            assertTrue(how, e.byOwner);
        }
    }

    @Test
    public void endingTheStoppedTurnFreesItAndTheNextTurnIsNotStopped() {
        TypedTurnState s = inSession("a");
        s.stop("owner stop: window");
        assertNull("another id does not end it", s.end("b", "session: closed by the actuator"));
        assertTrue(s.isOpen("a"));
        s.end("a", "session: closed by the actuator");
        assertNull(s.turnId());
        assertFalse(s.stopping());
        assertEquals("a late tool call is not this turn's: it is refused as no open turn",
                TypedTurnState.Tool.NOT_THIS_TURN, s.tool("a"));
        assertFalse(s.acceptsReply("a"));
        assertTrue(s.open("b"));
        assertFalse(s.stopping());
        assertTrue(s.sendToSession("b"));
        assertTrue(s.acceptsReply("b"));
        assertEquals(TypedTurnState.Tool.RUN, s.tool("b"));
    }

    @Test
    public void aTurnThatIsNotStoppedEndsWithItsOwnReason() {
        TypedTurnState s = inSession("a");
        TypedTurnState.Ended e = s.end("a", "session: typed reply");
        assertEquals("session: typed reply", e.reason);
        assertFalse(e.byOwner);
    }

    @Test
    public void theTurnIsSentToTheSessionOnceAndOnlyWhileOpen() {
        TypedTurnState s = new TypedTurnState();
        assertFalse("no open turn", s.sendToSession("a"));
        assertTrue(s.open("a"));
        assertFalse("only one typed turn at a time", s.open("b"));
        assertFalse("not another id", s.sendToSession("b"));
        assertTrue(s.sendToSession("a"));
        assertFalse("a rebind does not send it twice", s.sendToSession("a"));
    }

    @Test
    public void onlyTheOpenTurnsRepliesAndToolsAreAccepted() {
        TypedTurnState s = inSession("a");
        assertTrue(s.acceptsReply("a"));
        assertFalse(s.acceptsReply("forged"));
        assertFalse(s.acceptsReply(null));
        assertEquals(TypedTurnState.Tool.RUN, s.tool("a"));
        assertEquals(TypedTurnState.Tool.NOT_THIS_TURN, s.tool("voice-turn"));
        assertEquals(TypedTurnState.Tool.NOT_THIS_TURN, s.tool(null));
    }
}
