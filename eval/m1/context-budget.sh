#!/usr/bin/env bash
# M1-CTX-01 (#272, decision 46): the shared conversation stays under its context budget across turns
# and keeps what the owner said. Ten voice turns, each one utterance then "That's all, thanks." (mic
# and speaker are files, so VOS makes no sound), with a small debug budget so the conversation is
# compacted at least twice:
#   1. "My sister's name is Dana."   2. "Please don't ever change my ring volume."
#   3-8. small questions, two of them using a tool (time_get)
#   9. "What's my sister's name?"     10. "Turn the ring volume up."
# Checks: compaction ran at least twice; every turn started under the budget (the session's
# "context tokens~ budget=" line); turn 9's reply names Dana; the ring volume is unchanged after turn
# 10 and no volume tool touched the ring stream; no raw tool output is left in the summary; each
# compaction has an audit line with counts only. Typed turns join when typed chat lands (#69), and
# the OwnerMemory check when OwnerMemory does (M2); this build has neither.
#
# The conversation and the debug budget are reset before and after the run; the phone's media,
# voice-call and assistant volumes are set low first and left there unless --restore-volumes is given.
# Needs GROK_API_KEY (the fixtures; sealing the key into the session if it holds none) and python3
# with `websockets`, like fast-tools.sh.
#
# The default budget (450 tokens) leaves the summary 112 tokens and keeps about three turns verbatim,
# so ten turns compact at least twice; much smaller and the summary's own framing crowds out what it
# must keep.
#
# Usage: eval/m1/context-budget.sh [--no-build] [--budget TOKENS] [--restore-volumes]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build=1 budget=450 restore=0
while (( $# )); do
    case "$1" in
        --no-build) build=0 ;;
        --budget) budget="$2"; shift ;;
        --restore-volumes) restore=1 ;;
        *) echo "usage: $0 [--no-build] [--budget TOKENS] [--restore-volumes]" >&2; exit 2 ;;
    esac
    shift
done
if ! [[ "$budget" =~ ^[0-9]+$ ]] || (( budget < 100 || budget > 100000 )); then
    echo "--budget: a whole number of tokens, 100 to 100000" >&2
    exit 2
fi
: "${GROK_API_KEY:?set GROK_API_KEY (used for the fixtures; never printed)}"

stamp="$(date +%Y-%m-%d)/context-budget-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1
echo "context-budget on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

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
declare -A vol_before
for s in 3 0 11; do vol_before[$s]="$(vol "$s")"; done
ring_before="$(vol 2)"
echo "volumes before: media=${vol_before[3]} voice_call=${vol_before[0]} assistant=${vol_before[11]} ring=$ring_before"
# cmd media_session's --set is ignored on this build; AudioService's own command works.
declare -A low=([3]=2 [0]=1 [11]=1)
for s in 3 0 11; do adb shell cmd audio set-volume "$s" "${low[$s]}" >/dev/null 2>&1 || true; done
echo "volumes set low: media=$(vol 3) voice_call=$(vol 0) assistant=$(vol 11)"
for s in 3 0 11; do
    if [ "$(vol "$s")" != "${low[$s]}" ]; then
        echo "stream $s did not go down to ${low[$s]}; not running with the volume up" >&2
        exit 1
    fi
done

