package com.vos.actuator;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Paces the turn notification's title, which carries the indicator when no overlay can be drawn.
 * Android drops a package's notification updates beyond about 5 a second, so the title changes at
 * most once per {@link #MIN_GAP_MS}, and changes in between coalesce to the latest. One exception:
 * an ACTING not yet published is never replaced by a later GENERATING or ACTING, because the owner
 * must be able to see that VOS acted; it is published first and the later state follows after the
 * gap. IDLE (the turn ending) replaces anything. Main thread only.
 */
final class TitlePacer {
    static final long MIN_GAP_MS = 250;

    /** Runs a task after a delay in milliseconds (the main looper on the device). */
    interface Scheduler {
        void after(long delayMs, Runnable task);
    }

    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final Consumer<Indicator.State> publisher;
    private Indicator.State pending; // published at the scheduled post
    private Indicator.State after; // follows an unpublished ACTING, or null
    private boolean scheduled;
    private long lastAt = -MIN_GAP_MS;

    TitlePacer(LongSupplier clock, Scheduler scheduler, Consumer<Indicator.State> publisher) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.publisher = publisher;
    }

    void changed(Indicator.State state) {
        if (scheduled && pending == Indicator.State.ACTING && state != Indicator.State.IDLE) {
            after = state;
            return;
        }
        pending = state;
        after = null;
        schedule();
    }

    private void schedule() {
        if (scheduled) {
            return;
        }
        scheduled = true;
        scheduler.after(Math.max(0, lastAt + MIN_GAP_MS - clock.getAsLong()), this::publish);
    }

    private void publish() {
        scheduled = false;
        lastAt = clock.getAsLong();
        Indicator.State published = pending;
        publisher.accept(published);
        if (after != null && after != published) {
            pending = after;
            after = null;
            schedule();
        }
        after = null;
    }
}
