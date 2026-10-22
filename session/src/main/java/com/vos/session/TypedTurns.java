package com.vos.session;

import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Typed turns (decision 21): one at a time, off the Binder thread. Each goes through the shared
 * conversation like a voice turn (budget check, seed, owner / tool / VOS lines, compaction after) on
 * the {@code chat_text} backend, then answers the actuator with typedReply and ends the turn. The
 * typed text and the reply go to the {@link TranscriptLog}, never to logcat.
 */
final class TypedTurns {
    static final String TAG = "VOS_TYPED";

    /** What a typed turn needs; supplied per turn, so a key or policy change applies at once. */
    interface Wiring {
        ContextKeeper context();

        /** The chat_text backend, or null if none is available (no key). */
        XaiTextBackend backend();

        ActuatorLink actuator();

        TranscriptLog transcripts();
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "vos-typed"));
    private final Supplier<Wiring> wiring;

    TypedTurns(Supplier<Wiring> wiring) {
        this.wiring = wiring;
    }

    void open(String turnId, String text) {
        worker.execute(() -> run(turnId, text));
    }

    private void run(String turnId, String text) {
        Wiring w = wiring.get();
        ActuatorLink actuator = w.actuator();
        String reason;
        String refused = w.context().beforeTurn(turnId);
        XaiTextBackend backend = refused == null ? w.backend() : null;
        if (refused != null || backend == null) {
            reason = refused != null ? refused : "no chat_text backend";
            actuator.typedReply(turnId, "I can't answer right now (" + reason + ").");
            actuator.endTurn(turnId, reason);
            Log.i(TAG, "typed turn " + turnId + " not answered: " + reason);
            return;
        }
        long t0 = System.nanoTime();
        java.util.List<org.json.JSONObject> seed = w.context().seed(); // the conversation before this turn
        w.context().owner(turnId, text); // then the owner's line, its tool results, and the reply, in order
        w.transcripts().write(turnId, "owner", text);
        try {
            String reply = backend.reply(turnId, seed, text, actuator::callTool, actuator,
                    (id, name, result) -> w.context().tool(id, name, result));
            w.context().vos(turnId, reply);
            w.transcripts().write(turnId, "vos", reply);
            actuator.typedReply(turnId, reply);
            reason = "typed reply";
            Log.i(TAG, "typed turn " + turnId + " answered chars=" + reply.length()
                    + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception e) {
            reason = "typed turn failed: " + e.getClass().getSimpleName();
            actuator.typedReply(turnId, "Something went wrong; I couldn't answer that.");
            Log.i(TAG, "typed turn " + turnId + " failed: " + e.getClass().getSimpleName());
        } finally {
            w.context().afterTurn(turnId);
        }
        actuator.endTurn(turnId, reason);
    }
}
