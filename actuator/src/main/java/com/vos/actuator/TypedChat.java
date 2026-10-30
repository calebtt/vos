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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Typed chat (decision 21, Shell Contract row 25). The actuator mints every typed owner turn, from
 * its own chat window or its conversation notification's direct reply, so the session cannot forge
 * one. A typed turn opens no microphone and no audio focus: it never touches the AudioPlane or
 * VoiceTurn. It opens the indicator (the session reports its model calls as for voice), sends the
 * text with IVosSession.onTypedTurn, and accepts the session's typedReply only for a turn it has open;
 * anything else is refused and audited. Tools run for the open typed turn like for a voice turn;
 * every M1 fast tool is read-only or reversible (M1 scope: no irreversible act before M2's trusted path).
 *
 * <p>While the turn is open, {@link TypedTurnService} keeps the actuator in the foreground, so the
 * session it binds keeps its network.
 *
 * <p>The session ends a typed turn itself by {@link Ipc#TYPED_TURN_DEADLINE_MS}, after its last write,
 * and the owner-turn slot is freed on that endTurn. {@link #BACKSTOP_MS} is longer: it ends the turn
 * only if that endTurn never comes (the session died or hung).
 */
final class TypedChat {
    static final String TAG = "VOS_TYPED";
    /**
     * Backstop for a session that never sends endTurn. It must outlast the session's deadline plus
     * that close: a tool call in flight finishes first, and endTurn is then delivered.
     */
    static final long BACKSTOP_MS = Ipc.TYPED_TURN_DEADLINE_MS + 15_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The chat window, while it is showing; it re-reads {@link #lines} on each change. */
    interface Listener {
        void changed();
    }

    private final ActuatorApp app;
    private final List<ChatThread.Line> lines = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Shared with voice summons so the two cannot be open together (Shell Contract row 13). */
    private final OwnerTurnSlot slot = new OwnerTurnSlot();
    private final AtomicInteger loadGen = new AtomicInteger();
    private String openTurn; // the typed turn waiting for its reply, or null
    private PeerLink link;

    TypedChat(ActuatorApp app) {
        this.app = app;
    }

    synchronized List<ChatThread.Line> lines() {
        return new ArrayList<>(lines);
    }

    /**
     * Claims the owner-turn slot for a voice summon. {@link OwnerTurnSlot.Claim#BLOCKED} means a
     * typed turn is open: the summon must not start the microphone service.
     */
    synchronized OwnerTurnSlot.Claim claimVoice() {
        return slot.claimVoice();
    }

    /** Drops the voice claim. A typed holder is left alone. */
    synchronized void releaseVoice() {
        slot.release(OwnerTurnSlot.Holder.VOICE);
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
     * turn opened, else why not (shown to the owner). Main thread, at a moment that may start a
     * foreground service ({@link TypedTurnService}).
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
            // voiceTurnOpen covers a turn already assigned; the slot covers a summon that has
            // claimed it but not yet assigned the turn. Either one refuses, before a second writer.
            if (TurnService.voiceTurnOpen() || !slot.claimTyped()) {
                return "A voice turn is open; send this when it ends.";
            }
            turnId = UUID.randomUUID().toString();
            openTurn = turnId;
            lines.add(new ChatThread.Line("owner", t));
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "typed.turn_opened");
        line.put("turnId", turnId);
        line.put("source", source);
        line.put("chars", t.length());
        app.audit().record(line);
        app.indicator().turnOpened(turnId);
        // Keeps the session's network up for the turn (Android cuts a cached app's network within seconds).
        TypedTurnService.hold(app);
        changed();
        // Tokened by this turn's id: it can only end this turn, and end() drops it.
        MAIN.postAtTime(() -> end(turnId, "typed turn timed out: no end from the session"), turnId,
                android.os.SystemClock.uptimeMillis() + BACKSTOP_MS);
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
            lines.add(new ChatThread.Line("vos", t));
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

    /** Ends the open typed turn if it is {@code turnId}. Main thread. */
    private void end(String turnId, String reason) {
        String ended;
        PeerLink l;
        synchronized (this) {
            if (openTurn == null || !openTurn.equals(turnId)) {
                return;
            }
            ended = openTurn;
            openTurn = null;
            l = link;
            link = null;
            slot.release(OwnerTurnSlot.Holder.TYPED);
            if (!"session: typed reply".equals(reason)) {
                lines.add(new ChatThread.Line("note", "VOS couldn't answer (" + reason + ")."));
            }
        }
        MAIN.removeCallbacksAndMessages(ended); // this turn's backstop
        app.indicator().turnEnded(ended);
        TypedTurnService.release();
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

    /**
     * Reloads the thread from the shared conversation ({@code recentThread}), voice turns included.
     * Every reopen calls this (decision 21); a newer reload supersedes one still in flight.
     */
    void loadThread(Context context) {
        int gen = loadGen.incrementAndGet();
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
                                if (gen == loadGen.get()) {
                                    adoptThread(got);
                                }
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

    /** Session text shown as data. Replaces the window's thread; keeps an in-flight typed line. */
    private void adoptThread(String json) {
        if (json == null) {
            return;
        }
        List<ChatThread.Line> fresh = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length() && i < 200; i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String role = "owner".equals(o.optString("role")) ? "owner" : "vos"; // never "note": that is the actuator's own
                String text = o.optString("text", "");
                fresh.add(new ChatThread.Line(role, text.length() > 4000 ? text.substring(0, 4000) + "…" : text));
            }
        } catch (org.json.JSONException e) {
            return;
        }
        synchronized (this) {
            List<ChatThread.Line> merged = ChatThread.merge(lines, fresh, openTurn != null);
            lines.clear();
            lines.addAll(merged);
        }
        changed();
    }

    private void changed() {
        for (Listener l : listeners) {
            MAIN.post(l::changed);
        }
    }
}
