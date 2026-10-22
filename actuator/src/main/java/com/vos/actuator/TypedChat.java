package com.vos.actuator;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import com.vos.ipc.IVosSession;
import com.vos.ipc.Ipc;
import com.vos.ipc.PeerLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Typed chat (decision 21, Shell Contract row 25). The actuator mints every typed owner turn, from
 * its own chat window or its conversation notification's direct reply, so the session cannot forge
 * one. A typed turn opens no microphone and no audio focus: it never touches the AudioPlane or
 * VoiceTurn. It opens the indicator (the session reports its model calls as for voice), sends the
 * text with IVosSession.onTypedTurn, and accepts the session's typedReply only for a turn it has open;
 * anything else is refused and audited. Tools run for the open typed turn like for a voice turn;
 * every M1 fast tool is read-only or reversible (M1 scope: no irreversible act before M2's trusted path).
 */
final class TypedChat {
    static final String TAG = "VOS_TYPED";
    /** A typed turn the session has not ended by then is closed. */
    static final long TURN_TIMEOUT_MS = 60_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** One line of the chat thread as the window shows it. */
    static final class Line {
        final String role; // "owner", "vos", or "note" (the actuator's own status text)
        final String text;

        Line(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    /** The chat window, while it is showing; it re-reads {@link #lines} on each change. */
    interface Listener {
        void changed();
    }

    private final ActuatorApp app;
    private final List<Line> lines = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private String openTurn; // the typed turn waiting for its reply, or null
    private PeerLink link;
    private final Runnable timeout = () -> end(null, "typed turn timed out");

    TypedChat(ActuatorApp app) {
        this.app = app;
    }

    synchronized List<Line> lines() {
        return new ArrayList<>(lines);
    }

    void listen(Listener l) {
        listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    boolean windowShowing() {
        return !listeners.isEmpty();
    }

    synchronized boolean isOpen(String turnId) {
        return turnId != null && turnId.equals(openTurn);
    }

    /**
     * The owner typed {@code text} ({@code source}: "window" or "notification"). Returns null if a
     * turn opened, else why not (shown to the owner).
     */
    String send(String text, String source) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return "Nothing to send.";
        }
        if (t.length() > 2000) {
            t = t.substring(0, 2000);
        }
        String turnId;
        synchronized (this) {
            if (openTurn != null) {
                return "VOS is still answering the last message.";
            }
            if (TurnService.voiceTurnOpen()) {
                return "A voice turn is open; send this when it ends.";
            }
            turnId = UUID.randomUUID().toString();
            openTurn = turnId;
            lines.add(new Line("owner", t));
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "typed.turn_opened");
        line.put("turnId", turnId);
        line.put("source", source);
        line.put("chars", t.length());
        app.audit().record(line);
        app.indicator().turnOpened(turnId);
        changed();
        MAIN.postDelayed(timeout, TURN_TIMEOUT_MS);
        String typed = t;
        MAIN.post(() -> openInSession(turnId, typed));
        return null;
    }

    private void openInSession(String turnId, String text) {
        PeerLink l = new PeerLink(app, Ipc.sessionIntent(), Ipc.sessionHandshake(), new PeerLink.Listener() {
            @Override
            public void onPeerUp(IBinder binder) {
                try {
                    IVosSession.Stub.asInterface(binder).onTypedTurn(turnId, text);
                    Log.i(TAG, "typed turn opened in session turnId=" + turnId);
                } catch (RemoteException e) {
                    MAIN.post(() -> end(turnId, "session refused the typed turn"));
                }
            }

            @Override
            public void onPeerDown(String why) {
                MAIN.post(() -> end(turnId, "session down: " + why));
            }
        });
        synchronized (this) {
            if (!turnId.equals(openTurn)) {
                return; // ended before the session was reached
            }
            link = l;
        }
        if (!l.bind()) {
            end(turnId, "session service not found");
        }
    }

    /** From ActuatorService (a Binder thread): the session's reply. Only for the typed turn the actuator has open. */
    void reply(String turnId, String text) {
        String t = text == null ? "" : text;
        if (t.length() > 4000) {
            t = t.substring(0, 4000) + "…";
        }
        synchronized (this) {
            if (!isOpen(turnId)) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("event", "typed.reply_refused");
                line.put("turnId", turnId == null ? null : turnId.length() > 64 ? turnId.substring(0, 64) : turnId);
                line.put("chars", t.length());
                app.audit().record(line);
                Log.w(TAG, "typed reply refused: no such open typed turn");
                return;
            }
            lines.add(new Line("vos", t));
        }
        Log.i(TAG, "typed reply turnId=" + turnId + " chars=" + t.length());
        changed();
        String shown = t;
        MAIN.post(() -> {
            if (!windowShowing()) {
                ChatNotification.post(app, shown);
            }
        });
    }

    /** From ActuatorService: the session ended a turn; true if it was the open typed turn. */
    boolean sessionEnded(String turnId, String reason) {
        if (!isOpen(turnId)) {
            return false;
        }
        MAIN.post(() -> end(turnId, "session: " + reason));
        return true;
    }

    /** Runs a fast tool for the open typed turn (ActuatorService.callTool); null if {@code turnId} is not it. */
    String callTool(String turnId, String name, String argsJson) {
        if (!isOpen(turnId)) {
            return null;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        boolean acts = FastTools.changesState(name);
        if (acts) {
            app.indicator().actingStarted(turnId);
        }
        FastTools.Result r;
        try {
            r = app.fastTools().run(name, argsJson, FastTools.MediaIntent.NONE); // no summon: nothing to restore
        } finally {
            if (acts) {
                app.indicator().actingEnded(turnId);
            }
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "tool.call");
        line.put("turnId", turnId);
        line.put("typed", true);
        line.put("tool", name);
        line.put("args", argsJson == null || argsJson.length() > 200 ? "(omitted)" : argsJson);
        line.put("status", r.status.name());
        line.put("result", r.json.toString());
        line.put("ms", android.os.SystemClock.elapsedRealtime() - t0);
        app.audit().record(line);
        return r.json.toString();
    }

    /** Ends the open typed turn ({@code turnId} null: whichever is open). Main thread. */
    private void end(String turnId, String reason) {
        String ended;
        PeerLink l;
        synchronized (this) {
            if (openTurn == null || (turnId != null && !turnId.equals(openTurn))) {
                return;
            }
            ended = openTurn;
            openTurn = null;
            l = link;
            link = null;
            if (!"session: typed reply".equals(reason)) {
                lines.add(new Line("note", "VOS couldn't answer (" + reason + ")."));
            }
        }
        MAIN.removeCallbacks(timeout);
        app.indicator().turnEnded(ended);
        if (l != null) {
            l.unbind();
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "typed.turn_ended");
        line.put("turnId", ended);
        line.put("reason", reason);
        app.audit().record(line);
        Log.i(TAG, "typed turn ended turnId=" + ended + " reason=" + reason);
        changed();
    }

    /** The thread's earlier lines from the shared conversation (voice turns included), once per window. */
    void loadThread(Context context) {
        MAIN.post(() -> {
            PeerLink[] holder = new PeerLink[1];
            holder[0] = new PeerLink(context.getApplicationContext(), Ipc.sessionIntent(), Ipc.sessionHandshake(),
                    new PeerLink.Listener() {
                        @Override
                        public void onPeerUp(IBinder binder) {
                            String json;
                            try {
                                json = IVosSession.Stub.asInterface(binder).recentThread(50);
                            } catch (RemoteException e) {
                                json = null;
                            }
                            String got = json;
                            MAIN.post(() -> {
                                holder[0].unbind();
                                adoptThread(got);
                            });
                        }

                        @Override
                        public void onPeerDown(String why) {
                        }
                    });
            if (!holder[0].bind()) {
                Log.w(TAG, "thread not loaded: session service not found");
            }
        });
    }

    /** Session text shown as data: earlier owner and VOS lines, before anything typed in this window. */
    private void adoptThread(String json) {
        if (json == null) {
            return;
        }
        List<Line> earlier = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length() && i < 200; i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String role = "owner".equals(o.optString("role")) ? "owner" : "vos"; // never "note": that is the actuator's own
                String text = o.optString("text", "");
                earlier.add(new Line(role, text.length() > 4000 ? text.substring(0, 4000) + "…" : text));
            }
        } catch (org.json.JSONException e) {
            return;
        }
        synchronized (this) {
            if (lines.isEmpty()) {
                lines.addAll(earlier);
            }
        }
        changed();
    }

    private void changed() {
        for (Listener l : listeners) {
            MAIN.post(l::changed);
        }
    }
}
