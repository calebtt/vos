package com.vos.session;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * The session's only way out (§5.8). One OkHttp client that reaches only allowlisted hosts:
 * <ul>
 *   <li>an application interceptor refuses any other host before a connection is made;</li>
 *   <li>the DNS resolver refuses to resolve any other host, so nothing below the interceptor can
 *       reach one either;</li>
 *   <li>redirects are not followed, and no proxy is used.</li>
 * </ul>
 * Every HTTP request and WebSocket connection is written to the {@link RequestLog} with its host,
 * bytes and turn.
 */
public final class Egress {
    /** v0 allowlist: the voice vendor only. Vision, RSS and MCP hosts join with their features. */
    public static final Set<String> HOSTS_V0 = Set.of("api.x.ai");

    /** Thrown (as the call's failure) for a host that is not on the allowlist. */
    public static final class RefusedException extends IOException {
        RefusedException(String host) {
            super("egress refused: " + host + " is not on the allowlist");
        }
    }

    /** Request tag: the turn a request belongs to, for the log. */
    public static final class TurnTag {
        final String turnId;

        public TurnTag(String turnId) {
            this.turnId = turnId;
        }
    }

    private static final class WebSocketTag {
    }

    private final EgressAllowlist allowlist;
    private final RequestLog log;
    private final OkHttpClient client;

    public Egress(EgressAllowlist allowlist, RequestLog log) {
        this(allowlist, log, Dns.SYSTEM);
    }

    Egress(EgressAllowlist allowlist, RequestLog log, Dns system) {
        this.allowlist = allowlist;
        this.log = log;
        this.client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(new Dns() {
                    @Override
                    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                        if (!allowlist.isAllowed(hostname)) {
                            throw new UnknownHostException("egress refused: " + hostname + " is not on the allowlist");
                        }
                        return system.lookup(hostname);
                    }
                })
                .addInterceptor(chain -> {
                    Request r = chain.request();
                    if (!allowlist.isAllowed(r.url().host())) {
                        if (r.tag(WebSocketTag.class) == null) { // a WebSocket logs its own line on failure
                            log.write(entry(r, 0, 0, -1, "refused"));
                        }
                        throw new RefusedException(r.url().host());
                    }
                    return chain.proceed(r);
                })
                .eventListenerFactory(call -> call.request().tag(WebSocketTag.class) != null
                        ? EventListener.NONE : new HttpLogger())
                .pingInterval(15, TimeUnit.SECONDS)
                .build();
    }

    public OkHttpClient client() {
        return client;
    }

    public RequestLog log() {
        return log;
    }

    /**
     * Opens a WebSocket through the allowlist. Bytes sent through the returned socket and received
     * by {@code listener} are counted, and one log line is written when the connection ends.
     */
    public WebSocket openWebSocket(Request request, String turnId, WebSocketListener listener) {
        Request tagged = request.newBuilder().tag(WebSocketTag.class, new WebSocketTag())
                .tag(TurnTag.class, new TurnTag(turnId)).build();
        AtomicLong out = new AtomicLong();
        AtomicLong in = new AtomicLong();
        AtomicBoolean logged = new AtomicBoolean();
        int[] status = {-1};
        WebSocketListener counting = new WebSocketListener() {
            private void finish(String outcome) {
                if (logged.compareAndSet(false, true)) {
                    log.write(entry(tagged, out.get(), in.get(), status[0], outcome));
                }
            }

            @Override
            public void onOpen(WebSocket ws, Response response) {
                status[0] = response.code();
                listener.onOpen(ws, response);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                in.addAndGet(ByteString.encodeUtf8(text).size());
                listener.onMessage(ws, text);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                in.addAndGet(bytes.size());
                listener.onMessage(ws, bytes);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                listener.onClosing(ws, code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                finish("closed:" + code);
                listener.onClosed(ws, code, reason);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (response != null) {
                    status[0] = response.code();
                }
                finish(t instanceof RefusedException ? "refused" : "failed:" + t.getClass().getSimpleName());
                listener.onFailure(ws, t, response);
            }
        };
        WebSocket ws = client.newWebSocket(tagged, counting);
        return new WebSocket() {
            @Override
            public Request request() {
                return ws.request();
            }

            @Override
            public long queueSize() {
                return ws.queueSize();
            }

            @Override
            public boolean send(String text) {
                boolean ok = ws.send(text);
                if (ok) {
                    out.addAndGet(ByteString.encodeUtf8(text).size());
                }
                return ok;
            }

            @Override
            public boolean send(ByteString bytes) {
                boolean ok = ws.send(bytes);
                if (ok) {
                    out.addAndGet(bytes.size());
                }
                return ok;
            }

            @Override
            public boolean close(int code, String reason) {
                return ws.close(code, reason);
            }

            @Override
            public void cancel() {
                ws.cancel();
            }
        };
    }

    private static RequestLog.Entry entry(Request r, long out, long in, int status, String outcome) {
        HttpUrl u = r.url();
        TurnTag t = r.tag(TurnTag.class);
        String kind = r.tag(WebSocketTag.class) != null ? "ws" : "http";
        return new RequestLog.Entry(kind, u.host(), r.method(), u.encodedPath(), out, in, status,
                t == null ? null : t.turnId, outcome);
    }

    /** Logs one line per HTTP call when it ends. */
    private final class HttpLogger extends EventListener {
        private long out;
        private long in;
        private int status = -1;

        @Override
        public void requestHeadersEnd(Call call, Request request) {
            out += request.headers().byteCount();
        }

        @Override
        public void requestBodyEnd(Call call, long byteCount) {
            out += byteCount;
        }

        @Override
        public void responseHeadersEnd(Call call, Response response) {
            status = response.code();
            in += response.headers().byteCount();
        }

        @Override
        public void responseBodyEnd(Call call, long byteCount) {
            in += byteCount;
        }

        @Override
        public void callEnd(Call call) {
            log.write(entry(call.request(), out, in, status, "ok"));
        }

        @Override
        public void callFailed(Call call, IOException ioe) {
            if (!(ioe instanceof RefusedException)) { // the interceptor already logged a refusal
                log.write(entry(call.request(), out, in, status, "failed:" + ioe.getClass().getSimpleName()));
            }
        }
    }
}
