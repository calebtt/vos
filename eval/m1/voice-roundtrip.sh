#!/usr/bin/env bash
# Spoken round trip (#241): a debug turn whose mic is a synthesized spoken question ("What time is
# it?", made through the xAI API on the workstation) goes through the actuator, the session and the
# xAI realtime API, and the reply comes back to the actuator. Checks: the policy picked the xAI
# backend; the owner transcript and the reply arrived; reply audio reached the actuator; the session
# ended the turn when idle and the AudioPlane audit reads CAPTURE, CONVERSE, RESTORE, IDLE; the only
# egress was one WebSocket to api.x.ai for this turn; the key is nowhere in logcat, the actuator's
# data or its APK (M1-KEY-01, debug build); the session cannot record (M1-AUD-02).
#
# The reply goes to a file on the actuator (no sound) unless --speaker, which plays it through the
# phone and runs only between 08:00 and 22:00 (eval house rule). Needs GROK_API_KEY in the
# environment for the fixture, and to seal the key into the session if it holds none yet; the key
# travels only through adb stdin. Needs python3 with `websockets`.
#
# Usage: eval/m1/voice-roundtrip.sh [--no-build] [--speaker]
#   ANDROID_SERIAL picks the device. Results: $VOS_EVAL_OUT, else $VOS_EVAL_ROOT/<date>/voice-roundtrip-<time>,
#   else eval/runs/ in this checkout (gitignored).

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
: "${GROK_API_KEY:?set GROK_API_KEY (used for the fixture; never printed)}"
if (( speaker )); then vos_daytime_only "--speaker plays sound"; fi

vos_run_dir voice-roundtrip
echo "voice-roundtrip on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :actuator:assembleDebug :session:assembleDebug \
        :session:assembleDebugAndroidTest
fi
vos_install_apps "$ROOT/session/build/outputs/apk/androidTest/debug/session-debug-androidTest.apk"
vos_grant_mic

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

# The session's key: sealed already, or pushed now through stdin (never a command-line argument).
vos_seal_key_if_missing
if (( VOS_KEY_PUSHED )); then echo "pushed the key for the session to seal on its next turn"; fi
vos_clear_debug_overrides

python3 -I "$ROOT/eval/m1/make_fixture.py" "What time is it?" "$out/question.pcm"
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$out/question.pcm"

adb logcat -c
extras=(--es op start --ez mic_file true)
(( speaker )) || extras+=(--ez speaker_file true)
adb shell am start -n com.vos.actuator/.DebugSummonActivity "${extras[@]}" >/dev/null
over=0
for _ in $(seq 1 300); do
    adb logcat -d -s VOS_VOICE | grep -q " over after_ms=" && { over=1; break; }
    sleep 0.2
done
check "the session ended the turn" "(( $over ))"
sleep 1
adb logcat -d -s VOS_VOICE VOS_TURN VOS_SESSION VOS_AUDIT > "$out/logcat.txt"
turn="$(sed -n 's/.*turn opened in session turnId=\([0-9a-f-]*\).*/\1/p' "$out/logcat.txt" | head -1)"
echo "turnId=$turn"
grep -E "vendor session|configured|first reply audio|response done|over after_ms|first reply audio turnId|AudioRecord" "$out/logcat.txt" | sed 's/^.*VOS_/VOS_/' || true

reason="$(sed -n "s/.*turn $turn over after_ms=[0-9]* reason=//p" "$out/logcat.txt" | tr -d '\r' | head -1)"
check "the turn ended because the owner went quiet (reason: $reason)" "[[ '$reason' == idle ]]"
adb shell "run-as com.vos.session cat files/audit/audit.jsonl" | tr -d '\r\000' | grep '"event":"model.pick"' | tail -1 > "$out/pick.json" || true
check "chat_voice picked xai:grok-voice-3.0" "grep -q '\"model\":\"xai:grok-voice-3.0\"' '$out/pick.json'"

adb shell "run-as com.vos.session cat files/transcripts/transcripts.jsonl" | tr -d '\r' | grep -F "\"turn_id\":\"$turn\"" > "$out/transcripts.jsonl" || true
echo "transcripts:"; sed 's/^/  /' "$out/transcripts.jsonl"
check "the owner's question was transcribed" "grep '\"role\":\"owner\"' '$out/transcripts.jsonl' | grep -qi 'time'"
check "a reply was transcribed" "grep -q '\"role\":\"vos\",\"text\":\"[^\"]' '$out/transcripts.jsonl'"

adb shell "run-as com.vos.actuator cat files/audit/audit.jsonl" | tr -d '\r\000' | grep -F "\"turnId\":\"$turn\"" > "$out/audit.jsonl" || true
states="$(sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' "$out/audit.jsonl" | tr '\n' ' ')"
check "AudioPlane audit CAPTURE CONVERSE RESTORE IDLE ($states)" "[[ '$states' == 'CAPTURE CONVERSE RESTORE IDLE ' ]]"
check "RESTORE was the session's idle end" "grep '\"state\":\"RESTORE\"' '$out/audit.jsonl' | grep -q 'session: idle'"
if (( speaker )); then
    check "reply audio was played" "grep -q 'speaker done turnId=$turn bytes=[1-9]' '$out/logcat.txt'"
else
    bytes="$(adb shell "run-as com.vos.actuator sh -c 'wc -c < files/debug/speaker-$turn.pcm'" | tr -d '\r ')"
    check "reply audio reached the actuator (${bytes:-0} bytes)" "(( ${bytes:-0} > 16000 ))"
fi

adb shell "run-as com.vos.session cat files/egress/requests.jsonl" | tr -d '\r' > "$out/egress-all.jsonl" || true
grep -F "\"turn_id\":\"$turn\"" "$out/egress-all.jsonl" > "$out/egress.jsonl" || true
check "egress: one WebSocket to api.x.ai for this turn" \
    "[[ \$(wc -l < '$out/egress.jsonl') == 1 ]] && grep -q '\"kind\":\"ws\",\"host\":\"api.x.ai\"' '$out/egress.jsonl'"
check "egress: no host but api.x.ai, ever" "! grep -v '\"host\":\"api.x.ai\"' '$out/egress-all.jsonl' | grep -q ."

keyfile="$(mktemp)"; printf '%s\n' "$GROK_API_KEY" > "$keyfile"
check "the key is not in logcat" "! adb logcat -d | grep -q -F -f '$keyfile'"
rm -f "$keyfile"
check "no transcript text in logcat" "! grep -q -i 'what time is it' '$out/logcat.txt'"
check "M1-KEY-01: no key prefix in the actuator's data" "[[ -z \$(adb shell \"run-as com.vos.actuator sh -c 'grep -r -l xai- . 2>/dev/null'\" | tr -d '\r') ]]"
check "M1-KEY-01: no key prefix in the actuator APK" "! unzip -p '$ROOT/actuator/build/outputs/apk/debug/actuator-debug.apk' | grep -a -q 'xai-'"

aud02="$(adb shell am instrument -w -r -e probe mic com.vos.session.test/com.vos.eval.auth.AuthProbe | tr -d '\r')"
echo "$aud02" | grep -E "INSTRUMENTATION_RESULT: (outcome|requested|granted)" | sed 's/^/  /'
check "M1-AUD-02: the session cannot record" "grep -q 'INSTRUMENTATION_RESULT: pass=true' <<<\"\$aud02\""

adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug'"
adb uninstall com.vos.session.test >/dev/null || true
if (( fails == 0 )); then echo "voice-roundtrip: PASS"; else echo "voice-roundtrip: FAIL ($fails)"; exit 1; fi
