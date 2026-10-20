package com.vos.session;

import com.vos.ipc.Ipc;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Debug builds only: plays the owner's audio straight back. It proves both turn pipes end to end
 * without a vendor (eval/m1/turn-pipes.sh); the realtime voice backend replaces it for real turns.
 */
final class EchoBackend implements SessionTurns.Backend {
    @Override
    public String run(String turnId, InputStream mic, OutputStream speaker, Runnable ready) throws IOException {
        ready.run();
        byte[] buf = new byte[Ipc.TURN_FRAME_BYTES];
        int n;
        while ((n = mic.read(buf)) > 0) {
            speaker.write(buf, 0, n);
        }
        return null; // the mic closed: the actuator ended the turn
    }
}
