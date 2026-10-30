package com.vos.actuator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** {@link RoleWatch.Platform} on RoleManager and a persistent setup notice. */
final class AndroidRolePlatform implements RoleWatch.Platform {
    private static final String CHANNEL = "setup";
    private final Context context;

    AndroidRolePlatform(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean holdsAssistantRole() {
        return context.getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_ASSISTANT);
    }

    @Override
    public void showNotice() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.setup_channel), NotificationManager.IMPORTANCE_DEFAULT));
        // Apps cannot request the assistant role; the owner picks it under Default apps.
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NotificationIds.ROLE_NOTICE,new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_vos)
                .setContentTitle(context.getString(R.string.role_notice_title))
                .setContentText(context.getString(R.string.role_notice_text))
                .setStyle(new Notification.BigTextStyle().bigText(context.getString(R.string.role_notice_text)))
                .setContentIntent(open)
                .setOngoing(true)
                .build());
    }

    @Override
    public void clearNotice() {
        context.getSystemService(NotificationManager.class).cancel(NotificationIds.ROLE_NOTICE);
    }
}
