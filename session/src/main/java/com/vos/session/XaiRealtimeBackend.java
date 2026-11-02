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
import org.json.JSONObject;

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
 *       and starts a new one; its {@code response.done} (any done when the vendor sends no ids), a
 *       barge-in (the owner cut the reply off: VOS discards the rest of it) and the turn ending end the
 *       open call, and once the turn is ending no new call starts. A late done for another response
 *       (a dropped follow-up's) does not end it. A follow-up
 *       dropped because the owner is speaking is not reported: nothing is being generated for the owner;</li>
 *   <li>a tool call goes to the actuator ({@link ToolCaller}) off the WebSocket thread, and its result
 *       (VERIFIED, UNVERIFIED or FAILED from the actuator's oracle) goes back to the model. The calls
 *       of one response form a batch: the model is asked to answer once, after all their results. If
 *       the owner talks over that response (or it is cancelled), the results still go back but no
 *       response.create is sent: the vendor's VAD opens the next response itself;</li>
 *   <li>the heard note (decision 48, #248): xAI accepts {@code conversation.item.truncate} but does not
 *       apply it, so after a barge-in VOS adds a system message to the vendor conversation quoting the
 *       last words of the cut reply the owner heard ({@link HeardNote}). What was heard is fixed at the
 *       barge-in: the reply's audio written to the speaker pipe, minus what was still unplayed in it (the
 *       pacing lead the quiet clock also uses). It is mapped onto the reply's transcript in proportion to
 *       all the audio the vendor sent for that reply, dropped late deltas included. The note is sent once
 *       the reply's transcript is final (its {@code response.done}; at once if that came before the
 *       barge-in), or at the latest when the owner stops speaking. It also goes into the shared
 *       conversation, after the cut reply's line, so later turns are seeded with it. No note when nothing
 *       was heard or the whole reply was. xAI accepts the note (PR #309 probe) but its errors name no
 *       client event, so a refusal is matched by timing: an error while a note is unconfirmed (no
 *       {@code conversation.item.added} for it yet) is that note's refusal. It is logged and never ends
 *       the turn;</li>
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
    /** The event_id prefix of the heard notes VOS sends (decision 48); seeded notes add "seed-". */
    static final String NOTE_EVENT = "vos-heard-";

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
        /**
         * How long a heard note stays unconfirmed at most (decision 48). xAI answers it within ~40 ms;
         * the cap only keeps a vendor that never answers from turning later errors into refusals.
         */
        long noteAckMs = 5_000;
        /** The vendor's end-of-turn settings for this turn (patience, #247). */
        TurnDetection turnDetection = TurnDetection.VENDOR_DEFAULT;
    }

    private final Egress egress;
    private final KeyVault vault;
    private final ModelPolicy.Entry model;
    private final TranscriptLog transcripts;
    private final ContextKeeper context;
    private final Supplier<String> instructions;
    private final JSONArray toolSchemas;
    private final ToolCaller tools;
    private final ModelCalls calls;
    private final Timing timing;

    XaiRealtimeBackend(Egress egress, KeyVault vault, ModelPolicy.Entry model, TranscriptLog transcripts,
            Supplier<String> instructions, JSONArray toolSchemas, ToolCaller tools, ModelCalls calls, Timing timing,
            ContextKeeper context) {
        this.egress = egress;
        this.vault = vault;
        this.model = model;
        this.transcripts = transcripts;
        this.instructions = instructions;
        this.toolSchemas = toolSchemas;
        this.tools = tools;
        this.calls = calls;
        this.timing = timing;
        this.context = context;
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
        String refused = context.beforeTurn(turnId); // never send a request over the context budget
        if (refused != null) {
            return refused;
        }
        try {
            return new Turn(turnId, mic, speaker, ready).run(key);
        } finally {
            context.afterTurn(turnId);
        }
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
        /** Guards openCall, callSeq and callsClosed: vendor events and the turn's teardown run on different threads. */
        private final Object callLock = new Object();
        private String openCall; // the model call reported to the indicator and not yet ended, or null
        private String openCallResponse = ""; // the vendor's id for openCall's response ("" if none was sent)
        private final java.util.Set<String> createdResponses = new java.util.HashSet<>(); // every vendor id seen this turn
        private int callSeq;
        private boolean callsClosed; // the turn is ending: no new call starts
        private String endReason;
        private boolean ended;
        private boolean actuatorEnded;
        /**
         * Guards the replies and the pacing window the heard note reads (decision 48): the WebSocket
         * thread, the reply thread and the turn's teardown all touch them.
         */
        private final Object heardLock = new Object();
        private final java.util.Map<String, Reply> replies = new java.util.HashMap<>(); // by vendor id
        private final List<Reply> uncut = new ArrayList<>(); // replies no barge-in has cut yet
        private final List<Reply> pendingNotes = new ArrayList<>(); // cut, waiting for the response's done
        private final List<Reply> allReplies = new ArrayList<>(); // this turn's, for the teardown
        /** Heard notes sent and not yet confirmed by a conversation.item.added, oldest first. */
        private final java.util.ArrayDeque<Unconfirmed> unconfirmed = new java.util.ArrayDeque<>();
        /** Shared-conversation lines (VOS replies and heard notes), in the order decided under heardLock. */
        private final java.util.ArrayDeque<String[]> lines = new java.util.ArrayDeque<>();
        private boolean writingLines; // a thread is writing the lines queue out
        private Reply lastReply; // the response last created, or audio or a transcript last arrived for
        private Reply lastWritten; // the reply whose audio was last written to the speaker pipe
        private long playStart; // playReplies' pacing window, as of its last write
        private long playWritten;
        private int noteSeq;

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
                            toolSchemas, timing.turnDetection));
                    Log.i(TAG, "turn " + turnId + " turn detection " + timing.turnDetection);
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
            for (Reply r : waitingNotes()) {
                note(r, true); // the turn ended first: the note still goes into the shared conversation
            }
            synchronized (heardLock) { // notes still waiting for their reply's line get it from the deltas
                for (Reply r : allReplies) {
                    if (r.heldNote != null) {
                        lineFromDeltas(r);
                        releaseHeldNote(r);
                    }
                }
                unconfirmed.clear(); // the session is over: nothing answers them now
            }
            writeLines();
            synchronized (callLock) { // a response still open at the end ends with the turn; none starts after it
                callsClosed = true;
                endCallLocked();
            }
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
                        synchronized (heardLock) {
                            playStart = start;
                            playWritten = written;
                            c.reply.written += len;
                            lastWritten = c.reply;
                        }
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
                List<JSONObject> seed = context.seed(); // before any mic audio: the conversation so far
                WebSocket w = ws;
                if (w != null && !seed.isEmpty()) {
                    int tokens = 0;
                    int notes = 0;
                    for (JSONObject item : seed) {
                        if ("system".equals(item.optString("role"))) { // a heard note from an earlier turn
                            w.send(XaiRealtime.itemCreate(item, expectAnswer(NOTE_EVENT + "seed-" + (++notes))));
                        } else {
                            w.send(XaiRealtime.itemCreate(item));
                        }
                        tokens += ContextBudget.tokens(item.toString());
                    }
                    Log.i(TAG, "turn " + turnId + " seeded items=" + seed.size() + " tokens~" + tokens);
                }
                configuredAt = now();
                configured = true;
                Log.i(TAG, "turn " + turnId + " configured after_ms=" + (configuredAt - openedAt)
                        + " buffered_ms=" + micQueue.size() * 20);
                ready.run();
            }
        }

        @Override
        public void speechStarted() {
            List<Reply> cut = cutReplies(); // first: what had been heard when the owner started speaking
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
            endCall(); // the reply is cut off: nothing more is generated for it (its late done ends nothing)
            int dropped = replyQueue.size();
            replyQueue.clear();
            Log.i(TAG, "turn " + turnId + " speech started" + (dropped > 0 ? ", dropped " + dropped + " reply chunks" : ""));
            for (Reply r : cut) {
                note(r, false); // its transcript is final already
            }
        }

        /**
         * Fixes how much of each reply not yet cut the owner heard: its audio written to the speaker
         * pipe, minus, for the reply written last, what of the pipe was still unplayed. A reply that is
         * done and was heard whole needs no note. Returns the cut replies whose response is done (their
         * notes can go now); the others wait for their done in {@link #pendingNotes}. A reply is cut once,
         * so a later barge-in cuts only replies that came after this one.
         */
        private List<Reply> cutReplies() {
            List<Reply> ready = new ArrayList<>();
            synchronized (heardLock) {
                long unplayedMs = Math.max(0, playWritten * 1000 / BYTES_PER_SECOND - (now() - playStart));
                long unplayed = unplayedMs * BYTES_PER_SECOND / 1000;
                for (Reply r : uncut) {
                    r.heard = r == lastWritten ? Math.max(0, r.written - unplayed) : r.written;
                    if (r.done && r.heard >= r.received) {
                        r.noted = true; // played whole (or had no audio): nothing was cut
                    } else if (r.done) {
                        ready.add(r);
                    } else {
                        pendingNotes.add(r);
                    }
                }
                uncut.clear();
            }
            return ready;
        }

        /** A new reply record, not yet cut. Under heardLock. */
        private Reply newReply() {
            Reply r = new Reply();
            uncut.add(r);
            allReplies.add(r);
            lastReply = r;
            return r;
        }

        private List<Reply> waitingNotes() {
            synchronized (heardLock) {
                return new ArrayList<>(pendingNotes);
            }
        }

        /** The reply {@code responseId} names; "" means the last one, or a new one once that is done. */
        private Reply replyFor(String responseId) { // under heardLock
            if (!responseId.isEmpty()) {
                Reply r = replies.get(responseId);
                if (r == null) {
                    r = newReply();
                    replies.put(responseId, r);
                }
                return r;
            }
            return lastReply == null || lastReply.done ? newReply() : lastReply;
        }

        /**
         * Sends the heard note for a cut reply (decision 48), once: to the vendor while the turn is open
         * ({@code turnOver} false), and to the shared conversation once the reply's own line is there.
         * Logs counts and timings only, never the words.
         */
        private void note(Reply r, boolean turnOver) {
            String text;
            long heard;
            long total;
            synchronized (heardLock) {
                if (r.noted) {
                    return;
                }
                r.noted = true;
                pendingNotes.remove(r);
                text = r.transcript != null ? r.transcript : r.deltas.toString();
                heard = r.heard;
                total = r.received;
            }
            if (heard >= total) {
                return; // the whole reply had played: nothing was cut
            }
            HeardNote n = HeardNote.of(text, heard, total);
            String eventId = null;
            if (n.note != null) {
                WebSocket w = ws;
                boolean live = !turnOver && w != null && !over();
                synchronized (heardLock) {
                    r.heldNote = n.note;
                    if (r.done || turnOver) {
                        lineFromDeltas(r); // no transcript.done is coming for it
                    }
                    releaseHeldNote(r);
                    if (live) {
                        eventId = NOTE_EVENT + (++noteSeq); // counts notes sent, not cuts
                    }
                }
                if (eventId != null) {
                    w.send(XaiRealtime.systemNote(n.note, expectAnswer(eventId)));
                }
                writeLines();
            }
            Log.i(TAG, "turn " + turnId + " heard note "
                    + (n.note == null ? "none (" + n.outcome + ")" : eventId != null ? "sent " + eventId : "kept (turn over)")
                    + " heard_ms=" + heard * 1000 / BYTES_PER_SECOND + " reply_ms=" + total * 1000 / BYTES_PER_SECOND
                    + " words=" + n.heardWords + "/" + n.words);
        }

        /**
         * Registers a heard note about to be sent as unconfirmed, before the send so its answer cannot
         * arrive first; returns its event id.
         */
        private String expectAnswer(String eventId) {
            synchronized (heardLock) {
                unconfirmed.add(new Unconfirmed(eventId, now()));
            }
            return eventId;
        }

        /**
         * The unconfirmed heard note an error answers, or null. xAI answers each client event once, in
         * order, and its errors name no client event (PR #309 probe), so an error while a note is
         * unconfirmed is taken as the oldest one's refusal; a vendor that echoes {@code event_id} names
         * the note. The trade-off: an unrelated error arriving in that window (about 40 ms on xAI) is
         * taken as the refusal too and does not end the turn; a fatal error recurs or ends the turn by
         * its other limits. Notes past {@link Timing#noteAckMs} are no longer unconfirmed.
         */
        private Unconfirmed refusedNote(String eventId) {
            synchronized (heardLock) {
                long now = now();
                while (!unconfirmed.isEmpty() && now - unconfirmed.peekFirst().sentAt > timing.noteAckMs) {
                    unconfirmed.pollFirst();
                }
                for (Unconfirmed u : unconfirmed) {
                    if (u.eventId.equals(eventId)) {
                        unconfirmed.remove(u);
                        return u;
                    }
                }
                return unconfirmed.pollFirst();
            }
        }

        /** Queues the reply's line from its transcript deltas, if it has no line yet. Under heardLock. */
        private void lineFromDeltas(Reply r) {
            String d = r.deltas.toString();
            if (!r.lineRecorded && !d.isBlank()) {
                lines.add(new String[] {"vos", d});
                r.lineRecorded = true;
                r.lineFromDeltas = true; // a late transcript.done adds no second line
            }
        }

        /** Queues the reply's held note once its line is queued. Under heardLock. */
        private void releaseHeldNote(Reply r) {
            if (r.heldNote != null && r.lineRecorded) {
                lines.add(new String[] {"note", r.heldNote});
                r.heldNote = null;
            }
        }

        /**
         * Writes the queued lines to the shared conversation in order. One thread writes at a time, and
         * no lock is held while writing (the conversation saves to storage).
         */
        private void writeLines() {
            synchronized (heardLock) {
                if (writingLines) {
                    return; // the writing thread takes these too
                }
                writingLines = true;
            }
            while (true) {
                String[] l;
                synchronized (heardLock) {
                    l = lines.pollFirst();
                    if (l == null) {
                        writingLines = false;
                        return;
                    }
                }
                if (l[0].equals("vos")) {
                    context.vos(turnId, l[1]);
                } else {
                    context.note(turnId, l[1]);
                }
            }
        }

        @Override
        public void responseCreated(String responseId) {
            synchronized (callLock) { // started and ended reach the actuator in order, never after the turn's end
                endCallLocked(); // one response at a time: a new one means the last has finished
                if (!speaking && !callsClosed) { // the model is producing output for the owner: the indicator spins
                    openCall = "c" + (++callSeq);
                    openCallResponse = responseId;
                    calls.started(turnId, openCall);
                }
                if (!responseId.isEmpty()) {
                    createdResponses.add(responseId);
                }
            }
            if (!responseId.isEmpty()) {
                currentResponse = responseId; // so a barge-in before its first audio still drops it
            }
            synchronized (heardLock) { // a new response is the last reply, even before its first audio
                if (responseId.isEmpty()) {
                    newReply();
                } else {
                    replyFor(responseId);
                }
            }
            if (speaking) {
                // Only our tool follow-up can open a response while the owner talks (the vendor's VAD
                // opens one when speech stops). It answers what came before the new speech: drop it, and
                // cancel it so the vendor answers the owner instead (with or without an id).
                if (!responseId.isEmpty()) {
                    cancelledResponses.add(responseId);
                }
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
            for (Reply r : waitingNotes()) {
                note(r, false); // no done for the cut reply yet: the note goes with the transcript so far
            }
        }

        @Override
        public void ownerTranscript(String text) {
            transcripts.write(turnId, "owner", text);
            context.owner(turnId, text);
        }

        @Override
        public void audio(String responseId, byte[] pcm) {
            Reply reply;
            synchronized (heardLock) { // all of a reply's audio counts for the heard note, played or not
                reply = replyFor(responseId);
                reply.received += pcm.length;
            }
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
            replyQueue.add(new Chunk(responseId, pcm, reply));
        }

        /** Ends the open model call, if any: on a done, a barge-in, or the turn's teardown. */
        private void endCall() {
            synchronized (callLock) {
                endCallLocked();
            }
        }

        private void endCallLocked() {
            if (openCall != null) {
                calls.ended(turnId, openCall);
                openCall = null;
            }
        }

        @Override
        public void replyTranscriptDelta(String responseId, String delta) {
            synchronized (heardLock) {
                replyFor(responseId).deltas.append(delta);
            }
        }

        @Override
        public void replyTranscript(String responseId, String text) {
            synchronized (heardLock) {
                Reply r = replyFor(responseId);
                r.transcript = r.transcript == null ? text : r.transcript + " " + text;
                if (!r.lineFromDeltas) {
                    lines.add(new String[] {"vos", text});
                    r.lineRecorded = true;
                }
                releaseHeldNote(r); // a note held for this line follows it
            }
            transcripts.write(turnId, "vos", text);
            writeLines();
        }

        @Override
        public void itemAdded(String role) {
            if (!"system".equals(role)) {
                return;
            }
            Unconfirmed u;
            synchronized (heardLock) {
                u = unconfirmed.pollFirst(); // answers come in order: the oldest note is confirmed
            }
            if (u != null) {
                Log.i(TAG, "turn " + turnId + " heard note " + u.eventId + " confirmed after_ms=" + (now() - u.sentAt));
            }
        }

        @Override
        public void responseDone(String responseId, String status) {
            Log.i(TAG, "turn " + turnId + " response done status=" + status);
            Reply cutAndDone = null;
            synchronized (heardLock) {
                Reply r = responseId.isEmpty() ? lastReply : replies.get(responseId);
                if (r != null) {
                    r.done = true;
                    if (pendingNotes.contains(r)) {
                        cutAndDone = r;
                    }
                    if (r.heldNote != null) { // sent at speech_stopped; no transcript.done came before this done
                        lineFromDeltas(r);
                        releaseHeldNote(r);
                    }
                }
            }
            writeLines();
            if (cutAndDone != null) {
                note(cutAndDone, false); // its transcript is final now
            }
            synchronized (callLock) { // completed, cancelled or failed: that response no longer produces output
                // A done ends the open call when it is that call's (same id), carries no id, or carries an
                // id this turn never saw created (the open call was created without one). A done for
                // any earlier response is late and ends nothing.
                if (responseId.isEmpty() || responseId.equals(openCallResponse)
                        || (openCallResponse.isEmpty() && !createdResponses.contains(responseId))) {
                    endCallLocked();
                }
            }
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
                context.tool(turnId, name, result);
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
        public void error(String code, String message, String eventId) {
            Log.w(TAG, "turn " + turnId + " vendor error code=" + code);
            if (ACTIVE_RESPONSE.equals(code)) {
                return; // our follow-up met a response the vendor already opened; that one answers
            }
            Unconfirmed refused = refusedNote(eventId); // never the message: it may quote the note
            if (refused != null) {
                Log.w(TAG, "turn " + turnId + " heard note " + refused.eventId + " refused code=" + code
                        + " after_ms=" + (now() - refused.sentAt) + "; the turn goes on");
                return;
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

    /** One reply delta, the response it belongs to, and that response's reply record. */
    private static final class Chunk {
        final String responseId;
        final byte[] pcm;
        final Reply reply;

        Chunk(String responseId, byte[] pcm, Reply reply) {
            this.responseId = responseId;
            this.pcm = pcm;
            this.reply = reply;
        }
    }

    /** One response's reply, for the heard note (decision 48); guarded by its turn's heardLock. */
    private static final class Reply {
        long received; // audio bytes the vendor sent, played or not
        long written; // audio bytes written to the speaker pipe
        final StringBuilder deltas = new StringBuilder();
        String transcript; // the done transcript, or null
        boolean done; // its response.done arrived
        long heard = -1; // audio bytes heard, fixed by the first barge-in; -1 until then
        boolean noted;
        boolean lineRecorded; // its line is queued for the shared conversation
        boolean lineFromDeltas; // that line came from the deltas (no transcript.done before it was needed)
        String heldNote; // its heard note, waiting for that line
    }

    /** A heard note sent and not yet answered by the vendor. */
    private static final class Unconfirmed {
        final String eventId;
        final long sentAt;

        Unconfirmed(String eventId, long sentAt) {
            this.eventId = eventId;
            this.sentAt = sentAt;
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