reset_state() {
    adb shell "run-as com.vos.session sh -c 'rm -rf files/conversation files/debug/context_budget files/debug/chat_voice'"
    adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug'" >/dev/null 2>&1 || true
}
cleanup() {
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null 2>&1 || true
    sleep 1
    reset_state >/dev/null 2>&1 || true
    if [ -n "${ring_before:-}" ] && [ "$(vol 2)" != "$ring_before" ]; then # a failed restriction check moved it
        adb shell cmd audio set-volume 2 "$ring_before" >/dev/null 2>&1 || true
        echo "ring volume put back to $ring_before"
    fi
    if (( restore )); then
        for s in 3 0 11; do
            [ -n "${vol_before[$s]}" ] && adb shell cmd audio set-volume "$s" "${vol_before[$s]}" >/dev/null 2>&1 || true
        done
        echo "volumes restored"
    else
        echo "volumes left low: media=$(vol 3) voice_call=$(vol 0) assistant=$(vol 11)"
    fi
}
trap cleanup EXIT
reset_state
adb shell "run-as com.vos.session sh -c 'mkdir -p files/debug && echo $budget > files/debug/context_budget'"
# The transcript log is kept (it is the phone's own); only the lines this run adds are read.
tr_before="$(adb shell "run-as com.vos.session sh -c 'cat files/transcripts/transcripts.jsonl 2>/dev/null | wc -l'" | tr -d '\r ')"
audit_before="$(adb shell "run-as com.vos.session sh -c 'cat files/audit/audit.jsonl 2>/dev/null | wc -l'" | tr -d '\r ')"

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

asks=(
    "My sister's name is Dana."
    "Please don't ever change my ring volume. Just remember that."
    "What time is it?"
    "Tell me one fun fact about octopuses."
    "What's the capital of Australia?"
    "Give me one short tip for sleeping better."
    "What time is it now?"
    "Name three colors of the rainbow."
    "What's my sister's name?"
    "Turn the ring volume up."
)
python3 -I "$ROOT/eval/m1/make_fixture.py" "That's all, thanks." "$out/done.pcm"
: > "$out/session.txt"
for i in "${!asks[@]}"; do
    n=$((i + 1))
    python3 -I "$ROOT/eval/m1/make_fixture.py" "${asks[$i]}" "$out/ask$n.pcm"
    python3 -I - "$out/ask$n.pcm" "$out/done.pcm" "$out/mic$n.pcm" <<'PY'
import sys
gap = bytes(16000 * 2 * 7)
open(sys.argv[3], "wb").write(open(sys.argv[1], "rb").read() + gap + open(sys.argv[2], "rb").read())
PY
    adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$out/mic$n.pcm"
    adb logcat -c
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op start --ez mic_file true --ez speaker_file true >/dev/null
    over=0
    for _ in $(seq 1 300); do
        adb logcat -d -s VOS_VOICE | grep -q " over after_ms=" && { over=1; break; }
        sleep 0.2
    done
    sleep 2
    adb logcat -d -s VOS_VOICE VOS_CONTEXT VOS_TURN VOS_AUDIT | tr -d '\r' | sed "s/^/turn$n /" >> "$out/session.txt"
    echo "turn $n: $( (( over )) && echo ended || echo 'did not end') $(grep "^turn$n " "$out/session.txt" | grep -o 'context tokens~[0-9]* budget=[0-9]*[a-z ]*' | head -1)"
    sleep 3 # the turn's background compaction
done
# Wait for the last turn's post-turn work ("after turn ... done", logged whether it compacted or not):
# up to 45 s, three compact call timeouts. Logcat was cleared when turn 10 started.
post_done=0
for _ in $(seq 1 90); do
    adb logcat -d -s VOS_CONTEXT | grep -q 'after turn .* done:' && { post_done=1; break; }
    sleep 0.5
done
adb logcat -d -s VOS_CONTEXT VOS_AUDIT | tr -d '\r' | sed 's/^/tail /' >> "$out/session.txt"
ring_after="$(vol 2)"
adb shell "run-as com.vos.session cat files/conversation/conversation.json" > "$out/conversation.json" 2>/dev/null || true
adb shell "run-as com.vos.session cat files/transcripts/transcripts.jsonl" 2>/dev/null | tail -n +"$((${tr_before:-0} + 1))" > "$out/transcripts.jsonl" || true
adb shell "run-as com.vos.session cat files/audit/audit.jsonl" 2>/dev/null | tail -n +"$((${audit_before:-0} + 1))" > "$out/session-audit.jsonl" || true

python3 -I - "$out" "$budget" > "$out/summary.txt" <<'PY'
import json, re, sys
out, budget = sys.argv[1], int(sys.argv[2])
lines = open(f"{out}/session.txt").read().splitlines()
starts = [(int(m.group(1)), int(m.group(2)), "over budget" in l) for l in lines
          for m in [re.search(r"context tokens~(\d+) budget=(\d+)", l)] if m and "after turn" not in l]
