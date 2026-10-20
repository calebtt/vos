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
final class ActuatorLink {
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
        send(a -> {
            try {
                a.turnReady(turnId);
            } catch (RemoteException e) {
                Log.w(SessionTurns.TAG, "turnReady not delivered: " + e.getClass().getSimpleName());
            }
        });
    }

    void endTurn(String turnId, String reason) {
        send(a -> {
            try {
                a.endTurn(turnId, reason);
            } catch (RemoteException e) {
                Log.w(SessionTurns.TAG, "endTurn not delivered: " + e.getClass().getSimpleName());
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

    private synchronized void down() {
        actuator = null;
    }
}
