package com.vos.session;

import com.vos.ipc.Ipc;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Debug builds only: plays the owner's audio straight back. It proves both turn pipes end to end
 * without a vendor (eval/m1/turn-pipes.sh); the realtime voice backend replaces it for real turns.
 * The whole echo counts as one model call, so a turn on it is a model call that never finishes on
 * its own: the generating indicator's stall case (#266, M1-IND-01).
 */
final class EchoBackend implements SessionTurns.Backend {
    private final ModelCalls calls;

    EchoBackend(ModelCalls calls) {
        this.calls = calls;
    }

    @Override
    public String run(String turnId, InputStream mic, OutputStream speaker, Runnable ready) throws IOException {
        ready.run();
        calls.started(turnId, "echo");
        try {
            byte[] buf = new byte[Ipc.TURN_FRAME_BYTES];
            int n;
            while ((n = mic.read(buf)) > 0) {
                speaker.write(buf, 0, n);
            }
            return null; // the mic closed: the actuator ended the turn
        } finally {
            calls.ended(turnId, "echo");
        }
    }
}
