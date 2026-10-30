package com.vos.actuator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * The chat thread's conversation notification (decision 21, Shell Contract row 25): VOS's typed reply
 * when the chat window is not showing, with a direct reply (RemoteInput) that the actuator's own
 * {@link Reply} receiver turns into a typed owner turn. The channel is silent: a reply is text.
 * A direct reply from a locked phone may only read or do reversible things; every M1 fast tool is one.
 */
final class ChatNotification {
    static final String CHANNEL = "chat";
    static final String KEY_TEXT = "reply";

    private ChatNotification() {
    }

    static void post(Context context, String vosText) {
        post(context, vosText, null);
    }

    /** Posts or updates the notification; {@code ownerText} is the owner's line just sent from it, if any. */
    static void post(Context context, String vosText, String ownerText) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, context.getString(R.string.chat_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        Person you = new Person.Builder().setName(context.getString(R.string.chat_you)).build();
        Person vos = new Person.Builder().setName("VOS").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(you);
        if (vosText != null) {
            style.addMessage(vosText, System.currentTimeMillis(), vos);
        }
        if (ownerText != null) {
            style.addMessage(ownerText, System.currentTimeMillis(), (Person) null);
        }
        RemoteInput input = new RemoteInput.Builder(KEY_TEXT).setLabel(context.getString(R.string.chat_hint)).build();
        // Mutable so the system can add the typed text; explicit, to the actuator's own unexported receiver.
        PendingIntent replyIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(context, Reply.class).setPackage(context.getPackageName()),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action reply = new Notification.Action.Builder(null, context.getString(R.string.chat_reply), replyIntent)
                .addRemoteInput(input).build();
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, ChatActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_vos_generating)
                .setStyle(style)
                .setContentIntent(open)
                .addAction(reply)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(NotificationIds.CHAT, n);
    }

    /** The owner's line after a direct reply: the text, and why it was not sent; an empty reply shows only why. */
    static String ownerLine(String text, String why) {
        String t = text == null ? "" : text.strip();
        if (why == null) {
            return t;
        }
        return t.isEmpty() ? "(" + why + ")" : t + "\n(" + why + ")";
    }

    static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(NotificationIds.CHAT);
    }

    /** The direct reply: a typed owner turn from the notification (not exported; only our PendingIntent reaches it). */
    public static final class Reply extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = results == null ? null : results.getCharSequence(KEY_TEXT);
            handle(context, text == null ? null : text.toString(), "notification");
        }

        /** Shared with the debug build's simulated reply (DebugSummonActivity op reply). */
        static void handle(Context context, String text, String source) {
            ActuatorApp app = (ActuatorApp) context.getApplicationContext();
            String why = app.typedChat().send(text, source);
            post(context, null, ownerLine(text, why));
        }
    }
}
