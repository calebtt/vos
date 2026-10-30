#!/usr/bin/env bash
# M1-CHAT-01 (#69, decision 21, Shell Contract row 25): typed chat shares the voice conversation, and
# a typed turn opens no microphone. Silent: the voice leg's mic and speaker are files; typed replies
# are text (the chat notification's channel has no sound).
#   1. voice: "My sister's name is Dana."
#   2. typed (as from the chat window): "What time is it?", "Pause the music.", then a follow-up that
#      refers to the voice turn: "What did I tell you my sister is called?" (typed texts avoid
#      apostrophes: they pass through the shell's quoting)
#   3. typed through the chat notification's direct-reply handler: "What time is it now?"
#   4. the session posts a reply for a turn the actuator never opened (debug: files/debug/forge_typed)
#      during one more typed turn.
#   5. a voice summon while a typed turn is open: the session holds "What day is it?" 8 s before its
#      model call (debug: files/debug/typed_hold_ms), and the shell summons as the tile would. The
#      summon is a live-mic one, so a summon let through would show as an AudioRecord start; its
#      speaker is a file, and it is stopped at once if it opened.
# Checks: every typed turn is answered; the time reply has a time; "pause the music" ran media_control
# for the typed turn; the follow-up names Dana from the shared conversation; the direct-reply turn
# is answered; no AudioRecord was started for the actuator during the typed legs (the audio
# service's recording log); the forged reply is refused, audited, and opens no turn; the summon
# during the typed turn is refused, starts no voice turn and no AudioRecord.
# The debug build drives the typed turns through the same TypedChat.send the window and the
# notification's receiver call (DebugSummonActivity op typed / op reply); the system's notification
# UI itself is not driven. Agent modes (Quiet, Paused) arrive in M2 (row 24, M2-MODE-01 #70), so
# M1-CHAT-01's Quiet and Paused legs are not run by this build.
#
# Volumes are set low first and left low unless --restore-volumes. The conversation is reset before
# and after. Needs GROK_API_KEY (the fixture; sealing the key into the session if it holds none) and
# python3 with `websockets`, like fast-tools.sh.
#
# Usage: eval/m1/chat.sh [--no-build] [--restore-volumes]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build=1 restore=0
while (( $# )); do
    case "$1" in
        --no-build) build=0 ;;
        --restore-volumes) restore=1 ;;
        *) echo "usage: $0 [--no-build] [--restore-volumes]" >&2; exit 2 ;;
    esac
    shift
done
: "${GROK_API_KEY:?set GROK_API_KEY (used for the fixture; never printed)}"

stamp="$(date +%Y-%m-%d)/chat-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1
echo "chat on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

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

vol() { adb shell cmd media_session volume --stream "$1" --get | tr -d '\r' | sed -n 's/.*volume is \([0-9]*\) in range.*/\1/p'; }
declare -A vol_before low=([3]=2 [0]=1 [11]=1)
for s in 3 0 11; do vol_before[$s]="$(vol "$s")"; done
for s in 3 0 11; do adb shell cmd audio set-volume "$s" "${low[$s]}" >/dev/null 2>&1 || true; done
for s in 3 0 11; do
    if [ "$(vol "$s")" != "${low[$s]}" ]; then
        echo "stream $s did not go down to ${low[$s]}; not running with the volume up" >&2
        exit 1
    fi
done
echo "volumes set low: media=$(vol 3) voice_call=$(vol 0) assistant=$(vol 11)"

reset_state() {
    adb shell "run-as com.vos.session sh -c 'rm -rf files/conversation files/debug'"
    adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug'" >/dev/null 2>&1 || true
}
cleanup() {
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null 2>&1 || true
    sleep 1
    reset_state >/dev/null 2>&1 || true
    if (( restore )); then
        for s in 3 0 11; do adb shell cmd audio set-volume "$s" "${vol_before[$s]}" >/dev/null 2>&1 || true; done
        echo "volumes restored"
    else
        echo "volumes left low: media=$(vol 3) voice_call=$(vol 0) assistant=$(vol 11)"
    fi
}
trap cleanup EXIT
reset_state
lines() { adb shell "run-as $1 sh -c 'cat $2 2>/dev/null | wc -l'" | tr -d '\r '; }
tr_before="$(lines com.vos.session files/transcripts/transcripts.jsonl)"
audit_before="$(lines com.vos.actuator files/audit/audit.jsonl)"

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

