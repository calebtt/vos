package com.vos.eval.mic;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Process;
import java.util.Arrays;

/**
 * M1-AUD-02 probe, run by AuthProbe with {@code -e probe mic} in the session's androidTest APK, so
 * it runs in the session's process and UID. It tries to record. Pass: the session neither requests
 * nor holds RECORD_AUDIO, and AudioRecord yields no audio (it fails to initialize, refuses to
 * start, or is not recording).
 *
 *   am instrument -w -r -e probe mic com.vos.session.test/com.vos.eval.auth.AuthProbe
 */
public final class MicProbe {
    private MicProbe() {
    }

    /**
     * Result keys: outcome, requested, granted, uid, pass. Lint's MissingPermission is suppressed:
     * calling AudioRecord without RECORD_AUDIO is what this probe tests.
     */
    @SuppressLint("MissingPermission")
    public static Bundle run(Context ctx) {
        boolean requested = false;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
            requested = pi.requestedPermissions != null
                    && Arrays.asList(pi.requestedPermissions).contains(Manifest.permission.RECORD_AUDIO);
        } catch (PackageManager.NameNotFoundException ignored) {
            // cannot happen for our own package
        }
        boolean granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        String outcome;
        AudioRecord r = null;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, 6400);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                outcome = "not_initialized";
            } else {
                r.startRecording();
                if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    outcome = "not_recording";
                } else {
                    byte[] buf = new byte[6400];
                    int n = r.read(buf, 0, buf.length);
                    boolean audio = false;
                    for (int i = 0; i < Math.max(n, 0); i++) {
                        audio |= buf[i] != 0;
                    }
                    outcome = audio ? "recorded" : "recording_silenced";
                }
            }
        } catch (SecurityException e) {
            outcome = "security_exception";
        } catch (RuntimeException e) {
            outcome = "failed:" + e.getClass().getSimpleName();
        } finally {
            if (r != null) {
                r.release();
            }
        }
        boolean pass = !requested && !granted && !outcome.equals("recorded");
        Bundle res = new Bundle();
        res.putString("outcome", outcome);
        res.putString("requested", String.valueOf(requested));
        res.putString("granted", String.valueOf(granted));
        res.putString("uid", String.valueOf(Process.myUid()));
        res.putString("pass", String.valueOf(pass));
        return res;
    }
}
