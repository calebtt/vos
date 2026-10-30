package com.vos.actuator;

import com.vos.ipc.PeerLink;

/** Binds the session for TypedChat: a typed turn, or a reload of the thread. */
final class SessionBind {
    private SessionBind() {
    }

    /**
     * Starts {@code link}'s bind. Returns null if it is binding, else why not. A foreign-signed
     * session refuses the bind with SecurityException (M1-AUTH-01): that is a failed bind, not a crash
     * of the main thread (the voice path catches it in TurnService).
     */
    static String start(PeerLink link) {
        try {
            return link.bind() ? null : "session service not found";
        } catch (SecurityException e) {
            return "session not bound: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
    }
}
