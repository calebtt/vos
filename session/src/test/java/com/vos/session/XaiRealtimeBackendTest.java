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
    /** The model-call events the backend reported for the generating indicator, and when (nanoTime). */
    private final List<String> modelCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Long> modelCallNanos = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The shared conversation a test gives the backend; none by default. */
    private ContextKeeper context = ContextKeeper.NONE;
    /** The end-of-turn settings a test gives the backend (#247); the vendor's defaults by default. */
    private TurnDetection turnDetection = TurnDetection.VENDOR_DEFAULT;
    /** Every session.update the fake vendor of enqueueVendor received. */
    private final List<JSONObject> sessionUpdates = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long toolDelayMs;
    /** How long a heard note stays unconfirmed at most (decision 48); the backend's default unless a test sets it. */
    private long noteAckMs = new XaiRealtimeBackend.Timing().noteAckMs;

    private XaiRealtimeBackend backend(long idleMs) throws Exception {
        String policy = "{\"registry\":[{\"id\":\"xai:test\",\"kind\":\"realtime_voice\",\"vendor\":\"xai\","
                + "\"model\":\"grok-voice-3.0\",\"voice\":\"ara\",\"url\":\"ws://" + HOST + ":" + server.getPort()
                + "/v1/realtime\"}],\"policy\":{\"chat_voice\":[\"xai:test\"]}}";
        ModelPolicy.Entry e = new ModelPolicy(policy).entry("xai:test");
        XaiRealtimeBackend.Timing t = new XaiRealtimeBackend.Timing();
        t.idleMs = idleMs;
        t.firstSpeechMs = 5_000;
        t.maxTurnMs = 20_000;
        t.turnDetection = turnDetection;
        t.noteAckMs = noteAckMs;
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
                }, new ModelCalls() {
                    @Override
                    public void started(String turnId, String callId) {
                        modelCalls.add("started " + turnId + " " + callId);
                        modelCallNanos.add(System.nanoTime());
                    }

                    @Override
                    public void ended(String turnId, String callId) {
                        modelCalls.add("ended " + turnId + " " + callId);
                        modelCallNanos.add(System.nanoTime());
                    }
                }, t, context);
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
                    try {
                        sessionUpdates.add(new JSONObject(text));
                    } catch (Exception ignored) {
                        // parsed above
                    }
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

    /** Records what a turn tells the shared conversation; seeds two items. */
    private final class RecordingContext implements ContextKeeper {
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        String refuse;
        List<JSONObject> seedItems; // null: the two default items

        @Override
        public String beforeTurn(String turnId) {
            events.add("before " + turnId);
            return refuse;
        }

        @Override
        public List<JSONObject> seed() {
            if (seedItems != null) {
                return seedItems;
            }
            try {
                return List.of(new JSONObject("{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"My sister's name is Dana.\"}]}"),
                        new JSONObject("{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Got it.\"}]}"));
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void owner(String turnId, String text) {
            events.add("owner " + text);
        }

        @Override
        public void vos(String turnId, String text) {
            events.add("vos " + text);
        }

        @Override
        public void tool(String turnId, String name, String result) {
            events.add("tool " + name);
        }

        @Override
        public void note(String turnId, String text) {
            events.add("note " + text);
        }

        @Override
        public void afterTurn(String turnId) {
            events.add("after " + turnId);
        }
    }

    @Test
    public void theConversationIsSeededBeforeAnyMicAudioAndGrowsWithTheTurn() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        RecordingContext rc = new RecordingContext();
        context = rc;
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean answered;

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
                if (order.isEmpty() || !order.get(order.size() - 1).equals(type) || !type.equals("input_audio_buffer.append")) {
                    order.add(type);
                }
                if (type.equals("session.update")) {
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (type.equals("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !answered) {
                    answered = true;
                    ws.send(STARTED);
                    ws.send(STOPPED);
                    ws.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"hello\"}");
                    ws.send("{\"type\":\"response.output_audio_transcript.done\",\"transcript\":\"hi there\"}");
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        assertEquals("idle", backend(300).run("t-40", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(List.of("session.update", "conversation.item.create", "conversation.item.create",
                "input_audio_buffer.append"), order.subList(0, 4));
        assertEquals(List.of("before t-40", "owner hello", "vos hi there", "after t-40"), rc.events);
    }

    @Test
    public void aConversationOverItsBudgetStopsTheTurnBeforeAnyRequest() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        RecordingContext rc = new RecordingContext();
        rc.refuse = "context over budget";
        context = rc;
        Mic mic = new Mic();
        assertEquals("context over budget", backend(300).run("t-41", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(0, server.getRequestCount());
        assertEquals(List.of("before t-41"), rc.events);
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

    /** A fake vendor whose first answer is the given calls, all in one response; follow-ups get audio. */
    private void enqueueBatchVendor(String... calls) {
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
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (type.equals("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !asked) {
                    asked = true;
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    for (String c : calls) {
                        ws.send(c);
                    }
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
                } else if (type.equals("conversation.item.create")) {
                    vendorSaw.add("output " + m.optJSONObject("item").optString("call_id"));
                } else if (type.equals("response.create")) {
                    vendorSaw.add("response.create");
                    ws.send(delta("r-follow", REPLY));
                    ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
    }

    private static String call(String id, String name, String args) {
        return call("", id, name, args);
    }

    private static String call(String responseId, String id, String name, String args) {
        return "{\"type\":\"response.function_call_arguments.done\",\"response_id\":\"" + responseId
                + "\",\"call_id\":\"" + id + "\",\"name\":\"" + name + "\",\"arguments\":" + JSONObject.quote(args) + "}";
    }

    /** A vendor that plays {@code script} once the third mic chunk arrives; records what the client sends. */
    private WebSocketListener scripted(String... script) {
        return new WebSocketListener() {
            boolean sent;

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
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (type.equals("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !sent) {
                    sent = true;
                    for (String e : script) {
                        ws.send(e);
                    }
                } else if (type.equals("conversation.item.create")) {
                    vendorSaw.add("output " + m.optJSONObject("item").optString("call_id"));
                } else if (type.equals("response.create")) {
                    vendorSaw.add("response.create");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        };
    }

    private static final String STARTED = "{\"type\":\"input_audio_buffer.speech_started\"}";
    private static final String STOPPED = "{\"type\":\"input_audio_buffer.speech_stopped\"}";

    private static String done(String responseId, String status) {
        return "{\"type\":\"response.done\",\"response\":{\"id\":\"" + responseId + "\",\"status\":\"" + status + "\"}}";
    }

    @Test
    public void aLateEndTurnFromAResponseTheOwnerTalkedOverDoesNotEndTheTurn() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        // r1 is a goodbye with end_turn; the owner talks over it and its end_turn arrives after
        // speech_started; r2 answers what the owner said. The turn must end on the quiet time.
        server.enqueue(new MockResponse().withWebSocketUpgrade(scripted(
                STARTED, STOPPED, delta("r1", REPLY), STARTED, call("r1", "c-end", "end_turn", "{}"),
                done("r1", "cancelled"), STOPPED, delta("r2", REPLY), done("r2", "completed"))));
        Mic mic = new Mic();
        String reason = backend(600).run("t-14", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("idle", reason);
        assertEquals(List.of("output c-end"), vendorSaw); // the call still gets its output
    }

    @Test
    public void anEndTurnThatArrivesWhileTheOwnerIsSpeakingIsIgnored() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        // r1 has no audio, so barge-in does not know it; its end_turn arrives while the owner speaks.
        server.enqueue(new MockResponse().withWebSocketUpgrade(scripted(
                STARTED, STOPPED, STARTED, call("r1", "c-end", "end_turn", "{}"), done("r1", "completed"),
                STOPPED, delta("r2", REPLY), done("r2", "completed"))));
        Mic mic = new Mic();
        String reason = backend(600).run("t-15", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("idle", reason);
    }

    @Test
    public void anEndTurnFromAResponseThatIsThenCancelledDoesNotEndTheTurn() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        server.enqueue(new MockResponse().withWebSocketUpgrade(scripted(
                STARTED, STOPPED, call("r1", "c-end", "end_turn", "{}"), done("r1", "cancelled"),
                delta("r2", REPLY), done("r2", "completed"))));
        Mic mic = new Mic();
        String reason = backend(600).run("t-17", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("idle", reason);
    }

    @Test
    public void aToolFollowUpCreatedWhileTheOwnerSpeaksIsDroppedAndCancelled() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        byte[] stale = filled(3200, 0x55);
        byte[] answer = filled(3200, 0x66);
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
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (type.equals("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !asked) {
                    asked = true;
                    ws.send(STARTED);
                    ws.send(STOPPED);
                    ws.send(call("r1", "c-a", "media_control", "{\"action\":\"pause\"}"));
                    ws.send(done("r1", "completed"));
                } else if (type.equals("response.create")) {
                    vendorSaw.add("response.create");
                    // The owner is already talking again when the follow-up opens.
                    ws.send(STARTED);
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r2\",\"status\":\"in_progress\"}}");
                    ws.send(delta("r2", stale));
                    ws.send(done("r2", "cancelled"));
                    ws.send(STOPPED);
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r3\",\"status\":\"in_progress\"}}");
                    ws.send(delta("r3", answer));
                    ws.send(done("r3", "completed"));
                } else if (type.equals("response.cancel")) {
                    vendorSaw.add("response.cancel");
                } else if (type.equals("conversation.item.create")) {
                    vendorSaw.add("output " + m.optJSONObject("item").optString("call_id"));
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        assertEquals("idle", backend(500).run("t-19", mic.in, speaker, () -> { }));
        mic.close();
        assertArrayEquals("only the answer to the new speech plays", answer, speaker.toByteArray());
        assertEquals(List.of("output c-a", "response.create", "response.cancel"), vendorSaw);
        // The dropped follow-up (r2) never shows as generating: nothing was produced for the owner.
        assertEquals(List.of("started t-19 c1", "ended t-19 c1"), modelCalls);
    }

    @Test
    public void aToolCallFromAResponseTheOwnerTalkedOverIsNotRun() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        // r1 starts a reply and the owner talks over it; r1's media_control call arrives after that.
        server.enqueue(new MockResponse().withWebSocketUpgrade(scripted(
                STARTED, STOPPED, delta("r1", REPLY), STARTED,
                call("r1", "c-stale", "media_control", "{\"action\":\"pause\"}"), done("r1", "cancelled"),
                STOPPED, delta("r2", REPLY), done("r2", "completed"))));
        Mic mic = new Mic();
        String reason = backend(600).run("t-20", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("idle", reason);
        assertTrue("the stale call must not reach the actuator: " + toolCalls, toolCalls.isEmpty());
        assertEquals("its output still goes back, and no follow-up is asked for", List.of("output c-stale"), vendorSaw);
    }

    @Test
    public void anActiveResponseErrorDoesNotEndTheTurn() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        server.enqueue(new MockResponse().withWebSocketUpgrade(scripted(
                STARTED, STOPPED,
                "{\"type\":\"error\",\"error\":{\"code\":\"conversation_already_has_active_response\"}}",
                delta("r1", REPLY), done("r1", "completed"))));
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        String reason = backend(500).run("t-16", mic.in, speaker, () -> { });
        mic.close();
        assertEquals("idle", reason);
        assertEquals(REPLY.length, speaker.size());
    }

    @Test
    public void twoCallsInOneResponseGetOneFollowUpAfterBothResults() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueBatchVendor(call("c-a", "media_control", "{\"action\":\"pause\"}"),
                call("c-b", "volume_set", "{\"percent\":30}"));
        toolDelayMs = 400;
        Mic mic = new Mic();
        String reason = backend(300).run("t-9", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("idle", reason);
        assertEquals(2, toolCalls.size());
        assertEquals(List.of("output c-a", "output c-b", "response.create"), vendorSaw);
    }

    @Test
    public void endTurnWaitsForAToolInTheSameResponseAndItsReport() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueBatchVendor(call("c-a", "media_control", "{\"action\":\"pause\"}"), call("c-end", "end_turn", "{}"));
        toolDelayMs = 800; // longer than the goodbye's end deadline
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        String reason = backend(10_000).run("t-10", mic.in, speaker, () -> { });
        mic.close();
        assertEquals("model end", reason);
        assertEquals(List.of("t-10 media_control {\"action\":\"pause\"}"), toolCalls);
        // The tool's result reached the model, which then reported it before the turn ended.
        assertEquals(List.of("output c-end", "output c-a", "response.create"), vendorSaw);
        assertArrayEquals(REPLY, speaker.toByteArray());
    }

    @Test
    public void aBargeInDuringAToolSendsItsResultButNoFollowUp() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
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
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                } else if (type.equals("input_audio_buffer.append") && appends.incrementAndGet() == 3 && !asked) {
                    asked = true;
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    ws.send(call("c-a", "media_control", "{\"action\":\"pause\"}"));
                    // The owner talks while the tool runs; the vendor cancels r1 and answers in r2 itself.
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r1\",\"status\":\"cancelled\"}}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_stopped\"}");
                    ws.send(delta("r2", REPLY));
                    ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r2\",\"status\":\"completed\"}}");
                } else if (type.equals("conversation.item.create")) {
                    vendorSaw.add("output " + m.optJSONObject("item").optString("call_id"));
                } else if (type.equals("response.create")) {
                    vendorSaw.add("response.create"); // the real vendor errors: a response is already active
                    ws.send("{\"type\":\"error\",\"error\":{\"code\":\"conversation_already_has_active_response\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        toolDelayMs = 600;
        Mic mic = new Mic();
        String reason = backend(1_500).run("t-12", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("idle", reason);
        assertEquals(List.of("output c-a"), vendorSaw);
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
    public void thePatienceSettingsReachTheVendorsSessionUpdate() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        turnDetection = new TurnDetection("long", 1400, 250);
        enqueueVendor(null);
        Mic mic = new Mic();
        String reason = backend(400).run("t-1", mic.in, new ByteArrayOutputStream(), () -> { });
        mic.close();
        assertEquals("idle", reason);
        assertEquals(1, sessionUpdates.size());
        JSONObject vad = sessionUpdates.get(0).getJSONObject("session").getJSONObject("turn_detection");
        assertEquals("server_vad", vad.getString("type"));
        assertEquals(1400, vad.getInt("silence_duration_ms"));
        assertEquals(250, vad.getInt("prefix_padding_ms"));
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
        // Each response is one model call for the indicator, the cancelled one included.
        assertEquals(List.of("started t-18 c1", "ended t-18 c1", "started t-18 c2", "ended t-18 c2"), modelCalls);
    }

    @Test
    public void aLateDoneForADroppedFollowUpDoesNotEndTheNextCall() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
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
                    ws.send(STARTED); // the owner is talking
                    // A follow-up opens while the owner talks: dropped, cancelled, never reported.
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r2\",\"status\":\"in_progress\"}}");
                    ws.send(STOPPED);
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r3\",\"status\":\"in_progress\"}}");
                    ws.send(done("r2", "cancelled")); // late: r3 is the open call, and it keeps going
                    new Thread(() -> {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                            // test
                        }
                        ws.send(done("r3", "completed"));
                    }).start();
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        assertEquals("idle", backend(500).run("t-31", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(List.of("started t-31 c1", "ended t-31 c1"), modelCalls);
        long ms = (modelCallNanos.get(1) - modelCallNanos.get(0)) / 1_000_000;
        assertTrue("c1 ended with r3's done, not r2's late one (" + ms + " ms)", ms >= 300);
    }

    @Test
    public void aLateDoneDoesNotEndACallCreatedWithoutAnId() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
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
                    ws.send(STARTED);
                    ws.send(STOPPED);
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r1\",\"status\":\"in_progress\"}}");
                    ws.send("{\"type\":\"response.created\"}"); // c2, no id: c1 ends here
                    ws.send(done("r1", "completed")); // late, for c1: c2 keeps going
                    new Thread(() -> {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                            // test
                        }
                        ws.send("{\"type\":\"response.done\"}"); // c2's own done
                    }).start();
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        assertEquals("idle", backend(500).run("t-32", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(List.of("started t-32 c1", "ended t-32 c1", "started t-32 c2", "ended t-32 c2"), modelCalls);
        long ms = (modelCallNanos.get(3) - modelCallNanos.get(2)) / 1_000_000;
        assertTrue("c2 ended with its own done, not r1's late one (" + ms + " ms)", ms >= 300);
    }

    @Test
    public void modelCallsPairUpWhenTheVendorSendsNoIds() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
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
                    // No ids at all; an id on created but none on done; none on created but one on done.
                    ws.send("{\"type\":\"response.created\"}");
                    ws.send("{\"type\":\"response.done\"}");
                    ws.send("{\"type\":\"response.created\",\"response\":{\"id\":\"r2\",\"status\":\"in_progress\"}}");
                    ws.send("{\"type\":\"response.done\"}");
                    ws.send("{\"type\":\"response.created\"}");
                    ws.send("{\"type\":\"response.done\",\"response\":{\"id\":\"r3\",\"status\":\"completed\"}}");
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
        Mic mic = new Mic();
        assertEquals("idle", backend(500).run("t-30", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(List.of("started t-30 c1", "ended t-30 c1", "started t-30 c2", "ended t-30 c2",
                "started t-30 c3", "ended t-30 c3"), modelCalls);
    }

    @Test
    public void theVendorClosingWhileTheOwnerSpeaksEndsTheTurnAtOnce() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
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
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}"); // no quiet clock while speaking
                    ws.close(1001, "going away");
                }
            }
        }));
        Mic mic = new Mic();
        long t0 = System.nanoTime();
        String reason = backend(500).run("t-21", mic.in, new ByteArrayOutputStream(), () -> { });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        mic.close();
        assertEquals("vendor closed: 1001", reason);
        assertTrue("ended at once, not at a cap: " + ms + " ms", ms < 4_000);
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

    // ---- Decision 48 (#248): the heard note after a barge-in ----

    /** 32 kHz of bytes per second: 16 kHz s16le. */
    private static final int BPS = 32000;
    /** What the scripted vendor received (mic appends aside) and sent, in order: "got …" and "sent …". */
    private final List<String> vendorLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The heard notes (system message items) the scripted vendor received. */
    private final List<JSONObject> notesSeen = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** When the scripted vendor sent its barge-in (the owner talking over the reply), nanoTime in ms. */
    private volatile long bargeAtMs;

    private interface Script {
        void run(WebSocket ws) throws Exception;
    }

    /** Sends an event and logs it first, so what the session sent back in reply is logged after it. */
    private void send(WebSocket ws, String label, String event) {
        vendorLog.add("sent " + label);
        ws.send(event);
    }

    /**
     * A vendor that runs {@code script} on its own thread after the third mic append (so its sleeps do
     * not hold up what the session sends), and refuses every heard note when {@code refuseNotes}.
     */
    /**
     * How the scripted vendor answers each heard note (a system message item): as xAI does when it
     * accepts one (conversation.item.added, role system), with an error in xAI's shape (its own server
     * id in error.event_id, never the client's; PR #309 probe), or not at all.
     */
    private enum NoteAnswer { ADDED, REFUSED, NONE }

    private void enqueueScripted(Script script, boolean refuseNotes) {
        enqueueScripted(script, refuseNotes ? NoteAnswer.REFUSED : NoteAnswer.ADDED, 0);
    }

    /** An error as the live xAI API sends one: error.event_id is the error's own id. */
    private static String xaiError(String code) {
        String id = java.util.UUID.randomUUID().toString();
        return "{\"type\":\"error\",\"event_id\":\"" + id + "\",\"error\":{\"message\":\"Invalid event received\","
                + "\"type\":\"invalid_request_error\",\"code\":\"" + code + "\",\"event_id\":\"" + id + "\","
                + "\"params\":\"1 validation error for RealtimeClientEvent\"},\"previous_item_id\":null}";
    }

    /** The scripted vendor, answering heard notes as {@code answer} says, after {@code answerDelayMs}. */
    private void enqueueScripted(Script script, NoteAnswer answer, long answerDelayMs) {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            boolean started;

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
                    ws.send("{\"type\":\"session.updated\",\"session\":{}}");
                    return;
                }
                if (type.equals("input_audio_buffer.append")) {
                    if (appends.incrementAndGet() == 3 && !started) {
                        started = true;
                        new Thread(() -> {
                            try {
                                script.run(ws);
                            } catch (Exception ignored) {
                                // the turn is over
                            }
                        }).start();
                    }
                    return;
                }
                JSONObject item = m.optJSONObject("item");
                boolean note = type.equals("conversation.item.create") && item != null
                        && "system".equals(item.optString("role"));
                vendorLog.add("got " + type + (note ? " note" : ""));
                if (note) {
                    notesSeen.add(m);
                    String reply = answer == NoteAnswer.ADDED
                            ? "{\"type\":\"conversation.item.added\",\"item\":{\"type\":\"message\",\"status\":\"completed\","
                                    + "\"role\":\"system\",\"content\":" + item.optJSONArray("content") + "}}"
                            : answer == NoteAnswer.REFUSED ? xaiError("invalid_event") : null;
                    if (reply != null && answerDelayMs == 0) {
                        ws.send(reply);
                    } else if (reply != null) {
                        new Thread(() -> {
                            try {
                                Thread.sleep(answerDelayMs);
                            } catch (InterruptedException ignored) {
                                // test
                            }
                            ws.send(reply);
                        }).start();
                    }
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }
        }));
    }

    private static String transcriptDelta(String responseId, String text) {
        return "{\"type\":\"response.output_audio_transcript.delta\",\"response_id\":\"" + responseId
                + "\",\"delta\":" + JSONObject.quote(text) + "}";
    }

    private static String transcriptDone(String responseId, String text) {
        return "{\"type\":\"response.output_audio_transcript.done\",\"response_id\":\"" + responseId
                + "\",\"transcript\":" + JSONObject.quote(text) + "}";
    }

    private static String created(String responseId) {
        return "{\"type\":\"response.created\",\"response\":{\"id\":\"" + responseId + "\",\"status\":\"in_progress\"}}";
    }

    /** The owner talks over the reply: the barge-in, timed. */
    private void barge(WebSocket ws) {
        bargeAtMs = System.nanoTime() / 1_000_000L;
        send(ws, "speech_started", STARTED);
    }

    /** A short answer after the barge-in, so the turn then ends on the quiet clock. */
    private void answer(WebSocket ws) {
        send(ws, "speech_stopped", STOPPED);
        send(ws, "r2", created("r2"));
        ws.send(delta("r2", filled(3200, 0x33)));
        ws.send(transcriptDone("r2", "Yes?"));
        ws.send(done("r2", "completed"));
    }

    /** The number of the last word ("wNN") a heard note quotes. */
    private static int lastQuoted(JSONObject note) throws Exception {
        String text = note.getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text");
        String quote = text.substring(text.indexOf('"') + 1, text.lastIndexOf('"'));
        return Integer.parseInt(quote.substring(quote.lastIndexOf('w') + 1));
    }

    /** The words of {@code transcript} a note should quote, from when the reply's first write and the barge-in happened. */
    private int expectedWords(String transcript, TimedSpeaker speaker, long totalBytes) {
        long heardMs = bargeAtMs - speaker.writes.get(0)[0]; // the pipe never drained: playback ran from the first write
        return HeardNote.of(transcript, heardMs * BPS / 1000, totalBytes).heardWords;
    }

    @Test
    public void aBargeInMidReplySendsOneNoteQuotingTheWordsHeardBeforeTheOwnersWords() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        RecordingContext rc = new RecordingContext();
        context = rc;
        String story = HeardNoteTest.words(20);
        byte[] r1 = filled(2 * BPS, 0x11); // 2 s, 100 ms per word
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(transcriptDelta("r1", story));
            for (int i = 0; i < 4; i++) { // all of it at once, faster than real time, as the vendor does
                ws.send(delta("r1", java.util.Arrays.copyOfRange(r1, i * BPS / 2, (i + 1) * BPS / 2)));
            }
            ws.send(transcriptDone("r1", story));
            ws.send(done("r1", "completed")); // generated whole before the owner cuts in
            Thread.sleep(1300);
            barge(ws);
            Thread.sleep(300);
            answer(ws);
        }, false);
        Mic mic = new Mic();
        TimedSpeaker speaker = new TimedSpeaker();
        assertEquals("idle", backend(400).run("t-48a", mic.in, speaker, () -> { }));
        mic.close();

        assertEquals("one note", 1, notesSeen.size());
        JSONObject note = notesSeen.get(0);
        assertEquals(XaiRealtimeBackend.NOTE_EVENT + "1", note.getString("event_id"));
        int expected = expectedWords(story, speaker, r1.length);
        int quoted = lastQuoted(note);
        assertTrue("quoted up to w" + quoted + ", heard about " + expected + " words (one slice, 100 ms, is one word)",
                Math.abs(quoted - expected) <= 1);
        assertTrue(expected >= 9 && expected <= 15);
        // Sent at the barge-in (the reply was done), so it precedes the owner's new words in the conversation.
        assertTrue(vendorLog.toString(), vendorLog.indexOf("got conversation.item.create note") > vendorLog.indexOf("sent speech_started"));
        assertTrue(vendorLog.toString(), vendorLog.indexOf("got conversation.item.create note") < vendorLog.indexOf("sent speech_stopped"));
        // The shared conversation gets it too, right after the reply it is about.
        String text = note.getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text");
        int vos = rc.events.indexOf("vos " + story);
        assertTrue(rc.events.toString(), vos >= 0 && rc.events.get(vos + 1).equals("note " + text));
    }

    @Test
    public void aBargeInBeforeTheReplyIsDoneWaitsForItsDoneAndCountsItsDroppedAudio() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        String story = HeardNoteTest.words(20);
        byte[] head = filled(BPS, 0x11); // the first second arrives before the barge-in
        byte[] lateTail = filled(BPS, 0x22); // the second after it: never played, still part of the reply
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(transcriptDelta("r1", HeardNoteTest.words(10)));
            ws.send(delta("r1", head));
            Thread.sleep(600);
            barge(ws);
            Thread.sleep(150);
            ws.send(delta("r1", lateTail));
            ws.send(transcriptDone("r1", story));
            send(ws, "done r1", done("r1", "cancelled"));
            Thread.sleep(300);
            answer(ws);
        }, false);
        Mic mic = new Mic();
        TimedSpeaker speaker = new TimedSpeaker();
        assertEquals("idle", backend(400).run("t-48b", mic.in, speaker, () -> { }));
        mic.close();

        int played = 0;
        for (byte b : speaker.toByteArray()) {
            played += b == 0x22 ? 1 : 0;
        }
        assertEquals("the late tail is not played", 0, played);
        assertEquals("one note", 1, notesSeen.size());
        assertTrue(vendorLog.toString(), vendorLog.indexOf("got conversation.item.create note") > vendorLog.indexOf("sent done r1"));
        assertTrue(vendorLog.toString(), vendorLog.indexOf("got conversation.item.create note") < vendorLog.indexOf("sent speech_stopped"));
        // About 0.6 s heard of 2 s in all (the dropped second counts): under a third of the words, not 60 %.
        int expected = expectedWords(story, speaker, head.length + lateTail.length);
        int quoted = lastQuoted(notesSeen.get(0));
        assertTrue("quoted up to w" + quoted + ", heard about " + expected + " words", Math.abs(quoted - expected) <= 1);
        assertTrue("expected " + expected, expected >= 3 && expected <= 8);
    }

    @Test
    public void noNoteWhenTheReplyHadFinishedPlayingOrNothingOfItWasHeard() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1")); // a short reply that plays whole
            ws.send(delta("r1", filled(3200, 0x11)));
            ws.send(transcriptDone("r1", "It is noon."));
            ws.send(done("r1", "completed"));
            Thread.sleep(700);
            barge(ws); // the owner speaks again after it has played
            ws.send(STOPPED);
            ws.send(created("r2")); // and talks over the next one before any of it is played
            send(ws, "speech_started", STARTED);
            ws.send(delta("r2", filled(3200, 0x22)));
            ws.send(transcriptDone("r2", "The weather today is"));
            ws.send(done("r2", "cancelled"));
            Thread.sleep(100);
            answer(ws);
        }, false);
        Mic mic = new Mic();
        assertEquals("idle", backend(400).run("t-48c", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(vendorLog.toString(), 0, notesSeen.size());
    }

    @Test
    public void aRefusalThatNamesNoClientEventIsMatchedByTimingAndTheTurnGoesOn() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        String story = HeardNoteTest.words(20);
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(delta("r1", filled(2 * BPS, 0x11)));
            ws.send(transcriptDone("r1", story));
            ws.send(done("r1", "completed"));
            Thread.sleep(800);
            barge(ws);
            Thread.sleep(300);
            answer(ws);
        }, true);
        Mic mic = new Mic();
        ByteArrayOutputStream speaker = new ByteArrayOutputStream();
        assertEquals("idle", backend(400).run("t-48d", mic.in, speaker, () -> { }));
        mic.close();
        assertEquals(1, notesSeen.size());
        int r2 = 0;
        for (byte b : speaker.toByteArray()) {
            r2 += b == 0x33 ? 1 : 0;
        }
        assertEquals("the answer after the refused note still plays", 3200, r2);
    }

    @Test
    public void noNoteForAReplyCutWithoutATranscript() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(delta("r1", filled(2 * BPS, 0x11)));
            ws.send(done("r1", "completed"));
            Thread.sleep(800);
            barge(ws);
            Thread.sleep(200);
            answer(ws);
        }, false);
        Mic mic = new Mic();
        assertEquals("idle", backend(400).run("t-48e", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(0, notesSeen.size());
    }

    /** A system message item for a seed: a heard note from an earlier turn. */
    private static JSONObject seededNote(String text) throws Exception {
        return new JSONObject().put("type", "message").put("role", "system").put("content",
                new org.json.JSONArray().put(new JSONObject().put("type", "input_text").put("text", text)));
    }

    /** The shared conversation's "vos …" and "note …" events, in order. */
    private static List<String> linesOf(RecordingContext rc) {
        List<String> out = new ArrayList<>();
        for (String e : rc.events) {
            if (e.startsWith("vos ") || e.startsWith("note ") || e.startsWith("owner ")) {
                out.add(e.startsWith("note ") ? "note" : e);
            }
        }
        return out;
    }

    @Test
    public void anErrorAfterTheNoteIsConfirmedStillEndsTheTurn() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(delta("r1", filled(2 * BPS, 0x11)));
            ws.send(transcriptDone("r1", HeardNoteTest.words(20)));
            ws.send(done("r1", "completed"));
            Thread.sleep(800);
            barge(ws); // the note goes out and is confirmed at once (conversation.item.added)
            Thread.sleep(300);
            ws.send(xaiError("invalid_audio")); // then an unrelated error
        }, NoteAnswer.ADDED, 0);
        Mic mic = new Mic();
        assertEquals("vendor error: invalid_audio", backend(10_000).run("t-48f", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(1, notesSeen.size());
    }

    @Test
    public void aNoteTheVendorNeverAnswersIsUnconfirmedOnlyUntilTheCap() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        noteAckMs = 200;
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(delta("r1", filled(2 * BPS, 0x11)));
            ws.send(transcriptDone("r1", HeardNoteTest.words(20)));
            ws.send(done("r1", "completed"));
            Thread.sleep(800);
            barge(ws); // no answer to the note at all
            Thread.sleep(600);
            ws.send(xaiError("invalid_audio")); // well past the cap: not the note's
        }, NoteAnswer.NONE, 0);
        Mic mic = new Mic();
        assertEquals("vendor error: invalid_audio", backend(10_000).run("t-48g", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(1, notesSeen.size());
    }

    @Test
    public void twoUnconfirmedNotesTakeOneErrorEachAndThenErrorsEndTheTurnAgain() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        RecordingContext rc = new RecordingContext();
        rc.seedItems = List.of(seededNote("The owner interrupted your last reply after: \"one\"."),
                seededNote("The owner interrupted your last reply after: \"two\"."));
        context = rc;
        enqueueScripted(ws -> {
            Thread.sleep(500); // both seeded notes were refused by now, one error each, in order
            ws.send(xaiError("invalid_audio"));
        }, NoteAnswer.REFUSED, 150);
        Mic mic = new Mic();
        assertEquals("the third error is not a note's", "vendor error: invalid_audio",
                backend(10_000).run("t-48h", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(2, notesSeen.size());
        assertEquals(XaiRealtimeBackend.NOTE_EVENT + "seed-1", notesSeen.get(0).getString("event_id"));
        assertEquals(XaiRealtimeBackend.NOTE_EVENT + "seed-2", notesSeen.get(1).getString("event_id"));
    }

    @Test
    public void transcriptsAndDonesWithoutIdsAsTheLiveApiSentThemOnOctober20() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        RecordingContext rc = new RecordingContext();
        context = rc;
        String story = HeardNoteTest.words(20);
        byte[] r1 = filled(2 * BPS, 0x11);
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(delta("r1", r1)); // audio carries the response id
            ws.send("{\"type\":\"response.output_audio_transcript.delta\",\"delta\":" + JSONObject.quote(story) + "}");
            ws.send("{\"type\":\"response.output_audio_transcript.done\",\"transcript\":" + JSONObject.quote(story) + "}");
            ws.send("{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}"); // no id
            Thread.sleep(1300);
            barge(ws);
            Thread.sleep(300);
            answer(ws);
        }, NoteAnswer.ADDED, 0);
        Mic mic = new Mic();
        TimedSpeaker speaker = new TimedSpeaker();
        assertEquals("idle", backend(400).run("t-48i", mic.in, speaker, () -> { }));
        mic.close();
        assertEquals(1, notesSeen.size());
        int expected = expectedWords(story, speaker, r1.length);
        int quoted = lastQuoted(notesSeen.get(0));
        assertTrue("quoted up to w" + quoted + ", heard about " + expected + " words", Math.abs(quoted - expected) <= 1);
        List<String> lines = linesOf(rc);
        assertEquals(lines.toString(), "note", lines.get(lines.indexOf("vos " + story) + 1));
    }

    @Test
    public void theOwnerStoppingFirstSendsTheNoteThenAndTheConversationKeepsItAfterTheReply() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        RecordingContext rc = new RecordingContext();
        context = rc;
        String story = HeardNoteTest.words(20);
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(transcriptDelta("r1", HeardNoteTest.words(10)));
            ws.send(delta("r1", filled(BPS, 0x11)));
            Thread.sleep(600);
            barge(ws);
            Thread.sleep(150);
            send(ws, "speech_stopped", STOPPED); // before the cut reply's done: the note goes now
            Thread.sleep(200);
            ws.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"wait\"}");
            ws.send(transcriptDone("r1", story));
            send(ws, "done r1", done("r1", "cancelled"));
            Thread.sleep(100);
            ws.send(created("r2"));
            ws.send(delta("r2", filled(3200, 0x33)));
            ws.send(transcriptDone("r2", "Yes?"));
            ws.send(done("r2", "completed"));
        }, NoteAnswer.ADDED, 0);
        Mic mic = new Mic();
        assertEquals("idle", backend(400).run("t-48j", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(1, notesSeen.size());
        int note = vendorLog.indexOf("got conversation.item.create note");
        assertTrue(vendorLog.toString(), note > vendorLog.indexOf("sent speech_stopped") && note < vendorLog.indexOf("sent done r1"));
        // In the shared conversation the note waits for the reply's line, from its final transcript.
        List<String> lines = linesOf(rc);
        assertEquals(lines.toString(), List.of("owner wait", "vos " + story, "note", "vos Yes?"), lines);
    }

    @Test
    public void aTurnEndingBeforeTheCutReplyIsDoneKeepsTheReplyThenTheNoteAndSendsNothing() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        RecordingContext rc = new RecordingContext();
        context = rc;
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(transcriptDelta("r1", HeardNoteTest.words(10)));
            ws.send(delta("r1", filled(BPS, 0x11)));
            Thread.sleep(600);
            barge(ws);
            Thread.sleep(150);
            ws.close(1000, "bye"); // the vendor ends the session before the reply's done
        }, NoteAnswer.ADDED, 0);
        Mic mic = new Mic();
        assertEquals("vendor closed: 1000", backend(10_000).run("t-48k", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals("nothing goes to a closed session", 0, notesSeen.size());
        List<String> lines = linesOf(rc);
        assertEquals(List.of("vos " + HeardNoteTest.words(10), "note"), lines.subList(lines.size() - 2, lines.size()));
        String note = rc.events.get(rc.events.size() - 2);
        assertTrue(note, note.startsWith("note The owner interrupted your last reply after: \"w01"));
        assertEquals("after t-48k", rc.events.get(rc.events.size() - 1));
    }

    @Test
    public void aFollowUpCreatedWhileTheReplyStillPlaysDoesNotTakeItsNote() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        String story = HeardNoteTest.words(20);
        byte[] r1 = filled(2 * BPS, 0x11);
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            ws.send(transcriptDelta("r1", story));
            for (int i = 0; i < 20; i++) { // 100 ms deltas, as xAI streams them
                ws.send(delta("r1", java.util.Arrays.copyOfRange(r1, i * BPS / 10, (i + 1) * BPS / 10)));
            }
            ws.send(transcriptDone("r1", story));
            ws.send(done("r1", "completed"));
            ws.send(created("r2")); // e.g. the answer after a tool result, while r1 is still playing
            Thread.sleep(1000);
            barge(ws);
            ws.send(delta("r2", filled(3200, 0x22))); // r2's audio only after the cut: never played
            ws.send(transcriptDone("r2", "x01 x02 x03"));
            ws.send(done("r2", "cancelled"));
            Thread.sleep(200);
            send(ws, "speech_stopped", STOPPED);
            ws.send(created("r3"));
            ws.send(delta("r3", filled(3200, 0x33)));
            ws.send(done("r3", "completed"));
        }, NoteAnswer.ADDED, 0);
        Mic mic = new Mic();
        TimedSpeaker speaker = new TimedSpeaker();
        assertEquals("idle", backend(400).run("t-48l", mic.in, speaker, () -> { }));
        mic.close();
        assertEquals("one note, about the reply that was playing", 1, notesSeen.size());
        int expected = expectedWords(story, speaker, r1.length);
        int quoted = lastQuoted(notesSeen.get(0));
        assertTrue("quoted up to w" + quoted + ", heard about " + expected + " words", Math.abs(quoted - expected) <= 1);
    }

    @Test
    public void theFirstNoteSentIsNumberOneEvenAfterCutsThatSentNone() throws Exception {
        vault.put(KeyVault.XAI, "xai-test-key");
        enqueueScripted(ws -> {
            ws.send(STARTED);
            ws.send(STOPPED);
            ws.send(created("r1"));
            send(ws, "speech_started", STARTED); // r1 is cut before any of it played: no note
            ws.send(delta("r1", filled(3200, 0x22)));
            ws.send(transcriptDone("r1", "The weather today is"));
            ws.send(done("r1", "cancelled"));
            ws.send(STOPPED);
            ws.send(created("r2"));
            ws.send(delta("r2", filled(2 * BPS, 0x11)));
            ws.send(transcriptDone("r2", HeardNoteTest.words(20)));
            ws.send(done("r2", "completed"));
            Thread.sleep(800);
            barge(ws);
            Thread.sleep(200);
            ws.send(STOPPED);
            ws.send(created("r3"));
            ws.send(delta("r3", filled(3200, 0x33)));
            ws.send(done("r3", "completed"));
        }, NoteAnswer.ADDED, 0);
        Mic mic = new Mic();
        assertEquals("idle", backend(400).run("t-48m", mic.in, new ByteArrayOutputStream(), () -> { }));
        mic.close();
        assertEquals(1, notesSeen.size());
        assertEquals(XaiRealtimeBackend.NOTE_EVENT + "1", notesSeen.get(0).getString("event_id"));
    }
}
