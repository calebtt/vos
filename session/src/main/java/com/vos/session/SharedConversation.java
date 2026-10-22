package com.vos.session;

import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * The voice turns' shared conversation (decision 46, #270): one {@link Conversation} for every turn,
 * kept under the {@code chat_voice} budget by the {@code compact} task class. Compaction runs in the
 * background after a turn; the next turn waits for it, then checks the budget once more, and does not
 * start if the conversation still cannot fit (no request ever goes out over the window).
 */
final class SharedConversation implements ContextKeeper {
    static final String TAG = "VOS_CONTEXT";
    /** How long a new turn waits for the last turn's compaction before checking for itself. */
    static final long WAIT_MS = 20_000;

    private final Conversation conversation;
    private final Supplier<ContextBudget> budget;
    private final Supplier<Conversation.Compactor> compactor; // null when no compact model is available
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "vos-context"));
    private Future<?> pending;

    SharedConversation(Conversation conversation, Supplier<ContextBudget> budget,
            Supplier<Conversation.Compactor> compactor) {
        this.conversation = conversation;
        this.budget = budget;
        this.compactor = compactor;
    }

    @Override
    public String beforeTurn(String turnId) {
        Future<?> f;
        synchronized (this) {
            f = pending;
        }
        if (f != null) {
            try {
                f.get(WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // checked below, on this thread
            }
        }
        ContextBudget b = budget.get();
        if (b.fits(conversation.summary(), conversation.items())) {
            Log.i(TAG, "turn " + turnId + " context tokens~" + conversation.tokens() + " budget=" + b.budget);
            return null;
        }
        Conversation.Compactor c = compactor.get();
        boolean ok = c != null && conversation.fit(b, c);
        Log.i(TAG, "turn " + turnId + " context tokens~" + conversation.tokens() + " budget=" + b.budget
                + (ok ? "" : " over budget"));
        return ok ? null : "context over budget";
    }

    @Override
    public List<JSONObject> seed() {
        return conversation.seed();
    }

    @Override
    public void owner(String turnId, String text) {
        conversation.add(Conversation.Role.OWNER, turnId, text);
    }

    @Override
    public void vos(String turnId, String text) {
        conversation.add(Conversation.Role.VOS, turnId, text);
    }

    @Override
    public void tool(String turnId, String name, String result) {
        conversation.add(Conversation.Role.TOOL, turnId, name + " -> " + result);
    }

    @Override
    public synchronized void afterTurn(String turnId) {
        pending = worker.submit(() -> {
            ContextBudget b = budget.get();
            if (b.fits(conversation.summary(), conversation.items())) {
                return;
            }
            Conversation.Compactor c = compactor.get();
            boolean ok = c != null && conversation.fit(b, c);
            Log.i(TAG, "after turn " + turnId + " compacted to tokens~" + conversation.tokens() + " budget=" + b.budget
                    + (ok ? "" : " (still over; the next turn retries)"));
        });
    }
}
