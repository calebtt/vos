#!/usr/bin/env bash
# M1-IND-01 (#118, #266): the generating / acting indicator. Two legs, no sound:
#   1. a voice turn with a fast tool (the fast-tools fixture: "Set the media volume to 30 percent.",
#      then "That's all, thanks."; mic and speaker are files). Every model call the session reports
#      must draw the spinning chip <= 200 ms after it starts and clear it <= 200 ms after the last one
#      ends; the tool must show the acting state.
#   2. a stalled model call: the debug echo backend holds one call open for --stall-s (30) s. The chip
#      must keep spinning the whole time (the overlay window present, the notification title "VOS is
#      thinking", no idle in between), and the owner's stop must clear it <= 300 ms.
# Times come from the session's "model call started/ended" lines and the actuator's "indicator state"
# (decided) and "indicator drawn" lines, all SystemClock.uptimeMillis (one clock for the whole
# device). A chip shown or changed is "drawn" just before its next frame; a chip cleared is "drawn"
# when its window is removed (the app cannot see the compositor finish). Every state change, ACTING
# included, must be drawn <= 200 ms after the actuator decided it; a state after ACTING waits until
# ACTING has been on screen 100 ms (so a tool that returns within a frame is still seen), and its
# 200 ms count from the end of that wait.
#
# The chip is an accessibility overlay, so VOS's accessibility service (HandsService) must be on. On
# an emulator the script turns it on and restores the setting afterwards. On a real phone it does not:
# that is the owner's decision (Phone B holds personal data), so it stops unless the service is
# already on and bound. Needs GROK_API_KEY (the fixture; sealing the key into the session if it holds none) and
# python3 with `websockets`, like fast-tools.sh.
#
# Usage: eval/m1/indicator.sh [--no-build] [--stall-s N]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/eval/m1/lib.sh"
build=1 stall=30
while (( $# )); do
    case "$1" in
        --no-build) build=0 ;;
        --stall-s) stall="$2"; shift ;;
        *) echo "usage: $0 [--no-build] [--stall-s N]" >&2; exit 2 ;;
    esac
    shift
done
# At least 4 s; and under the actuator's 10-minute turn cap (TurnService.MAX_TURN_MS), which would end the turn.
if ! [[ "$stall" =~ ^[0-9]+$ ]] || (( stall < 4 || stall > 590 )); then
    echo "--stall-s: a whole number of seconds, 4 to 590" >&2
    exit 2
fi
: "${GROK_API_KEY:?set GROK_API_KEY (used for the fixture; never printed)}"

vos_run_dir indicator
echo "indicator on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

HANDS=com.vos.actuator/.HandsService
a11y_before="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
a11y_on_before="$(adb shell settings get secure accessibility_enabled | tr -d '\r')"
emulator=0
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] && emulator=1
# Android lists enabled services separated by ':', each in the short or the full component form.
has_hands() { tr ':' '\n' <<<"$1" | grep -qxE 'com\.vos\.actuator/(\.|com\.vos\.actuator\.)HandsService'; }
# Listed and switched on is not enough: the system must have bound the service (dumpsys lists it
# under "Bound services" by its label, VOS) or there is no window token to draw with.
hands_bound() {
    local d; d="$(adb shell dumpsys accessibility | tr -d '\r')"
    grep -E 'Enabled services:' <<<"$d" | grep -qE 'com\.vos\.actuator/(\.|com\.vos\.actuator\.)HandsService' \
        && grep -E 'Bound services:' <<<"$d" | grep -q 'label=VOS[,}]'
}
if ! has_hands "$a11y_before"; then
    if (( ! emulator )); then
        echo "VOS's accessibility service is off on this phone; the overlay needs it. Turning it on is the owner's call: run on the emulator, or enable it in Settings first." >&2
        exit 2
    fi
    enabled_by_us=1
else
    enabled_by_us=0
    if [ "$a11y_on_before" != 1 ] || ! hands_bound; then
        echo "VOS's accessibility service is listed but not running (accessibility_enabled=$a11y_on_before, or not bound); turn it on in Settings first." >&2
        exit 2
    fi
fi

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :actuator:assembleDebug :session:assembleDebug
fi
vos_install_apps
vos_grant_mic
vos_grant_notifications
vos_seal_key_if_missing
media_volume() { vos_volume 3 | cut -d' ' -f1; }
vol_before="$(media_volume)"

