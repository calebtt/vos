package com.vos.actuator;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.quicksettings.TileService;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
 *
 * <p>The owner can stop the turn mid-way (P5, {@link #stop}): from the window's Stop, the typed
 * turn's notification, or "Stop VOS" (the QS tile, the assist gesture). The indicator clears at once
 * and nothing more of the turn is shown or run here; the session is told with onTurnClosed, closes its
 * side, and the slot is freed on its endTurn, as at the deadline ({@link TypedTurnState}).
 */
final class TypedChat {
    static final String TAG = "VOS_TYPED";
    /**
     * Backstop for a session that never sends endTurn. It must outlast the session's deadline plus
     * that close: a tool call in flight finishes first, and endTurn is then delivered.
     */
    static final long BACKSTOP_MS = Ipc.TYPED_TURN_DEADLINE_MS + 15_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Runs recentThread, a blocking call into the session, off the main thread (#298). */
    private static final Executor LOADS = Executors.newSingleThreadExecutor(r -> new Thread(r, "vos-chat-load"));

    /** The chat window, while it is showing; it re-reads {@link #lines} on each change. */
    interface Listener {
        void changed();
    }

    private final ActuatorApp app;
    private final List<ChatThread.Line> lines = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Shared with voice summons so the two cannot be open together (Shell Contract row 13). */
    private final OwnerTurnSlot slot = new OwnerTurnSlot();
    private final ChatThreadLoader loader = new ChatThreadLoader(LOADS, MAIN::post, this::adoptThread);
    private final TypedTurnState turn = new TypedTurnState(); // the typed turn waiting for its reply
    private PeerLink link;
    private IVosSession session; // the open turn's session, once onTypedTurn was sent

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
        return turn.isOpen(turnId);
    }

    /** A typed turn holds the owner-turn slot (stopping or not): "Stop VOS" stops it, and the tile shows it. */
    synchronized boolean turnOpen() {
        return turn.turnId() != null;
    }

    /** The window's Stop shows: a typed turn is open and the owner has not stopped it yet. */
    synchronized boolean stoppable() {
        return turn.turnId() != null && !turn.stopping();
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
            if (turn.stopping()) {
                return "VOS is still stopping the last message; send this in a moment.";
            }
            if (turn.turnId() != null) {
                return "VOS is still answering the last message.";
            }
            // voiceTurnOpen covers a turn already assigned; the slot covers a summon that has
            // claimed it but not yet assigned the turn. Either one refuses, before a second writer.
            if (TurnService.voiceTurnOpen() || !slot.claimTyped()) {
                return "A voice turn is open; send this when it ends.";
            }
            turnId = UUID.randomUUID().toString();
            turn.open(turnId);
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
        refreshTile();
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
            public void onPeerUp(IBinder binder) { // main thread, like stop(): a stop is either before or after this
                IVosSession s = IVosSession.Stub.asInterface(binder);
                synchronized (TypedChat.this) {
                    if (!turn.sendToSession(turnId)) {
                        return; // ended or stopped before the session was reached, or a rebind: never sent twice
                    }
                    session = s;
                }
                try {
                    s.onTypedTurn(turnId, text);
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
            if (!turn.isOpen(turnId) || turn.stopping()) {
                return; // ended or stopped before the session was reached
            }
            link = l;
        }
        String failed = SessionBind.start(l);
        if (failed != null) {
            end(turnId, failed);
        }
    }

    /** From ActuatorService (a Binder thread): the session's reply. Only for the typed turn the actuator has open. */
    void reply(String turnId, String text) {
        String t = text == null ? "" : text;
        if (t.length() > 4000) {
            t = t.substring(0, 4000) + "…";
        }
        synchronized (this) {
            if (!turn.acceptsReply(turnId)) {
                boolean stopped = turn.isOpen(turnId); // a reply the session sent before it heard the owner's stop
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("event", "typed.reply_refused");
                line.put("turnId", turnId == null ? null : turnId.length() > 64 ? turnId.substring(0, 64) : turnId);
                line.put("chars", t.length());
                line.put("stopped", stopped);
                app.audit().record(line);
                Log.w(TAG, "typed reply refused: " + (stopped ? "the owner stopped the turn" : "no such open typed turn"));
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

    /**
     * Runs a fast tool for the open typed turn (ActuatorService.callTool); null if {@code turnId} is not
     * it. Once the owner has stopped the turn, a call is refused without running. One that had already
     * started when the stop came finishes (every M1 tool is short and reversible), and the session's
     * close waits for it.
     */
    String callTool(String turnId, String name, String argsJson) {
        TypedTurnState.Tool may;
        synchronized (this) {
            may = turn.tool(turnId);
        }
        if (may == TypedTurnState.Tool.NOT_THIS_TURN) {
            return null;
        }
        long t0 = android.os.SystemClock.elapsedRealtime();
        boolean acts = may == TypedTurnState.Tool.RUN && FastTools.changesState(name);
        if (acts) {
            app.indicator().actingStarted(turnId);
        }
        FastTools.Result r;
        try {
            r = may == TypedTurnState.Tool.STOPPED
                    ? FastTools.Result.of(FastTools.Status.FAILED, "detail", "the owner stopped the turn")
                    : app.fastTools().run(name, argsJson, FastTools.MediaIntent.NONE); // no summon: nothing to restore
        } finally {
            if (acts) {
                app.indicator().actingEnded(turnId);
            }
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "tool.call");
        line.put("turnId", turnId);
        line.put("typed", true);
        if (may == TypedTurnState.Tool.STOPPED) {
            line.put("stopped", true);
        }
        line.put("tool", name);
        line.put("args", argsJson == null || argsJson.length() > 200 ? "(omitted)" : argsJson);
        line.put("status", r.status.name());
        line.put("result", r.json.toString());
        line.put("ms", android.os.SystemClock.elapsedRealtime() - t0);
        app.audit().record(line);
        return r.json.toString();
    }

    /**
     * The owner's stop (P5) for the open typed turn, from {@code source} ("window", "notification",
     * or a "Stop VOS" press: "qs_tile", "assist", ...). False if no typed turn is open, so the press is
     * not a typed stop. The indicator clears now (Shell Contract row 13: both states clear on stop),
     * and the reply and further tool calls are refused. The session is told to close its side and the
     * turn ends on its endTurn; a turn the session was never sent ends now. Main thread.
     */
    boolean stop(String source) {
        String reason = "owner stop: " + source;
        String turnId;
        IVosSession s;
        TypedTurnState.Stop how;
        synchronized (this) {
            turnId = turn.turnId();
            how = turn.stop(reason);
            s = session;
            if (how == TypedTurnState.Stop.LOCAL || how == TypedTurnState.Stop.SESSION) {
                lines.add(new ChatThread.Line("note", "Stopped."));
            }
        }
        if (how == TypedTurnState.Stop.NONE) {
            return false;
        }
        if (how == TypedTurnState.Stop.ALREADY) {
            return true;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "typed.turn_stop");
        line.put("turnId", turnId);
        line.put("source", source);
        line.put("in_session", how == TypedTurnState.Stop.SESSION);
        app.audit().record(line);
        Log.i(TAG, "typed turn stop turnId=" + turnId + " source=" + source + " t_up=" + android.os.SystemClock.uptimeMillis());
        app.indicator().turnEnded(turnId);
        changed();
        if (how == TypedTurnState.Stop.LOCAL || s == null) {
            end(turnId, "stopped before the session had it"); // nothing of it reached the session
            return true;
        }
        try {
            // Oneway, after onTypedTurn on the same binder, so the session sees it after the turn.
            s.onTurnClosed(turnId, reason);
        } catch (RemoteException | RuntimeException e) { // the session is gone: nothing more of the turn can come
            end(turnId, "session gone at the stop: " + e.getClass().getSimpleName());
        }
        return true;
    }

    /** Ends the open typed turn if it is {@code turnId}. Main thread. */
    private void end(String turnId, String how) {
        PeerLink l;
        TypedTurnState.Ended e;
        synchronized (this) {
            e = turn.end(turnId, how);
            if (e == null) {
                return;
            }
            l = link;
            link = null;
            session = null;
            slot.release(OwnerTurnSlot.Holder.TYPED);
            if (!e.byOwner && !"session: typed reply".equals(how)) {
                lines.add(new ChatThread.Line("note", "VOS couldn't answer (" + how + ")."));
            }
        }
        MAIN.removeCallbacksAndMessages(turnId); // this turn's backstop
        app.indicator().turnEnded(turnId);
        TypedTurnService.release();
        if (l != null) {
            l.unbind();
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "typed.turn_ended");
        line.put("turnId", turnId);
        line.put("reason", e.reason);
        if (e.byOwner) {
            line.put("ended_by", how); // after the stop: the session's endTurn, or the backstop
        }
        app.audit().record(line);
        Log.i(TAG, "typed turn ended turnId=" + turnId + " t_up=" + android.os.SystemClock.uptimeMillis()
                + " reason=" + e.reason);
        changed();
        refreshTile();
    }

    /** The tile shows an open typed turn, which its tap stops (TurnService.toggle). */
    private void refreshTile() {
        TileService.requestListeningState(app, new ComponentName(app, SummonTile.class));
    }

    /**
     * Reloads the thread from the shared conversation ({@code recentThread}), voice turns included.
     * Every reopen calls this (decision 21); a newer reload, or the session going down, drops one
     * still in flight. The call runs off the main thread ({@link ChatThreadLoader}).
     */
    void loadThread(Context context) {
        int load = loader.begin();
        MAIN.post(() -> {
            PeerLink[] holder = new PeerLink[1];
            holder[0] = new PeerLink(context.getApplicationContext(), Ipc.sessionIntent(), Ipc.sessionHandshake(),
                    new PeerLink.Listener() {
                        @Override
                        public void onPeerUp(IBinder binder) {
                            IVosSession session = IVosSession.Stub.asInterface(binder);
                            loader.fetch(load, () -> {
                                try {
                                    return session.recentThread(50);
                                } catch (RemoteException | RuntimeException e) {
                                    return null; // a throw from the session must not crash the actuator or skip the unbind
                                }
                            }, holder[0]::unbind);
                        }

                        @Override
                        public void onPeerDown(String why) {
                            loader.drop(load);
                            holder[0].unbind();
                        }
                    });
            String failed = SessionBind.start(holder[0]);
            if (failed != null) {
                Log.w(TAG, "thread not loaded: " + failed);
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
            List<ChatThread.Line> merged = ChatThread.merge(lines, fresh, turn.turnId() != null);
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
