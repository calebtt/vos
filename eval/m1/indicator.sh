#!/usr/bin/env bash
# M1-IND-01 (#118, #266): the generating / acting indicator. Two legs, no sound:
#   1. a voice turn with a fast tool (the fast-tools fixture: "Set the media volume to 30 percent.",
#      then "That's all, thanks."; mic and speaker are files). Every model call the session reports
#      must draw the spinning chip <= 200 ms after it starts and clear it <= 200 ms after the last one
#      ends; the tool must show the acting state.
#   2. a stalled model call: the debug echo backend holds one call open for --stall-s (30) s. The chip
#      must keep spinning the whole time (the overlay window present, the notification title "VOS is
#      thinking", no idle in between), and the owner's stop must clear it <= 300 ms.
# Times come from the session's "model call started/ended" lines and the actuator's "indicator drawn"
# lines, all SystemClock.uptimeMillis (one clock for the whole device).
#
# The chip is an accessibility overlay, so VOS's accessibility service (HandsService) must be on. On
# an emulator the script turns it on and restores the setting afterwards. On a real phone it does not:
# that is the owner's decision (Phone B holds personal data), so it stops unless the service is
# already on. Needs GROK_API_KEY (the fixture; sealing the key into the session if it holds none) and
# python3 with `websockets`, like fast-tools.sh.
#
# Usage: eval/m1/indicator.sh [--no-build] [--stall-s N]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build=1 stall=30
while (( $# )); do
    case "$1" in
        --no-build) build=0 ;;
        --stall-s) stall="$2"; shift ;;
        *) echo "usage: $0 [--no-build] [--stall-s N]" >&2; exit 2 ;;
    esac
    shift
done
if ! [[ "$stall" =~ ^[0-9]+$ ]] || (( stall < 4 )); then
    echo "--stall-s: a whole number of seconds, at least 4" >&2
    exit 2
fi
: "${GROK_API_KEY:?set GROK_API_KEY (used for the fixture; never printed)}"

stamp="$(date +%Y-%m-%d)/indicator-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1
echo "indicator on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

HANDS=com.vos.actuator/.HandsService
a11y_before="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
a11y_on_before="$(adb shell settings get secure accessibility_enabled | tr -d '\r')"
emulator=0
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] && emulator=1
# Android lists enabled services separated by ':', each in the short or the full component form.
has_hands() { tr ':' '\n' <<<"$1" | grep -qxE 'com\.vos\.actuator/(\.|com\.vos\.actuator\.)HandsService'; }
if ! has_hands "$a11y_before"; then
    if (( ! emulator )); then
        echo "VOS's accessibility service is off on this phone; the overlay needs it. Turning it on is the owner's call: run on the emulator, or enable it in Settings first." >&2
        exit 2
    fi
    enabled_by_us=1
else
    enabled_by_us=0
fi

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :actuator:assembleDebug :session:assembleDebug
fi
adb install -r -t "$ROOT/actuator/build/outputs/apk/debug/actuator-debug.apk" >/dev/null
adb install -r -t "$ROOT/session/build/outputs/apk/debug/session-debug.apk" >/dev/null
adb shell pm grant com.vos.actuator android.permission.RECORD_AUDIO
adb shell pm grant com.vos.actuator android.permission.POST_NOTIFICATIONS
if ! adb shell "run-as com.vos.session test -f files/keys/xai.bin"; then
    printf '%s' "$GROK_API_KEY" | adb shell "run-as com.vos.session sh -c 'umask 077 && mkdir -p files/key-import && cat > files/key-import/xai'"
fi
media_volume() { adb shell cmd media_session volume --stream 3 --get | tr -d '\r' | sed -n 's/.*volume is \([0-9]*\) in range.*/\1/p'; }
vol_before="$(media_volume)"

cleanup() {
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null 2>&1 || true
    sleep 1
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op volume --ei stream 3 --ei index "$vol_before" >/dev/null 2>&1 || true
    adb shell "run-as com.vos.session rm -f files/debug/chat_voice" >/dev/null 2>&1 || true
    adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug'" >/dev/null 2>&1 || true
    if (( enabled_by_us )); then # put back exactly what was there; an unset value is deleted, not set empty
        if [ "$a11y_before" = null ] || [ -z "$a11y_before" ]; then
            adb shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
        else
            adb shell settings put secure enabled_accessibility_services "$a11y_before" >/dev/null 2>&1 || true
        fi
        if [ "$a11y_on_before" = null ] || [ -z "$a11y_on_before" ]; then
            adb shell settings delete secure accessibility_enabled >/dev/null 2>&1 || true
        else
            adb shell settings put secure accessibility_enabled "$a11y_on_before" >/dev/null 2>&1 || true
        fi
        echo "accessibility restored: services '$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')'"
    fi
}
trap cleanup EXIT
if (( enabled_by_us )); then # added to whatever else is on, never in place of it
    if [ "$a11y_before" = null ] || [ -z "$a11y_before" ]; then
        adb shell settings put secure enabled_accessibility_services "$HANDS"
    else
        adb shell settings put secure enabled_accessibility_services "$a11y_before:$HANDS"
    fi
    adb shell settings put secure accessibility_enabled 1
    sleep 3
fi

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
window_up() { adb shell dumpsys window windows | grep -c "VOS indicator" || true; }

