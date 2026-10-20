package com.vos.actuator;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.List;

/**
 * {@link AudioPlane.Platform} on AudioManager and MediaSessionManager. Media control needs the
 * actuator's notification listener to be enabled (Tier S); without it the plane sees no playing
 * media and leaves media alone.
 */
final class AndroidAudioPlatform implements AudioPlane.Platform {
    /** Communication devices in order of preference; the speaker when no headset is connected. */
    private static final int[] PREFERRED = {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    };

    private final Context context;
    private final AudioManager am;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioFocusRequest focus;
    private String commType;

    AndroidAudioPlatform(Context context) {
        this.context = context.getApplicationContext();
        this.am = context.getSystemService(AudioManager.class);
    }

    @Override
    public boolean callActive() {
        int m = am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
                || m == AudioManager.MODE_CALL_SCREENING || m == AudioManager.MODE_CALL_REDIRECT
                || m == AudioManager.MODE_COMMUNICATION_REDIRECT;
    }

    @Override
    public int mode() {
        return am.getMode();
    }

    @Override
    public void setMode(int mode) {
        am.setMode(mode);
    }

    @Override
    public String setCommunicationDevice() {
        List<AudioDeviceInfo> devices = am.getAvailableCommunicationDevices();
        for (int type : PREFERRED) {
            for (AudioDeviceInfo d : devices) {
                if (d.getType() == type && am.setCommunicationDevice(d)) {
                    commType = typeName(type);
                    return commType;
                }
            }
        }
        commType = null;
        return null;
    }

    @Override
    public void clearCommunicationDevice() {
        am.clearCommunicationDevice();
        commType = null;
    }

    @Override
    public String inputDevice() {
        if (commType == null || commType.equals("BUILTIN_SPEAKER")) {
            return "BUILTIN_MIC";
        }
        return commType; // a headset's own mic
    }

    @Override
    public boolean requestFocus() {
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setOnAudioFocusChangeListener(change -> { }, handler)
                .build();
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void abandonFocus() {
        if (focus != null) {
            am.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }

    @Override
    public String playingMediaPackage() {
        for (MediaController c : sessions()) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                return c.getPackageName();
            }
        }
        return null;
    }

    @Override
    public boolean pauseMedia(String pkg) {
        MediaController c = controller(pkg);
        if (c == null) {
            return false;
        }
        c.getTransportControls().pause();
        return true;
    }

    @Override
    public boolean playMedia(String pkg) {
        MediaController c = controller(pkg);
        if (c == null) {
            return false;
        }
        c.getTransportControls().play();
        return true;
    }

    @Override
    public boolean isMediaPlaying(String pkg) {
        MediaController c = controller(pkg);
        PlaybackState s = c == null ? null : c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    @Override
    public void schedule(Runnable task, long delayMs) {
        handler.postDelayed(task, delayMs);
    }

    private MediaController controller(String pkg) {
        for (MediaController c : sessions()) {
            if (c.getPackageName().equals(pkg)) {
                return c;
            }
        }
        return null;
    }

    private List<MediaController> sessions() {
        try {
            return context.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(context, NotificationsService.class));
        } catch (SecurityException e) {
            return Collections.emptyList(); // notification access not granted
        }
    }

    static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB_HEADSET";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "BLE_HEADSET";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "BLUETOOTH_SCO";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "BUILTIN_SPEAKER";
            default:
                return "TYPE_" + type;
        }
    }
}
