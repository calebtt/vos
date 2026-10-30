package com.vos.actuator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * Holds the actuator in the foreground while a typed turn is open (decision 21), as a
 * {@code shortService}: no microphone, no audio focus. The session's network depends on it. Android
 * blocks a cached app's network a few seconds after it leaves the foreground (the background
 * firewall chain), and the session is only as foreground as the actuator, which binds it for the
 * turn. Without this, once the chat window goes or the direct reply's receiver returns, both apps
 * are cached, and a model call still running or starting about 5 s later fails
 * ({@code UnknownHostException}). A voice turn's microphone service does the same for voice.
 *
 * <p>{@link TypedChat} holds it from the owner's send (the window, the notification's action:
 * moments that may start a foreground service) and releases it when the turn ends. It always calls
 * {@code startForeground} before stopping, so a turn that ends before it starts cannot crash the app.
 * Main thread only.
 */
public final class TypedTurnService extends Service {
    private static final String CHANNEL = "typed";

    private static TypedTurnService live;
    private static boolean wanted; // a typed turn is open
    private boolean started; // onStartCommand has run, so startForeground has been called

    /** A typed turn opened. False if Android refused the foreground service; the turn goes on without it. */
    static boolean hold(Context context) {
        wanted = true;
        try {
            context.startForegroundService(new Intent(context, TypedTurnService.class));
            return true;
        } catch (RuntimeException e) { // ForegroundServiceStartNotAllowedException
            Log.w(TypedChat.TAG, "typed turn has no foreground service (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** The typed turn ended. */
    static void release() {
        wanted = false;
        if (live != null && live.started) {
            live.stop(); // else onStartCommand stops it, after startForeground
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.typed_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public void onDestroy() {
        if (live == this) {
            live = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        try {
            startForeground(NotificationIds.TYPED_TURN,notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        } catch (RuntimeException e) { // ForegroundServiceStartNotAllowedException
            Log.w(TypedChat.TAG, "typed turn has no foreground service (" + e.getClass().getSimpleName() + ")");
        }
        if (!wanted) {
            stop(); // the turn ended before the service started
        }
        return START_NOT_STICKY;
    }

    /** A short service may run 3 minutes; a typed turn ends long before ({@link TypedChat#BACKSTOP_MS}). */
    @Override
    public void onTimeout(int startId) {
        Log.w(TypedChat.TAG, "typed turn service timed out");
        stop();
    }

    private void stop() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ChatActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_vos_generating)
                .setContentTitle(getString(R.string.typed_answering))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