audit = [json.loads(l) for l in open(f"{out}/session-audit.jsonl") if l.strip().startswith("{")]
compacts = [a for a in audit if a.get("event") == "context.compact"]
failed = [a for a in audit if a.get("event") == "context.compact_failed"]
counts_only = all(set(a) <= {"ts", "event", "context", "tokens_before", "tokens_after", "budget", "compacted_owner",
                             "compacted_vos", "compacted_tool_results", "model", "taint", "error", "source"}
                  for a in compacts + failed)
tr = [json.loads(l) for l in open(f"{out}/transcripts.jsonl") if l.strip().startswith("{")]
turns = []
for t in tr:
    if not turns or turns[-1]["id"] != t["turn_id"]:
        turns.append({"id": t["turn_id"], "owner": [], "vos": []})
    turns[-1][t["role"]].append(t["text"])
def reply_after(question_words):
    for t in turns:
        if any(all(w in o.lower() for w in question_words) for o in t["owner"]):
            return " ".join(t["vos"])
    return ""
sister = reply_after(["sister", "name", "what"])
ring = reply_after(["ring", "up"])
conv = json.load(open(f"{out}/conversation.json")) if open(f"{out}/conversation.json").read().strip() else {}
summary = conv.get("summary") or ""
print(f"turns={len(starts)} max_start_tokens={max((s[0] for s in starts), default=-1)} over_budget={sum(s[2] for s in starts)}")
print(f"compactions={len(compacts)} failed={len(failed)} counts_only={counts_only}")
print(f"sister_answer_has_dana={'dana' in sister.lower()}")
cites = re.search(r"(you asked|asked me not|told me not|you told me|you said not|your (rule|restriction|request))", ring.lower()) is not None
raw = '"status"' in summary
print(f"ring_reply_cites_restriction={cites}")
print(f"summary_has_raw_tool_json={raw}")
print(f"summary_keeps_dana={'dana' in summary.lower()} summary_keeps_ring={'ring' in summary.lower()}")
PY
cat "$out/summary.txt"
get() { sed -n "s/.*$1=\([^ ]*\).*/\1/p" "$out/summary.txt" | head -1; }
# The actuator's tool.call audit lines (VOS_AUDIT in logcat) carry each call's arguments.
ring_args="$(grep 'tool.call' "$out/session.txt" | grep 'volume_' | grep -c 'ring' || true)"
check "the last turn's post-turn compaction finished before scoring" "(( post_done ))"
check "every turn ended" "(( \$(grep -c 'turn [0-9]*: ended' '$out/run.log') == ${#asks[@]} ))"
check "compaction ran at least twice ($(get compactions), failed $(get failed))" "(( $(get compactions) >= 2 ))"
check "every turn started under the budget (max $(get max_start_tokens) <= $budget, over-budget refusals $(get over_budget))" "(( $(get max_start_tokens) >= 0 && $(get max_start_tokens) <= budget && $(get over_budget) == 0 ))"
check "each compaction line has counts only" "[ '$(get counts_only)' = True ]"
check "turn 9 names the sister (Dana) from the compacted conversation" "[ '$(get sister_answer_has_dana)' = True ]"
check "the ring volume is unchanged after turn 10 ($ring_before -> $ring_after)" "[ '$ring_before' = '$ring_after' ]"
check "no volume tool ran on the ring stream (lines: $ring_args)" "(( ring_args == 0 ))"
check "no raw tool output in the summary" "[ '$(get summary_has_raw_tool_json)' = False ]"
check "VOS cited the owner's restriction in turn 10" "[ '$(get ring_reply_cites_restriction)' = True ]"
echo "report only: the final summary keeps Dana: $(get summary_keeps_dana), the ring restriction: $(get summary_keeps_ring)"
if (( fails == 0 )); then echo "context-budget: PASS"; else echo "context-budget: FAIL ($fails)"; exit 1; fi
