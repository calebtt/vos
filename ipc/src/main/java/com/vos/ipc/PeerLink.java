package com.vos.ipc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;

/**
 * Binds the other VOS app's service and watches it with {@code linkToDeath} (§5.3: each side
 * watches the other). Each new binder must pass a {@link Handshake} (the protocol version check,
 * {@link Ipc#actuatorHandshake()} / {@link Ipc#sessionHandshake()}) before it is published; a peer
 * that fails it is dropped and not rebound. A binder that dies is dropped, and a binding that dies
 * is rebound.
 *
 * <p>Up and down are decided under one lock and delivered on the main thread in order. Each
 * callback re-checks the state when it runs, so a binder that dies before its up is delivered is
 * never published, and the last callback the listener hears always matches the link's state.
 */
public final class PeerLink {
    public interface Listener {
        void onPeerUp(IBinder binder);

        void onPeerDown(String why);
    }

    /** Runs once per new binder, before it is published. False or an exception drops the peer. */
    public interface Handshake {
        boolean accept(IBinder binder) throws RemoteException;
    }

    private final Context context;
    private final Intent intent;
    private final Handshake handshake;
    private final Listener listener;
    private final Executor callbacks;
    private IBinder current; // the connected binder, from onServiceConnected until it drops
    private boolean verified; // current passed the handshake
    private boolean bound;

    private final IBinder.DeathRecipient death = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            drop(null, "binder died");
        }

        @Override
        public void binderDied(IBinder who) {
            drop(who, "binder died");
        }
    };

    final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            connected(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            drop(null, "disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            drop(null, "binding died");
            unbind();
            bind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            drop(null, "null binding");
        }
    };

    public PeerLink(Context context, Intent intent, Handshake handshake, Listener listener) {
        this(context.getApplicationContext(), intent, handshake, listener, context.getMainExecutor());
    }

    /** {@code callbacks} must run tasks one at a time, in order (the main thread in production). */
    PeerLink(Context context, Intent intent, Handshake handshake, Listener listener, Executor callbacks) {
        this.context = context;
        this.intent = intent;
        this.handshake = handshake;
        this.listener = listener;
        this.callbacks = callbacks;
    }

    /**
     * Starts binding. Returns false if the service was not found. Throws SecurityException if this
     * app lacks the service's permission (a foreign-signed caller, M1-AUTH-01).
     */
    public synchronized boolean bind() {
        if (!bound) {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
        return bound;
    }

    public synchronized void unbind() {
        if (bound) {
            bound = false;
            context.unbindService(connection);
        }
    }

    /** The live binder once it has passed the handshake, or null while the peer is down. */
    public synchronized IBinder current() {
        return verified ? current : null;
    }

    private void connected(IBinder binder) {
        synchronized (this) {
            // Set before linkToDeath, so a death from here on finds it and is reported.
            current = binder;
            verified = false;
        }
        try {
            binder.linkToDeath(death, 0);
        } catch (RemoteException e) {
            drop(binder, "died before linkToDeath");
            return;
        }
        String refused;
        try {
            refused = handshake.accept(binder) ? null : "protocol mismatch";
        } catch (RemoteException | RuntimeException e) {
            refused = "handshake failed: " + e.getMessage();
        }
        if (refused != null) {
            drop(binder, refused);
            unbind();
            return;
        }
        synchronized (this) {
            if (current != binder) {
                return; // died during the handshake; drop() has queued the down
            }
            verified = true;
        }
        callbacks.execute(() -> {
            if (current() == binder) {
                listener.onPeerUp(binder);
            }
        });
    }

    /** Drops {@code which} (null: whatever is current) and queues a down; a stale binder is ignored. */
    private void drop(IBinder which, String why) {
        IBinder gone;
        synchronized (this) {
            if (current == null || (which != null && which != current)) {
                return;
            }
            gone = current;
            current = null;
            verified = false;
        }
        try {
            gone.unlinkToDeath(death, 0);
        } catch (NoSuchElementException e) {
            // never linked (it died before linkToDeath)
        }
        callbacks.execute(() -> {
            if (current() == null) {
                listener.onPeerDown(why);
            }
        });
    }
}
