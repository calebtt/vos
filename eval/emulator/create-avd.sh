#!/usr/bin/env bash
# Creates an emulator-lane AVD (eval/README.md, "Emulator lane", #314):
#   35 -> vos_emu_lane_api35 from system-images;android-35;default;x86_64
#   36 -> vos_emu_lane_api36 from system-images;android-36;default;x86_64
# "default" is the AOSP image: no Google APIs, no Play. It installs the image and the emulator with
# sdkmanager if the SDK lacks them (sdkmanager asks about licences itself), then runs avdmanager.
# An AVD that already exists is left as it is (exit 0) unless --force, which recreates it and
# discards its data. This script never calls adb and never touches a device.
#
# Usage: eval/emulator/create-avd.sh [--force] [--dry-run] [--device PROFILE] 35|36
#   The SDK is $ANDROID_HOME, else $ANDROID_SDK_ROOT. --device is an avdmanager hardware profile
#   (default pixel_7). --dry-run prints the commands and runs none of them.

set -euo pipefail

usage() { echo "usage: $0 [--force] [--dry-run] [--device PROFILE] 35|36" >&2; exit 2; }

force=0 dry=0 device=pixel_7 api=""
while (( $# )); do
    case "$1" in
        --force) force=1 ;;
        --dry-run) dry=1 ;;
        --device) [[ $# -ge 2 ]] || usage; device="$2"; shift ;;
        -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        35|36) [[ -z $api ]] || usage; api="$1" ;;
        *) usage ;;
    esac
    shift
done
[[ -n $api ]] || usage

name="vos_emu_lane_api$api"
image="system-images;android-$api;default;x86_64"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n $sdk && -d $sdk ]] || { echo "set ANDROID_HOME (or ANDROID_SDK_ROOT) to the Android SDK" >&2; exit 2; }

# find_tool NAME: the command-line tool in the SDK (cmdline-tools/latest first), else on PATH.
find_tool() {
    local c
    for c in "$sdk/cmdline-tools/latest/bin/$1" "$sdk"/cmdline-tools/*/bin/"$1"; do
        if [[ -x $c ]]; then echo "$c"; return 0; fi
    done
    command -v "$1" || { echo "$1 not found: install the SDK command-line tools" >&2; return 1; }
}
sdkmanager="$(find_tool sdkmanager)"
avdmanager="$(find_tool avdmanager)"

# run CMD...: prints the command; runs it unless --dry-run.
run() {
    printf '+'; printf ' %q' "$@"; printf '\n'
    if (( ! dry )); then "$@"; fi
}

existing="$("$avdmanager" list avd -c | tr -d '\r')" # read whole: grep -q in the pipe would trip pipefail
if grep -qxF "$name" <<<"$existing"; then
    if (( ! force )); then
        echo "$name already exists; left as it is (--force recreates it and discards its data)"
        exit 0
    fi
    echo "$name exists; --force: recreating it"
fi

packages=()
[[ -d "$sdk/system-images/android-$api/default/x86_64" ]] || packages+=("$image")
[[ -x "$sdk/emulator/emulator" ]] || packages+=(emulator)
if (( ${#packages[@]} )); then run "$sdkmanager" --sdk_root="$sdk" --install "${packages[@]}"; fi

# avdmanager asks "create a custom hardware profile?"; its stdin is closed, which answers no.
create=("$avdmanager" create avd --name "$name" --package "$image" --device "$device")
if (( force )); then create+=(--force); fi
run "${create[@]}" </dev/null

if (( dry )); then echo "dry run: nothing was created"; else echo "created $name ($image, $device)"; fi
