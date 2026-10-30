package com.vos.actuator;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reloads the chat thread from the session's {@code recentThread} off the main thread (#298). The
 * call blocks on the session, and a hung or hostile session must not hold the actuator's main thread
 * (the window would freeze, an ANR). One background thread runs the calls in order, so a hung session
 * ties up only that thread. Each result comes back on the main thread and is adopted only if its load
 * is still the latest: a newer load, or its peer going down, drops it.
 */
final class ChatThreadLoader {
    private final Executor background;
    private final Executor main;
    private final Consumer<String> adopt;
    private final AtomicInteger latest = new AtomicInteger();

    /** {@code background} runs the blocking calls; {@code main} runs what follows them (the main looper on the device). */
    ChatThreadLoader(Executor background, Executor main, Consumer<String> adopt) {
        this.background = background;
        this.main = main;
        this.adopt = adopt;
    }

    /** Starts a load that supersedes every older one; returns its number. */
    int begin() {
        return latest.incrementAndGet();
    }

    /**
     * Load {@code load}'s peer is up. Returns at once: {@code call} (the thread's JSON, or null if it
     * failed) runs on the background thread. Then, on the main thread, {@code done} runs, and the
     * result is adopted if {@code load} is still the latest.
     */
    void fetch(int load, Supplier<String> call, Runnable done) {
        background.execute(() -> {
            String json = call.get();
            main.execute(() -> {
                done.run();
                if (load == latest.get()) {
                    adopt.accept(json);
                }
            });
        });
    }

    /** Load {@code load}'s peer went down: a result still in flight for it is dropped, as if a newer load had begun. */
    void drop(int load) {
        latest.compareAndSet(load, load + 1);
    }
}
