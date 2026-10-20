package com.vos.ipc;

import android.os.ParcelFileDescriptor;

/**
 * The actuator's interface into the session (§3.1), served by SessionService behind the
 * com.vos.permission.ACTUATOR signature permission and BinderGuard (§5.3).
 */
interface IVosSession {
    /**
     * {@link com.vos.ipc.Ipc#PROTOCOL_VERSION} of this build. PeerLink calls it on every new binder
     * ({@link com.vos.ipc.Ipc#sessionHandshake()}) and drops a peer whose version differs.
     */
    int protocolVersion();

    /**
     * A voice turn opened (§3.2). {@code mic} carries the owner's audio from the summon on, and the
     * session writes the reply audio into {@code speaker}; both are {@link com.vos.ipc.Ipc#TURN_SAMPLE_RATE}
     * mono s16le. The session answers with IVosActuator.turnReady, and ends the turn with
     * IVosActuator.endTurn or when the actuator closes the mic pipe.
     */
    oneway void onTurnOpened(String turnId, in ParcelFileDescriptor mic, in ParcelFileDescriptor speaker);

    /** The actuator ended the turn (stop, timeout, error); the session closes its side. */
    oneway void onTurnClosed(String turnId, String reason);
}