echo "== leg 1: a voice turn with a fast tool"
python3 -I "$ROOT/eval/m1/make_fixture.py" "Set the media volume to 30 percent." "$out/ask.pcm"
python3 -I "$ROOT/eval/m1/make_fixture.py" "That's all, thanks." "$out/done.pcm"
python3 -I - "$out/ask.pcm" "$out/done.pcm" "$out/mic.pcm" <<'PY'
import sys
gap = bytes(16000 * 2 * 7)
open(sys.argv[3], "wb").write(open(sys.argv[1], "rb").read() + gap + open(sys.argv[2], "rb").read())
PY
adb shell "run-as com.vos.session rm -f files/debug/chat_voice"
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$out/mic.pcm"
adb logcat -c
adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op start --ez mic_file true --ez speaker_file true >/dev/null
over=0
for _ in $(seq 1 300); do
    adb logcat -d -s VOS_VOICE | grep -q " over after_ms=" && { over=1; break; }
    sleep 0.2
done
sleep 1
adb logcat -d -s VOS_VOICE VOS_TURN VOS_SESSION VOS_INDICATOR | tr -d '\r' > "$out/leg1.txt"
check "the voice turn ended" "(( $over ))"
python3 -I - "$out/leg1.txt" > "$out/leg1-times.txt" <<'PY'
import re, sys
ev = []
for line in open(sys.argv[1]):
    m = re.search(r"model call (started|ended) turnId=\S+ t_up=(\d+)", line)
    if m:
        ev.append((int(m.group(2)), "call_" + m.group(1)))
    m = re.search(r"indicator drawn state=(\w+) t_up=(\d+)", line)
    if m:
        ev.append((int(m.group(2)), "drawn_" + m.group(1)))
ev.sort()
show, clear, acting, prev = [], [], 0, "IDLE"
last_start = last_end = None
for t, k in ev:
    if k == "call_started":
        last_start = t
    elif k == "call_ended":
        last_end = t
    elif k.startswith("drawn_"):
        s = k[6:]
        if s == "GENERATING" and prev == "IDLE" and last_start is not None:
            show.append(t - last_start)
        if s == "IDLE" and prev == "GENERATING" and last_end is not None:
            clear.append(t - last_end)
        if s == "ACTING":
            acting += 1
        prev = s
starts = sum(1 for _, k in ev if k == "call_started")
print(f"calls={starts} show_ms={','.join(map(str, show)) or '-'} clear_ms={','.join(map(str, clear)) or '-'} acting={acting} final={prev}")
PY
cat "$out/leg1-times.txt"
read -r calls show_ms clear_ms acting final < <(sed 's/[a-z_]*=//g' "$out/leg1-times.txt")
check "the session reported model calls ($calls)" "(( calls >= 1 ))"
check "the chip spun <= 200 ms after a call started (ms: $show_ms)" "[ '$show_ms' != - ] && python3 -c 'import sys; sys.exit(0 if max(map(int, \"$show_ms\".split(\",\"))) <= 200 else 1)'"
check "the chip cleared <= 200 ms after the last call ended (ms: $clear_ms)" "[ '$clear_ms' != - ] && python3 -c 'import sys; sys.exit(0 if max(map(int, \"$clear_ms\".split(\",\"))) <= 200 else 1)'"
check "the tool showed the acting state ($acting times)" "(( acting >= 1 ))"
check "nothing showing after the turn (last drawn: $final)" "[ '$final' = IDLE ]"
check "the tool ran and was VERIFIED" "adb shell 'run-as com.vos.actuator cat files/audit/audit.jsonl' | tail -20 | grep '\"tool\":\"volume_set\"' | grep -q VERIFIED"

echo "== leg 2: a stalled model call for ${stall} s, then stop"
adb shell "run-as com.vos.session sh -c 'mkdir -p files/debug && echo debug:echo > files/debug/chat_voice'"
head -c 32000 /dev/zero > "$out/silence.pcm"
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$out/silence.pcm"
adb logcat -c
adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op start --ez mic_file true --ez speaker_file true >/dev/null
sleep 3
up_early="$(window_up)"
title_early="$(adb shell dumpsys notification --noredact | grep -c "VOS is thinking" || true)"
sleep "$((stall - 3))"
up_late="$(window_up)"
adb logcat -d -s VOS_INDICATOR | tr -d '\r' > "$out/leg2-before-stop.txt"
adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null
sleep 2
adb logcat -d -s VOS_INDICATOR VOS_TURN VOS_SESSION | tr -d '\r' > "$out/leg2.txt"
up_after="$(window_up)"
check "the chip is up 3 s into the call (windows: $up_early)" "(( up_early >= 1 ))"
check "the notification title says VOS is thinking" "(( $title_early >= 1 ))"
check "the chip is still up after ${stall} s with no output (windows: $up_late)" "(( up_late >= 1 ))"
check "no idle while the call stalled" "grep 'indicator drawn' '$out/leg2-before-stop.txt' | grep -q GENERATING && ! grep 'indicator drawn' '$out/leg2-before-stop.txt' | grep -q 'state=IDLE'"
stop_ms="$(python3 -I - "$out/leg2.txt" <<'PY'
import re, sys
stop = idle = None
for line in open(sys.argv[1]):
    m = re.search(r"stop received .*t_up=(\d+)", line)
    if m and stop is None:
        stop = int(m.group(1))
    m = re.search(r"indicator drawn state=IDLE t_up=(\d+)", line)
    if m and stop is not None and idle is None:
        idle = int(m.group(1))
print(idle - stop if stop is not None and idle is not None else -1)
PY
)"
check "stop cleared the chip in ${stop_ms} ms (<= 300)" "(( stop_ms >= 0 && stop_ms <= 300 ))"
check "the overlay window is gone after stop (windows: $up_after)" "(( up_after == 0 ))"

if (( fails == 0 )); then echo "indicator: PASS"; else echo "indicator: FAIL ($fails)"; exit 1; fi
