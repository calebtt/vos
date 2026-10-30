package com.vos.actuator;

/**
 * Every notification ID the actuator posts, in one place: two notifications that share an ID replace
 * and cancel each other. IDs are never 0 ({@code startForeground} rejects it).
 */
final class NotificationIds {
    /** {@link TurnService}'s foreground notification, while a voice turn holds the mic. */
    static final int TURN = 1;
    /** {@link AndroidRolePlatform}'s ongoing notice that VOS lacks the assistant role (decision 45). */
    static final int ROLE_NOTICE = 2;
    /** {@link TypedTurnService}'s foreground notification, while a typed turn is open. */
    static final int TYPED_TURN = 3;
    /** {@link ChatNotification}: VOS's typed reply, with a direct reply. */
    static final int CHAT = 4;

    private NotificationIds() {
    }
}
