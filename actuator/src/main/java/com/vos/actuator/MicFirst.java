package com.vos.actuator;

import java.io.IOException;

/**
 * The summon order (#262): the turn's mic opens before the AudioPlane takes the call-style mode and
 * device. Opened after them, the capture starts about 400 ms later on Phone B; opened first, it is as
 * fast as with no mode switch at all, and loses no audio when the mode changes under it (S-07, #261).
 * The plane still decides: if it refuses after the mic opened (a call began, a turn opened), the mic
 * is closed again before anything else happens.
 */
final class MicFirst {
    /** The AudioPlane's side of a summon. */
    interface Plane {
        boolean admits();

        String summon(String source);

        void refuseSummon(String source, String why);
    }

    /** Opens and closes the turn's capture; open() may return null when the turn needs none (a debug file mic). */
    interface Mic<C> {
        C open() throws IOException;

        void close(C capture);
    }

    /** An opened turn: its id, and the capture opened for it (null: the turn opens its mic itself). */
    static final class Opened<C> {
        final String turnId;
        final C capture;

        Opened(String turnId, C capture) {
            this.turnId = turnId;
            this.capture = capture;
        }
    }

    private MicFirst() {}

    /**
     * Opens the mic if the plane would admit a summon, then summons. Returns null if refused; the plane
     * (or this method, when the mic does not open) has written the audit line and nothing is left open.
     */
    static <C> Opened<C> summon(Plane plane, Mic<C> mic, String source) {
        C capture = null;
        if (plane.admits()) {
            try {
                capture = mic.open();
            } catch (IOException | RuntimeException e) {
                plane.refuseSummon(source, "mic unavailable: " + e.getClass().getSimpleName());
                return null;
            }
        }
        // Refuses with its own audit line if a turn opened or a call began meanwhile. If it admits a
        // summon it did not admit a moment ago, the turn opens its mic after the mode (the old order).
        String id = plane.summon(source);
        if (id == null) {
            if (capture != null) {
                mic.close(capture);
            }
            return null;
        }
        return new Opened<>(id, capture);
    }
}
