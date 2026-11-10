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

# vos_install_apps [APK...]: the debug session, then the debug actuator, then any extra APKs (e.g. a
# test APK). Each app defines the signature permission the other uses (the session defines
# com.vos.permission.ACTUATOR, the actuator com.vos.permission.SESSION). On a device with neither
# installed, the app installed before the other's definition exists is left without that permission,
# and every turn then fails to bind (#341). Upgrades are not affected. So after both installs it reads
# both grants back, reinstalls an app that lacks its grant once, and returns 1 (loudly) if one is
# still missing. Returns 1 rather than exiting: the sweep runbook calls it from an interactive shell
# (as `vos_install_apps || echo "STOP: ..."`); runners under set -e exit 1 on it.
# shellcheck disable=SC2120 # the extra APKs are optional; most runners pass none
vos_install_apps() {
    local apk
    local session_apk="$ROOT/session/build/outputs/apk/debug/session-debug.apk"
    local actuator_apk="$ROOT/actuator/build/outputs/apk/debug/actuator-debug.apk"
    adb install -r -t "$session_apk" >/dev/null
    adb install -r -t "$actuator_apk" >/dev/null
    vos_ensure_grant com.vos.actuator com.vos.permission.ACTUATOR "$actuator_apk" || return 1
    vos_ensure_grant com.vos.session com.vos.permission.SESSION "$session_apk" || return 1
    for apk in "$@"; do
        adb install -r -t "$apk" >/dev/null
    done
}

# vos_perm_granted PERM: reads `dumpsys package PKG` on stdin; true if it lists "PERM: granted=true"
# (leading blanks, a trailing CR and anything after the match, e.g. ", flags=[...]", allowed). awk
# reads all its input, so a pipe from adb never dies of SIGPIPE under pipefail (grep -q could).
vos_perm_granted() {
    awk -v want="$1: granted=true" '
        { sub(/\r$/, ""); sub(/^[ \t]+/, "") }
        index($0, want) == 1 { found = 1 }
        END { exit !found }'
}

