package com.vos.session;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.vos.ipc.IVosActuator;
import com.vos.ipc.Ipc;
import com.vos.ipc.PeerLink;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The session's link to ActuatorService, bound while turns need it (§5.3: protocol handshake and
 * death watch through PeerLink). Calls made before the link is up are queued and sent in order.
 */
final class ActuatorLink implements ModelCalls {
    private final PeerLink link;
    private final Queue<Consumer<IVosActuator>> pending = new ArrayDeque<>();
    private IVosActuator actuator;
    private boolean bound;

    ActuatorLink(Context context) {
        link = new PeerLink(context, Ipc.actuatorIntent(), Ipc.actuatorHandshake(), new PeerLink.Listener() {
            @Override
            public void onPeerUp(IBinder binder) {
                up(IVosActuator.Stub.asInterface(binder));
            }

            @Override
            public void onPeerDown(String why) {
                down();
            }
        });
    }

    void turnReady(String turnId) {
        oneway("turnReady", a -> a.turnReady(turnId));
    }

    void endTurn(String turnId, String reason) {
        oneway("endTurn", a -> a.endTurn(turnId, reason));
    }

    /** A model call for this turn started: the actuator's generating indicator (Shell Contract row 13). */
    @Override
    public void started(String turnId, String callId) {
        Log.i(SessionTurns.TAG, "model call started turnId=" + turnId + " t_up=" + android.os.SystemClock.uptimeMillis());
        oneway("modelCallStarted", a -> a.modelCallStarted(turnId, callId));
    }

    @Override
    public void ended(String turnId, String callId) {
        Log.i(SessionTurns.TAG, "model call ended turnId=" + turnId + " t_up=" + android.os.SystemClock.uptimeMillis());
        oneway("modelCallEnded", a -> a.modelCallEnded(turnId, callId));
    }

    /** One oneway call to the actuator. */
    private interface Call {
        void on(IVosActuator a) throws RemoteException;
    }

    /** Sends a oneway call, queued until the link is up; a failure is logged, never thrown. */
    private void oneway(String what, Call call) {
        send(a -> {
            try {
                call.on(a);
            } catch (RemoteException e) {
                Log.w(SessionTurns.TAG, what + " not delivered: " + e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Runs a fast tool in the actuator (a two-way Binder call; not on the WebSocket thread). Waits up
     * to 2 s for the link; any failure comes back as a FAILED result, never an exception.
     */
    String callTool(String turnId, String name, String argsJson) {
        CompletableFuture<IVosActuator> ready = new CompletableFuture<>();
        send(ready::complete);
        try {
            return ready.get(2, TimeUnit.SECONDS).callTool(turnId, name, argsJson);
        } catch (Exception e) {
            return "{\"status\":\"FAILED\",\"detail\":\"the actuator could not be reached\"}";
        }
    }

    private synchronized void send(Consumer<IVosActuator> call) {
        if (actuator != null) {
            call.accept(actuator);
            return;
        }
        pending.add(call);
        if (!bound) {
            bound = link.bind();
        }
    }

    private synchronized void up(IVosActuator a) {
        actuator = a;
        for (Consumer<IVosActuator> c; (c = pending.poll()) != null; ) {
            c.accept(a);
        }
    }

    /**
     * The actuator dropped (died, or failed the protocol handshake, after which PeerLink unbinds for
     * good): unbind and forget queued calls meant for that peer; the next call binds afresh.
     */
    private synchronized void down() {
        actuator = null;
        if (!pending.isEmpty()) {
            Log.w(SessionTurns.TAG, "dropped " + pending.size() + " calls for an actuator that went away");
            pending.clear();
        }
        if (bound) {
            bound = false;
            link.unbind();
        }
    }
}
