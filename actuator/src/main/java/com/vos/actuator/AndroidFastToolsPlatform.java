package com.vos.actuator;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.time.ZoneId;
import java.util.List;

/**
 * {@link FastTools.Platform} on AudioManager and MediaSessionManager. Media keys go through
 * {@code AudioManager.dispatchMediaKeyEvent} (no special access); media state needs the actuator's
 * notification listener, and without it {@link #mediaState()} is null, so media results are UNVERIFIED.
 */
final class AndroidFastToolsPlatform implements FastTools.Platform {
    private final Context context;
    private final AudioManager am;

    AndroidFastToolsPlatform(Context context) {
        this.context = context.getApplicationContext();
        this.am = context.getSystemService(AudioManager.class);
    }

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public String zoneId() {
        return ZoneId.systemDefault().getId();
    }

    @Override
    public int streamMin(int stream) {
        return am.getStreamMinVolume(stream);
    }

    @Override
    public int streamMax(int stream) {
        return am.getStreamMaxVolume(stream);
    }

    @Override
    public int streamVolume(int stream) {
        return am.getStreamVolume(stream);
    }

    @Override
    public void setStreamVolume(int stream, int index) {
        am.setStreamVolume(stream, index, 0); // no volume UI, no sound
    }

    @Override
    public void mediaKey(int keyCode) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }

    @Override
    public String mediaState() {
        List<MediaController> sessions = sessions();
        if (sessions == null) {
            return null;
        }
        if (sessions.isEmpty()) {
            return "none";
        }
        PlaybackState s = sessions.get(0).getPlaybackState();
        if (s == null) {
            return "other";
        }
        switch (s.getState()) {
            case PlaybackState.STATE_PLAYING:
                return "playing";
            case PlaybackState.STATE_PAUSED:
                return "paused";
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_NONE:
                return "stopped";
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
                return "buffering";
            default:
                return "other";
        }
    }

    @Override
    public String mediaId() {
        MediaMetadata m = metadata();
        String id = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        return id == null || id.isEmpty() ? null : id;
    }

    @Override
    public String mediaTitle() {
        MediaMetadata m = metadata();
        return m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
    }

    private MediaMetadata metadata() {
        List<MediaController> sessions = sessions();
        return sessions == null || sessions.isEmpty() ? null : sessions.get(0).getMetadata();
    }

    @Override
    public void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** Active sessions, most important first; null when the notification listener is not enabled. */
    private List<MediaController> sessions() {
        try {
            return context.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(context, NotificationsService.class));
        } catch (SecurityException e) {
            return null;
        }
    }
}
