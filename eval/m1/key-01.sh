#!/usr/bin/env bash
# M1-KEY-01 (#133): scan the actuator's data dir and APK (debug build) for the xAI key. Pass: 0 hits.
# The scan covers the APK installed on the device (every zip entry: dex, resources, assets), the
# actuator's credential-encrypted and device-encrypted data dirs and its external files dir, each for
# the key prefix ("xai-") and, when the key is in the environment, for the key itself. Also checked,
# beyond the plan's row: the session holds its key sealed (no plaintext copy anywhere in its data
# dir), and logcat holds no copy. TARGET-PLAN §5.4.
#
# Needs the debug builds installed (run-as) and a session that has sealed its key (any earlier
# voice-roundtrip or assistant run), so there is a key the actuator could have been handed. Makes no
# sound, calls no vendor, and changes nothing on the device. The data copied off the device (audit
# and transcript logs) stays in a private temp dir that is deleted on exit; only counts and file
# names are written to the results.
#
# Usage: eval/m1/key-01.sh
#   ANDROID_SERIAL picks the device. XAI_API_KEY (or GROK_API_KEY) enables the exact-key scan; it
#   is never printed. Results go to $VOS_EVAL_OUT if set, else to
#   $VOS_EVAL_ROOT/<date>/m1-key-01-<time>, else under eval/runs/ in this checkout (gitignored).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if (( $# )); then echo "usage: $0" >&2; exit 2; fi
PREFIX="xai-"

stamp="$(date +%Y-%m-%d)/m1-key-01-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1

work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT

echo "M1-KEY-01 on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint | tr -d '\r')); results in $out"

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

key="${XAI_API_KEY:-${GROK_API_KEY:-}}"
key="${key//[$'\r\n\t ']/}"
keyfile="$work/key"
if [[ -n "$key" ]]; then
    (umask 077 && printf '%s\n' "$key" > "$keyfile")
    echo "exact-key scan: on"
else
    echo "NOTE exact-key scan: off (no XAI_API_KEY or GROK_API_KEY); the prefix scan still runs"
fi
unset key

# hits DIR [PREFIX [KEYFILE]]: the files under DIR that hold the prefix or the key, as UTF-8 or UTF-16, or the
# key in base64 at any alignment (names and forms only). Python, not grep: grep here may be ugrep,
# whose handling of NUL bytes in fixed patterns differs.
hits() {
    python3 -I - "$1" "${2:-$PREFIX}" "${3:-$keyfile}" <<'EOF'
import base64, os, sys
root, prefix, keyfile = sys.argv[1], sys.argv[2], sys.argv[3]
forms = {"prefix utf-8": prefix.encode(), "prefix utf-16le": prefix.encode("utf-16-le"),
         "prefix utf-16be": prefix.encode("utf-16-be")}
if os.path.isfile(keyfile) and os.path.getsize(keyfile):
    key = open(keyfile, "rb").read().strip()
    forms["key utf-8"] = key
    forms["key utf-16le"] = key.decode().encode("utf-16-le")
    forms["key utf-16be"] = key.decode().encode("utf-16-be")
    for i in range(3):  # after i other bytes; the edge groups also hold neighbouring bytes, so drop them
        for name, enc in (("base64", base64.b64encode), ("base64url", base64.urlsafe_b64encode)):
            forms[f"key {name}@{i}"] = enc(b"\0" * i + key)[4 if i else 0:-4]
out = set()
for d, _, files in os.walk(root):
    for f in files:
        path = os.path.join(d, f)
        if os.path.islink(path) or not os.path.isfile(path):
            continue
        data = open(path, "rb").read()
        for name, pat in forms.items():
            if pat and pat in data:
                out.add(f"{os.path.relpath(path, root)} ({name})")
for line in sorted(out):
    print(line)
EOF
}
# pull_dir PACKAGE DIR NAME: copies DIR (as the app, through run-as) to $work/NAME.
pull_dir() {
    mkdir -p "$work/$3"
    adb exec-out run-as "$1" tar -cf - -C "$2" . | tar -xf - -C "$work/$3"
}

