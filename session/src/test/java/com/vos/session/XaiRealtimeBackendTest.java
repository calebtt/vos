package com.vos.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The backend against a scripted fake of the xAI realtime endpoint (MockWebServer). */
public class XaiRealtimeBackendTest {
    private static final String HOST = "vendor.test";
    private static final byte[] REPLY = new byte[3200]; // 100 ms of 16 kHz s16le

    static {
        for (int i = 0; i < REPLY.length; i++) {
            REPLY[i] = (byte) (i * 7);
        }
    }

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MockWebServer server = new MockWebServer();
    private final AtomicInteger appends = new AtomicInteger();
    private Egress egress;
    private KeyVault vault;
    private TranscriptLog transcripts;
    private File egressDir;

    @Before
    public void setUp() throws Exception {
        server.start(InetAddress.getLoopbackAddress(), 0);
        egressDir = tmp.newFolder("egress");
        egress = new Egress(new EgressAllowlist(Set.of(HOST)), new RequestLog(egressDir),
                h -> List.of(InetAddress.getLoopbackAddress()));
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        SecretKey k = g.generateKey();
        vault = new KeyVault(tmp.newFolder("keys"), new AesGcmSealer(() -> k));
        transcripts = new TranscriptLog(tmp.newFolder("transcripts"));
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private XaiRealtimeBackend backend(long idleMs) throws Exception {
        String policy = "{\"registry\":[{\"id\":\"xai:test\",\"kind\":\"realtime_voice\",\"vendor\":\"xai\","
                + "\"model\":\"grok-voice-3.0\",\"voice\":\"ara\",\"url\":\"ws://" + HOST + ":" + server.getPort()
                + "/v1/realtime\"}],\"policy\":{\"chat_voice\":[\"xai:test\"]}}";
        ModelPolicy.Entry e = new ModelPolicy(policy).entry("xai:test");
        XaiRealtimeBackend.Timing t = new XaiRealtimeBackend.Timing();
        t.idleMs = idleMs;
        t.firstSpeechMs = 5_000;
        t.maxTurnMs = 20_000;
        return new XaiRealtimeBackend(egress, vault, e, transcripts, () -> "be brief", t);
    }

    /** A fake vendor: configures, and after a few mic appends hears speech and answers once. */
    private void enqueueVendor(String errorCode) {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean answered;

            @Override
            public void onOpen(WebSocket ws, Response r) {
                ws.send("{\"type\":\"session.created\",\"session\":{\"model\":\"grok-voice-3.0\"}}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                String type;
                try {
                    type = new JSONObject(text).getString("type");
                } catch (Exception e) {
                    return;
                }
                if (type.equals("session.update")) {
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (type.equals("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !answered) {
                    answered = true;
                    if (errorCode != null) {
                        ws.send("{\"type\":\"error\",\"error\":{\"code\":\"" + errorCode + "\",\"message\":\"x\"}}");
                        return;
                    }
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    ws.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"hello\"}");
                    ws.send("{\"type\":\"response.output_audio.delta\",\"delta\":\""
                            + Base64.getEncoder().encodeToString(REPLY) + "\"}");
                    ws.send("{\"type\":\"response.output_audio_transcript.done\",\"transcript\":\"hi there\"}");
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
    }

    /** An open mic: some audio, then silence until the test closes it. */
    private static final class Mic {
        final PipedOutputStream out = new PipedOutputStream();
        final PipedInputStream in;
        final Thread writer;
        volatile boolean open = true;

        Mic() throws IOException {
            in = new PipedInputStream(out, 64 * 1024);
            writer = new Thread(() -> {
                byte[] frame = new byte[640];
                try {
                    while (open) {
                        out.write(frame);
                        Thread.sleep(20);
                    }
                    out.close();
                } catch (IOException | InterruptedException ignored) {
                    // closed
                }
            });
            writer.start();
        }

        void close() {
            open = false;
        }
    }

    @Test
    public void aTurnIsConfiguredAnsweredTranscribedLoggedAndEndsWhenIdle() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueVendor(null);
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        AtomicInteger ready = new AtomicInteger();
        String reason = backend(400).run("t-1", mic.in, speaker, ready::incrementAndGet);
        mic.close();

        assertEquals("idle", reason);
        assertEquals(1, ready.get());
        assertArrayEquals(REPLY, speaker.toByteArray());
        RecordedRequest upgrade = server.takeRequest();
        assertEquals("Bearer xai-test-key", upgrade.getHeader("Authorization"));
        assertEquals("/v1/realtime?model=grok-voice-3.0", upgrade.getPath());

        List<String> roles = new ArrayList<>();
        for (String l : Files.readAllLines(transcripts.file().toPath())) {
            JSONObject o = new JSONObject(l);
            assertEquals("t-1", o.getString("turn_id"));
            roles.add(o.getString("role") + ":" + o.getString("text"));
        }
        assertEquals(List.of("owner:hello", "vos:hi there"), roles);

        // The egress line is written when the socket closes; give the close handshake a moment.
        File log = new File(egressDir, "requests.jsonl");
        for (int i = 0; i < 50 && !log.isFile(); i++) {
            Thread.sleep(20);
        }
        JSONObject line = new JSONObject(Files.readAllLines(log.toPath()).get(0));
        assertEquals("ws", line.getString("kind"));
        assertEquals(HOST, line.getString("host"));
        assertEquals("t-1", line.getString("turn_id"));
        assertTrue(line.getLong("bytes_out") > 0);
        assertTrue(line.getLong("bytes_in") > REPLY.length);
        assertTrue(!new String(Files.readAllBytes(log.toPath())).contains("xai-test-key"));
    }

    @Test
    public void bargeInDropsTheRestOfTheInterruptedResponse() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        byte[] first = filled(3200, 0x11);
        byte[] lateTail = filled(32000, 0x22); // a second of r1 arriving after the owner spoke
        byte[] next = filled(3200, 0x33);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean sent;

            @Override
            public void onOpen(WebSocket ws, Response r) {
                ws.send("{\"type\":\"session.created\",\"session\":{\"model\":\"grok-voice-3.0\"}}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.contains("\"session.update\"")) {
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (text.contains("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !sent) {
                    sent = true;
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    ws.send(delta("r1", first));
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}"); // the owner talks over r1
                    ws.send(delta("r1", lateTail));
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"cancelled\"}}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    ws.send(delta("r2", next));
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        String reason = backend(300).run("t-8", mic.in, speaker, () -> { });
        mic.close();
        assertEquals("idle", reason);
        byte[] played = speaker.toByteArray();
        int tail = 0;
        int r2 = 0;
        for (byte b : played) {
            tail += b == 0x22 ? 1 : 0;
            r2 += b == 0x33 ? 1 : 0;
        }
        assertEquals("no audio from the interrupted response's late deltas", 0, tail);
        assertEquals(next.length, r2);
    }

    /** Records when each write landed and how many bytes had been written by then. */
    private static final class TimedSpeaker extends ByteArrayOutputStream {
        final List<long[]> writes = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            super.write(b, off, len);
            writes.add(new long[] {System.nanoTime() / 1_000_000L, size()});
        }
    }

    @Test
    public void aCancelledResponseDoesNotStartTheQuietClockAndTheNextReplyPlaysWhole() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        byte[] first = filled(3200, 0x11);
        byte[] answer = filled(48000, 0x33); // 1.5 s, longer than the 500 ms quiet time
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean sent;

            @Override
            public void onOpen(WebSocket ws, Response r) {
                ws.send("{\"type\":\"session.created\",\"session\":{\"model\":\"grok-voice-3.0\"}}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.contains("\"session.update\"")) {
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (text.contains("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !sent) {
                    sent = true;
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    ws.send(delta("r1", first));
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r1\",\"status\":\"cancelled\"}}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    new Thread(() -> { // the answer starts 300 ms later (inside the quiet time), in four pieces
                        try {
                            Thread.sleep(300);
                            for (int i = 0; i < 4; i++) {
                                ws.send(delta("r2", java.util.Arrays.copyOfRange(answer, i * 12000, (i + 1) * 12000)));
                                Thread.sleep(150);
                            }
                            ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r2\",\"status\":\"completed\"}}");
                        } catch (InterruptedException ignored) {
                            // test
                        }
                    }).start();
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        TimedSpeaker speaker = new TimedSpeaker();
        String reason = backend(500).run("t-11", mic.in, speaker, () -> { });
        mic.close();
        assertEquals("idle", reason);
        int r2 = 0;
        for (byte b : speaker.toByteArray()) {
            r2 += b == 0x33 ? 1 : 0;
        }
        assertEquals("the whole answer was played before the quiet end", answer.length, r2);
        // Pacing: never more than the 300 ms lead (plus scheduling slack) ahead of real time.
        long t0 = -1;
        long base = 0;
        for (long[] w : speaker.writes) {
            if (t0 < 0 && w[1] > first.length) { // from the answer's first write
                t0 = w[0];
                base = w[1];
                continue;
            }
            if (t0 >= 0) {
                long aheadMs = (w[1] - base) * 1000 / 32000 - (w[0] - t0);
                assertTrue("ahead " + aheadMs + " ms", aheadMs <= 300 + 60);
            }
        }
    }

    @Test
    public void aBargeInBeforeTheFirstDeltaStillDropsThatResponse() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        byte[] r1 = filled(3200, 0x11);
        byte[] r2 = filled(3200, 0x22);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean sent;

            @Override
            public void onOpen(WebSocket ws, Response r) {
                ws.send("{\"type\":\"session.created\",\"session\":{\"model\":\"grok-voice-3.0\"}}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.contains("\"session.update\"")) {
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (text.contains("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !sent) {
                    sent = true;
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    // r1 opens, and the owner talks again before its first audio arrives.
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r1\",\"status\":\"in_progress\"}}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send(delta("r1", r1));
                    ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r1\",\"status\":\"cancelled\"}}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r2\",\"status\":\"in_progress\"}}");
                    ws.send(delta("r2", r2));
                    ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r2\",\"status\":\"completed\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        assertEquals("idle", backend(500).run("t-18", mic.in, speaker, () -> { }));
        mic.close();
        assertArrayEquals("only the answer after the barge-in plays", r2, speaker.toByteArray());
    }

    @Test
    public void pacingHoldsTheLeadAcrossGapsLongerThanThePoll() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        byte[] answer = filled(51200, 0x44); // 1.6 s in four 400 ms deltas, 250 ms apart
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean sent;

            @Override
            public void onOpen(WebSocket ws, Response r) {
                ws.send("{\"type\":\"session.created\",\"session\":{\"model\":\"grok-voice-3.0\"}}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.contains("\"session.update\"")) {
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (text.contains("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !sent) {
                    sent = true;
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    new Thread(() -> {
                        try {
                            for (int i = 0; i < 4; i++) {
                                ws.send(delta("r1", java.util.Arrays.copyOfRange(answer, i * 12800, (i + 1) * 12800)));
                                Thread.sleep(250);
                            }
                            ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r1\",\"status\":\"completed\"}}");
                        } catch (InterruptedException ignored) {
                            // test
                        }
                    }).start();
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        TimedSpeaker speaker = new TimedSpeaker();
        assertEquals("idle", backend(500).run("t-13", mic.in, speaker, () -> { }));
        mic.close();
        assertEquals(answer.length, speaker.size());
        // The deltas arrive faster than real time, so the pipe never drains: measured from the first
        // write, the audio written never runs more than the 300 ms lead (plus slack) ahead.
        long t0 = speaker.writes.get(0)[0];
        for (long[] w : speaker.writes) {
            long aheadMs = w[1] * 1000 / 32000 - (w[0] - t0);
            assertTrue("ahead " + aheadMs + " ms at byte " + w[1], aheadMs <= 300 + 60);
        }
    }

    private static byte[] filled(int n, int v) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) v);
        return b;
    }

    private static String delta(String responseId, byte[] pcm) {
        return "{\"type\":\"response.output_audio.delta\",\"response_id\":\"" + responseId + "\",\"delta\":\""
                + Base64.getEncoder().encodeToString(pcm) + "\"}";
    }

    @Test
    public void theActuatorClosingTheMicEndsTheTurnWithoutAReport() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueVendor(null);
        Mic mic = new Mic();
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // test
            }
            mic.close();
        }).start();
        assertNull(backend(10_000).run("t-2", mic.in, new ByteArrayOutputStream(), () -> { }));
    }

    @Test
    public void noKeyMeansNoConnection() throws Exception {
        InputStream silent = new java.io.ByteArrayInputStream(new byte[0]);
        assertEquals("no xAI key", backend(400).run("t-3", silent, new ByteArrayOutputStream(), () -> { }));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void aVendorErrorEndsTheTurnWithItsCode() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueVendor("invalid_audio");
        Mic mic = new Mic();
        String reason = backend(10_000).run("t-4", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("vendor error: invalid_audio", reason);
    }

    @Test
    public void aRefusedKeyEndsTheTurnWithTheHttpStatus() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        server.enqueue(new MockResponse().setResponseCode(401));
        Mic mic = new Mic();
        String reason = backend(10_000).run("t-5", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("vendor refused: HTTP 401", reason);
    }
}
