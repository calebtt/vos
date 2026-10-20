package com.vos.session;

import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The session's side of voice turns (§3.2), one at a time, each on its own thread. A backend reads
 * the owner's audio from the mic pipe and writes the reply into the speaker pipe; it reports
 * {@code ready} when its vendor session is up (the actuator moves to CONVERSE) and returns when the
 * turn is over. A turn the session ends is reported to the actuator first, and its pipes stay open
 * until the actuator closes the turn (or {@link #CLOSE_GRACE_MS}), so the actuator records the
 * session's reason rather than a broken pipe. A turn the actuator ends (it closes the mic pipe, or
 * calls onTurnClosed) is not reported back.
 */
final class SessionTurns {
    static final String TAG = "VOS_SESSION";
    static final long CLOSE_GRACE_MS = 1500;

    /**
     * Runs one turn. Returns why the session ended it, or null if the actuator ended it. An
     * IOException means a turn pipe broke, which only the actuator (or its death) can cause, so it
     * also counts as the actuator ending the turn.
     */
    interface Backend {
        String run(String turnId, InputStream mic, OutputStream speaker, Runnable ready) throws IOException;
    }

    private final ActuatorLink actuator;
    private final Supplier<Backend> backends;
    private Active current;

    SessionTurns(ActuatorLink actuator, Supplier<Backend> backends) {
        this.actuator = actuator;
        this.backends = backends;
    }

    synchronized void open(String turnId, ParcelFileDescriptor mic, ParcelFileDescriptor speaker) {
        if (current != null) {
            current.stop();
        }
        Backend b = backends.get();
        if (b == null) { // ends through the same path as any session end: endTurn first, then the pipes
            b = (id, m, s, ready) -> "no voice backend";
        }
        current = new Active(turnId, mic, speaker, b);
        current.thread.start();
    }

    synchronized void closed(String turnId) {
        if (current != null && current.turnId.equals(turnId)) {
            current.stop();
            current = null;
        }
    }

    private synchronized void finished(Active a) {
        if (current == a) {
            current = null;
        }
    }

    private final class Active {
        final String turnId;
        final InputStream mic;
        final OutputStream speaker;
        final Thread thread;
        private final CountDownLatch closedByActuator = new CountDownLatch(1);
        private volatile boolean stopped;

        Active(String turnId, ParcelFileDescriptor micPfd, ParcelFileDescriptor speakerPfd, Backend backend) {
            this.turnId = turnId;
            this.mic = new ParcelFileDescriptor.AutoCloseInputStream(micPfd);
            this.speaker = new ParcelFileDescriptor.AutoCloseOutputStream(speakerPfd);
            this.thread = new Thread(() -> run(backend), "vos-turn");
        }

        private void run(Backend backend) {
            String reason;
            try {
                reason = backend.run(turnId, mic, speaker, () -> actuator.turnReady(turnId));
            } catch (IOException e) {
                reason = null; // a pipe broke: the actuator closed the turn
            } catch (RuntimeException e) {
                reason = stopped ? null : "backend error: " + e.getClass().getSimpleName();
            }
            if (reason != null && !stopped) {
                Log.i(TAG, "turn " + turnId + " ended by the session: " + reason);
                actuator.endTurn(turnId, reason);
                try {
                    closedByActuator.await(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            closeQuietly(mic);
            closeQuietly(speaker);
            finished(this);
        }

        void stop() {
            stopped = true;
            closedByActuator.countDown();
            closeQuietly(mic);
            closeQuietly(speaker);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
