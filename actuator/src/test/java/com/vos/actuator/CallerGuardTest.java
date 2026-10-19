package com.vos.actuator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CallerGuardTest {
    @Test
    public void acceptsOnlyTheExactPackage() {
        assertTrue(CallerGuard.isExactly(new String[] {"com.vos.session"}, CallerGuard.SESSION_PACKAGE));
    }

    @Test
    public void rejectsUnknownSharedOrMissingCallers() {
        assertFalse(CallerGuard.isExactly(null, CallerGuard.SESSION_PACKAGE));
        assertFalse(CallerGuard.isExactly(new String[] {}, CallerGuard.SESSION_PACKAGE));
        assertFalse(CallerGuard.isExactly(new String[] {"com.example.other"}, CallerGuard.SESSION_PACKAGE));
        // A shared UID that includes the session is still not "exactly" the session.
        assertFalse(CallerGuard.isExactly(
                new String[] {"com.vos.session", "com.example.other"}, CallerGuard.SESSION_PACKAGE));
        assertFalse(CallerGuard.isExactly(new String[] {"com.vos.session"}, null));
    }
}
