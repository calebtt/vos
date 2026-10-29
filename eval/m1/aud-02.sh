#!/usr/bin/env bash
# M1-AUD-02 (#135): instrumentation in the session's process tries AudioRecord.startRecording().
# Pass: it fails for lack of permission. MicProbe (eval/m1/auth-probe) runs in the session's UID: the
# session neither requests nor holds RECORD_AUDIO, and AudioRecord is refused (not initialized, a
# SecurityException, or not recording; a recording of silence fails). Also checked from
# outside the app: dumpsys lists no RECORD_AUDIO for the session, and a grant from the shell has no
# effect (a permission the manifest does not request cannot be granted). TARGET-PLAN §5.2.
#
# Installs only the session's androidTest APK (removed afterwards), against the session already
# installed (a debug build, same debug cert), so the installed actuator/session pair is left as it is.
# Calls no vendor, makes no sound, records nothing.
#
# --control also runs the same probe in the actuator's process (it holds RECORD_AUDIO), which must
# report requested, granted, pass=false and outcome=recorded. pass=false alone is not enough: the
# probe's pass bit is false whenever the permission is granted, including when nothing was recorded.
# That opens the real mic for one 0.2 s read into memory, discarded; nothing is stored or played.
#
# Usage: eval/m1/aud-02.sh [--no-build] [--control] [--self-test]
#   ANDROID_SERIAL picks the device. Results go to $VOS_EVAL_OUT if set, else to
#   $VOS_EVAL_ROOT/<date>/m1-aud-02-<time>, else under eval/runs/ in this checkout (gitignored).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# True only when the actuator control shows a real recording. pass=false follows from granted=true
# for every outcome, so outcome=recorded is what shows the probe saw audio.
control_recorded() {
    local ctl=$1
    grep -q 'INSTRUMENTATION_RESULT: requested=true' <<<"$ctl" \
        && grep -q 'INSTRUMENTATION_RESULT: granted=true' <<<"$ctl" \
        && grep -q 'INSTRUMENTATION_RESULT: pass=false' <<<"$ctl" \
        && grep -q 'INSTRUMENTATION_RESULT: outcome=recorded' <<<"$ctl"
}

self_test() {
    local line=$'INSTRUMENTATION_RESULT: requested=true\nINSTRUMENTATION_RESULT: granted=true\nINSTRUMENTATION_RESULT: pass=false\n'
    control_recorded "${line}INSTRUMENTATION_RESULT: outcome=recorded"
    if control_recorded "${line}INSTRUMENTATION_RESULT: outcome=not_recording"; then
        echo "self-test FAIL: a refusal with the permission was accepted" >&2
        exit 1
    fi
    if control_recorded "${line}INSTRUMENTATION_RESULT: outcome=recording_silenced"; then
        echo "self-test FAIL: silence was accepted as a recording" >&2
        exit 1
    fi
    echo "self-test PASS"
}

build=1 control=0 self_test=0
for a in "$@"; do
    case "$a" in
        --no-build) build=0 ;;
        --control) control=1 ;;
        --self-test) self_test=1 ;;
        *) echo "usage: $0 [--no-build] [--control] [--self-test]" >&2; exit 2 ;;
    esac
done
if (( self_test )); then
    self_test
    exit
fi

stamp="$(date +%Y-%m-%d)/m1-aud-02-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1

echo "M1-AUD-02 on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint | tr -d '\r')); results in $out"

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :session:assembleDebugAndroidTest \
        $( (( control )) && echo :actuator:assembleDebugAndroidTest )
fi
TEST_APK="$ROOT/session/build/outputs/apk/androidTest/debug/session-debug-androidTest.apk"
ACTUATOR_TEST_APK="$ROOT/actuator/build/outputs/apk/androidTest/debug/actuator-debug-androidTest.apk"

check "the session is installed" "[[ \$(adb shell pm path com.vos.session | tr -d '\r') == package:* ]]"
echo "installed session: $(adb shell dumpsys package com.vos.session | tr -d '\r' | grep -m1 -o 'lastUpdateTime=.*')"
adb install -r -t "$TEST_APK" >/dev/null
trap 'adb uninstall com.vos.session.test >/dev/null 2>&1 || true; adb uninstall com.vos.actuator.test >/dev/null 2>&1 || true' EXIT

# 1. From outside: no RECORD_AUDIO in the package, and a shell grant changes nothing.
adb shell dumpsys package com.vos.session | tr -d '\r' > "$out/session-package.txt"
check "dumpsys lists no RECORD_AUDIO for the session" "! grep -q 'android.permission.RECORD_AUDIO' '$out/session-package.txt'"
# Android 17 answers a grant of a permission the manifest does not request with exit 0 and no
# output, and ignores it, so the outcome is what counts: still not granted, here and in the probe.
adb shell pm grant com.vos.session android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
adb shell dumpsys package com.vos.session | tr -d '\r' > "$out/session-package-after-grant.txt" # a file, not a pipe into grep -q (pipefail)
if grep -q 'android.permission.RECORD_AUDIO: granted=true' "$out/session-package-after-grant.txt"; then
    adb shell pm revoke com.vos.session android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
    check "a shell grant of RECORD_AUDIO has no effect" "false"
else
    check "a shell grant of RECORD_AUDIO has no effect" "true"
fi
echo "appops: $(adb shell appops get com.vos.session RECORD_AUDIO | tr -d '\r')"

# 2. From inside, after the grant attempt: the session's own process tries to record.
probe="$(adb shell am instrument -w -r -e probe mic com.vos.session.test/com.vos.eval.auth.AuthProbe | tr -d '\r')"
echo "$probe" > "$out/probe.txt"
echo "$probe" | grep -E "INSTRUMENTATION_RESULT: (outcome|requested|granted|uid)" | sed 's/^/  /' || true
uid_session=$(adb shell pm list packages -U com.vos.session | tr -d '\r' | sed -n 's/^package:com.vos.session uid://p')
check "the probe ran in the session's UID ($uid_session)" "grep -q \"INSTRUMENTATION_RESULT: uid=$uid_session\$\" <<<\"\$probe\""
check "M1-AUD-02: the session cannot record (no permission, no audio)" "grep -q 'INSTRUMENTATION_RESULT: pass=true' <<<\"\$probe\""

if (( control )); then
    adb install -r -t "$ACTUATOR_TEST_APK" >/dev/null
    ctl="$(adb shell am instrument -w -r -e probe mic com.vos.actuator.test/com.vos.eval.auth.AuthProbe | tr -d '\r')"
    echo "$ctl" > "$out/control-actuator-probe.txt"
    echo "$ctl" | grep -E "INSTRUMENTATION_RESULT: (outcome|requested|granted)" | sed 's/^/  control: /' || true
    check "control: in the actuator (holds RECORD_AUDIO) the probe records" \
        "control_recorded \"\$ctl\""
fi

if (( fails == 0 )); then echo "M1-AUD-02: PASS"; else echo "M1-AUD-02: FAIL ($fails)"; exit 1; fi
