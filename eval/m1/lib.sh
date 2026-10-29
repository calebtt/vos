# shellcheck shell=bash
# The setup the eval/m1 scripts share (#268), sourced after the script sets ROOT:
#   source "$ROOT/eval/m1/lib.sh"
# Each function runs exactly the commands the scripts ran inline, so a fix lands once.
# ANDROID_SERIAL picks the device, as before.

# vos_run_dir NAME: the results dir ($VOS_EVAL_OUT, else $VOS_EVAL_ROOT/<date>/NAME-<time>, else
# under eval/runs/ in this checkout), created; $out is set, and all output also goes to $out/run.log.
vos_run_dir() {
    local stamp
    stamp="$(date +%Y-%m-%d)/$1-$(date +%H%M%S)"
    out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
    mkdir -p "$out"
    exec > >(tee -a "$out/run.log") 2>&1
}

# vos_daytime_only WHAT: exits 2 outside 08:00-22:00 local time (eval house rule 1: sound only by day).
vos_daytime_only() {
    local h
    h=$((10#$(date +%H)))
    (( h >= 8 && h < 22 )) || { echo "$1: daytime only (08:00-22:00)" >&2; exit 2; }
}

# vos_install_apps [APK...]: the debug actuator and session, then any extra APKs (e.g. a test APK).
vos_install_apps() {
    local apk
    for apk in "$ROOT/actuator/build/outputs/apk/debug/actuator-debug.apk" \
            "$ROOT/session/build/outputs/apk/debug/session-debug.apk" "$@"; do
        adb install -r -t "$apk" >/dev/null
    done
}

# vos_grant_mic: a microphone-type foreground service needs RECORD_AUDIO, even when the mic is a file.
vos_grant_mic() {
    adb shell pm grant com.vos.actuator android.permission.RECORD_AUDIO
}

# vos_grant_notifications: the turn notification and the lost-role notice need it (a setup step on Tier S).
vos_grant_notifications() {
    adb shell pm grant com.vos.actuator android.permission.POST_NOTIFICATIONS
}

# vos_seal_key_if_missing: if the session holds no sealed key, pushes GROK_API_KEY through run-as
# stdin (never a command-line argument, never printed) for the session to seal on its next turn.
# Sets VOS_KEY_PUSHED to 1 if it pushed, else 0.
vos_seal_key_if_missing() {
    VOS_KEY_PUSHED=0
    if ! adb shell "run-as com.vos.session test -f files/keys/xai.bin"; then
        : "${GROK_API_KEY:?the session holds no key: set GROK_API_KEY to seal it (never printed)}"
        printf '%s' "$GROK_API_KEY" | adb shell "run-as com.vos.session sh -c 'umask 077 && mkdir -p files/key-import && cat > files/key-import/xai'"
        VOS_KEY_PUSHED=1
    fi
}

# vos_clear_debug_overrides: no debug chat_voice override, so each turn runs on the policy's backend.
vos_clear_debug_overrides() {
    adb shell "run-as com.vos.session rm -f files/debug/chat_voice"
}

# vos_volume STREAM: prints "index max" for an audio stream (3 media, 0 voice call, 2 ring, 11
# assistant), or nothing if the device's answer does not parse.
vos_volume() {
    adb shell cmd media_session volume --stream "$1" --get | tr -d '\r' \
        | sed -n 's/.*volume is \([0-9]*\) in range \[\([0-9]*\)\.\.\([0-9]*\)\].*/\1 \3/p'
}

# vos_restore_media_volume INDEX: sets the media volume back. The shell's own volume command does not
# apply on Phone B's Android 17, so the actuator's debug summon activity sets it. Never fails.
vos_restore_media_volume() {
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op volume --ei stream 3 --ei index "$1" >/dev/null 2>&1 || true
}
