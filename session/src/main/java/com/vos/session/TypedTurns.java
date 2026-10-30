package com.vos.session;

import android.util.Log;
import com.vos.ipc.Ipc;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * Typed turns (decision 21): one at a time, off the Binder thread. Each goes through the shared
 * conversation like a voice turn (budget check, seed, owner / tool / VOS lines, compaction after) on
 * the {@code chat_text} backend, then answers the actuator with typedReply and ends the turn. The
 * typed text and the reply go to the {@link TranscriptLog}, never to logcat.
 *
 * <p>A turn not answered by {@link Ipc#TYPED_TURN_DEADLINE_MS} after it arrived is ended here, not by
 * the actuator's timer. Its {@link TurnGate} closes, its model call is cancelled, any tool call in
 * flight finishes first, and its compaction is queued; only then does endTurn go out. The actuator
 * frees the owner-turn slot on that endTurn, so nothing this turn sends later (a tool result, a reply,
 * a compaction) can land in a voice turn that starts next.
 */
final class TypedTurns {
    static final String TAG = "VOS_TYPED";
    /** The endTurn reason for a turn the deadline ended. */
    static final String DEADLINE = "typed turn deadline";
    /** What a tool call gets once the turn has ended: it does not reach the actuator. */
    static final String TURN_OVER = "{\"status\":\"FAILED\",\"detail\":\"the typed turn is over\"}";

    /** What a typed turn needs; supplied per turn, so a key or policy change applies at once. */
    interface Wiring {
        ContextKeeper context();

        /** The chat_text backend, or null if none is available (no key). */
        XaiTextBackend backend();

        Actuator actuator();

        TranscriptLog transcripts();
    }

    /** The actuator as a typed turn sees it: {@link ActuatorLink}. */
    interface Actuator extends ModelCalls {
        String callTool(String turnId, String name, String argsJson);

        void typedReply(String turnId, String text);

        void endTurn(String turnId, String reason);
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "vos-typed"));
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "vos-typed-deadline");
        t.setDaemon(true);
        return t;
    });
    private final Supplier<Wiring> wiring;
    private final long deadlineMs;

    TypedTurns(Supplier<Wiring> wiring) {
        this(wiring, Ipc.TYPED_TURN_DEADLINE_MS);
    }

    TypedTurns(Supplier<Wiring> wiring, long deadlineMs) {
        this.wiring = wiring;
        this.deadlineMs = deadlineMs;
        deadlines.setRemoveOnCancelPolicy(true); // a turn answered in time leaves nothing queued
    }

    /**
     * Runs the typed turn. {@code holdMs} (debug builds, eval/m1/chat.sh) holds it that long before
     * the model call, as a slow model would; 0 otherwise.
     */
    void open(String turnId, String text, long holdMs) {
        Wiring w = wiring.get();
        TurnGate gate = new TurnGate();
        long t0 = System.nanoTime();
        ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            if (end(w, turnId, gate, null, DEADLINE)) {
                Log.i(TAG, "typed turn " + turnId + " ended at its deadline ms=" + (System.nanoTime() - t0) / 1_000_000);
            }
        }, deadlineMs, TimeUnit.MILLISECONDS);
        worker.execute(() -> {
            try {
                run(w, turnId, text, gate, holdMs, t0);
            } finally {
                deadline.cancel(false);
            }
        });
    }

    private void run(Wiring w, String turnId, String text, TurnGate gate, long holdMs, long t0) {
        if (!gate.isOpen()) {
            return; // its deadline passed while it waited behind the turn before it
        }
        Actuator actuator = w.actuator();
        String refused = w.context().beforeTurn(turnId);
        XaiTextBackend backend = refused == null ? w.backend() : null;
        if (refused != null || backend == null) {
            String reason = refused != null ? refused : "no chat_text backend";
            if (gate.close(() -> {
                actuator.typedReply(turnId, "I can't answer right now (" + reason + ").");
                actuator.endTurn(turnId, reason);
            })) {
                Log.i(TAG, "typed turn " + turnId + " not answered: " + reason);
            }
            return;
        }
        List<JSONObject> seed = gate.ifOpen(() -> {
            List<JSONObject> s = w.context().seed(); // the conversation before this turn
            w.context().owner(turnId, text); // then the owner's line, its tool results, and the reply, in order
            w.transcripts().write(turnId, "owner", text);
            return s;
        }, null);
        if (seed == null) {
            return; // its deadline passed during the budget check
        }
        String reply = null;
        String failed = null;
        try {
            if (holdMs > 0) {
                Thread.sleep(holdMs);
            }
            reply = backend.reply(turnId, seed, text,
                    (id, name, args) -> gate.ifOpen(() -> actuator.callTool(id, name, args), TURN_OVER), actuator,
                    (id, name, result) -> gate.ifOpen(() -> w.context().tool(id, name, result)), gate);
        } catch (Exception e) {
            failed = e.getClass().getSimpleName();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (failed == null && end(w, turnId, gate, reply, "typed reply")) {
            Log.i(TAG, "typed turn " + turnId + " answered chars=" + reply.length() + " ms=" + ms);
        } else if (failed != null && end(w, turnId, gate, null, "typed turn failed: " + failed)) {
            Log.i(TAG, "typed turn " + turnId + " failed: " + failed);
        } else {
            Log.i(TAG, "typed turn " + turnId + " finished after its deadline; dropped ms=" + ms);
        }
    }

    /**
     * The turn's last writes, once: the reply (or, with {@code reply} null after a failure, an
     * apology; at the deadline, nothing), the compaction after the turn, then endTurn. False if the
     * turn had already ended.
     */
    private static boolean end(Wiring w, String turnId, TurnGate gate, String reply, String reason) {
        return gate.close(() -> {
            Actuator actuator = w.actuator();
            if (reply != null) {
                w.context().vos(turnId, reply);
                w.transcripts().write(turnId, "vos", reply);
                actuator.typedReply(turnId, reply);
            } else if (!DEADLINE.equals(reason)) {
                actuator.typedReply(turnId, "Something went wrong; I couldn't answer that.");
            }
            w.context().afterTurn(turnId);
            actuator.endTurn(turnId, reason);
        });
    }
}
