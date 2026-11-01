package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The owner can stop a typed turn mid-way (P5, #300). A stop takes effect in the actuator at once
 * (no reply shown, no new tool run), and the turn keeps the owner-turn slot until the session
 * confirms with endTurn that its side is closed, or 5 s after the stop, whichever is first (Caleb's
 * call on #303). After that stop backstop the turn's late writes are still refused, so nothing of it
 * lands beside a voice turn that starts next. A turn the session never had ends at once. A turn
 * that is not stopped keeps the 60 s backstop.
 */
public class TypedTurnStateTest {
    private static TypedTurnState inSession(String id) {
        return inSession(id, 0);
    }

    private static TypedTurnState inSession(String id, long openedAt) {
        TypedTurnState s = new TypedTurnState();
        assertTrue(s.open(id, openedAt));
        assertTrue(s.sendToSession(id));
        return s;
    }

    @Test
    public void aStopWithNoTypedTurnOpenIsNotATypedStop() {
        TypedTurnState s = new TypedTurnState();
        assertEquals(TypedTurnState.Stop.NONE, s.stop("owner stop: qs_tile", 1_000));
        assertNull(s.turnId());
        assertFalse(s.stopping());
    }

    @Test
    public void aStopAfterTheSessionHasTheTurnClosesItThereAndHoldsTheSlotUntilItsEndTurn() {
        TypedTurnState s = inSession("a");
        assertEquals(TypedTurnState.Stop.SESSION, s.stop("owner stop: window", 1_000));
        assertTrue(s.stopping());
        assertEquals("still open: the slot waits for the session's endTurn", "a", s.turnId());
        assertTrue(s.isOpen("a"));
        assertFalse("the reply is not shown after the stop", s.acceptsReply("a"));
        assertEquals("a tool call that had not started is refused", TypedTurnState.Tool.STOPPED, s.tool("a"));
    }

    @Test
    public void aStopBeforeTheSessionWasReachedEndsItLocallyAndTheTurnIsNeverSent() {
        TypedTurnState s = new TypedTurnState();
        assertTrue(s.open("a", 0));
        assertEquals(TypedTurnState.Stop.LOCAL, s.stop("owner stop: notification", 1_000));
        assertFalse("a link that comes up later does not send it", s.sendToSession("a"));
        TypedTurnState.Ended e = s.end("a", "stopped before the session had it");
        assertEquals("owner stop: notification", e.reason);
        assertTrue(e.byOwner);
        assertNull(s.turnId());
    }

    @Test
    public void aSecondStopDoesNothingMore() {
        TypedTurnState s = inSession("a");
        assertEquals(TypedTurnState.Stop.SESSION, s.stop("owner stop: window", 1_000));
        assertEquals(TypedTurnState.Stop.ALREADY, s.stop("owner stop: qs_tile", 1_000));
        assertEquals("the first stop is the one recorded", "owner stop: window", s.end("a", "session: closed").reason);
    }

    @Test
    public void theOwnersStopIsTheReasonRecordedHoweverTheTurnThenEnds() {
        for (String how : new String[] {"session: closed by the actuator", "session: typed reply",
                "typed turn timed out: no end from the session", "session down: binder died"}) {
            TypedTurnState s = inSession("a");
            s.stop("owner stop: assist", 1_000);
            TypedTurnState.Ended e = s.end("a", how);
            assertEquals(how, "owner stop: assist", e.reason);
            assertTrue(how, e.byOwner);
        }
    }

    @Test
    public void endingTheStoppedTurnFreesItAndTheNextTurnIsNotStopped() {
        TypedTurnState s = inSession("a");
        s.stop("owner stop: window", 1_000);
        assertNull("another id does not end it", s.end("b", "session: closed by the actuator"));
        assertTrue(s.isOpen("a"));
        s.end("a", "session: closed by the actuator");
        assertNull(s.turnId());
        assertFalse(s.stopping());
        assertEquals("a late tool call is not this turn's: it is refused as no open turn",
                TypedTurnState.Tool.NOT_THIS_TURN, s.tool("a"));
        assertFalse(s.acceptsReply("a"));
        assertTrue(s.open("b", 0));
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
        assertTrue(s.open("a", 0));
        assertFalse("only one typed turn at a time", s.open("b", 0));
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

    @Test
    public void aStopConfirmedBeforeFiveSecondsEndsOnTheSessionsEndTurnAndTheStopBackstopDoesNothing() {
        TypedTurnState s = inSession("a", 0);
        assertEquals(TypedTurnState.Stop.SESSION, s.stop("owner stop: qs_tile", 1_000));
        assertEquals("the stop backstop is 5 s after the stop", 6_000, s.backstopAt());
        TypedTurnState.Ended e = s.end("a", "session: closed by the actuator");
        assertEquals("owner stop: qs_tile", e.reason);
        assertTrue(e.byOwner);
        assertEquals("ended by the session's confirmation", "session: closed by the actuator", e.how);
        assertNull("the slot is free", s.turnId());
        assertNull("the stop backstop, coming due later, ends nothing", s.backstop("a", 6_000));
        assertFalse("a confirmed stop is not remembered as unconfirmed", s.unconfirmed("a"));
        assertFalse(s.lateEnd("a"));
    }

    @Test
    public void withNoConfirmationTheStopBackstopFreesTheSlotFiveSecondsAfterTheStop() {
        TypedTurnState s = inSession("a", 0);
        s.stop("owner stop: qs_tile", 1_000);
        assertNull("not yet due", s.backstop("a", 5_999));
        assertEquals("the slot is held until then", "a", s.turnId());
        assertTrue(s.stopping());
        TypedTurnState.Ended e = s.backstop("a", 6_000);
        assertEquals("the owner's stop is still the reason recorded", "owner stop: qs_tile", e.reason);
        assertTrue(e.byOwner);
        assertEquals(TypedTurnState.STOP_BACKSTOP_REASON, e.how);
        assertNull("the slot is free", s.turnId());
        assertFalse(s.stopping());
        assertTrue("a voice or typed turn can open now", s.open("b", 6_001));
        assertEquals(5_000, TypedTurnState.STOP_BACKSTOP_MS);
    }

    @Test
    public void afterTheStopBackstopTheStoppedTurnsLateWritesAreStillRefused() {
        TypedTurnState s = inSession("a", 0);
        s.stop("owner stop: window", 1_000);
        s.backstop("a", 6_000);
        assertTrue("remembered until the session confirms", s.unconfirmed("a"));
        assertFalse("its late reply is not shown", s.acceptsReply("a"));
        assertTrue("and is refused as the stopped turn's", s.stopped("a"));
        assertEquals("its late tool call is refused here, not passed on to a voice turn",
                TypedTurnState.Tool.STOPPED, s.tool("a"));
        assertTrue(s.open("b", 7_000));
        assertTrue(s.sendToSession("b"));
        assertEquals("still refused while the next turn is open", TypedTurnState.Tool.STOPPED, s.tool("a"));
        assertFalse(s.acceptsReply("a"));
        assertNull("its late endTurn does not end the next turn", s.end("a", "session: closed by the actuator"));
        assertEquals("b", s.turnId());
        assertTrue("its late endTurn is its confirmation, once", s.lateEnd("a"));
        assertFalse(s.lateEnd("a"));
        assertFalse(s.unconfirmed("a"));
        assertEquals("the next turn's tools run", TypedTurnState.Tool.RUN, s.tool("b"));
        assertTrue(s.acceptsReply("b"));
        assertFalse("the next turn is not stopped", s.stopped("b"));
    }

    @Test
    public void aTurnThatIsNotStoppedKeepsTheSixtySecondBackstop() {
        TypedTurnState s = inSession("a", 1_000);
        assertEquals(60_000, TypedTurnState.BACKSTOP_MS);
        assertEquals(61_000, s.backstopAt());
        assertNull("5 s after it opened is nothing", s.backstop("a", 6_000));
        assertNull(s.backstop("a", 60_999));
        assertEquals("a", s.turnId());
        TypedTurnState.Ended e = s.backstop("a", 61_000);
        assertEquals(TypedTurnState.BACKSTOP_REASON, e.reason);
        assertFalse(e.byOwner);
        assertNull(s.turnId());
        assertFalse("not stopped: nothing to remember", s.unconfirmed("a"));
        assertEquals(TypedTurnState.Tool.NOT_THIS_TURN, s.tool("a"));
    }

    @Test
    public void aStopLateInTheTurnKeepsTheEarlierBackstop() {
        TypedTurnState s = inSession("a", 0);
        s.stop("owner stop: window", 58_000);
        assertEquals("whichever is first: the 60 s backstop", 60_000, s.backstopAt());
    }

    @Test
    public void aStopBeforeTheSessionHadTheTurnLeavesNothingToConfirm() {
        TypedTurnState s = new TypedTurnState();
        s.open("a", 0);
        assertEquals(TypedTurnState.Stop.LOCAL, s.stop("owner stop: window", 1_000));
        s.end("a", "stopped before the session had it");
        assertFalse(s.unconfirmed("a"));
        assertNull(s.backstop("a", 6_000));
    }

    @Test
    public void onlyTheLatestUnconfirmedStopsAreRemembered() {
        TypedTurnState s = new TypedTurnState();
        int n = TypedTurnState.MAX_UNCONFIRMED + 1;
        for (int i = 0; i < n; i++) {
            String id = "t" + i;
            long t0 = i * 10_000L;
            s.open(id, t0);
            s.sendToSession(id);
            s.stop("owner stop: window", t0);
            s.backstop(id, t0 + TypedTurnState.STOP_BACKSTOP_MS);
        }
        assertFalse("the oldest is dropped", s.unconfirmed("t0"));
        assertTrue(s.unconfirmed("t1"));
        assertTrue(s.unconfirmed("t" + (n - 1)));
    }
}