cleanup() {
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null 2>&1 || true
    sleep 1
    vos_restore_media_volume "$vol_before"
    vos_clear_debug_overrides >/dev/null 2>&1 || true
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
    for _ in $(seq 1 20); do hands_bound && break; sleep 0.5; done
    hands_bound || { echo "VOS's accessibility service did not bind within 10 s" >&2; exit 1; }
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
    m = re.search(r"model call (started|ended) turnId=\S+ callId=\S+ t_up=(\d+)", line)
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
# A "drawn" line names the state on the chip at that frame. A state drawn after ACTING is held until
# ACTING has been on screen for ACTING_MIN_MS (HandsService), so its lag is measured from the later
# of its decision and the end of that hold; the hold itself is checked too.
python3 -I - "$out/leg1.txt" > "$out/leg1-draws.txt" <<'PY'
import re, sys
ACTING_MIN_MS = 100
decided, lags, held = [], [], []
prev = None  # (state, t) of the previous draw
for line in open(sys.argv[1]):
    m = re.search(r"indicator state=(\w+) t_up=(\d+)", line)
    if m:
        decided.append((m.group(1), int(m.group(2))))
    m = re.search(r"indicator drawn state=(\w+) t_up=(\d+)", line)
    if m:
        state, t = m.group(1), int(m.group(2))
        before = [d for s, d in decided if s == state and d <= t]
        if before:
            start = before[-1]
            if prev and prev[0] == "ACTING":
                held.append(t - prev[1])
                start = max(start, prev[1] + ACTING_MIN_MS)
            lags.append(t - start)
        prev = (state, t)
print(f"changes={len(lags)} max_ms={max(lags) if lags else -1} acting_held_ms={min(held) if held else -1}")
PY
cat "$out/leg1-times.txt"
read -r calls show_ms clear_ms acting final < <(sed 's/[a-z_]*=//g' "$out/leg1-times.txt")
check "the session reported model calls ($calls)" "(( calls >= 1 ))"
check "the chip spun <= 200 ms after a call started (ms: $show_ms)" "[ '$show_ms' != - ] && python3 -c 'import sys; sys.exit(0 if max(map(int, \"$show_ms\".split(\",\"))) <= 200 else 1)'"
check "the chip cleared <= 200 ms after the last call ended (ms: $clear_ms)" "[ '$clear_ms' != - ] && python3 -c 'import sys; sys.exit(0 if max(map(int, \"$clear_ms\".split(\",\"))) <= 200 else 1)'"
check "the tool showed the acting state ($acting times)" "(( acting >= 1 ))"
check "nothing showing after the turn (last drawn: $final)" "[ '$final' = IDLE ]"
read -r changes draw_max acting_held < <(sed 's/[a-z_]*=//g' "$out/leg1-draws.txt")
check "every state change was drawn <= 200 ms after the actuator decided it, or after ACTING's minimum time ($changes changes, max ${draw_max} ms)" "(( changes >= 3 && draw_max >= 0 && draw_max <= 200 ))"
check "ACTING stayed on screen >= 100 ms before the next state (min ${acting_held} ms)" "(( acting_held >= 100 ))"
turn="$(sed -n 's/.*turn opened in session turnId=\([0-9a-f-]*\).*/\1/p' "$out/leg1.txt" | head -1)"
check "this turn's tool ran and was VERIFIED (turn ${turn:-?})" "[ -n '$turn' ] && adb shell 'run-as com.vos.actuator cat files/audit/audit.jsonl' | tr -d '\000' | grep -F '\"turnId\":\"$turn\"' | grep '\"tool\":\"volume_set\"' | grep -q VERIFIED"
pairs="$(python3 -I - "$out/leg1.txt" <<'PY'
import re, sys
started, ended = set(), set()
for line in open(sys.argv[1]):
    m = re.search(r"model call (started|ended) turnId=(\S+) callId=(\S+) ", line)
    if m:
        (started if m.group(1) == "started" else ended).add((m.group(2), m.group(3)))
print("ok" if started and started == ended else f"unpaired: started {sorted(started - ended)} ended {sorted(ended - started)}")
PY
)"
check "every model call the session started also ended ($pairs)" "[ '$pairs' = ok ]"

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
