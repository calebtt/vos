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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The {@code chat_voice} backend on the xAI realtime API (§3.2). Per turn:
 * <ul>
 *   <li>the owner's audio is read from the mic pipe from the first moment and buffered, so words
 *       spoken while the WebSocket connects are kept, then streamed once the session is configured;</li>
 *   <li>reply audio is written to the speaker pipe paced to real time plus {@link Timing#leadMs}, so a
 *       barge-in (the vendor's VAD hears the owner) can drop what has not been sent yet;</li>
 *   <li>the turn ends when the owner stays quiet for {@link Timing#idleMs} after a reply, never speaks
 *       within {@link Timing#firstSpeechMs}, reaches {@link Timing#maxTurnMs}, or on a vendor error;
 *       the actuator ending it (the mic pipe closes) is not reported back.</li>
 * </ul>
 * The key comes from the {@link KeyVault} at the start of each turn and only ever goes into the
 * WebSocket's Authorization header. Transcripts go to the {@link TranscriptLog}; logcat gets event
 * names and timings only.
 */
final class XaiRealtimeBackend implements SessionTurns.Backend {
    static final String TAG = "VOS_VOICE";
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
    private final Timing timing;

    XaiRealtimeBackend(Egress egress, KeyVault vault, ModelPolicy.Entry model, TranscriptLog transcripts,
            Supplier<String> instructions, Timing timing) {
        this.egress = egress;
        this.vault = vault;
        this.model = model;
        this.transcripts = transcripts;
        this.instructions = instructions;
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
        final BlockingQueue<byte[]> replyQueue = new LinkedBlockingQueue<>();
        final CountDownLatch done = new CountDownLatch(1);
        final long openedAt = now();
        final List<Thread> threads = new ArrayList<>();

        volatile WebSocket ws;
        volatile boolean configured;
        volatile boolean speaking;
        volatile boolean anySpeech;
        volatile boolean responseDone;
        volatile boolean firstAudioOfReply = true;
        volatile long configuredAt;
        volatile long speechEndedAt;
        volatile long idleDeadline = Long.MAX_VALUE;
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
                    w.send(XaiRealtime.sessionUpdate(instructions.get(), model.get("voice"), Ipc.TURN_SAMPLE_RATE));
                }

                @Override
                public void onMessage(WebSocket w, String text) {
                    XaiRealtime.dispatch(text, Turn.this);
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
                    } else if (!speaking && now > idleDeadline) {
                        finish("idle");
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

        /** Reply queue → speaker pipe, paced to real time plus the lead. */
        private void playReplies() {
            long start = -1;
            long written = 0;
            try {
                while (!over()) {
                    byte[] b = replyQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (b == null) {
                        if (responseDone && idleDeadline == Long.MAX_VALUE && !speaking) {
                            long left = Math.max(0, written * 1000 / BYTES_PER_SECOND - (now() - start));
                            idleDeadline = now() + left + timing.idleMs;
                        }
                        start = -1;
                        continue;
                    }
                    if (start < 0) {
                        start = now();
                        written = 0;
                    }
                    speaker.write(b);
                    written += b.length;
                    long ahead = written * 1000 / BYTES_PER_SECOND - (now() - start);
                    if (ahead > timing.leadMs) {
                        Thread.sleep(ahead - timing.leadMs);
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
            speaking = true;
            anySpeech = true;
            responseDone = false;
            idleDeadline = Long.MAX_VALUE;
            int dropped = replyQueue.size();
            replyQueue.clear(); // barge-in: what is not yet in the pipe is not played
            Log.i(TAG, "turn " + turnId + " speech started" + (dropped > 0 ? ", dropped " + dropped + " reply chunks" : ""));
        }

        @Override
        public void speechStopped() {
            speaking = false;
            speechEndedAt = now();
            firstAudioOfReply = true;
        }

        @Override
        public void ownerTranscript(String text) {
            transcripts.write(turnId, "owner", text);
        }

        @Override
        public void audio(byte[] pcm) {
            if (firstAudioOfReply) {
                firstAudioOfReply = false;
                Log.i(TAG, "turn " + turnId + " first reply audio speech_end_to_audio_ms="
                        + (speechEndedAt > 0 ? now() - speechEndedAt : -1));
            }
            replyQueue.add(pcm);
        }

        @Override
        public void replyTranscript(String text) {
            transcripts.write(turnId, "vos", text);
        }

        @Override
        public void responseDone(String status) {
            responseDone = true;
            Log.i(TAG, "turn " + turnId + " response done status=" + status);
        }

        @Override
        public void error(String code, String message) {
            Log.w(TAG, "turn " + turnId + " vendor error code=" + code);
            finish("vendor error: " + code);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