# vos_ensure_grant PKG PERM APK: PKG holds PERM, or it is reinstalled from APK once and checked
# again. Returns 1 with a FAIL line on stderr if PERM is still not granted.
vos_ensure_grant() {
    adb shell dumpsys package "$1" | vos_perm_granted "$2" && return 0
    echo "$1 lacks $2 (install order, #341): reinstalling $1 once" >&2
    adb install -r -t "$3" >/dev/null
    adb shell dumpsys package "$1" | vos_perm_granted "$2" && return 0
    echo "FAIL: $1 still lacks $2 after one reinstall (#341); every turn would fail to bind" >&2
    return 1
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
# shellcheck disable=SC2034 # VOS_KEY_PUSHED is for the sourcing runner (voice-roundtrip.sh prints it)
vos_seal_key_if_missing() {
    VOS_KEY_PUSHED=0
    if ! adb shell "run-as com.vos.session test -f files/keys/xai.bin"; then
        : "${GROK_API_KEY:?the session holds no key: set GROK_API_KEY to seal it (never printed)}"
        printf '%s' "$GROK_API_KEY" | adb shell "run-as com.vos.session sh -c 'umask 077 && mkdir -p files/key-import && cat > files/key-import/xai'"
        VOS_KEY_PUSHED=1
    fi
}

# vos_clear_debug_overrides: no debug chat_voice, patience or silence_duration_ms override (#247's
# sweep), so each turn runs on the policy's backend and its turn-detection values.
vos_clear_debug_overrides() {
    adb shell "run-as com.vos.session rm -f files/debug/chat_voice files/debug/patience files/debug/silence_duration_ms"
}

# vos_volume STREAM: prints "index max" for an audio stream (3 media, 0 voice call, 2 ring, 11
# assistant), or nothing if the device's answer does not parse.
vos_volume() {
    adb shell cmd media_session volume --stream "$1" --get | tr -d '\r' \
        | sed -n 's/.*volume is \([0-9]*\) in range \[\([0-9]*\)\.\.\([0-9]*\)\].*/\1 \3/p'
}

# Exit codes the runners share (#312): 0 PASS, 1 FAIL (a defect), 2 usage, 3 SKIP (a precondition the
# device does not meet yet, e.g. a fresh emulator with no sealed key: nothing was judged).
VOS_EXIT_SKIP=3

# vos_verdict NAME FAILS [SKIP_REASON]: the run's last line and exit. Any failed check wins (exit 1);
# else a skip reason prints "SKIP <reason>" and exits 3; else PASS (exit 0).
vos_verdict() {
    if (( $2 > 0 )); then
        echo "$1: FAIL ($2)"; exit 1
    elif [[ -n ${3:-} ]]; then
        echo "SKIP $3"; echo "$1: SKIP"; exit "$VOS_EXIT_SKIP"
    fi
    echo "$1: PASS"; exit 0
}

# vos_low_target BEFORE LOW: the volume a quiet run sets a stream to: LOW, or BEFORE if that is
# already lower, so a stream never ends up louder than it was. An unreadable BEFORE gives LOW.
vos_low_target() {
    if [[ $1 =~ ^[0-9]+$ ]] && (( $1 < $2 )); then echo "$1"; else echo "$2"; fi
}

# vos_volume_is_low NOW TARGET: the stream reads a number at or below TARGET. Below is fine: on the
# API 35 emulator STREAM_ASSISTANT (11) aliases STREAM_MUSIC (3), so setting one moves the other.
vos_volume_is_low() {
    [[ $1 =~ ^[0-9]+$ ]] && (( $1 <= $2 ))
}

# vos_lib_self_test: the decisions above, with no device. Returns 1 (and says which) on a wrong one.
vos_lib_self_test() {
    local rc case f reason want
    for case in "0||0" "2||1" "0|no key yet|3" "1|no key yet|1"; do
        IFS='|' read -r f reason want <<<"$case"
        rc=0; ( vos_verdict T "$f" "$reason" ) >/dev/null || rc=$?
        [[ $rc == "$want" ]] || { echo "lib self-test FAIL: vos_verdict $f '$reason' exit $rc, want $want" >&2; return 1; }
    done
    [[ "$( ( vos_verdict T 0 "no key yet" ) || true)" == $'SKIP no key yet\nT: SKIP' ]] \
        || { echo "lib self-test FAIL: the SKIP lines" >&2; return 1; }
    [[ $(vos_low_target 7 2) == 2 && $(vos_low_target 1 2) == 1 && $(vos_low_target "" 2) == 2 ]] \
        || { echo "lib self-test FAIL: vos_low_target" >&2; return 1; }
    if ! { vos_volume_is_low 1 2 && vos_volume_is_low 2 2 && ! vos_volume_is_low 3 2 && ! vos_volume_is_low "" 2; }; then
        echo "lib self-test FAIL: vos_volume_is_low" >&2; return 1
    fi
    local p=com.vos.permission.ACTUATOR
    local dump=$'    install permissions:\r\n      android.permission.INTERNET: granted=true\r\n'
    if ! { vos_perm_granted "$p" <<<"$dump      $p: granted=true"$'\r' \
            && vos_perm_granted "$p" <<<"$dump      $p: granted=true, flags=[ ]" \
            && ! vos_perm_granted "$p" <<<"$dump      $p: granted=false" \
            && ! vos_perm_granted "$p" <<<"$dump    requested permissions:"$'\n'"      $p" \
            && ! vos_perm_granted "$p" <<<"$dump      x$p: granted=true" \
            && ! vos_perm_granted "$p" <<<"$dump      ${p}_X: granted=true" \
            && ! vos_perm_granted "$p" </dev/null; }; then
        echo "lib self-test FAIL: vos_perm_granted" >&2; return 1
    fi
}

# vos_restore_media_volume INDEX: sets the media volume back. The shell's own volume command does not
# apply on Phone B's Android 17, so the actuator's debug summon activity sets it. Never fails.
vos_restore_media_volume() {
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op volume --ei stream 3 --ei index "$1" >/dev/null 2>&1 || true
}

# vos_actuator_audit: the actuator's audit, CRs and NULs stripped (a torn tail, #319). The offline
# self-test (ci/tests/test_wait_audit_state.py) redefines it to read a local file.
vos_actuator_audit() {
    adb shell "run-as com.vos.actuator cat files/audit/audit.jsonl" | tr -d '\r\000'
}

# vos_wait_audit_state TURN_ID STATE SECONDS FILE: writes this turn's audit lines to FILE, re-reading
# every 0.2 s until one is in STATE or SECONDS pass (#310: the AudioPlane writes IDLE just after the
# turn's stop, so a single read can miss it). Returns 1 on a timeout; FILE then holds the last read,
# so the caller's own check still fails honestly. An empty TURN_ID fails at once (nothing to match).
vos_wait_audit_state() {
    local i err
    : > "$4"
    [[ -n "$1" ]] || return 1
    # Count sleeps, not wall-clock SECONDS: bash SECONDS is whole seconds, so a 1 s budget can end
    # after a few hundred ms if we start near a tick.
    for (( i = 0; i <= $3 * 5; i++ )); do
        err="$(mktemp)"
        { vos_actuator_audit 2>"$err" || true; } | grep -F "\"turnId\":\"$1\"" > "$4" || true
        if grep -q "\"state\":\"$2\"" "$4"; then
            rm -f "$err"
            return 0
        fi
        if (( i == $3 * 5 )); then
            [[ -s "$err" ]] && cat "$err" >&2
            rm -f "$err"
            return 1
        fi
        rm -f "$err"
        sleep 0.2
    done
}
