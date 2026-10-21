package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RoleWatchTest {
    private boolean held;
    private boolean noticeShown;
    private boolean roleServiceDown;
    private final List<Map<String, Object>> audit = new ArrayList<>();
    private final RoleWatch watch = new RoleWatch(new RoleWatch.Platform() {
        @Override
        public boolean holdsAssistantRole() {
            if (roleServiceDown) {
                throw new IllegalStateException("role service unavailable");
            }
            return held;
        }

        @Override
        public void showNotice() {
            noticeShown = true;
        }

        @Override
        public void clearNotice() {
            noticeShown = false;
        }
    }, audit::add);

    @Test
    public void withoutTheRoleTheNoticeShowsAndTheFirstCheckIsAudited() {
        assertFalse(watch.check("actuator start"));
        assertTrue(noticeShown);
        assertEquals(1, audit.size());
        assertEquals("role.assistant", audit.get(0).get("event"));
        assertEquals(false, audit.get(0).get("held"));
        assertEquals("actuator start", audit.get(0).get("reason"));
    }

    @Test
    public void eachChangeIsAuditedOnceAndRepeatedChecksAreQuiet() {
        held = true;
        assertTrue(watch.check("assistant service ready"));
        assertFalse(noticeShown);
        watch.check("boot");
        assertEquals(1, audit.size());
        held = false; // another app took the role, or it was cleared
        watch.check("assistant service shut down");
        assertTrue(noticeShown);
        assertEquals(2, audit.size());
        assertEquals(false, audit.get(1).get("held"));
        watch.check("boot");
        assertEquals(2, audit.size());
        held = true; // the owner picked VOS again
        watch.check("assistant service ready");
        assertFalse(noticeShown);
        assertEquals(3, audit.size());
        assertEquals(true, audit.get(2).get("held"));
    }

    @Test
    public void aRoleServiceFailureKeepsTheLastStateAndAuditsNothing() {
        held = true;
        watch.check("actuator start");
        roleServiceDown = true;
        assertTrue(watch.check("boot"));
        assertFalse(noticeShown);
        assertEquals(1, audit.size());
    }
}
