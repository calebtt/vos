package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * #298: {@code recentThread} blocks on the session, so the chat window's reload must not run it on
 * the actuator's main thread, where a hung or hostile session would freeze the window (an ANR).
 */
public class ChatThreadLoaderTest {
    private final Deque<Runnable> background = new ArrayDeque<>();
    private final Deque<Runnable> main = new ArrayDeque<>();
    private final List<String> adopted = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final ChatThreadLoader loader = new ChatThreadLoader(background::add, main::add, json -> {
        adopted.add(json);
        events.add("adopt " + json);
    });

    /** Runs the background calls, then what they posted to the main thread. */
    private void runAll() {
        while (!background.isEmpty() || !main.isEmpty()) {
            Runnable next = background.isEmpty() ? main.poll() : background.poll();
            next.run();
        }
    }

    @Test(timeout = 10_000)
    public void onPeerUpReturnsWhileRecentThreadIsStillBlocked() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        LinkedBlockingQueue<Runnable> mainQueue = new LinkedBlockingQueue<>();
        Thread mainThread = Thread.currentThread();
        Thread[] adoptedOn = new Thread[1];
        ChatThreadLoader real = new ChatThreadLoader(worker, mainQueue::add, json -> {
            adoptedOn[0] = Thread.currentThread();
            adopted.add(json);
        });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            int load = real.begin();
            // A hung session: the call does not return until released. fetch must not wait for it.
            real.fetch(load, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "[{\"role\":\"vos\",\"text\":\"hi\"}]";
            }, () -> events.add("unbind"));
            assertTrue("the call runs on the background thread", started.await(5, TimeUnit.SECONDS));
            assertTrue("nothing is adopted while the call is blocked", adopted.isEmpty());
            assertTrue(mainQueue.isEmpty());

            release.countDown();
            Runnable delivery = mainQueue.poll(5, TimeUnit.SECONDS);
            assertNotNull("the result is posted to the main thread", delivery);
            assertTrue("and not adopted on the background thread", adopted.isEmpty());
            delivery.run();
            assertEquals(List.of("[{\"role\":\"vos\",\"text\":\"hi\"}]"), adopted);
            assertSame("adopted on the main thread", mainThread, adoptedOn[0]);
            assertEquals(List.of("unbind"), events);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void theLinkIsUnboundBeforeTheResultIsAdopted() {
        int load = loader.begin();
        loader.fetch(load, () -> "a", () -> events.add("unbind"));
        runAll();
        assertEquals(List.of("unbind", "adopt a"), events);
    }

    @Test
    public void aNewerLoadDropsAnOlderResultStillInFlight() {
        int first = loader.begin();
        loader.fetch(first, () -> "old", () -> events.add("unbind old"));
        int second = loader.begin();
        loader.fetch(second, () -> "new", () -> events.add("unbind new"));
        runAll();
        assertEquals(List.of("new"), adopted);
        assertTrue("the older link is still unbound", events.contains("unbind old"));
    }

    @Test
    public void anOlderResultArrivingLastDoesNotReplaceTheNewerOne() {
        int first = loader.begin();
        int second = loader.begin();
        loader.fetch(second, () -> "new", () -> { });
        runAll();
        loader.fetch(first, () -> "old", () -> events.add("unbind old")); // the old session answered late
        runAll();
        assertEquals(List.of("new"), adopted);
        assertTrue(events.contains("unbind old"));
    }

    @Test
    public void thePeerGoingDownDropsItsResult() {
        int load = loader.begin();
        loader.fetch(load, () -> "stale", () -> events.add("unbind"));
        loader.drop(load); // onPeerDown, while the call is in flight
        runAll();
        assertTrue(adopted.isEmpty());
        assertEquals(List.of("unbind"), events);
    }

    @Test
    public void anOlderLoadsPeerGoingDownLeavesTheNewerLoad() {
        int first = loader.begin();
        int second = loader.begin();
        loader.fetch(second, () -> "new", () -> { });
        loader.drop(first);
        runAll();
        assertEquals(List.of("new"), adopted);
    }

    @Test
    public void aLoadAfterADroppedOneIsAdopted() {
        int first = loader.begin();
        loader.drop(first);
        int second = loader.begin();
        loader.fetch(second, () -> "fresh", () -> { });
        runAll();
        assertEquals(List.of("fresh"), adopted);
    }
}
