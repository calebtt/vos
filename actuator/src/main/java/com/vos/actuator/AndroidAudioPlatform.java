package com.vos.actuator;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.util.Log;
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
    /**
     * Communication devices in order of preference: headsets with a mic, then output-only devices the
     * owner is wearing or chose (headphones, a hearing aid, an LE Audio speaker), and the phone's
     * speaker only when none of those is connected.
     */
    static final int[] PREFERRED = {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    };
    /** Of {@link #PREFERRED}, the types whose own mic VOS can capture from. */
    static final int[] WITH_MIC = {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    };

    /** The preferred type among those available, or -1. Pure, for the JVM test. */
    static int pick(int[] available) {
        for (int type : PREFERRED) {
            for (int a : available) {
                if (a == type) {
                    return type;
                }
            }
        }
        return -1;
    }

    private final Context context;
    private final AudioManager am;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioFocusRequest focus;
    private String commType;
    private boolean commHasMic;
    private AudioManager.OnModeChangedListener callWatch;

    AndroidAudioPlatform(Context context) {
        this.context = context.getApplicationContext();
        this.am = context.getSystemService(AudioManager.class);
    }

    @Override
    public boolean callActive() {
        int m = am.getMode();
        return m == AudioManager.MODE_IN_COMMUNICATION || AudioPlane.isCallMode(m);
    }

    /**
     * Calls {@code onCall} when the audio mode changes to a call mode (ringing, a phone call, call
     * screening or a redirect) while a turn is open. VOS's own MODE_IN_COMMUNICATION does not count;
     * a VoIP call that takes over that same mode is not seen here (it shows as a focus loss, which a
     * media app starting also causes, so it is not treated as a call).
     */
    void watchCalls(Runnable onCall) {
        unwatchCalls();
        callWatch = mode -> {
            if (AudioPlane.isCallMode(mode)) {
                onCall.run();
            }
        };
        am.addOnModeChangedListener(context.getMainExecutor(), callWatch);
        // The listener reports changes only; a call that started before it was added is checked once here.
        try {
            if (AudioPlane.isCallMode(am.getMode())) {
                onCall.run();
            }
        } catch (RuntimeException e) { // the audio server is down; the turn's own errors end it
            Log.w(VoiceTurn.TAG, "call check failed: " + e.getClass().getSimpleName());
        }
    }

    void unwatchCalls() {
        if (callWatch != null) {
            am.removeOnModeChangedListener(callWatch);
            callWatch = null;
        }
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
        int[] types = devices.stream().mapToInt(AudioDeviceInfo::getType).toArray();
        int type = pick(types);
        for (AudioDeviceInfo d : devices) {
            if (d.getType() == type && am.setCommunicationDevice(d)) {
                commType = typeName(type);
                commHasMic = java.util.Arrays.stream(WITH_MIC).anyMatch(t -> t == type);
                return commType;
            }
        }
        commType = null;
        commHasMic = false;
        return null;
    }

    @Override
    public void clearCommunicationDevice() {
        am.clearCommunicationDevice();
        commType = null;
    }

    @Override
    public String inputDevice() {
        return commHasMic ? commType : "BUILTIN_MIC"; // a headset's own mic, else the phone's
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
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "WIRED_HEADPHONES";
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return "HEARING_AID";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "BLE_SPEAKER";
            default:
                return "TYPE_" + type;
        }
    }
}
