package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.vos.ipc.PeerLink;
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * TypedChat's binds (openInSession, loadThread). A foreign-signed session refuses the bind with
 * SecurityException (M1-AUTH-01); on the main thread that must end the typed turn, not crash the
 * actuator (#297).
 */
public class SessionBindTest {
    /** Stands in for the actuator's context: bindService throws {@code refuse} if set, else returns {@code found}. */
    private static final class FakeContext extends ContextWrapper {
        RuntimeException refuse;
        boolean found = true;
        int binds;
        int unbinds;

        FakeContext() {
            super(null);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Executor getMainExecutor() {
            return Runnable::run;
        }

        @Override
        public boolean bindService(Intent service, ServiceConnection conn, int flags) {
            binds++;
            if (refuse != null) {
                throw refuse;
            }
            return found;
        }

        @Override
        public void unbindService(ServiceConnection conn) {
            unbinds++;
        }
    }

    private final FakeContext context = new FakeContext();
    private final PeerLink link = new PeerLink(context, null, b -> true, new PeerLink.Listener() {
        @Override
        public void onPeerUp(IBinder binder) {
        }

        @Override
        public void onPeerDown(String why) {
        }
    });

    @Test
    public void aForeignSignedSessionIsAFailedBindNotACrash() {
        context.refuse = new SecurityException("Not allowed to bind to service");
        assertEquals("session not bound: SecurityException Not allowed to bind to service", SessionBind.start(link));
    }

    @Test
    public void aRefusedBindLeavesNothingToUnbind() {
        context.refuse = new SecurityException();
        assertEquals("session not bound: SecurityException", SessionBind.start(link));
        link.unbind(); // what TypedChat.end does next: unbinding a connection never registered would throw
        assertEquals(0, context.unbinds);
    }

    @Test
    public void theNextBindTriesAgain() {
        context.refuse = new SecurityException();
        SessionBind.start(link);
        context.refuse = null;
        assertNull(SessionBind.start(link));
        assertEquals(2, context.binds);
    }

    @Test
    public void aMissingSessionIsNotFound() {
        context.found = false;
        assertEquals("session service not found", SessionBind.start(link));
    }

    @Test
    public void aSessionThatBindsIsNoFailure() {
        assertNull(SessionBind.start(link));
        link.unbind();
        assertEquals(1, context.unbinds);
    }
}
