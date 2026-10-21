#!/usr/bin/env bash
# The assistant role and the assist gesture (#256, decision 45, S-07). Makes VOS the Digital
# assistant on Phone B (no restore of the previous holder: Caleb, 2026-10-20), then:
#   1. checks the holder, the voice interaction service and the role audit line;
#   2. runs N assist summons (KEYCODE_ASSIST, then KEYCODE_ASSIST again to stop), in whatever lock
#      state the phone is in, and summarizes VOS's own S-07 line: the assist session showing to the
#      first non-zero mic buffer, with the mic service, the AudioPlane and the recording steps along
#      the way (the system's gesture handling before onShow is not counted);
#   3. with --stand-in PKG: moves the role to that app, expects the lost-role notice and a
#      held=false audit line within 5 s, clears the role (no holder) and expects the same, then
#      gives the role back to VOS and expects the notice gone.
# Each summon opens a real turn with the live mic and the session on the xAI backend (nobody speaks,
# so VOS stays silent; the debug echo backend would play the room back, so the run checks the pick).
# It runs 08:00-22:00 only, like the other live-mic steps. Needs GROK_API_KEY only if the session
# holds no key yet (sealed through run-as stdin, never printed), like voice-roundtrip.sh.
#
# Usage: eval/m1/assistant.sh [--no-build] [--trials N] [--stand-in PKG]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build=1 trials=20 standin=""
while (( $# )); do
    case "$1" in
        --no-build) build=0 ;;
        --trials) trials="$2"; shift ;;
        --stand-in) standin="$2"; shift ;;
        *) echo "usage: $0 [--no-build] [--trials N] [--stand-in PKG]" >&2; exit 2 ;;
    esac
    shift
