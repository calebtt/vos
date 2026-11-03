#!/usr/bin/env bash
# Echo baseline for barge-in on the phone speaker (#249): does VOS's own voice on the loudspeaker
# make the vendor's VAD think the owner is talking? Each of N turns opens the live mic as in
# production (debug mic_file_then_live), sends a fixture question in place of the mic's first frames
# ("tell me a story, about twenty seconds"), then the room. The reply plays on the phone speaker.
# Nobody speaks, so every vendor "speech started" after the reply began is VOS hearing itself (or the
# room). It is a false barge-in when it cut the reply: it dropped queued reply audio, or the response
# it fell in ended cancelled. Logs per turn: AEC and NS enabled, the input and output devices, the
# reply length, speech starts and false barge-ins; then the rate per reply against decision 31's
# target (<= 2 %). It also measures the echo directly, from the actuator's debug "mic level" lines
# (one per second: the live mic's dBFS and the reply bytes played meanwhile): the median level in
# seconds while VOS plays (>= 0.8 s of reply) against seconds with nothing playing (the room), the
# difference, and the convergence time (seconds of reply until a second is within 3 dB of the room;
# "never" if none is). A false barge-in count of zero says little unless the echo level is low too. --raw-mic is the control: the same turns recorded
# from MIC with no AEC or NS, to show the test can see echo at all.
#
# Plays sound: 08:00-22:00 only, in a quiet room. Needs GROK_API_KEY (the fixture; sealing the key
# into the session if it holds none) and python3 with `websockets`, like voice-roundtrip.sh. The
# volumes are logged, not changed.
#
# Usage: eval/m1/echo-barge.sh [--no-build] [--replies N] [--raw-mic]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/eval/m1/lib.sh"
build=1 replies=10 raw=false
while (( $# )); do
    case "$1" in
        --no-build) build=0 ;;
        --replies) replies="$2"; shift ;;
        --raw-mic) raw=true ;;
        *) echo "usage: $0 [--no-build] [--replies N] [--raw-mic]" >&2; exit 2 ;;
    esac
    shift
done
: "${GROK_API_KEY:?set GROK_API_KEY (used for the fixture; never printed)}"
vos_daytime_only "plays VOS's replies on the speaker"

vos_run_dir "echo-barge$([ "$raw" = true ] && echo -raw)"
echo "echo-barge$([ "$raw" = true ] && echo " (control: MIC, no AEC or NS)") on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint)); results in $out"

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain :actuator:assembleDebug :session:assembleDebug
fi
vos_install_apps
vos_grant_mic
vos_clear_debug_overrides
vos_seal_key_if_missing
volume() { vos_volume "$1" | sed 's/ / of /'; }
echo "volumes: media $(volume 3), voice call $(volume 0), assistant playback uses USAGE_ASSISTANT"

python3 -I "$ROOT/eval/m1/make_fixture.py" "Please tell me a calm story about a lighthouse keeper. Make it about twenty seconds long." "$out/ask.pcm"
adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$out/ask.pcm"
# On any exit (Ctrl-C, a failed command): stop a turn that may still be playing, then remove the fixture.
trap 'adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null 2>&1 || true
      adb shell "run-as com.vos.actuator sh -c \"rm -rf files/debug\"" >/dev/null 2>&1 || true' EXIT