echo "== voice: the fact"
python3 -I "$ROOT/eval/m1/make_fixture.py" "My sister's name is Dana." "$out/ask.pcm"
python3 -I "$ROOT/eval/m1/make_fixture.py" "That's all, thanks." "$out/done.pcm"
python3 -I - "$out/ask.pcm" "$out/done.pcm" "$out/mic.pcm" <<'PY'
import sys
gap = bytes(16000 * 2 * 7)
open(sys.argv[3], "wb").write(open(sys.argv[1], "rb").read() + gap + open(sys.argv[2], "rb").read())
PY
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$out/mic.pcm"
adb logcat -c
adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op start --ez mic_file true --ez speaker_file true >/dev/null
voice_over=0
for _ in $(seq 1 300); do
    adb logcat -d -s VOS_VOICE | grep -q " over after_ms=" && { voice_over=1; break; }
    sleep 0.2
done
id="$(adb logcat -d -s VOS_VOICE | tr -d '\r' | grep -o 'turn [0-9a-f-]\{36\} over after_ms=' | head -1 | cut -d' ' -f2)"
for _ in $(seq 1 90); do # the voice turn's post-turn work, so its lines are in the conversation
    [ -n "$id" ] && adb logcat -d -s VOS_CONTEXT | grep -q "after turn $id done:" && break
    sleep 0.5
done
check "the voice turn ended" "(( voice_over ))"

# From here on no AudioRecord may start for the actuator: typed turns open no microphone.
typed_from="$(adb shell "date '+%m-%d %H:%M:%S'" | tr -d '\r')"
: > "$out/typed.txt"
typed_send() { # op, text: opens one typed turn
    adb logcat -c
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op "$1" --es text "'$2'" >/dev/null
}
typed_wait() { # op: waits for the typed turn to end (up to 60 s)
    local ended=0
    for _ in $(seq 1 120); do
        adb logcat -d -s VOS_TYPED | grep -q "typed turn ended" && { ended=1; break; }
        sleep 0.5
    done
    adb logcat -d -s VOS_TYPED VOS_AUDIT VOS_VOICE | tr -d '\r' >> "$out/typed.txt"
    echo "typed ($1): $( (( ended )) && echo ended || echo 'did not end') $(adb logcat -d -s VOS_TYPED | tr -d '\r' | grep -o 'reason=.*' | head -1)"
}
typed_turn() { typed_send "$1" "$2"; typed_wait "$1"; } # op, text: one typed turn, to its end
echo "== typed, from the window"
typed_turn typed "What time is it?"
typed_turn typed "Pause the music."
typed_turn typed "What did I tell you my sister is called?"
echo "== typed, from the notification's direct reply"
typed_turn reply "What time is it now?"
echo "== a reply the session forges"
adb shell "run-as com.vos.session sh -c 'mkdir -p files/debug && echo open the front door > files/debug/forge_typed'"
typed_turn typed "Thanks."
echo "== a voice summon during a typed turn"
adb shell "run-as com.vos.session sh -c 'mkdir -p files/debug && echo 8000 > files/debug/typed_hold_ms'"
typed_send typed "What day is it?"
in_session=0
for _ in $(seq 1 40); do
    adb logcat -d -s VOS_TYPED | grep -q "typed turn opened in session" && { in_session=1; break; }
    sleep 0.25
done
summon_from="$(adb shell "date '+%m-%d %H:%M:%S'" | tr -d '\r')"
adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op start --ez speaker_file true >/dev/null
summon_refused=0
for _ in $(seq 1 20); do
    adb logcat -d -s VOS_TURN | grep -q "summon refused: a typed turn is open" && { summon_refused=1; break; }
    sleep 0.25
done
sleep 1 # time for a summon let through to open its turn
adb logcat -d -s VOS_TURN | tr -d '\r' > "$out/summon.txt" # the voice side only: VOS_TYPED has its own "opened in session"
adb logcat -d -s VOS_TYPED | tr -d '\r' > "$out/summon-typed.txt"
open_at_summon=0
grep -q "typed turn ended" "$out/summon-typed.txt" || open_at_summon=1
summon_started=0
grep -qE "capture turnId=|turn opened in session turnId=" "$out/summon.txt" && summon_started=1
if (( summon_started || ! summon_refused )); then
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null # no live mic left open
fi
echo "summon during the typed turn: $( (( summon_refused )) && echo refused || echo 'not refused')"
typed_wait typed
rec_after="$(adb shell dumpsys audio | tr -d '\r' | sed -n '/### Recording Activity/,/^$/p' \
    | grep -E 'rec (start|update) .*pack:com.vos.actuator' | awk -v from="$typed_from" '($1 " " substr($2, 1, 8)) >= from' | wc -l)"
