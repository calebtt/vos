package com.vos.actuator;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The owner's line the chat notification shows after a direct reply. */
public class ChatNotificationTest {
    @Test
    public void anEmptyDirectReplyShowsOnlyWhyItWasNotSent() {
        assertEquals("(Nothing to send.)", ChatNotification.ownerLine(null, "Nothing to send."));
        assertEquals("(Nothing to send.)", ChatNotification.ownerLine("  ", "Nothing to send."));
    }

    @Test
    public void aSentReplyShowsItsText() {
        assertEquals("and now?", ChatNotification.ownerLine(" and now? ", null));
    }

    @Test
    public void aRefusedReplyShowsItsTextAndWhy() {
        assertEquals("and now?\n(A voice turn is open; send this when it ends.)",
                ChatNotification.ownerLine("and now?", "A voice turn is open; send this when it ends."));
    }
}