printf '%-4s %-36s %-5s %-5s %-6s %-16s %-8s %-6s %-9s %-9s %-9s %-6s %-6s %s\n' n turnId aec ns inDev outDev reply_s starts barge_ins echo_dbfs room_dbfs above conv_s reason
total_starts=0 total_cancelled=0 replied=0
: > "$out/levels.tsv"
for i in $(seq "$replies"); do
    h=$((10#$(date +%H)))
    if (( h < 8 || h >= 22 )); then
        echo "22:00 reached: stopping after $((i - 1)) replies"
        replies=$((i - 1))
        break
    fi
    adb logcat -c
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op start --ez mic_file_then_live true --ez raw_mic "$raw" >/dev/null
    over=0
    for _ in $(seq 1 600); do  # 120 s
        adb logcat -d -s VOS_VOICE | grep -q " over after_ms=" && { over=1; break; }
        sleep 0.2
    done
    if (( ! over )); then
        adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op stop >/dev/null
        sleep 2
    fi
    sleep 1
    log="$out/turn-$i.txt"
    adb logcat -d -s VOS_VOICE VOS_TURN | tr -d '\r' > "$log"
    turn="$(sed -n 's/.*turn opened in session turnId=\([0-9a-f-]*\).*/\1/p' "$log" | head -1)"
    turn="${turn:-?}"
    aec="$(sed -n 's/.*capture turnId=[^ ]* source=[^ ]* aec=\([a-z]*\).*/\1/p' "$log" | head -1)"
    ns="$(sed -n 's/.*capture turnId=.* ns=\([a-z]*\).*/\1/p' "$log" | head -1)"
    indev="$(sed -n 's/.*capture turnId=.* inDev=\([A-Za-z0-9_]*\).*/\1/p' "$log" | head -1)"
    outdev="$(adb shell "run-as com.vos.actuator cat files/audit/audit.jsonl" | tr -d '\r\000' | grep -F "\"turnId\":\"$turn\"" \
        | grep '"state":"CAPTURE"' | sed -n 's/.*"outDev":"\([A-Z_]*\)".*/\1/p' | head -1 || true)"
    bytes="$(sed -n 's/.*speaker done turnId=[^ ]* bytes=\([0-9]*\).*/\1/p' "$log" | head -1)"
    reply_s="$(awk -v b="${bytes:-0}" 'BEGIN { printf "%.1f", b / 32000 }')"
    # After the first reply audio nobody is talking, so every speech start is VOS heard back (or the
    # room). It is a false barge-in when it cut the reply: it dropped queued reply audio, or the
    # response it fell in ended cancelled. A start after the reply has fully played interrupts nothing.
    read -r starts cancelled < <(python3 -I - "$log" <<'PY'
import sys
lines = [l for l in open(sys.argv[1])]
first = next((i for i, l in enumerate(lines) if "first reply audio" in l and "VOS_VOICE" in l), None)
starts = barges = 0
if first is not None:
    after = lines[first + 1:]
    for i, l in enumerate(after):
        if " speech started" not in l:
            continue
        starts += 1
        cut = "dropped" in l
        for m in after[i + 1:]:
            if " speech started" in m:
                break
            if "response done status=" in m:
                cut = cut or "status=cancelled" in m
                break
        barges += cut
print(starts, barges)
PY
)
    reason="$(sed -n 's/.* over after_ms=[0-9]* reason=//p' "$log" | head -1)"
    # Median mic level while VOS plays (>= 0.8 s of reply in that second) and with nothing playing.
    read -r echo_db room_db above conv < <(python3 -I "$ROOT/eval/m1/echo_levels.py" "$log")
    printf '%s\t%s\t%s\t%s\n' "$echo_db" "$room_db" "$above" "$conv" >> "$out/levels.tsv"
    grep -q "first reply audio" "$log" && replied=$((replied + 1))
    total_starts=$((total_starts + starts)) total_cancelled=$((total_cancelled + cancelled))
    printf '%-4s %-36s %-5s %-5s %-6s %-16s %-8s %-6s %-9s %-9s %-9s %-6s %-6s %s\n' "$i" "$turn" "${aec:-?}" "${ns:-?}" "${indev:-?}" \
        "${outdev:-?}" "$reply_s" "$starts" "$cancelled" "$echo_db" "$room_db" "$above" "$conv" "${reason:-no end (stopped)}"
    sleep 3
done

echo "replies: $replied of $replies turns; speech starts after the reply began: $total_starts; of them false barge-ins (cut the reply): $total_cancelled"
if (( replied == 0 )); then
    echo "echo-barge: FAIL (no turn got a reply; nothing measured)"
    exit 1
fi
rate="$(awk -v c="$total_cancelled" -v r="$replied" 'BEGIN { printf "%.0f", 100 * c / r }')"
if (( total_cancelled * 50 <= replied )); then verdict="met"; else verdict="not met"; fi
echo "false barge-ins: $total_cancelled per $replied replies ($rate %); decision 31 target <= 2 %: $verdict"
python3 -I - "$out/levels.tsv" <<'PY'
import statistics, sys
rows = [l.split() for l in open(sys.argv[1]) if l.strip()]
vals = lambda i: [float(r[i]) for r in rows if r[i] != "?"]
for name, i in (("mic level while VOS plays (dBFS)", 0), ("room level (dBFS)", 1), ("echo above the room (dB)", 2)):
    v = vals(i)
    print(f"{name}: median {statistics.median(v):.1f} over {len(v)} turns" if v else f"{name}: no data")
conv = [r[3] for r in rows if len(r) > 3]
print("convergence (s of reply until within 3 dB of the room), per turn: " + " ".join(conv))
PY
echo "echo-barge: DONE (a report, not a gate)"
