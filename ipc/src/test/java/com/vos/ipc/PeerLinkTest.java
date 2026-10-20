package com.vos.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.FileDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.Test;

/** PeerLink's up/down decisions under the interleavings §5.3's death watch must survive. */
public class PeerLinkTest {
    /** Stands in for a peer's BinderProxy: records its death recipient and can die on demand. */
    private static final class FakeBinder implements IBinder {
        IBinder.DeathRecipient recipient;
        boolean deadAtLink;

        void die() {
            IBinder.DeathRecipient r = recipient;
            if (r != null) {
                r.binderDied(this);
            }
        }

        @Override
        public void linkToDeath(IBinder.DeathRecipient r, int flags) throws RemoteException {
            if (deadAtLink) {
                throw new RemoteException();
            }
            recipient = r;
        }

        @Override
        public boolean unlinkToDeath(IBinder.DeathRecipient r, int flags) {
            boolean was = recipient == r;
            recipient = null;
            return was;
        }

        @Override
        public String getInterfaceDescriptor() {
            return "fake";
        }

        @Override
        public boolean pingBinder() {
            return true;
        }

        @Override
        public boolean isBinderAlive() {
            return true;
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public void dump(FileDescriptor fd, String[] args) {
        }

        @Override
        public void dumpAsync(FileDescriptor fd, String[] args) {
        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags) {
            return false;
        }
    }

    private final List<String> heard = new ArrayList<>();
    private final Queue<Runnable> mainThread = new ArrayDeque<>();
    private PeerLink.Handshake handshake = b -> true;

    private final PeerLink link = new PeerLink(null, null, b -> handshake.accept(b),
            new PeerLink.Listener() {
                @Override
                public void onPeerUp(IBinder binder) {
                    heard.add("up " + System.identityHashCode(binder));
                }

                @Override
                public void onPeerDown(String why) {
                    heard.add("down " + why);
                }
            }, mainThread::add);

    private void runMainThread() {
        for (Runnable r; (r = mainThread.poll()) != null; ) {
            r.run();
        }
    }

    private static String up(IBinder b) {
        return "up " + System.identityHashCode(b);
    }

    @Test
    public void upThenDeathIsUpThenDown() {
        FakeBinder a = new FakeBinder();
        link.connection.onServiceConnected(null, a);
        runMainThread();
        assertSame(a, link.current());
        a.die();
        runMainThread();
        assertEquals(List.of(up(a), "down binder died"), heard);
        assertNull(link.current());
    }

    @Test
    public void deathDuringTheHandshakeIsNeverPublishedAsUp() {
        FakeBinder a = new FakeBinder();
        handshake = b -> {
            a.die(); // the death arrives on a binder thread after linkToDeath, before up is decided
            return true;
        };
        link.connection.onServiceConnected(null, a);
        runMainThread();
        assertEquals(List.of("down binder died"), heard);
        assertNull(link.current());
    }

    @Test
    public void deathAfterUpIsQueuedButBeforeItIsDeliveredEndsDown() {
        FakeBinder a = new FakeBinder();
        link.connection.onServiceConnected(null, a);
        a.die();
        runMainThread();
        assertEquals(List.of("down binder died"), heard);
        assertNull(link.current());
    }

    @Test
    public void deadBeforeLinkToDeathIsDown() {
        FakeBinder a = new FakeBinder();
        a.deadAtLink = true;
        link.connection.onServiceConnected(null, a);
        runMainThread();
        assertEquals(List.of("down died before linkToDeath"), heard);
        assertNull(link.current());
    }

    @Test
    public void aProtocolMismatchDropsThePeer() {
        handshake = b -> false;
        FakeBinder a = new FakeBinder();
        link.connection.onServiceConnected(null, a);
        runMainThread();
        assertEquals(List.of("down protocol mismatch"), heard);
        assertNull(link.current());
        assertNull(a.recipient);
    }

    @Test
    public void aRefusedHandshakeDropsThePeer() {
        handshake = b -> {
            throw new SecurityException(BinderGuard.UNAUTHORIZED);
        };
        link.connection.onServiceConnected(null, new FakeBinder());
        runMainThread();
        assertEquals(List.of("down handshake failed: UNAUTHORIZED"), heard);
        assertNull(link.current());
    }

    @Test
    public void aLateDeathOfAnOldBinderDoesNotDropTheNewOne() {
        FakeBinder a = new FakeBinder();
        FakeBinder b = new FakeBinder();
        // ServiceConnection callbacks run on the main thread, so queued callbacks run between them.
        link.connection.onServiceConnected(null, a);
        runMainThread();
        IBinder.DeathRecipient aRecipient = a.recipient;
        link.connection.onServiceDisconnected(null);
        runMainThread();
        link.connection.onServiceConnected(null, b);
        runMainThread();
        aRecipient.binderDied(a); // a's death notice arrives late, on a binder thread
        runMainThread();
        assertEquals(List.of(up(a), "down disconnected", up(b)), heard);
        assertSame(b, link.current());
    }

    @Test
    public void aDownSupersededByANewPeerIsSkipped() {
        FakeBinder a = new FakeBinder();
        FakeBinder b = new FakeBinder();
        link.connection.onServiceConnected(null, a);
        runMainThread();
        a.die();
        link.connection.onServiceConnected(null, b);
        runMainThread();
        assertEquals(List.of(up(a), up(b)), heard);
        assertSame(b, link.current());
        assertTrue(heard.get(heard.size() - 1).startsWith("up"));
    }
}
