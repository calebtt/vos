package com.vos.session;

import android.util.Log;
import com.vos.ipc.Ipc;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;

/**
 * The {@code chat_voice} backend on the xAI realtime API (§3.2). Per turn:
 * <ul>
 *   <li>the owner's audio is read from the mic pipe from the first moment and buffered, so words
 *       spoken while the WebSocket connects are kept, then streamed once the session is configured;</li>
 *   <li>reply audio goes into the speaker pipe in slices of at most {@link Timing#leadMs}, paced to real
 *       time plus that lead; a barge-in (the vendor's VAD hears the owner) drops the interrupted
 *       response's queued audio and any of its deltas that arrive afterwards, so at most about one
 *       lead of it is still in the pipe;</li>
 *   <li>each vendor response is a model call for the actuator's generating indicator ({@link ModelCalls}).
 *       The realtime API runs one response at a time, so the turn tracks one open call under ids of its
 *       own ("c1", "c2", …), whatever ids the vendor sends: {@code response.created} ends any open call
 *       and starts a new one, any {@code response.done} ends the open call, and so does the turn
 *       ending. A follow-up dropped because the owner is speaking is not reported: nothing is being
 *       generated for the owner;</li>
 *   <li>a tool call goes to the actuator ({@link ToolCaller}) off the WebSocket thread, and its result
 *       (VERIFIED, UNVERIFIED or FAILED from the actuator's oracle) goes back to the model. The calls
 *       of one response form a batch: the model is asked to answer once, after all their results. If
 *       the owner talks over that response (or it is cancelled), the results still go back but no
 *       response.create is sent: the vendor's VAD opens the next response itself;</li>
 *   <li>the quiet clock: it starts when the owner stops speaking (in case no reply comes), stops while
 *       reply audio arrives, and restarts once a completed reply has finished playing; a cancelled
 *       response (the owner talked over it) does not count as a finished reply;</li>
 *   <li>the turn ends when the owner stays quiet for {@link Timing#idleMs} after a reply (never while a
 *       tool call runs), never speaks within {@link Timing#firstSpeechMs}, reaches
 *       {@link Timing#maxTurnMs}, the model ends it ({@code end_turn}, once the goodbye has played and
 *       no tool call runs), or on a vendor error; the actuator ending it (the mic pipe closes) is not
 *       reported back.</li>
 * </ul>
 * The key comes from the {@link KeyVault} at the start of each turn and only ever goes into the
 * WebSocket's Authorization header. Transcripts go to the {@link TranscriptLog}; logcat gets event
 * names and timings only.
 */
final class XaiRealtimeBackend implements SessionTurns.Backend {
    static final String TAG = "VOS_VOICE";
    static final String END_TURN = "end_turn";
    /** The OpenAI-shaped error for a response.create while a response is active; not fatal here. */
    static final String ACTIVE_RESPONSE = "conversation_already_has_active_response";

    /** Runs a tool in the actuator for this turn; returns the result JSON (never throws). */
    interface ToolCaller {
        String call(String turnId, String name, String argsJson);
    }
    private static final int BYTES_PER_SECOND = Ipc.TURN_SAMPLE_RATE * 2;
    private static final int FRAMES_PER_SEND = 5; // 100 ms per append message

    /** Turn timing knobs (decision 31 targets are logged, not gated; tuned from S-40 later). */
    static final class Timing {
        long firstSpeechMs = 10_000;
        long idleMs = 8_000;
        long maxTurnMs = 180_000;
        long leadMs = 300;
    }

    private final Egress egress;
    private final KeyVault vault;
    private final ModelPolicy.Entry model;
    private final TranscriptLog transcripts;
    private final Supplier<String> instructions;
    private final JSONArray toolSchemas;
    private final ToolCaller tools;
    private final ModelCalls calls;
    private final Timing timing;

    XaiRealtimeBackend(Egress egress, KeyVault vault, ModelPolicy.Entry model, TranscriptLog transcripts,
            Supplier<String> instructions, JSONArray toolSchemas, ToolCaller tools, ModelCalls calls, Timing timing) {
        this.egress = egress;
        this.vault = vault;
        this.model = model;
        this.transcripts = transcripts;
        this.instructions = instructions;
        this.toolSchemas = toolSchemas;
        this.tools = tools;
        this.calls = calls;
        this.timing = timing;
    }

