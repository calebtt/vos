package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A typed turn's deadline (decision 21) and the owner's stop (P5, #300, onTurnClosed): the session ends
 * the turn, and nothing of it reaches the shared conversation or the actuator after its endTurn, which
 * is when the actuator frees the owner-turn slot for a voice summon. Against the real chat_text
 * backend and a scripted fake of xAI chat completions (MockWebServer).
 */
public class TypedTurnsTest {
    private static final String HOST = "vendor.test";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MockWebServer server = new MockWebServer();
    /** Everything the turn did to the conversation and the actuator, in order. */
    private final List<String> log = new ArrayList<>();
    private final BlockingQueue<String> endTurns = new LinkedBlockingQueue<>();
    private volatile long toolMs;
    private XaiTextBackend backend;
    private TranscriptLog transcripts;

    private synchronized void log(String event) {
        log.add(event);
    }

    private synchronized List<String> log() {
        return new ArrayList<>(log);
    }

    private final ContextKeeper context = new ContextKeeper() {
        @Override
        public String beforeTurn(String turnId) {
            log("beforeTurn " + turnId);
            return null;
        }

        @Override
        public List<JSONObject> seed() {
            return List.of();
        }

        @Override
        public void owner(String turnId, String text) {
            log("owner " + turnId);
        }

        @Override
        public void vos(String turnId, String text) {
            log("vos " + turnId + " " + text);
        }

        @Override
        public void tool(String turnId, String name, String result) {
            log("tool " + turnId + " " + name);
        }

        @Override
        public void afterTurn(String turnId) {
            log("afterTurn " + turnId);
        }
    };

    private final TypedTurns.Actuator actuator = new TypedTurns.Actuator() {
        @Override
        public String callTool(String turnId, String name, String argsJson) {
            log("callTool " + turnId + " " + name);
            try {
                Thread.sleep(toolMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log("callTool done " + turnId);
            return "{\"status\":\"VERIFIED\",\"spoken\":\"9:00 AM\"}";
        }

        @Override
        public void typedReply(String turnId, String text) {
            log("typedReply " + turnId + " " + text);
        }

        @Override
        public void endTurn(String turnId, String reason) {
            log("endTurn " + turnId + " " + reason);
            endTurns.add(turnId + " " + reason);
        }

        @Override
        public void started(String turnId, String callId) {
        }

        @Override
        public void ended(String turnId, String callId) {
        }
    };

    @Before
    public void setUp() throws Exception {
        server.start(InetAddress.getLoopbackAddress(), 0);
        Egress egress = new Egress(new EgressAllowlist(Set.of(HOST)), new RequestLog(tmp.newFolder("egress")),
                h -> List.of(InetAddress.getLoopbackAddress()));
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        SecretKey k = g.generateKey();
        KeyVault vault = new KeyVault(tmp.newFolder("keys"), new AesGcmSealer(() -> k));
        vault.put(KeyVault.XAI, "xai-test-key");
        String policy = "{\"registry\":[{\"id\":\"xai:text\",\"kind\":\"text_chat\",\"vendor\":\"xai\",\"model\":\"m\","
                + "\"url\":\"http://" + HOST + ":" + server.getPort() + "/v1/chat/completions\"}],\"policy\":{\"chat_text\":[\"xai:text\"]}}";
        ModelPolicy.Entry e = new ModelPolicy(policy).entry("xai:text");
        JSONArray tools = new JSONArray("[{\"type\":\"function\",\"name\":\"time_get\",\"description\":\"clock\","
                + "\"parameters\":{\"type\":\"object\"}}]");
        backend = new XaiTextBackend(egress, vault, e, "be brief", tools);
        transcripts = new TranscriptLog(tmp.newFolder("transcripts"));
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private TypedTurns turns(long deadlineMs) {
        return new TypedTurns(() -> new TypedTurns.Wiring() {
            @Override
            public ContextKeeper context() {
                return context;
            }

            @Override
            public XaiTextBackend backend() {
                return backend;
            }

            @Override
            public TypedTurns.Actuator actuator() {
                return actuator;
            }

            @Override
            public TranscriptLog transcripts() {
                return transcripts;
            }
        }, deadlineMs);
    }

    private static MockResponse content(String text) {
        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + JSONObject.quote(text) + "}}]}");
    }

    /** One round asking for {@code n} time_get calls. */
    private static MockResponse toolCalls(int n) {
        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < n; i++) {
            calls.append(i == 0 ? "" : ",").append("{\"id\":\"c").append(i)
                    .append("\",\"type\":\"function\",\"function\":{\"name\":\"time_get\",\"arguments\":\"{}\"}}");
        }
        return new MockResponse().setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[" + calls + "]}}]}");
    }

    private List<String> transcriptRoles(String turnId) throws Exception {
        List<String> roles = new ArrayList<>();
        if (!transcripts.file().isFile()) {
            return roles;
        }
        for (String line : Files.readAllLines(transcripts.file().toPath())) {
            JSONObject o = new JSONObject(line);
            if (turnId.equals(o.getString("turn_id"))) {
                roles.add(o.getString("role"));
            }
        }
        return roles;
    }

    @Test
    public void aTurnAnsweredInTimeEndsOnceAndItsDeadlineDoesNothing() throws Exception {
        server.enqueue(content("It's 9:00 AM."));
        TypedTurns turns = turns(300);
        turns.open("a", "what time is it", 0);
        assertEquals("a typed reply", endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(600); // past the deadline
        assertNull("no second end", endTurns.poll());
        assertEquals(List.of("beforeTurn a", "owner a", "vos a It's 9:00 AM.", "typedReply a It's 9:00 AM.",
                "afterTurn a", "endTurn a typed reply"), log());
    }

    @Test
    public void theSessionEndsATurnAtItsDeadlineAndItsModelCallIsCancelled() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); // a model that never answers
        TypedTurns turns = turns(300);
        long t0 = System.nanoTime();
        turns.open("a", "hello", 0);
        assertEquals("a " + TypedTurns.DEADLINE, endTurns.poll(5, TimeUnit.SECONDS));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("ended at the deadline, not by the call's own timeout: " + ms + " ms", ms >= 300 && ms < 3_000);
        // The worker is free at once: the next typed turn does not wait behind the stale call (30 s).
        server.enqueue(content("Hi."));
        turns.open("b", "hi", 0);
        assertEquals("b typed reply", endTurns.poll(5, TimeUnit.SECONDS));
        assertEquals(List.of("beforeTurn a", "owner a", "afterTurn a", "endTurn a " + TypedTurns.DEADLINE,
                "beforeTurn b", "owner b", "vos b Hi.", "typedReply b Hi.", "afterTurn b", "endTurn b typed reply"), log());
        assertEquals("the late turn wrote no reply", List.of("owner"), transcriptRoles("a"));
    }

    @Test
    public void aToolCallInFlightAtTheDeadlineFinishesBeforeEndTurnAndNothingFollowsIt() throws Exception {
        server.enqueue(toolCalls(2));
        server.enqueue(content("never asked for"));
        toolMs = 1_500; // the first tool is still running at the deadline
        TypedTurns turns = turns(200);
        turns.open("a", "what time is it", 0);
        assertEquals("a " + TypedTurns.DEADLINE, endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(500); // anything the worker would still do after the end
        // endTurn waits for the tool in flight; its result, the second tool and the next round are dropped.
        assertEquals(List.of("beforeTurn a", "owner a", "callTool a time_get", "callTool done a", "afterTurn a",
                "endTurn a " + TypedTurns.DEADLINE), log());
        assertEquals("no model call after the deadline", 1, server.getRequestCount());
    }

    /** Waits until {@code event} is in the log. */
    private void awaitLog(String event) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!log().contains(event)) {
            assertTrue("never logged: " + event + " in " + log(), System.nanoTime() < until);
            Thread.sleep(10);
        }
    }

    @Test
    public void theOwnersStopMidModelCallCancelsItEndsTheTurnAtOnceAndNothingOfItLands() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); // a slow model
        TypedTurns turns = turns(30_000); // the deadline is far off: only the stop ends it
        turns.open("a", "tell me a long story", 0);
        server.takeRequest(5, TimeUnit.SECONDS); // the model call is in flight
        long t0 = System.nanoTime();
        assertTrue(turns.close("a"));
        assertEquals("a " + TypedTurns.CLOSED, endTurns.poll(5, TimeUnit.SECONDS));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("ended at the stop, not by the call's own timeout: " + ms + " ms", ms < 2_000);
        Thread.sleep(300); // anything the worker would still do after the end
        // No reply and no apology: the owner stopped it. The owner's line stays, as at the deadline.
        assertEquals(List.of("beforeTurn a", "owner a", "afterTurn a", "endTurn a " + TypedTurns.CLOSED), log());
        assertEquals("the stopped turn wrote no reply", List.of("owner"), transcriptRoles("a"));
        assertNull("one end only", endTurns.poll());
        // The worker is free at once: the next typed turn does not wait behind the cancelled call.
        server.enqueue(content("Hi."));
        turns.open("b", "hi", 0);
        assertEquals("b typed reply", endTurns.poll(5, TimeUnit.SECONDS));
    }

    @Test
    public void aToolCallInFlightAtTheStopFinishesBeforeEndTurnAndNoOtherToolOrModelCallFollows() throws Exception {
        server.enqueue(toolCalls(2));
        server.enqueue(content("never asked for"));
        toolMs = 800; // the first tool is still running at the stop
        TypedTurns turns = turns(30_000);
        turns.open("a", "what time is it", 0);
        awaitLog("callTool a time_get");
        assertTrue(turns.close("a"));
        assertEquals("a " + TypedTurns.CLOSED, endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(500);
        // endTurn waits for the tool in flight; its result, the second tool and the next round are dropped.
        assertEquals(List.of("beforeTurn a", "owner a", "callTool a time_get", "callTool done a", "afterTurn a",
                "endTurn a " + TypedTurns.CLOSED), log());
        assertEquals("no model call after the stop", 1, server.getRequestCount());
    }

    @Test
    public void aTurnStoppedDuringTheDebugHoldSendsNoModelCall() throws Exception {
        server.enqueue(content("never asked for"));
        TypedTurns turns = turns(30_000);
        turns.open("a", "what day is it", 800); // eval/m1/chat.sh stops it during this hold
        awaitLog("owner a");
        assertTrue(turns.close("a"));
        assertEquals("a " + TypedTurns.CLOSED, endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(1_200); // past the hold
        assertEquals(List.of("beforeTurn a", "owner a", "afterTurn a", "endTurn a " + TypedTurns.CLOSED), log());
        assertEquals(0, server.getRequestCount());
        assertFalse("ended turns are forgotten", turns.close("a"));
    }

    @Test
    public void aStopForATurnQueuedBehindAnotherEndsItBeforeItRuns() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        TypedTurns turns = turns(30_000);
        turns.open("a", "first", 0);
        server.takeRequest(5, TimeUnit.SECONDS);
        turns.open("b", "second", 0); // waits behind a on the worker
        assertTrue(turns.close("b"));
        assertEquals("b " + TypedTurns.CLOSED, endTurns.poll(5, TimeUnit.SECONDS));
        assertTrue(turns.close("a"));
        assertEquals("a " + TypedTurns.CLOSED, endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertFalse("b never ran", log().contains("beforeTurn b"));
        assertEquals("only a's model call", 1, server.getRequestCount());
    }

    @Test
    public void aStopAfterTheReplyOrTwiceSendsNoSecondEndTurn() throws Exception {
        server.enqueue(content("It's 9:00 AM."));
        TypedTurns turns = turns(30_000);
        turns.open("a", "what time is it", 0);
        assertEquals("a typed reply", endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(100); // the worker forgets the turn after its end
        assertFalse("a voice turn's id, or one already over, is not a typed turn here", turns.close("a"));
        assertFalse(turns.close("voice-1"));
        assertFalse(turns.close(null));

        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        turns.open("b", "hello", 0);
        server.takeRequest(5, TimeUnit.SECONDS);
        turns.close("b");
        turns.close("b");
        assertEquals("b " + TypedTurns.CLOSED, endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertNull("one end only", endTurns.poll());
    }

    @Test
    public void aTurnHeldPastItsDeadlineSendsNoModelCall() throws Exception {
        server.enqueue(content("never asked for"));
        TypedTurns turns = turns(200);
        turns.open("a", "hello", 1_000); // the debug hold (eval/m1/chat.sh), as a slow model
        assertEquals("a " + TypedTurns.DEADLINE, endTurns.poll(5, TimeUnit.SECONDS));
        Thread.sleep(1_200); // past the hold
        assertEquals(List.of("beforeTurn a", "owner a", "afterTurn a", "endTurn a " + TypedTurns.DEADLINE), log());
        assertEquals(0, server.getRequestCount());
    }
}