# The controls: the scan finds each planted form (one per file) and no near miss. The prefix
# controls run without the key, the key controls without the prefix, so each form is shown alone.
mkdir -p "$work/control/prefix" "$work/control/key"
python3 -I - "$work/control" "$PREFIX" "$keyfile" <<'EOF'
import base64, os, sys
d, prefix, keyfile = sys.argv[1:4]
def put(sub, name, data):
    open(os.path.join(d, sub, name), "wb").write(b"a\0b " + data + b" c")
put("prefix", "p8", prefix.encode())
put("prefix", "p16", prefix.encode("utf-16-le"))
put("prefix", "near", prefix[:-1].encode() + b"_")
if os.path.isfile(keyfile) and os.path.getsize(keyfile):
    key = open(keyfile, "rb").read().strip()
    put("key", "k8", key)
    put("key", "k16", key.decode().encode("utf-16-be"))
    put("key", "k64", base64.b64encode(b"zz" + key))
    put("key", "knear", key[:-1] + (b"A" if key[-1:] != b"A" else b"B"))
EOF
found() { hits "$@" | cut -d' ' -f1 | sort -u | tr '\n' ' '; }
check "control: the scan finds a planted prefix, plain and UTF-16, and no near miss" \
    "[[ \"\$(found '$work/control/prefix' '$PREFIX' /nonexistent)\" == 'p16 p8 ' ]]"
if [[ -s "$keyfile" ]]; then
    check "control: the key scan finds the key plain, UTF-16 and base64, and no near miss" \
        "[[ \"\$(found '$work/control/key' '<no such prefix>' '$keyfile')\" == 'k16 k64 k8 ' ]]"
fi
rm -rf "$work/control"

echo "installed actuator: $(adb shell dumpsys package com.vos.actuator | tr -d '\r' | grep -m1 -o 'lastUpdateTime=.*')"
check "the actuator is a debug build (run-as works)" "adb shell run-as com.vos.actuator true"
check "the session holds a sealed key (there is a key to leak)" "adb shell run-as com.vos.session test -s files/keys/xai.bin"

# 1. The installed actuator APK(s), every entry.
mkdir -p "$work/apk"
apks=$(adb shell pm path com.vos.actuator | tr -d '\r' | sed -n 's/^package://p')
for p in $apks; do adb pull "$p" "$work/apk/$(basename "$p")" >/dev/null; done
entries=0
for a in "$work"/apk/*.apk; do
    d="$work/apk-x/$(basename "$a" .apk)"
    mkdir -p "$d"
    unzip -q -o "$a" -d "$d"
    entries=$((entries + $(unzip -Z1 "$a" | wc -l)))
done
apk_hits="$(hits "$work/apk-x")"
echo "actuator APK: $(echo "$apks" | wc -w) file(s), $entries entries, hits: ${apk_hits:-none}"
check "M1-KEY-01: no key in the actuator APK" "[[ -z \"\$apk_hits\" ]]"

# 2. The actuator's data: credential-encrypted, device-encrypted, and external files.
pull_dir com.vos.actuator /data/data/com.vos.actuator ce
pull_dir com.vos.actuator /data/user_de/0/com.vos.actuator de
if adb shell test -d /sdcard/Android/data/com.vos.actuator; then
    adb pull /sdcard/Android/data/com.vos.actuator "$work/ext" >/dev/null
fi
mkdir -p "$work/ext"
for d in ce de ext; do echo "actuator $d: $(find "$work/$d" -type f | wc -l) files"; done
data_hits="$(for d in ce de ext; do hits "$work/$d" | sed "s|^|$d/|"; done)"
echo "actuator data hits: ${data_hits:-none}"
check "M1-KEY-01: no key in the actuator's data" "[[ -z \"\$data_hits\" ]]"

# 3. Beyond the row: the session's data holds the key only sealed; logcat holds no copy.
pull_dir com.vos.session /data/data/com.vos.session session
session_hits="$(hits "$work/session")"
echo "session data: $(find "$work/session" -type f | wc -l) files, hits: ${session_hits:-none}"
check "the session holds no plaintext key at rest" "[[ -z \"\$session_hits\" ]]"
if [[ -s "$keyfile" ]]; then
    adb logcat -d > "$work/logcat.txt"
    check "the key is not in logcat" "! grep -q -F -f '$keyfile' '$work/logcat.txt'"
fi

if (( fails == 0 )); then echo "M1-KEY-01: PASS"; else echo "M1-KEY-01: FAIL ($fails)"; exit 1; fi