    @Override
    public String run(String turnId, InputStream mic, OutputStream speaker, Runnable ready) throws IOException {
        String key;
        try {
            key = vault.get(KeyVault.XAI);
        } catch (Exception e) {
            return "xAI key unreadable";
        }
        if (key == null) {
            return "no xAI key";
        }
        return new Turn(turnId, mic, speaker, ready).run(key);
    }

    private final class Turn implements XaiRealtime.Handler {
        final String turnId;
        final InputStream mic;
        final OutputStream speaker;
        final Runnable ready;
        final BlockingQueue<byte[]> micQueue = new LinkedBlockingQueue<>();
        final BlockingQueue<Chunk> replyQueue = new LinkedBlockingQueue<>();
        final CountDownLatch done = new CountDownLatch(1);
        final long openedAt = now();
        final List<Thread> threads = new ArrayList<>();
        final ExecutorService toolRunner = Executors.newSingleThreadExecutor(r -> new Thread(r, "vos-tool"));
        final AtomicInteger toolsInFlight = new AtomicInteger();

        volatile WebSocket ws;
        volatile boolean configured;
        volatile boolean speaking;
        volatile boolean anySpeech;
        /** Guards the quiet clock: speaking, replyDone and idleDeadline change together. */
        final Object idleLock = new Object();
        volatile boolean responseDone; // a completed (not cancelled) reply has all its audio queued
        volatile boolean firstAudioOfReply = true;
        volatile long configuredAt;
        volatile long speechEndedAt;
        volatile long idleDeadline = Long.MAX_VALUE;
        volatile long endDeadline = Long.MAX_VALUE;
        volatile boolean responseHadCall;
        volatile boolean endRequested; // under idleLock, with endResponse and endDeadline
        String endResponse = "";
        /** One response's tool calls; abandoned (batchGen bumped) by a barge-in or a cancelled response. */
        private final Object batchLock = new Object();
        private int batchGen;
        private int batchPending;
        private boolean batchHasTools;
        private boolean batchResponseDone;
        /** The response audio is arriving for, and the one a barge-in cut off ("" when the vendor sends no ids). */
        volatile String currentResponse = "";
        /** Responses the owner talked over; their late deltas and calls are dropped. */
        final java.util.Set<String> cancelledResponses = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** The model call reported to the indicator and not yet ended, or null; the turn's end ends it too. */
        private final java.util.concurrent.atomic.AtomicReference<String> openCall =
                new java.util.concurrent.atomic.AtomicReference<>();
        private int callSeq; // WebSocket thread only
        private String endReason;
        private boolean ended;
        private boolean actuatorEnded;

        Turn(String turnId, InputStream mic, OutputStream speaker, Runnable ready) {
            this.turnId = turnId;
            this.mic = mic;
            this.speaker = speaker;
            this.ready = ready;
        }

