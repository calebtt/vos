package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * A typed turn's gate (decision 21): it closes once, and the close that started first makes the last
 * writes, even when a later close gets the lock first (#303). Before the fix, a deadline or the
 * owner's stop that began closing while the worker held the gate for a step lost to the worker's own
 * "failed" close once the step returned: the turn ended with an apology and the wrong reason.
 */
public class TurnGateTest {
    private final List<String> last = new ArrayList<>();

    private synchronized void wrote(String who) {
        last.add(who);
    }

    private synchronized List<String> last() {
        return new ArrayList<>(last);
    }

    @Test
    public void theCloseThatStartsFirstMakesTheLastWritesEvenIfALaterOneGetsTheLockFirst() throws Exception {
        TurnGate gate = new TurnGate();
        CountDownLatch stepRunning = new CountDownLatch(1);
        CountDownLatch deadlineStarted = new CountDownLatch(1);
        boolean[] workerClosed = new boolean[1];
        Thread deadline = new Thread(() -> {
            assertTrue(awaitQuietly(stepRunning));
            deadlineStarted.countDown();
            gate.close(() -> wrote("deadline")); // waits for the step in progress
        });
        deadline.start();
        // The worker's step holds the gate. The deadline starts closing meanwhile; then the worker,
        // still holding the lock (the lock is reentrant, so it gets there first for certain), fails
        // and closes too, as XaiTextBackend's "typed turn over" did once the step returned.
        assertTrue(gate.ifOpen(() -> {
            stepRunning.countDown();
            assertTrue(awaitQuietly(deadlineStarted));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gate.isOpen()) {
                assertTrue("the deadline never started closing", System.nanoTime() < until);
                Thread.onSpinWait();
            }
            workerClosed[0] = gate.close(() -> wrote("worker"));
        }));
        deadline.join(5_000);
        assertFalse(deadline.isAlive());
        assertFalse("the worker's close came second: it writes nothing", workerClosed[0]);
        assertEquals("the deadline's last writes, once, after the step", List.of("deadline"), last());
    }

    @Test
    public void aClosedGateRunsNoStepAndClosesOnce() {
        TurnGate gate = new TurnGate();
        assertTrue(gate.isOpen());
        assertTrue(gate.close(() -> wrote("first")));
        assertFalse(gate.isOpen());
        assertFalse(gate.close(() -> wrote("second")));
        assertFalse("no step after the close", gate.ifOpen(() -> wrote("step")));
        assertNull(gate.ifOpen(() -> "result", null));
        assertEquals(List.of("first"), last());
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
