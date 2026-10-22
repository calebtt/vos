package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import org.junit.Test;

public class TitlePacerTest {
    private static final Indicator.State IDLE = Indicator.State.IDLE;
    private static final Indicator.State GENERATING = Indicator.State.GENERATING;
    private static final Indicator.State ACTING = Indicator.State.ACTING;

    /** A manual clock and a queue of delayed tasks, run in time order as the clock advances. */
    private long now = 1000;
    private long seq;
    private final PriorityQueue<long[]> due = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<String> published = new ArrayList<>();
    private final List<Long> publishedAt = new ArrayList<>();
    private final TitlePacer pacer = new TitlePacer(() -> now, (delay, task) -> {
        tasks.add(task);
        due.add(new long[] {now + delay, seq++, tasks.size() - 1});
    }, s -> {
        published.add(s.name());
        publishedAt.add(now);
    });

    private void advance(long ms) {
        long until = now + ms;
        while (!due.isEmpty() && due.peek()[0] <= until) {
            long[] next = due.poll();
            now = next[0];
            tasks.get((int) next[2]).run();
        }
        now = until;
    }

    @Test
    public void aBurstCoalescesToTheLatestState() {
        pacer.changed(GENERATING);
        advance(0);
        pacer.changed(IDLE);
        pacer.changed(GENERATING);
        pacer.changed(IDLE);
        advance(1000);
        assertEquals(List.of("GENERATING", "IDLE"), published);
        assertEquals(250, publishedAt.get(1) - publishedAt.get(0));
    }

    @Test
    public void aShortActingIsPublishedBeforeTheStateAfterIt() {
        pacer.changed(GENERATING);
        advance(0);
        advance(100);
        pacer.changed(ACTING); // a 10 ms tool, inside the gap
        advance(10);
        pacer.changed(GENERATING);
        advance(1000);
        assertEquals(List.of("GENERATING", "ACTING", "GENERATING"), published);
        assertEquals(250, publishedAt.get(1) - publishedAt.get(0));
        assertEquals(250, publishedAt.get(2) - publishedAt.get(1));
    }

    @Test
    public void bothQueuedBeforeTheFirstPostStillShowActing() {
        pacer.changed(ACTING); // the looper had not run the post yet when the tool returned
        pacer.changed(GENERATING);
        advance(1000);
        assertEquals(List.of("ACTING", "GENERATING"), published);
    }

    @Test
    public void aMidTurnIdleDoesNotReplaceAnUnpublishedActing() {
        pacer.changed(GENERATING);
        advance(0);
        pacer.changed(IDLE); // the response is done
        pacer.changed(ACTING); // a fast tool runs and returns inside the gap, with no call open
        pacer.changed(IDLE);
        advance(1000);
        assertEquals(List.of("GENERATING", "ACTING", "IDLE"), published);
        assertEquals(250, publishedAt.get(1) - publishedAt.get(0));
        assertEquals(250, publishedAt.get(2) - publishedAt.get(1));
    }

    @Test
    public void neverTwoTitlesCloserThanTheGap() {
        Indicator.State[] states = {GENERATING, ACTING, GENERATING, IDLE, GENERATING, ACTING, IDLE};
        for (int i = 0; i < 40; i++) {
            pacer.changed(states[i % states.length]);
            advance(37);
        }
        advance(2000);
        for (int i = 1; i < publishedAt.size(); i++) {
            assertTrue("gap " + i, publishedAt.get(i) - publishedAt.get(i - 1) >= TitlePacer.MIN_GAP_MS);
        }
        assertEquals("the last state wins", states[39 % states.length].name(), published.get(published.size() - 1));
    }
}