        String run(String key) throws IOException {
            start("vos-mic-reader", this::readMic);
            start("vos-mic-sender", this::sendMic);
            start("vos-reply", this::playReplies);
            Request req = new Request.Builder()
                    .url(model.get("url") + "?model=" + model.get("model"))
                    .header("Authorization", "Bearer " + key)
                    .build();
            ws = egress.openWebSocket(req, turnId, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket w, Response response) {
                    w.send(XaiRealtime.sessionUpdate(instructions.get(), model.get("voice"), Ipc.TURN_SAMPLE_RATE,
                            toolSchemas));
                }

                @Override
                public void onMessage(WebSocket w, String text) {
                    XaiRealtime.dispatch(text, Turn.this);
                }

                @Override
                public void onClosing(WebSocket w, int code, String reason) {
                    // The vendor closed first. OkHttp reports that here (onClosed comes only after our
                    // own close), so the turn ends now, not at the cap. Our close completes the handshake;
                    // 1000 is always sendable, unlike some codes a peer may report (e.g. 1005).
                    finish("vendor closed: " + code);
                    w.close(1000, null);
                }

                @Override
                public void onClosed(WebSocket w, int code, String reason) {
                    finish("vendor closed: " + code);
                }

                @Override
                public void onFailure(WebSocket w, Throwable t, Response response) {
                    finish(response != null ? "vendor refused: HTTP " + response.code()
                            : "vendor connection failed: " + t.getClass().getSimpleName());
                }
            });
            try {
                while (!done.await(100, TimeUnit.MILLISECONDS)) {
                    long now = now();
                    if (now - openedAt > timing.maxTurnMs) {
                        finish("max turn length");
                    } else if (configured && !anySpeech && now - configuredAt > timing.firstSpeechMs) {
                        finish("no speech");
                    } else if (!speaking && toolsInFlight.get() == 0 && now > idleDeadline) {
                        finish("idle");
                    } else if (!speaking && toolsInFlight.get() == 0 && now > endDeadline) {
                        finish("model end");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finish("interrupted");
            }
            WebSocket w = ws;
            if (w != null && !w.close(1000, "turn over")) {
                w.cancel();
            }
            for (Thread t : threads) {
                t.interrupt();
            }
            toolRunner.shutdownNow();
            endCall(); // a response still open at the end (vendor gone, a cap, the model's end) ends with the turn
            synchronized (this) {
                Log.i(TAG, "turn " + turnId + " over after_ms=" + (now() - openedAt) + " reason="
                        + (actuatorEnded ? "actuator" : endReason));
                return actuatorEnded ? null : endReason;
            }
        }

        private void start(String name, Runnable r) {
            Thread t = new Thread(r, name);
            threads.add(t);
            t.start();
        }

        private synchronized void finish(String reason) {
            if (!ended) {
                ended = true;
                endReason = reason;
                done.countDown();
            }
        }

        private synchronized void actuatorClosed() {
            if (!ended) {
                actuatorEnded = true;
            }
            finish("actuator");
        }

        /** A response the owner talked over. Without vendor ids nothing is "cancelled": barge-in only clears the queue. */
        private boolean cancelled(String responseId) {
            return !responseId.isEmpty() && cancelledResponses.contains(responseId);
        }

        private boolean over() {
            return done.getCount() == 0;
        }

        /** Mic pipe → queue, from the first moment; EOF or an error means the actuator ended the turn. */
        private void readMic() {
            byte[] buf = new byte[Ipc.TURN_FRAME_BYTES];
            try {
                while (!over()) {
                    int n = mic.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (n > 0) {
                        micQueue.add(java.util.Arrays.copyOf(buf, n));
                    }
                }
            } catch (IOException e) {
                // the actuator closed the pipe
            }
            actuatorClosed();
        }

        /** Queue → vendor, once the session is configured; batches of 100 ms. */
        private void sendMic() {
            try {
                while (!over() && !configured) {
                    Thread.sleep(10);
                }
                byte[] batch = new byte[Ipc.TURN_FRAME_BYTES * FRAMES_PER_SEND];
                while (!over()) {
                    byte[] first = micQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    int len = 0;
                    for (byte[] f = first; f != null && len + f.length <= batch.length; ) {
                        System.arraycopy(f, 0, batch, len, f.length);
                        len += f.length;
                        f = len + Ipc.TURN_FRAME_BYTES <= batch.length ? micQueue.poll() : null;
                    }
                    WebSocket w = ws;
                    if (w != null) {
                        w.send(XaiRealtime.append(batch, 0, len));
                    }
                }
            } catch (InterruptedException e) {
                // turn over
            }
        }

        /**
         * Reply queue → speaker pipe, in slices of a third of the lead, paced so that at most the lead
         * is unplayed in the pipe. The pacing window ({@code start}, {@code written}) follows the audio
         * still unplayed: it survives a gap in the deltas while earlier audio is playing, and restarts
         * only once that audio has drained (the pipe is empty, so playback restarts with the next write).
         */
        private void playReplies() {
            long start = 0;
            long written = 0;
            int slice = Math.max(Ipc.TURN_FRAME_BYTES, (int) (timing.leadMs * BYTES_PER_SECOND / 1000 / 3) / 2 * 2);
            try {
                while (!over()) {
                    Chunk c = replyQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (c == null) {
                        long left = Math.max(0, written * 1000 / BYTES_PER_SECOND - (now() - start));
                        synchronized (idleLock) { // atomic with speechStarted's reset
                            if (responseDone && !speaking && replyQueue.isEmpty() && toolsInFlight.get() == 0) {
                                if (endRequested) {
                                    endDeadline = now() + left + timing.leadMs;
                                } else {
                                    idleDeadline = now() + left + timing.idleMs;
                                }
                                responseDone = false; // armed once per reply
                            }
                        }
                        continue;
                    }
                    for (int off = 0; off < c.pcm.length && !cancelled(c.responseId); off += slice) {
                        int len = Math.min(slice, c.pcm.length - off);
                        long unplayed = written * 1000 / BYTES_PER_SECOND - (now() - start);
                        if (unplayed <= 0) { // drained: the window restarts here
                            start = now();
                            written = 0;
                            unplayed = 0;
                        }
                        // Wait until this slice fits within the lead, then write it.
                        long after = unplayed + len * 1000L / BYTES_PER_SECOND;
                        if (after > timing.leadMs) {
                            Thread.sleep(after - timing.leadMs);
                        }
                        if (cancelled(c.responseId)) {
                            break;
                        }
                        speaker.write(c.pcm, off, len);
                        written += len;
                    }
                }
            } catch (IOException e) {
                actuatorClosed(); // the actuator closed the speaker pipe
            } catch (InterruptedException e) {
                // turn over
            }
        }

        @Override
        public void sessionCreated(String servedModel) {
            Log.i(TAG, "turn " + turnId + " vendor session: requested=" + model.get("model") + " served=" + servedModel);
        }

        @Override
        public void sessionUpdated() {
            if (!configured) {
                configuredAt = now();
                configured = true;
                Log.i(TAG, "turn " + turnId + " configured after_ms=" + (configuredAt - openedAt)
                        + " buffered_ms=" + micQueue.size() * 20);
                ready.run();
            }
        }

        @Override
        public void speechStarted() {
            synchronized (idleLock) {
                speaking = true;
                responseDone = false;
                idleDeadline = Long.MAX_VALUE;
                endRequested = false; // the owner kept talking
                endDeadline = Long.MAX_VALUE;
            }
            abandonBatch(); // results of calls in flight still go back, but nothing asks the model to answer
            anySpeech = true;
            if (!currentResponse.isEmpty()) {
                cancelledResponses.add(currentResponse); // barge-in: the rest of that response is not played
            }
            int dropped = replyQueue.size();
            replyQueue.clear();
            Log.i(TAG, "turn " + turnId + " speech started" + (dropped > 0 ? ", dropped " + dropped + " reply chunks" : ""));
        }

        @Override
        public void responseCreated(String responseId) {
            endCall(); // one response at a time: a new one means the last has finished
            if (!speaking) { // the model is producing output for the owner: the indicator spins
                String id = "c" + (++callSeq);
                openCall.set(id);
                calls.started(turnId, id);
            }
            if (responseId.isEmpty()) {
                return;
            }
            currentResponse = responseId; // so a barge-in before its first audio still drops it
            if (speaking) {
                // Only our tool follow-up can open a response while the owner talks (the vendor's VAD
                // opens one when speech stops). It answers what came before the new speech: drop it, and
                // cancel it so the vendor answers the owner instead.
                cancelledResponses.add(responseId);
                WebSocket w = ws;
                if (w != null && !over()) {
                    w.send(XaiRealtime.responseCancel());
                }
                Log.i(TAG, "turn " + turnId + " dropped a follow-up created while the owner was speaking");
            }
        }

        @Override
        public void speechStopped() {
            synchronized (idleLock) {
                speaking = false;
                // If no reply comes (the vendor heard noise, or a barge-in it does not answer), the
                // turn still ends after the quiet time; reply audio stops this clock.
                idleDeadline = now() + timing.idleMs;
            }
            speechEndedAt = now();
            firstAudioOfReply = true;
        }

        @Override
        public void ownerTranscript(String text) {
            transcripts.write(turnId, "owner", text);
        }

        @Override
        public void audio(String responseId, byte[] pcm) {
            if (cancelled(responseId)) {
                return; // a late delta of a response the owner interrupted
            }
            currentResponse = responseId;
            synchronized (idleLock) { // a reply is playing: no quiet clock until it has finished
                responseDone = false;
                idleDeadline = Long.MAX_VALUE;
            }
            if (firstAudioOfReply) {
                firstAudioOfReply = false;
                Log.i(TAG, "turn " + turnId + " first reply audio speech_end_to_audio_ms="
                        + (speechEndedAt > 0 ? now() - speechEndedAt : -1));
            }
            replyQueue.add(new Chunk(responseId, pcm));
        }

        /** Ends the open model call, if any: on a vendor event, or when the turn is torn down mid-response. */
        private void endCall() {
            String id = openCall.getAndSet(null);
            if (id != null) {
                calls.ended(turnId, id);
            }
        }

        @Override
        public void replyTranscript(String text) {
            transcripts.write(turnId, "vos", text);
        }

        @Override
        public void responseDone(String responseId, String status) {
            Log.i(TAG, "turn " + turnId + " response done status=" + status);
            endCall(); // completed, cancelled or failed: it no longer produces output
            boolean hadCall = responseHadCall;
            responseHadCall = false;
            if ("cancelled".equals(status) || cancelled(responseId)) {
                if (hadCall) {
                    abandonBatch();
                }
                synchronized (idleLock) {
                    if (endRequested && responseId.equals(endResponse)) { // the owner talked over the goodbye
                        endRequested = false;
                        endDeadline = Long.MAX_VALUE;
                    }
                }
                return; // the owner talked over it; the next reply (or the quiet clock) decides
            }
            if (hadCall) {
                boolean tools;
                synchronized (batchLock) {
                    tools = batchHasTools;
                    batchResponseDone = true;
                    if (tools && batchPending == 0) {
                        resetBatch();
                        continueAfterTools(); // under the lock: a barge-in cannot abandon the batch in between
                    }
                }
                if (tools) {
                    return; // the answer comes in the response after the tool results
                }
            }
            synchronized (idleLock) {
                responseDone = true;
            }
        }

        /**
         * All of one response's tool results are in: let the model answer, once. Called under
         * batchLock. If the vendor's VAD has already opened a response for new speech, it ignores this
         * one (observed on xAI 2026-10-20: no error, no second response).
         */
        private void continueAfterTools() {
            WebSocket w = ws;
            if (w != null && !over()) {
                w.send(XaiRealtime.responseCreate());
            }
        }

        /** The owner talked over the batch's response (or it was cancelled): no follow-up for it. */
        private void abandonBatch() {
            synchronized (batchLock) {
                batchGen++;
                resetBatch();
            }
        }

        private void resetBatch() {
            batchPending = 0;
            batchHasTools = false;
            batchResponseDone = false;
        }

        @Override
        public void functionCall(String responseId, String callId, String name, String argsJson) {
            responseHadCall = true;
            WebSocket w = ws;
            if (w == null) {
                return;
            }
            if (END_TURN.equals(name)) {
                // A late end_turn from a response the owner already talked over does not end the turn.
                boolean stands = !cancelled(responseId);
                synchronized (idleLock) {
                    if (stands && !speaking) {
                        endRequested = true;
                        endResponse = responseId;
                    }
                }
                w.send(XaiRealtime.functionOutput(callId, "{\"status\":\"VERIFIED\"}"));
                Log.i(TAG, "turn " + turnId + " tool " + name
                        + (stands ? " (the model ends the turn)" : " (ignored: the owner talked over that response)"));
                return;
            }
            if (cancelled(responseId)) { // a stale act from a response the owner talked over is not run
                w.send(XaiRealtime.functionOutput(callId,
                        "{\"status\":\"FAILED\",\"detail\":\"not run: the owner talked over this response\"}"));
                Log.i(TAG, "turn " + turnId + " tool " + name + " not run (the owner talked over that response)");
                return; // not part of any batch, so no response.create follows
            }
            int gen;
            synchronized (batchLock) {
                gen = batchGen;
                batchPending++;
                batchHasTools = true;
            }
            toolsInFlight.incrementAndGet();
            synchronized (idleLock) {
                idleDeadline = Long.MAX_VALUE;
            }
            toolRunner.execute(() -> {
                long t0 = now();
                String result = tools.call(turnId, name, argsJson);
                Log.i(TAG, "turn " + turnId + " tool " + name + " " + statusOf(result) + " ms=" + (now() - t0));
                WebSocket ws2 = ws;
                if (ws2 != null && !over()) {
                    ws2.send(XaiRealtime.functionOutput(callId, result));
                }
                toolsInFlight.decrementAndGet();
                synchronized (batchLock) { // decide and send under the lock, so a barge-in cannot slip in between
                    if (gen == batchGen) {
                        batchPending--;
                        if (batchPending == 0 && batchResponseDone) {
                            resetBatch();
                            continueAfterTools();
                        }
                    }
                }
            });
        }

        @Override
        public void error(String code, String message) {
            Log.w(TAG, "turn " + turnId + " vendor error code=" + code);
            if (ACTIVE_RESPONSE.equals(code)) {
                return; // our follow-up met a response the vendor already opened; that one answers
            }
            finish("vendor error: " + code);
        }
    }

    private static String statusOf(String resultJson) {
        try {
            return new org.json.JSONObject(resultJson).optString("status", "?");
        } catch (Exception e) {
            return "?";
        }
    }

    /** One reply delta and the response it belongs to. */
    private static final class Chunk {
        final String responseId;
        final byte[] pcm;

        Chunk(String responseId, byte[] pcm) {
            this.responseId = responseId;
            this.pcm = pcm;
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
