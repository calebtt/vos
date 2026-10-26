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

    @Override
    public boolean globalAction(int action) {
        HandsService h = HandsService.connected();
        return h != null && h.performGlobalAction(action);
    }

    @Override
    public String foregroundPackage() {
        HandsService h = HandsService.connected();
        return h == null ? null : h.activePackage();
    }

    @Override
    public String homePackage() {
        android.content.pm.ResolveInfo r = context.getPackageManager().resolveActivity(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? null : r.activityInfo.packageName;
    }

    @Override
    public List<String[]> launchableApps() {
        android.content.pm.PackageManager pm = context.getPackageManager();
        List<String[]> out = new java.util.ArrayList<>();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = r.activityInfo == null ? null : r.activityInfo.packageName;
            if (pkg != null && !pkg.startsWith("com.vos.")) {
                out.add(new String[] {String.valueOf(r.loadLabel(pm)), pkg, r.activityInfo.name});
            }
        }
        return out;
    }

    /**
     * From a turn the actuator runs as a bound accessibility or voice interaction service, which may
     * start activities from the background (S-26 checks this on Tier S).
     */
    @Override
    public boolean launch(String pkg, String activity) {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setClassName(pkg, activity);
        try {
            context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
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