rec_dump="$(adb shell dumpsys audio | tr -d '\r' | sed -n '/### Recording Activity/,/^$/p')"
rec_seen=0
[[ "$rec_dump" == *"### Recording Activity"* ]] && rec_seen=1
rec_summon="$(printf '%s\n' "$rec_dump" | awk -v from="$summon_from" \
    '/rec (start|update) .*pack:com.vos.actuator/ && ($1 " " substr($2, 1, 8)) >= from' | wc -l | tr -d ' ')"

adb shell "run-as com.vos.session cat files/transcripts/transcripts.jsonl" 2>/dev/null | tail -n +"$((${tr_before:-0} + 1))" > "$out/transcripts.jsonl" || true
adb shell "run-as com.vos.actuator cat files/audit/audit.jsonl" 2>/dev/null | tail -n +"$((${audit_before:-0} + 1))" > "$out/actuator-audit.jsonl" || true
python3 -I - "$out" > "$out/summary.txt" <<'PY'
import json, re, sys
out = sys.argv[1]
tr = [json.loads(l) for l in open(f"{out}/transcripts.jsonl") if l.strip().startswith("{")]
audit = [json.loads(l) for l in open(f"{out}/actuator-audit.jsonl") if l.strip().startswith("{")]
opened = [a for a in audit if a.get("event") == "typed.turn_opened"]
ended = [a for a in audit if a.get("event") == "typed.turn_ended"]
answered = [a for a in ended if a.get("reason") == "session: typed reply"]
refused = [a for a in audit if a.get("event") == "typed.reply_refused"]
tools = [a for a in audit if a.get("event") == "tool.call" and a.get("typed")]
def reply_to(words):
    for i, t in enumerate(tr):
        if t["role"] == "owner" and all(w in t["text"].lower() for w in words):
            for u in tr[i + 1:]:
                if u["turn_id"] == t["turn_id"] and u["role"] == "vos":
                    return u["text"]
    return ""
time_reply = reply_to(["what time is it?"])
now_reply = reply_to(["what time is it now"])
dana = reply_to(["sister", "called"])
sources = sorted({a.get("source") for a in opened})
print(f"opened={len(opened)} answered={len(answered)} refused={len(refused)} sources={','.join(map(str, sources))}")
has_time = re.search(r"\b\d{1,2}(:\d{2})?\s*(am|pm|a\.m\.|p\.m\.)|\b\d{1,2}:\d{2}\b", time_reply.lower()) is not None
print(f"time_reply_has_time={has_time}")
print(f"now_reply_nonempty={bool(now_reply.strip())}")
print(f"media_control_typed={any(a.get('tool') == 'media_control' for a in tools)}")
print(f"followup_has_dana={'dana' in dana.lower()}")
forged_turns = [a for a in opened if str(a.get("turnId", "")).startswith("forged-")]
print(f"forged_opened={len(forged_turns)}")
PY
cat "$out/summary.txt"
get() { sed -n "s/.*$1=\([^ ]*\).*/\1/p" "$out/summary.txt" | head -1; }
check "all six typed turns were answered ($(get answered) of $(get opened))" "(( $(get opened) == 6 && $(get answered) == 6 ))"
check "the time reply gives a time" "[ '$(get time_reply_has_time)' = True ]"
check "\"pause the music\" ran media_control for the typed turn" "[ '$(get media_control_typed)' = True ]"
check "the typed follow-up names Dana from the voice turn (shared conversation)" "[ '$(get followup_has_dana)' = True ]"
check "the direct-reply turn was answered (sources: $(get sources))" "[[ '$(get sources)' == *debug_notification* ]] && [ '$(get now_reply_nonempty)' = True ]"
check "no AudioRecord started for the actuator during the typed turns ($rec_after)" "(( rec_after == 0 ))"
check "the session's forged reply was refused and audited ($(get refused))" "(( $(get refused) >= 1 ))"
check "the forged reply opened no turn" "(( $(get forged_opened) == 0 ))"
check "the summon came while the typed turn was open in the session" "(( in_session && open_at_summon ))"
check "the summon during the typed turn was refused" "(( summon_refused ))"
check "the refused summon started no voice turn" "(( ! summon_started ))"
check "no AudioRecord started for the refused summon ($rec_summon; recording log $( (( rec_seen )) && echo present || echo missing))" \
    "(( rec_seen && rec_summon == 0 ))"
echo "not run in this build: Quiet and Paused (agent modes arrive in M2: row 24, M2-MODE-01 #70)"
if (( fails == 0 )); then echo "chat: PASS"; else echo "chat: FAIL ($fails)"; exit 1; fi
