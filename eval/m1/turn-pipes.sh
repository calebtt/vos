#!/usr/bin/env bash
# Turn pipes (#240): open a voice turn through the actuator's debug summon, with the mic replaced by
# a pushed PCM file and the speaker by a file. The debug session echoes the mic back, so the speaker
# file must start with the pushed audio. Also checks the AudioPlane audit (CAPTURE, CONVERSE,
# RESTORE, IDLE for this turn, mode restored) and that the turn closes on stop.
#
# Debug builds only. Makes no sound and opens no microphone, unless --live-mic: then the real mic is
# captured for ~3 s (privacy indicator on), only its byte count and the AudioRecord release time are
# checked, and the captured audio is deleted on the device without being pulled.
#
# Usage: eval/m1/turn-pipes.sh [--no-build] [--live-mic]
#   ANDROID_SERIAL picks the device. Results: $VOS_EVAL_OUT, else $VOS_EVAL_ROOT/<date>/turn-pipes-<time>,
#   else eval/runs/ in this checkout (gitignored).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/eval/m1/lib.sh"
build=1 live=0
for a in "$@"; do
    case "$a" in
        --no-build) build=0 ;;
        --live-mic) live=1 ;;
        *) echo "usage: $0 [--no-build] [--live-mic]" >&2; exit 2 ;;
    esac
done

vos_run_dir turn-pipes
echo "turn-pipes on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :actuator:assembleDebug :session:assembleDebug
fi
vos_install_apps
vos_grant_mic

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
summon() { adb shell am start -n com.vos.actuator/.DebugSummonActivity "$@" >/dev/null; }
wait_log() { # pattern seconds
    for _ in $(seq 1 $(( $2 * 5 ))); do adb logcat -d -s VOS_TURN | grep -q "$1" && return 0; sleep 0.2; done
    return 1
}

# 1.5 s fixture: a 440 Hz tone with a 2-sample marker every 100 ms, 16 kHz mono s16le.
fixture="$out/mic-inject.pcm"
python3 -I - "$fixture" <<'PY'
import math, struct, sys
s = []
for i in range(24000):
    v = int(8000 * math.sin(2 * math.pi * 440 * i / 16000))
    if i % 1600 < 2:
        v = 32000
    s.append(v)
open(sys.argv[1], "wb").write(struct.pack("<%dh" % len(s), *s))
PY

adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug'"
# The session's echo backend is debug-only and opt-in (files/debug/chat_voice puts it first, #241).
adb shell "run-as com.vos.session sh -c 'mkdir -p files/debug && echo debug:echo > files/debug/chat_voice'"
if (( ! live )); then
    adb shell "run-as com.vos.actuator sh -c 'umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$fixture"
fi
actual_mode() { adb shell dumpsys audio | sed -n 's/^- Actual mode = //p' | head -1 | tr -d '\r'; }
mode_before="$(actual_mode)"
adb logcat -c

if (( live )); then summon --es op start --ez speaker_file true; else summon --es op start --ez mic_file true --ez speaker_file true; fi
check "turn opened in the session" "wait_log 'turn opened in session' 10"
check "reply audio reached the actuator" "wait_log 'first reply audio' 10"
sleep 3
summon --es op stop
check "speaker closed after stop" "wait_log 'speaker done' 10"
adb logcat -d -s VOS_TURN VOS_SESSION > "$out/logcat.txt"

turn_id="$(sed -n 's/.*turn opened in session turnId=\([0-9a-f-]*\).*/\1/p' "$out/logcat.txt" | head -1)"
echo "turnId=$turn_id"
adb shell "run-as com.vos.actuator cat files/audit/audit.jsonl" | tr -d '\r\000' | grep -F "\"turnId\":\"$turn_id\"" > "$out/audit.jsonl" || true
states="$(sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' "$out/audit.jsonl" | tr '\n' ' ')"
echo "audit states: $states"
check "audit CAPTURE CONVERSE RESTORE IDLE" "[[ '$states' == 'CAPTURE CONVERSE RESTORE IDLE ' ]]"
idle_mode="$(grep '"state":"IDLE"' "$out/audit.jsonl" | sed -n 's/.*"mode":\([0-9]*\).*/\1/p' | tail -1)"
mode_number() { # AudioManager mode name -> its value
    case "$1" in
        MODE_NORMAL) echo 0 ;; MODE_RINGTONE) echo 1 ;; MODE_IN_CALL) echo 2 ;; MODE_IN_COMMUNICATION) echo 3 ;;
        MODE_CALL_SCREENING) echo 4 ;; MODE_CALL_REDIRECT) echo 5 ;; MODE_COMMUNICATION_REDIRECT) echo 6 ;; *) echo "?" ;;
    esac
}
want_mode="$(mode_number "$mode_before")"
check "audit: mode restored to the one before the turn ($idle_mode, want $want_mode)" "[[ -n '$idle_mode' && '$idle_mode' == '$want_mode' ]]"

speaker_bytes="$(adb shell "run-as com.vos.actuator sh -c 'wc -c < files/debug/speaker-$turn_id.pcm'" | tr -d '\r ')"
echo "speaker bytes: $speaker_bytes"
if (( live )); then
    check "live mic delivered audio (${speaker_bytes} bytes)" "(( ${speaker_bytes:-0} > 32000 ))"
    rel="$(sed -n 's/.*AudioRecord.release turnId=[0-9a-f-]* ms=\([0-9]*\).*/\1/p' "$out/logcat.txt" | head -1)"
    check "AudioRecord released ${rel} ms after stop began (<= 500)" "[[ -n '$rel' ]] && (( rel <= 500 ))"
else
    adb exec-out "run-as com.vos.actuator cat files/debug/speaker-$turn_id.pcm" > "$out/speaker.pcm"
    check "speaker echo starts with the pushed audio, byte for byte" \
        "cmp -s -n $(stat -c %s "$fixture") '$fixture' '$out/speaker.pcm'"
fi
mode_after="$(actual_mode)"
check "platform audio mode back to $mode_before (now $mode_after)" "[[ -n '$mode_after' && '$mode_after' == '$mode_before' ]]"
check "the session did not report an error for the actuator's stop" "! grep -q 'ended by the session: backend error' '$out/logcat.txt'"
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug'"
adb shell "run-as com.vos.session rm -f files/debug/chat_voice"

if (( fails == 0 )); then echo "turn-pipes: PASS"; else echo "turn-pipes: FAIL ($fails)"; exit 1; fi
