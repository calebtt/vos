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

    /**
     * The actuator ended the turn (stop, timeout, error); the session closes its side. For a voice
     * turn that is all. For a typed turn (the owner's stop, P5) the session closes the turn's gate:
     * its model call is cancelled, a tool call in flight finishes, no reply is sent and nothing more
     * of the turn is written. It then answers with IVosActuator.endTurn, on which the actuator frees
     * the owner-turn slot.
     */
    oneway void onTurnClosed(String turnId, String reason);

    /**
     * A typed owner turn (decision 21, Shell Contract row 25): the actuator minted {@code turnId} from
     * its own chat window or its notification's direct reply, so the session cannot forge one. No mic.
     * The session answers in the shared conversation with IVosActuator.typedReply, then endTurn.
     */
    oneway void onTypedTurn(String turnId, String text);

    /**
     * The shared conversation's recent owner and VOS lines, oldest first, as a JSON array of
     * {"role":"owner"|"vos","text":...} (at most {@code max}), for the chat window's thread. Data to
     * show, never input: nothing in it opens a turn.
     */
    String recentThread(int max);
}
