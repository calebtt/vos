package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The chat_text backend against a scripted fake of xAI chat completions (MockWebServer). */
public class XaiTextBackendTest {
    private static final String HOST = "vendor.test";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MockWebServer server = new MockWebServer();
    private final List<String> events = new ArrayList<>();
    private KeyVault vault;
    private XaiTextBackend backend;

    @Before
    public void setUp() throws Exception {
        server.start(InetAddress.getLoopbackAddress(), 0);
        Egress egress = new Egress(new EgressAllowlist(Set.of(HOST)), new RequestLog(tmp.newFolder("egress")),
                h -> List.of(InetAddress.getLoopbackAddress()));
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        SecretKey k = g.generateKey();
        vault = new KeyVault(tmp.newFolder("keys"), new AesGcmSealer(() -> k));
        vault.put(KeyVault.XAI, "xai-test-key");
        String policy = "{\"registry\":[{\"id\":\"xai:text\",\"kind\":\"text_chat\",\"vendor\":\"xai\",\"model\":\"m\","
                + "\"url\":\"http://" + HOST + ":" + server.getPort() + "/v1/chat/completions\"}],\"policy\":{\"chat_text\":[\"xai:text\"]}}";
        ModelPolicy.Entry e = new ModelPolicy(policy).entry("xai:text");
        JSONArray tools = new JSONArray("[{\"type\":\"function\",\"name\":\"time_get\",\"description\":\"clock\","
                + "\"parameters\":{\"type\":\"object\"}},{\"type\":\"function\",\"name\":\"end_turn\",\"parameters\":{}}]");
        backend = new XaiTextBackend(egress, vault, e, "be brief", tools);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private final ModelCalls calls = new ModelCalls() {
        @Override
        public void started(String turnId, String callId) {
            events.add("started " + turnId + " " + callId);
        }

        @Override
        public void ended(String turnId, String callId) {
            events.add("ended " + turnId + " " + callId);
        }
    };

    private static String content(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + JSONObject.quote(text) + "}}]}";
    }

    @Test
    public void endTurnIsNotOfferedToATypedTurn() throws Exception {
        JSONArray t = XaiTextBackend.chatTools(new JSONArray("[{\"type\":\"function\",\"name\":\"time_get\",\"parameters\":{}},"
                + "{\"type\":\"function\",\"name\":\"end_turn\",\"parameters\":{}}]"));
        assertEquals(1, t.length());
        assertEquals("time_get", t.getJSONObject(0).getJSONObject("function").getString("name"));
    }

    @Test
    public void theSharedConversationComesFirstThenTheTypedText() throws Exception {
        server.enqueue(new MockResponse().setBody(content("Your sister is Dana.")));
        List<JSONObject> seed = List.of(
                new JSONObject("{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"My sister's name is Dana.\"}]}"),
                new JSONObject("{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Got it.\"}]}"));
        String reply = backend.reply("t-1", seed, "What's my sister's name?", (turn, name, args) -> "{}", calls,
                (turn, name, result) -> events.add("tool " + name));
        assertEquals("Your sister is Dana.", reply);
        RecordedRequest r = server.takeRequest();
        assertEquals("Bearer xai-test-key", r.getHeader("Authorization"));
        JSONArray m = new JSONObject(r.getBody().readUtf8()).getJSONArray("messages");
        assertEquals("system", m.getJSONObject(0).getString("role"));
        assertEquals("My sister's name is Dana.", m.getJSONObject(1).getString("content"));
        assertEquals("assistant", m.getJSONObject(2).getString("role"));
        assertEquals("What's my sister's name?", m.getJSONObject(3).getString("content"));
        assertEquals(List.of("started t-1 t1", "ended t-1 t1"), events);
    }

    @Test
    public void aToolCallRunsInTheActuatorAndItsResultGoesBack() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"time_get\",\"arguments\":\"{}\"}}]}}]}"));
        server.enqueue(new MockResponse().setBody(content("It's 9:00 AM.")));
        List<String> ran = new ArrayList<>();
        String reply = backend.reply("t-2", List.of(), "what time is it", (turn, name, args) -> {
            ran.add(turn + " " + name + " " + args);
            return "{\"status\":\"VERIFIED\",\"spoken\":\"9:00 AM\"}";
        }, calls, (turn, name, result) -> events.add("tool " + name));
        assertEquals("It's 9:00 AM.", reply);
        assertEquals(List.of("t-2 time_get {}"), ran);
        assertEquals(List.of("started t-2 t1", "ended t-2 t1", "tool time_get", "started t-2 t2", "ended t-2 t2"), events);
        server.takeRequest();
        JSONArray m = new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("messages");
        JSONObject toolMsg = m.getJSONObject(m.length() - 1);
        assertEquals("tool", toolMsg.getString("role"));
        assertEquals("call-1", toolMsg.getString("tool_call_id"));
        assertTrue(toolMsg.getString("content").contains("VERIFIED"));
    }

    @Test
    public void aVendorErrorThrowsAndTheModelCallStillEnds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        boolean threw = false;
        try {
            backend.reply("t-3", List.of(), "hi", (turn, name, args) -> "{}", calls, (turn, name, result) -> { });
        } catch (java.io.IOException e) {
            threw = true;
        }
        assertTrue(threw);
        assertEquals(List.of("started t-3 t1", "ended t-3 t1"), events);
    }

    @Test
    public void theToolRoundsAreBounded() throws Exception {
        String toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"time_get\",\"arguments\":\"{}\"}}]}}]}";
        for (int i = 0; i < XaiTextBackend.MAX_TOOL_ROUNDS; i++) {
            server.enqueue(new MockResponse().setBody(toolCall));
        }
        server.enqueue(new MockResponse().setBody(content("done")));
        backend.reply("t-4", List.of(), "loop", (turn, name, args) -> "{}", calls, (turn, name, result) -> { });
        for (int i = 0; i < XaiTextBackend.MAX_TOOL_ROUNDS; i++) {
            server.takeRequest();
        }
        assertFalse("the last request offers no tools", new JSONObject(server.takeRequest().getBody().readUtf8()).has("tools"));
    }
}
