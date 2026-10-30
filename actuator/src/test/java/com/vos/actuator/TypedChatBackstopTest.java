package com.vos.actuator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.vos.ipc.Ipc;
import org.junit.Test;

/**
 * The session ends a typed turn at {@link Ipc#TYPED_TURN_DEADLINE_MS}; the actuator's timer is only
 * a backstop for a session that never sends endTurn (decision 21). If the backstop fired first, it
 * would free the owner-turn slot while the session could still write the turn, so a voice turn
 * summoned next would share the conversation with it.
 */
public class TypedChatBackstopTest {
    /** The session's close waits for a tool call in flight: up to 2 s for the link, then the tool. */
    private static final long LINK_WAIT_MS = 2_000;

    @Test
    public void theBackstopOutlastsTheSessionsDeadlineAndALastToolCall() {
        long longestTool = Math.max(FastTools.APP_ORACLE_MS, Math.max(FastTools.MEDIA_ORACLE_MS, FastTools.NAV_ORACLE_MS));
        long close = LINK_WAIT_MS + longestTool;
        assertTrue("backstop " + TypedChat.BACKSTOP_MS + " ms vs deadline " + Ipc.TYPED_TURN_DEADLINE_MS + " + close "
                + close + " ms, with room for delivery",
                TypedChat.BACKSTOP_MS >= Ipc.TYPED_TURN_DEADLINE_MS + 2 * close);
    }

    @Test
    public void theBackstopIsStillSixtySeconds() {
        assertEquals(60_000, TypedChat.BACKSTOP_MS);
    }
}
