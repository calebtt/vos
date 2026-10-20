package com.vos.ipc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Binds the other VOS app's service and watches it with {@code linkToDeath} (§5.3: each side
 * watches the other). The listener hears every up and down; a dead binding is rebound.
 * Callbacks arrive on the main thread, except a binder death, which arrives on a binder thread.
 */
public final class PeerLink {
    public interface Listener {
        void onPeerUp(IBinder binder);

        void onPeerDown(String why);
    }

    private final Context context;
    private final Intent intent;
    private final Listener listener;
    private IBinder current;
    private boolean bound;

    private final IBinder.DeathRecipient death = () -> drop("binder died");

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                binder.linkToDeath(death, 0);
            } catch (RemoteException e) {
                listener.onPeerDown("died before linkToDeath");
                return;
            }
            synchronized (PeerLink.this) {
                current = binder;
            }
            listener.onPeerUp(binder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            drop("disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            drop("binding died");
            unbind();
            bind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            drop("null binding");
        }
    };

    public PeerLink(Context context, Intent intent, Listener listener) {
        this.context = context.getApplicationContext();
        this.intent = intent;
        this.listener = listener;
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

    /** The live binder, or null while the peer is down. */
    public synchronized IBinder current() {
        return current;
    }

    private void drop(String why) {
        synchronized (this) {
            if (current == null) {
                return;
            }
            current.unlinkToDeath(death, 0);
            current = null;
        }
        listener.onPeerDown(why);
    }
}
