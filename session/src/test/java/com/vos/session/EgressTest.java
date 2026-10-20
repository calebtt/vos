package com.vos.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Dns;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EgressTest {
    private static final String ALLOWED = "vendor.test";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MockWebServer server = new MockWebServer();
    private final List<String> resolved = new ArrayList<>();
    private RequestLog log;
    private Egress egress;

    @Before
    public void setUp() throws Exception {
        server.start(InetAddress.getLoopbackAddress(), 0);
        log = new RequestLog(tmp.newFolder("egress"));
        // Every name resolves to the local server, so only the allowlist decides what is reachable.
        Dns toServer = hostname -> {
            resolved.add(hostname);
            return List.of(InetAddress.getLoopbackAddress());
        };
        egress = new Egress(new EgressAllowlist(Set.of(ALLOWED)), log, toServer);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private String url(String host, String path) {
        return "http://" + host + ":" + server.getPort() + path;
    }

    private List<JSONObject> lines() throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (!log.file().isFile()) {
            return out;
        }
        for (String l : Files.readAllLines(log.file().toPath())) {
            out.add(new JSONObject(l));
        }
        return out;
    }

    @Test
    public void anAllowedHostIsReachedAndLogged() throws Exception {
        server.enqueue(new MockResponse().setBody("hello"));
        Request r = new Request.Builder().url(url(ALLOWED, "/v1/models?secret=q")).tag(Egress.TurnTag.class,
                new Egress.TurnTag("t-1")).build();
        try (Response resp = egress.client().newCall(r).execute()) {
            assertEquals("hello", resp.body().string());
        }
        JSONObject l = lines().get(0);
        assertEquals("http", l.getString("kind"));
        assertEquals(ALLOWED, l.getString("host"));
        assertEquals("/v1/models", l.getString("path")); // no query string
        assertEquals(200, l.getInt("status"));
        assertTrue(l.getLong("bytes_in") >= 5); // headers plus the 5-byte body
        assertTrue(l.getLong("bytes_out") > 0);
        assertEquals("t-1", l.getString("turn_id"));
        assertEquals("ok", l.getString("outcome"));
    }

    @Test
    public void anotherHostIsRefusedBeforeAnyLookupOrConnection() throws Exception {
        Request r = new Request.Builder().url(url("elsewhere.test", "/collect")).build();
        assertThrows(Egress.RefusedException.class, () -> egress.client().newCall(r).execute());
        assertEquals(0, server.getRequestCount());
        assertFalse(resolved.contains("elsewhere.test"));
        JSONObject l = lines().get(0);
        assertEquals("elsewhere.test", l.getString("host"));
        assertEquals("refused", l.getString("outcome"));
    }

    @Test
    public void theResolverRefusesOtherHostsToo() {
        Egress plain = new Egress(new EgressAllowlist(Set.of(ALLOWED)), log, hostname -> {
            throw new AssertionError("system DNS must not be asked for " + hostname);
        });
        Dns guarded = plain.client().dns();
        assertThrows(UnknownHostException.class, () -> guarded.lookup("elsewhere.test"));
    }

    @Test
    public void redirectsAreNotFollowed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", url("elsewhere.test", "/x")));
        Request r = new Request.Builder().url(url(ALLOWED, "/start")).build();
        try (Response resp = egress.client().newCall(r).execute()) {
            assertEquals(302, resp.code());
        }
        assertEquals(1, server.getRequestCount());
        assertFalse(resolved.contains("elsewhere.test"));
    }

    @Test
    public void aWebSocketIsCountedAndLoggedOnce() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> echoed = new AtomicReference<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket ws, String text) {
                ws.send("echo:" + text);
                ws.close(1000, "done");
            }
        }));
        WebSocket ws = egress.openWebSocket(new Request.Builder().url(url(ALLOWED, "/v1/realtime")).build(), "t-2",
                new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket w, String text) {
                        echoed.set(text);
                    }

                    @Override
                    public void onClosing(WebSocket w, int code, String reason) {
                        w.close(1000, null);
                    }

                    @Override
                    public void onClosed(WebSocket w, int code, String reason) {
                        closed.countDown();
                    }
                });
        assertTrue(ws.send("ping-1"));
        assertTrue(closed.await(10, TimeUnit.SECONDS));
        assertEquals("echo:ping-1", echoed.get());
        List<JSONObject> l = lines();
        assertEquals(1, l.size());
        assertEquals("ws", l.get(0).getString("kind"));
        assertEquals("t-2", l.get(0).getString("turn_id"));
        assertEquals(6, l.get(0).getLong("bytes_out"));
        assertEquals(11, l.get(0).getLong("bytes_in"));
        assertEquals(101, l.get(0).getInt("status"));
        assertEquals("closed:1000", l.get(0).getString("outcome"));
    }

    @Test
    public void sendsFromTheListenersCallbacksAreCountedToo() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text.equals("second")) {
                    ws.close(1000, "done");
                }
            }
        }));
        egress.openWebSocket(new Request.Builder().url(url(ALLOWED, "/v1/realtime")).build(), "t-4",
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket w, Response response) {
                        w.send("first-from-onOpen"); // the usual place for a session's first message
                        w.send("second");
                    }

                    @Override
                    public void onClosing(WebSocket w, int code, String reason) {
                        w.close(1000, null);
                    }

                    @Override
                    public void onClosed(WebSocket w, int code, String reason) {
                        closed.countDown();
                    }
                });
        assertTrue(closed.await(10, TimeUnit.SECONDS));
        List<JSONObject> l = lines();
        assertEquals(1, l.size());
        assertEquals("first-from-onOpen".length() + "second".length(), l.get(0).getLong("bytes_out"));
    }

    @Test
    public void failedConnectionsAreNotSilentlyRetried() {
        assertFalse(egress.client().retryOnConnectionFailure());
    }

    @Test
    public void a503WithRetryAfterZeroIsNotResent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
        server.enqueue(new MockResponse().setBody("second"));
        Request r = new Request.Builder().url(url(ALLOWED, "/v1/x"))
                .post(okhttp3.RequestBody.create("{}", okhttp3.MediaType.get("application/json"))).build();
        try (Response resp = egress.client().newCall(r).execute()) {
            assertEquals(503, resp.code());
        }
        assertEquals(1, server.getRequestCount());
        assertEquals(1, lines().size());
    }

    @Test
    public void aRefusedWebSocketIsLoggedOnce() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> why = new AtomicReference<>();
        egress.openWebSocket(new Request.Builder().url(url("elsewhere.test", "/ws")).build(), "t-3",
                new WebSocketListener() {
                    @Override
                    public void onFailure(WebSocket w, Throwable t, Response r) {
                        why.set(t);
                        failed.countDown();
                    }
                });
        assertTrue(failed.await(10, TimeUnit.SECONDS));
        assertNotNull(why.get());
        assertTrue(why.get() instanceof Egress.RefusedException);
        List<JSONObject> l = lines();
        assertEquals(1, l.size());
        assertEquals("refused", l.get(0).getString("outcome"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void theLogRotates() throws Exception {
        File dir = log.file().getParentFile();
        Files.write(log.file().toPath(), new byte[(int) RequestLog.MAX_BYTES + 1]);
        log.write(new RequestLog.Entry("http", ALLOWED, "GET", "/", 0, 0, 200, null, "ok"));
        assertTrue(new File(dir, "requests.1.jsonl").isFile());
        assertEquals(1, Files.readAllLines(log.file().toPath()).size());
    }
}
