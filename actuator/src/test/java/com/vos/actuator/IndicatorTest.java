package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.vos.actuator.Indicator.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Shell Contract row 13 / M1-IND-01: what the indicator shows, decided by the actuator alone. */
public class IndicatorTest {
    private final List<State> drawn = new ArrayList<>();
    private final Indicator ind = new Indicator(drawn::add);

    @Test
    public void aModelCallSpinsUntilItEnds() {
        ind.turnOpened("t1");
        assertTrue(ind.callStarted("t1", "r1"));
        assertEquals(State.GENERATING, ind.state());
        ind.callEnded("t1", "r1");
        assertEquals(State.IDLE, ind.state());
        assertEquals(List.of(State.GENERATING, State.IDLE), drawn);
    }

    @Test
    public void itSpinsWhileAnyCallIsOpen() {
        ind.turnOpened("t1");
        ind.callStarted("t1", "r1");
        ind.callStarted("t1", "r2");
        ind.callEnded("t1", "r1");
        assertEquals(State.GENERATING, ind.state());
        ind.callEnded("t1", "r2");
        assertEquals(State.IDLE, ind.state());
        assertEquals("one draw per change, not per event", List.of(State.GENERATING, State.IDLE), drawn);
    }

    @Test
    public void actingWinsWhileAToolRunsThenGeneratingReturns() {
        ind.turnOpened("t1");
        ind.callStarted("t1", "r1");
        ind.actingStarted("t1");
        assertEquals(State.ACTING, ind.state());
        ind.actingEnded("t1");
        assertEquals(State.GENERATING, ind.state());
        ind.callEnded("t1", "r1");
        assertEquals(List.of(State.GENERATING, State.ACTING, State.GENERATING, State.IDLE), drawn);
    }

    @Test
    public void aStalledCallKeepsSpinning() {
        ind.turnOpened("t1");
        ind.callStarted("t1", "r1");
        // No end and no time passes in a state machine without a clock: there is no timeout to clear it.
        assertEquals(State.GENERATING, ind.state());
        ind.callEnded("t1", "other"); // the end of a call that never started changes nothing
        assertEquals(State.GENERATING, ind.state());
    }

    @Test
    public void theTurnEndingClearsBothStates() {
        ind.turnOpened("t1");
        ind.callStarted("t1", "r1");
        ind.actingStarted("t1");
        ind.turnEnded("t1");
        assertEquals(State.IDLE, ind.state());
        assertFalse("no turn open: nothing shows", ind.callStarted("t1", "r2"));
        ind.actingStarted("t1");
        assertEquals(State.IDLE, ind.state());
    }

    @Test
    public void onlyTheOpenTurnCounts() {
        assertFalse("no turn yet", ind.callStarted("t1", "r1"));
        ind.turnOpened("t2");
        assertFalse("an old or unknown turn", ind.callStarted("t1", "r1"));
        ind.actingStarted("t1");
        assertEquals(State.IDLE, ind.state());
        ind.turnEnded("t1"); // a stale end does not clear the open turn
        ind.callStarted("t2", "r1");
        ind.turnEnded("t1");
        assertEquals(State.GENERATING, ind.state());
        assertTrue(drawn.stream().allMatch(s -> s == State.GENERATING));
    }

    @Test
    public void aNewTurnStartsClean() {
        ind.turnOpened("t1");
        ind.callStarted("t1", "r1");
        ind.turnOpened("t2"); // the actuator replaced the turn without an end (it never does, but clean anyway)
        assertEquals(State.IDLE, ind.state());
        ind.callEnded("t1", "r1");
        assertEquals(State.IDLE, ind.state());
    }

    @Test
    public void openCallsAreCapped() {
        ind.turnOpened("t1");
        for (int k = 0; k < Indicator.MAX_CALLS; k++) {
            assertTrue(ind.callStarted("t1", "r" + k));
        }
        assertFalse(ind.callStarted("t1", "one-too-many"));
        for (int k = 0; k < Indicator.MAX_CALLS; k++) {
            ind.callEnded("t1", "r" + k);
        }
        assertEquals(State.IDLE, ind.state());
    }

    @Test
    public void malformedIdsAreRefusedNotClipped() {
        ind.turnOpened("t1");
        assertFalse(ind.callStarted(null, "r1"));
        assertFalse(ind.callStarted("t1", null));
        assertFalse(ind.callStarted("t1", ""));
        assertFalse(ind.callStarted("t1", "has space"));
        assertFalse(ind.callStarted("t1", "x".repeat(65)));
        assertFalse(ind.callStarted("t1", "nul\u0000"));
        assertTrue(ind.callStarted("t1", "x".repeat(64)));
        ind.callEnded("t1", null); // nothing to end, no throw
        assertEquals(State.GENERATING, ind.state());
    }

    @Test
    public void unbalancedActingEndsDoNotGoNegative() {
        ind.turnOpened("t1");
        ind.actingEnded("t1");
        ind.actingStarted("t1");
        assertEquals(State.ACTING, ind.state());
        ind.actingEnded("t1");
        assertEquals(State.IDLE, ind.state());
    }
}
