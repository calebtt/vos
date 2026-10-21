#!/usr/bin/env bash
# S-07 follow-up (#261): where VOS's summon-to-mic time goes. Runs the S-07 probe's audio-path
# variants (see MicFgs) N times each, round-robin, from TrialActivity (am start: no Quick Settings
# change), 3 s apart so every capture opens cold like a real summon. Then summarizes each variant's
# steps, checks the audio mode is back to normal (before uninstalling, so the probe's own restore is
# what is tested), uninstalls the probe, and checks it is gone and the assistant role holder is
# unchanged. It fails unless every variant has N ok trials. The probe makes no sound; the live mic
# runs 08:00-22:00 only.
#
# Usage: spikes/probes/s07-variants.sh [--no-build] [--trials N] [variant ...]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build=1 trials=20 variants=()
while (( $# )); do
    case "$1" in
        --no-build) build=0 ;;
        --trials) trials="$2"; shift ;;
        -*) echo "usage: $0 [--no-build] [--trials N] [variant ...]" >&2; exit 2 ;;
        *) variants+=("$1") ;;
    esac
    shift
done
(( ${#variants[@]} )) || variants=(rec vc vc_fx mode_vc_fx vc_fx_then_mode parallel)
h=$((10#$(date +%H)))
(( h >= 8 && h < 22 )) || { echo "live mic: daytime only (08:00-22:00)" >&2; exit 2; }

stamp="$(date +%Y-%m-%d)/s07-variants-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1
echo "s07-variants on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

apk="$ROOT/spikes/probes/probe-s07/build/outputs/apk/debug/probe-s07-debug.apk"
if (( build )); then
    "$ROOT/gradlew" -p "$ROOT/spikes/probes" -q --console=plain :probe-s07:assembleDebug
fi
mode() { adb shell dumpsys audio | tr -d '\r' | sed -n 's/^- Actual mode = //p' | tail -1; }
holder() { adb shell cmd role get-role-holders android.app.role.ASSISTANT | tr -d '\r'; }
holder_before="$(holder)"
mode_before="$(mode)"
echo "before: assistant '$holder_before', audio mode $mode_before"
[ "$mode_before" = MODE_NORMAL ] || { echo "the audio mode is $mode_before (a call?): not starting" >&2; exit 1; }

installed() { adb shell pm list packages com.vos.spike.s07 | tr -d '\r' | grep -c . || true; }
cleanup() {
    adb uninstall com.vos.spike.s07 >/dev/null 2>&1 || true
    echo "after: assistant '$(holder)', audio mode $(mode), probe $(installed) installed"
}
trap cleanup EXIT
adb install -r -t "$apk" >/dev/null
adb shell pm grant com.vos.spike.s07 android.permission.RECORD_AUDIO

# Each trial's line is saved as soon as it appears and logcat is cleared after it: over a long run
# the phone's log buffer wraps, and lines read only at the end would be lost.
: > "$out/trials.log"
trial_line() { adb logcat -d -s VOS_S07 | tr -d '\r' | grep "VOS_S07 trial" || true; }
for i in $(seq "$trials"); do
    for v in "${variants[@]}"; do
        adb logcat -c
        adb shell input keyevent KEYCODE_WAKEUP
        adb shell am start -n com.vos.spike.s07/.TrialActivity --es variant "$v" >/dev/null
        line=""
        for _ in $(seq 40); do line="$(trial_line)"; [ -n "$line" ] && break; sleep 0.2; done
        if [ -n "$line" ]; then echo "$line" >> "$out/trials.log"; else echo "no trial line for $v in round $i"; fi
        sleep 3
    done
    echo "round $i/$trials done"
done
echo "$(wc -l < "$out/trials.log") trial lines for $((trials * ${#variants[@]})) trials"
python3 -I "$ROOT/spikes/probes/summarize.py" "$out/trials.log" | tee "$out/summary.txt"

fails=0
for v in "${variants[@]}"; do
    ok="$(grep -c " variant=$v .* ok=true " "$out/trials.log" || true)"
    (( ok == trials )) || { echo "FAIL $v: $ok of $trials trials ok"; fails=1; }
done
# Before uninstalling: the probe's own restore is what has to have put the mode back.
mode_after="$(mode)"
[ "$mode_after" = MODE_NORMAL ] || { echo "FAIL audio mode after the trials is $mode_after"; fails=1; }
cleanup
trap - EXIT
[ "$(installed)" -eq 0 ] || { echo "FAIL the probe is still installed"; fails=1; }
[ "$(holder)" = "$holder_before" ] || { echo "FAIL assistant holder changed: '$(holder)'"; fails=1; }
if (( fails == 0 )); then
    echo "PASS all trials ok; phone restored: assistant '$holder_before', MODE_NORMAL, probe uninstalled"
fi
exit "$fails"
