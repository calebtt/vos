#!/usr/bin/env bash
# Fast tools (#245): one debug turn with two spoken requests (made through the xAI API on the
# workstation): "Set the media volume to 30 percent." then, after a pause, "That's all, thanks."
# Checks: the model called volume_set; the actuator set the media volume, read it back (VERIFIED)
# and audited the call; the earcon was the actuator's VERIFIED; the reply was spoken after the
# result; the model ended the turn (end_turn) and the AudioPlane audit shows that end. The media
# volume is restored afterwards.
#
# The reply and the earcon go to a file (no sound) unless --speaker (08:00-22:00 only). Needs
# GROK_API_KEY (fixtures; sealing the key into the session if it holds none) and python3 with
# `websockets`, like voice-roundtrip.sh.
#
# Usage: eval/m1/fast-tools.sh [--no-build] [--speaker]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/eval/m1/lib.sh"
build=1 speaker=0
for a in "$@"; do
    case "$a" in
        --no-build) build=0 ;;
        --speaker) speaker=1 ;;
        *) echo "usage: $0 [--no-build] [--speaker]" >&2; exit 2 ;;
    esac
done
: "${GROK_API_KEY:?set GROK_API_KEY (used for the fixtures; never printed)}"
if (( speaker )); then vos_daytime_only "--speaker plays sound"; fi

vos_run_dir fast-tools
echo "fast-tools on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :actuator:assembleDebug :session:assembleDebug
fi
vos_install_apps
vos_grant_mic
vos_seal_key_if_missing
vos_clear_debug_overrides

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
media_volume() { vos_volume 3; }
read -r vol_before vol_max <<<"$(media_volume)"
echo "media volume before: $vol_before of $vol_max"
# The shell's own volume command does not apply on Phone B's Android 17; the debug summon activity restores it.
restore() {
    vos_restore_media_volume "$vol_before"
    sleep 1
}
trap restore EXIT

python3 -I "$ROOT/eval/m1/make_fixture.py" "Set the media volume to 30 percent." "$out/ask.pcm"
python3 -I "$ROOT/eval/m1/make_fixture.py" "That's all, thanks." "$out/done.pcm"
python3 -I - "$out/ask.pcm" "$out/done.pcm" "$out/mic.pcm" <<'PY'
import sys
gap = bytes(16000 * 2 * 7)  # 7 s: the reply plays, then the owner speaks again
open(sys.argv[3], "wb").write(open(sys.argv[1], "rb").read() + gap + open(sys.argv[2], "rb").read())
PY
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$out/mic.pcm"

adb logcat -c
extras=(--es op start --ez mic_file true)
(( speaker )) || extras+=(--ez speaker_file true)
adb shell am start -n com.vos.actuator/.DebugSummonActivity "${extras[@]}" >/dev/null
over=0
for _ in $(seq 1 300); do
    adb logcat -d -s VOS_VOICE | grep -q " over after_ms=" && { over=1; break; }
    sleep 0.2
done
check "the turn ended" "(( $over ))"
sleep 1
adb logcat -d -s VOS_VOICE VOS_TURN VOS_SESSION > "$out/logcat.txt"
turn="$(sed -n 's/.*turn opened in session turnId=\([0-9a-f-]*\).*/\1/p' "$out/logcat.txt" | head -1)"
echo "turnId=$turn"
grep -E "tool |earcon|first reply audio|response done|over after_ms" "$out/logcat.txt" | sed 's/^.*VOS_/VOS_/' || true

adb shell "run-as com.vos.actuator cat files/audit/audit.jsonl" | tr -d '\r\000' | grep -F "\"turnId\":\"$turn\"" > "$out/audit.jsonl" || true
grep '"event":"tool.call"' "$out/audit.jsonl" | sed 's/^/  /' || true
check "volume_set ran in the actuator and was VERIFIED" \
    "grep '\"event\":\"tool.call\"' '$out/audit.jsonl' | grep '\"tool\":\"volume_set\"' | grep -q '\"status\":\"VERIFIED\"'"
check "no tool call failed" "! grep '\"event\":\"tool.call\"' '$out/audit.jsonl' | grep -q '\"status\":\"FAILED\"'"
read -r vol_now _ <<<"$(media_volume)"
want=$(( (30 * vol_max + 50) / 100 ))
check "media volume is now $vol_now of $vol_max (30% is $want)" "(( vol_now == want ))"
check "the earcon was the actuator's VERIFIED" "grep -q 'earcon VERIFIED' '$out/logcat.txt'"
adb shell "run-as com.vos.session cat files/transcripts/transcripts.jsonl" | tr -d '\r' | grep -F "\"turn_id\":\"$turn\"" > "$out/transcripts.jsonl" || true
echo "transcripts:"; sed 's/^/  /' "$out/transcripts.jsonl"
check "the reply came after the result and names the level" "grep '\"role\":\"vos\"' '$out/transcripts.jsonl' | grep -q -E '3[0-9]|thirty'"
reason="$(sed -n "s/.*turn $turn over after_ms=[0-9]* reason=//p" "$out/logcat.txt" | tr -d '\r' | head -1)"
check "the model ended the turn (reason: $reason)" "[[ '$reason' == 'model end' ]]"
check "the AudioPlane recorded the model's end" "grep '\"state\":\"RESTORE\"' '$out/audit.jsonl' | grep -q 'session: model end'"

restore
read -r vol_after _ <<<"$(media_volume)"
check "media volume restored to $vol_before (now $vol_after)" "(( vol_after == vol_before ))"
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug'"
if (( fails == 0 )); then echo "fast-tools: PASS"; else echo "fast-tools: FAIL ($fails)"; exit 1; fi
