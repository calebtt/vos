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

    private final List<String> toolCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long toolDelayMs;

    private XaiRealtimeBackend backend(long idleMs) throws Exception {
        String policy = "{\"registry\":[{\"id\":\"xai:test\",\"kind\":\"realtime_voice\",\"vendor\":\"xai\","
                + "\"model\":\"grok-voice-3.0\",\"voice\":\"ara\",\"url\":\"ws://" + HOST + ":" + server.getPort()
                + "/v1/realtime\"}],\"policy\":{\"chat_voice\":[\"xai:test\"]}}";
        ModelPolicy.Entry e = new ModelPolicy(policy).entry("xai:test");
        XaiRealtimeBackend.Timing t = new XaiRealtimeBackend.Timing();
        t.idleMs = idleMs;
        t.firstSpeechMs = 5_000;
        t.maxTurnMs = 20_000;
        org.json.JSONArray schemas = new org.json.JSONArray(
                "[{\"type\":\"function\",\"name\":\"volume_set\",\"parameters\":{\"type\":\"object\"}}]");
        return new XaiRealtimeBackend(egress, vault, e, transcripts, () -> "be brief", schemas,
                (turn, name, args) -> {
                    toolCalls.add(turn + " " + name + " " + args);
                    try {
                        Thread.sleep(toolDelayMs);
                    } catch (InterruptedException ignored) {
                        // test
                    }
                    return "{\"status\":\"VERIFIED\",\"percent\":30}";
                }, t);
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

    /** A fake vendor that calls volume_set, or end_turn, and checks the session's tool messages. */
    private final List<String> vendorSaw = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void enqueueToolVendor(boolean endTurn) {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean asked;

            @Override
            public void onOpen(WebSocket ws, Response r) {
                ws.send("{\"type\":\"session.created\",\"session\":{\"model\":\"grok-voice-3.0\"}}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                JSONObject m;
                try {
                    m = new JSONObject(text);
                } catch (Exception e) {
                    return;
                }
                String type = m.optString("type");
                if (type.equals("session.update")) {
                    vendorSaw.add("tools " + (m.optJSONObject("session").has("tools")));
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (type.equals("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !asked) {
                    asked = true;
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    if (endTurn) {
                        ws.send("{\"type\":\"response.output_audio.delta\",\"delta\":\""
                                + Base64.getEncoder().encodeToString(REPLY) + "\"}");
                        ws.send("{\"type\":\"response.function_call_arguments.done\",\"call_id\":\"c-end\","
                                + "\"name\":\"end_turn\",\"arguments\":\"{}\"}");
                    } else {
                        ws.send("{\"type\":\"response.function_call_arguments.done\",\"call_id\":\"c-1\","
                                + "\"name\":\"volume_set\",\"arguments\":\"{\\\"percent\\\":30}\"}");
                    }
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
                } else if (type.equals("conversation.item.create")) {
                    JSONObject item = m.optJSONObject("item");
                    vendorSaw.add("output " + item.optString("call_id") + " " + item.optString("output"));
                } else if (type.equals("response.create")) {
                    vendorSaw.add("response.create");
                    ws.send("{\"type\":\"response.output_audio.delta\",\"delta\":\""
                            + Base64.getEncoder().encodeToString(REPLY) + "\"}");
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
    }

    @Test
    public void aToolCallRunsInTheActuatorAndTheModelAnswersAfterItsResult() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueToolVendor(false);
        toolDelayMs = 600; // longer than the idle time: a pending tool must not end the turn
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        String reason = backend(300).run("t-6", mic.in, speaker, () -> { });
        mic.close();
        assertEquals("idle", reason);
        assertEquals(List.of("t-6 volume_set {\"percent\":30}"), toolCalls);
        assertEquals(List.of("tools true", "output c-1 {\"status\":\"VERIFIED\",\"percent\":30}", "response.create"),
                vendorSaw);
        assertArrayEquals(REPLY, speaker.toByteArray());
    }

    @Test
    public void endTurnClosesTheTurnAfterTheGoodbyeHasPlayed() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueToolVendor(true);
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        String reason = backend(10_000).run("t-7", mic.in, speaker, () -> { });
        mic.close();
        assertEquals("model end", reason);
        assertArrayEquals(REPLY, speaker.toByteArray());
        assertTrue(toolCalls.isEmpty()); // end_turn never reaches the actuator
        assertEquals(List.of("tools true", "output c-end {\"status\":\"VERIFIED\"}"), vendorSaw);
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