done
h=$((10#$(date +%H)))
(( h >= 8 && h < 22 )) || { echo "live-mic summons: daytime only (08:00-22:00)" >&2; exit 2; }

stamp="$(date +%Y-%m-%d)/assistant-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1
echo "assistant on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :actuator:assembleDebug :session:assembleDebug
fi
adb install -r -t "$ROOT/actuator/build/outputs/apk/debug/actuator-debug.apk" >/dev/null
adb install -r -t "$ROOT/session/build/outputs/apk/debug/session-debug.apk" >/dev/null
adb shell pm grant com.vos.actuator android.permission.RECORD_AUDIO
adb shell "run-as com.vos.session rm -f files/debug/chat_voice"
if ! adb shell "run-as com.vos.session test -f files/keys/xai.bin"; then
    : "${GROK_API_KEY:?the session holds no key: set GROK_API_KEY to seal it (never printed)}"
    printf '%s' "$GROK_API_KEY" | adb shell "run-as com.vos.session sh -c 'umask 077 && mkdir -p files/key-import && cat > files/key-import/xai'"
fi

fails=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; fails=$((fails + 1)); }
holder() { adb shell cmd role get-role-holders android.app.role.ASSISTANT | tr -d '\r'; }
notice() { adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' | grep -c "VOS is not your assistant" || true; }
last_role_audit() { adb shell "run-as com.vos.actuator cat files/audit/audit.jsonl" | tr -d '\r' | grep '"role.assistant"' | tail -1; }
turn_open() { adb shell dumpsys activity services com.vos.actuator | grep -c "TurnService" || true; }
# Waits up to 5 s for the notice to reach the wanted count (1 shown, 0 gone).
wait_notice() { for _ in $(seq 25); do [ "$(notice)" -eq "$1" ] && return 0; sleep 0.2; done; return 1; }

# Setup (Tier S, debug): the notice needs notification permission, a required setup step on a stock phone.
adb shell pm grant com.vos.actuator android.permission.POST_NOTIFICATIONS
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.vos.actuator 0
sleep 2
if [ "$(holder)" = com.vos.actuator ]; then pass "VOS holds the assistant role"; else fail "holder is '$(holder)'"; fi
vis="$(adb shell settings get secure voice_interaction_service | tr -d '\r')"
if [ "$vis" = com.vos.actuator/.VosAssistService ]; then pass "voice interaction service is $vis"; else fail "voice interaction service is $vis"; fi
if last_role_audit | grep -q '"held":true'; then pass "role audit line: $(last_role_audit)"; else fail "no held=true role audit line"; fi
if wait_notice 0; then pass "no lost-role notice while VOS holds the role"; else fail "lost-role notice shown while VOS holds the role"; fi

locked="$(adb shell dumpsys window | tr -d '\r' | grep -oE 'isKeyguardShowing=[a-z]+' | tail -1 | cut -d= -f2)"  # grep reads it all: -m1 would trip pipefail
echo "== $trials assist summons, keyguard showing: $locked"
adb logcat -c
for _ in $(seq "$trials"); do
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell input keyevent KEYCODE_ASSIST
    sleep 2.5
    adb shell input keyevent KEYCODE_ASSIST
    sleep 2
done
adb logcat -d -s VOS_TURN | tr -d '\r' | grep "summon source=assist" > "$out/summons.log" || true
n="$(wc -l < "$out/summons.log")"
if [ "$n" -eq "$trials" ]; then pass "$n/$trials assist summons opened a turn and heard the mic"; else fail "$n/$trials assist summons heard the mic"; fi
if [ "$(turn_open)" -eq 0 ]; then pass "no turn left open (each second press stopped it)"; else fail "a turn is still open"; fi
picks="$(adb shell "run-as com.vos.session cat files/audit/audit.jsonl" | tr -d '\r' | grep '"event":"model.pick"' | tail -n "$trials" || true)"
if [ "$(grep -c '"model":"xai:' <<<"$picks")" -eq "$trials" ]; then pass "every summon's turn ran on the xAI backend"; else fail "not every turn ran on xAI: $(grep -o '"model":"[^"]*"' <<<"$picks" | sort | uniq -c | tr '\n' ' ')"; fi
python3 -I - "$out/summons.log" "$locked" <<'EOF'
import re, sys
rows = [dict(re.findall(r"(\w+)=(-?\d+)", l)) for l in open(sys.argv[1])]
if not rows:
    sys.exit(0)
def pct(vals, p):
    v = sorted(vals)
    return v[max(0, min(len(v) - 1, int(round(p / 100 * len(v) + 0.5)) - 1))]
print(f"S-07 assist, VOS, keyguard showing={sys.argv[2]}, n={len(rows)} (ms from the assist session showing)")
for k in ("to_start_command_ms", "to_foreground_ms", "to_plane_ms", "to_recording_ms", "first_nonzero_ms"):
    v = [int(r[k]) for r in rows if k in r]
    print(f"  {k:22s} p50 {pct(v, 50):5d}  p95 {pct(v, 95):5d}  max {max(v):5d}")
EOF

if [ -n "$standin" ]; then
    echo "== lost role: $standin takes it, then no holder, then VOS again"
    adb shell cmd role add-role-holder android.app.role.ASSISTANT "$standin" 0
    if wait_notice 1; then pass "notice within 5 s of $standin taking the role"; else fail "no notice after $standin took the role"; fi
    if last_role_audit | grep -q '"held":false'; then pass "held=false audited"; else fail "no held=false audit line"; fi
    adb shell cmd role remove-role-holder android.app.role.ASSISTANT "$standin" 0
    sleep 1
    echo "holder after remove: '$(holder)'"
    if [ -z "$(holder)" ]; then
        if wait_notice 1; then pass "notice stays with no holder"; else fail "no notice with no holder"; fi
    fi
    adb shell cmd role add-role-holder android.app.role.ASSISTANT com.vos.actuator 0
    if wait_notice 0; then pass "notice gone once VOS holds the role again"; else fail "notice still shown"; fi
    if last_role_audit | grep -q '"held":true'; then pass "held=true audited"; else fail "no held=true audit line"; fi
fi

if (( fails == 0 )); then echo "assistant: PASS"; else echo "assistant: FAIL ($fails)"; exit 1; fi
