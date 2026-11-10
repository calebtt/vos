#!/usr/bin/env bash
# Boots an emulator-lane AVD headless on a fixed console port and prints the serial to export
# (eval/README.md, "Emulator lane", #314). The port fixes the serial: port 5584 is emulator-5584.
# The emulator runs in the background with the lane's flags (no window, no audio, no snapshot
# save, software GPU, 4 GB, 4 cores); its output goes to a log file. This script never calls adb:
# it starts one emulator and names that emulator's serial, nothing else.
#
# Usage: eval/emulator/start-avd.sh [--port N] [--dry-run] 35|36|AVD_NAME
#   35 and 36 mean vos_emu_lane_api35 and vos_emu_lane_api36 (eval/emulator/create-avd.sh).
#   --port: an even console port, 5554-5682 (default 5584). It must be free.
#   The SDK is $ANDROID_HOME, else $ANDROID_SDK_ROOT. The log is $VOS_EMU_LOG, else
#   ${TMPDIR:-/tmp}/vos-emu-<port>.log. --dry-run prints the command and starts nothing.

set -euo pipefail

usage() { echo "usage: $0 [--port N] [--dry-run] 35|36|AVD_NAME" >&2; exit 2; }

port=5584 dry=0 avd=""
while (( $# )); do
    case "$1" in
        --port) [[ $# -ge 2 ]] || usage; port="$2"; shift ;;
        --dry-run) dry=1 ;;
        -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) usage ;;
        *) [[ -z $avd ]] || usage; avd="$1" ;;
    esac
    shift
done
[[ -n $avd ]] || usage
case "$avd" in 35|36) avd="vos_emu_lane_api$avd" ;; esac
[[ $avd =~ ^[A-Za-z0-9._-]+$ ]] || { echo "not an AVD name: $avd" >&2; exit 2; }
if ! [[ $port =~ ^[0-9]+$ ]] || (( port < 5554 || port > 5682 || port % 2 )); then
    echo "--port must be an even number from 5554 to 5682" >&2; exit 2
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n $sdk && -d $sdk ]] || { echo "set ANDROID_HOME (or ANDROID_SDK_ROOT) to the Android SDK" >&2; exit 2; }
emulator="$sdk/emulator/emulator"
serial="emulator-$port"
log="${VOS_EMU_LOG:-${TMPDIR:-/tmp}/vos-emu-$port.log}"

cmd=("$emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot-save
    -gpu swiftshader_indirect -memory 4096 -cores 4)

if (( dry )); then
    printf '+'; printf ' %q' "${cmd[@]}"; printf '\n'
    echo "export ANDROID_SERIAL=$serial"
    exit 0
fi

[[ -x $emulator ]] || { echo "no emulator in the SDK: run eval/emulator/create-avd.sh first" >&2; exit 1; }
avds="$("$emulator" -list-avds | tr -d '\r')" # read whole: grep -q in the pipe would trip pipefail
grep -qxF "$avd" <<<"$avds" \
    || { echo "no AVD named $avd: run eval/emulator/create-avd.sh first" >&2; exit 1; }
# The console port and the adb port above it must be free, or the serial would not be ours.
for p in "$port" "$((port + 1))"; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then
        echo "port $p is in use (is an emulator already running as $serial?); pick another --port" >&2
        exit 1
    fi
done

nohup "${cmd[@]}" >"$log" 2>&1 </dev/null &
pid=$!
sleep 3
kill -0 "$pid" 2>/dev/null || { echo "the emulator exited at once; see $log" >&2; exit 1; }
{
    echo "started $avd as $serial (pid $pid, log $log); it is still booting"
    echo "stop it with: kill $pid"
} >&2
echo "export ANDROID_SERIAL=$serial"
