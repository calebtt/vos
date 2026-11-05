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
# sound, calls no vendor, and changes nothing on the device. With no sealed key (a fresh device) it
# reports "SKIP ..." and exits 3, unless a check already failed (#312). The data copied off the
# device (audit and transcript logs) stays in a private temp dir that is deleted on exit; only counts
# and file names are written to the results.
#
# A scanner error or any unreadable file or directory fails the run. A partial scan does not
# report that no key was found.
#
# Usage: eval/m1/key-01.sh [--self-test]
#   --self-test checks that an unreadable path fails closed, with no device. Otherwise
#   ANDROID_SERIAL picks the device. XAI_API_KEY (or GROK_API_KEY) enables the exact-key scan; it
#   is never printed. Results go to $VOS_EVAL_OUT if set, else to
#   $VOS_EVAL_ROOT/<date>/m1-key-01-<time>, else under eval/runs/ in this checkout (gitignored).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/eval/m1/lib.sh"
if [[ "${1:-}" == "--self-test" ]]; then
    SELF_TEST=1
elif (( $# )); then
    echo "usage: $0 [--self-test]" >&2
    exit 2
fi
PREFIX="xai-"

if [[ "${SELF_TEST:-}" != 1 ]]; then
vos_run_dir m1-key-01

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
fi

# hits DIR [PREFIX [KEYFILE]]: the files under DIR that hold the prefix or the key, as UTF-8 or UTF-16, or the
# key in base64 at any alignment (names and forms only). Python, not grep: grep here may be ugrep,
# whose handling of NUL bytes in fixed patterns differs.
hits() {
    python3 -I - "$1" "${2:-$PREFIX}" "${3:-${keyfile:-}}" <<'EOF'
import base64, os, stat, sys
root, prefix, keyfile = sys.argv[1], sys.argv[2], sys.argv[3]

def fail(msg):
    print("scan error: " + msg, file=sys.stderr)
    raise SystemExit(1)

if not os.path.isdir(root):
    fail("not a directory: " + root)
forms = {"prefix utf-8": prefix.encode(), "prefix utf-16le": prefix.encode("utf-16-le"),
         "prefix utf-16be": prefix.encode("utf-16-be")}
if keyfile and os.path.isfile(keyfile) and os.path.getsize(keyfile):
    try:
        key = open(keyfile, "rb").read().strip()
    except OSError as e:
        fail("cannot read key file: " + str(e.strerror or e))
    forms["key utf-8"] = key
    forms["key utf-16le"] = key.decode().encode("utf-16-le")
    forms["key utf-16be"] = key.decode().encode("utf-16-be")
    for i in range(3):  # after i other bytes; the edge groups also hold neighbouring bytes, so drop them
        for name, enc in (("base64", base64.b64encode), ("base64url", base64.urlsafe_b64encode)):
            forms[f"key {name}@{i}"] = enc(b"\0" * i + key)[4 if i else 0:-4]

def onerror(err):
    fail("unreadable directory " + str(err.filename) + ": " + str(err.strerror))

out = set()
for d, _, files in os.walk(root, onerror=onerror):
    for f in files:
        path = os.path.join(d, f)
        try:
            mode = os.lstat(path).st_mode
        except OSError as e:
            fail("unreadable path " + path + ": " + str(e.strerror))
        if not stat.S_ISREG(mode):  # symlink, socket, fifo: not file contents
            continue
        try:
            data = open(path, "rb").read()
        except OSError as e:
            fail("unreadable file " + path + ": " + str(e.strerror))
        for name, pat in forms.items():
            if pat and pat in data:
                out.add(f"{os.path.relpath(path, root)} ({name})")
for line in sorted(out):
    print(line)
EOF
}

# A finished scan's hit lines, or non-zero (and no hit lines) if any path could not be read.
# Callers that report "no key" must abort on failure: an empty result is not a clean tree.
finished_hits() {
    local out
    if ! out="$(hits "$@")"; then
        return 1
    fi
    printf '%s' "$out"
}

# Device-free check that an unfinished scan cannot be reported as a clean tree.
self_test() {
    local t log phrase="no key in the actuator's data"
    t="$(mktemp -d)"
    chmod 700 "$t"
    # shellcheck disable=SC2064 # $t is local to self_test, so the trap takes its value now
    trap "chmod -R u+rwx '$t' 2>/dev/null || true; rm -rf '$t'" EXIT
    mkdir -p "$t/tree/readable" "$t/tree/locked" "$t/files" "$t/clean"
    printf 'ordinary\n' > "$t/tree/readable/note.txt"
    printf 'xai-secret\n' > "$t/tree/locked/secret.txt"
    chmod 000 "$t/tree/locked"
    printf 'ordinary\n' > "$t/files/note.txt"
    printf 'xai-secret\n' > "$t/files/secret.txt"
    chmod 000 "$t/files/secret.txt"
    printf 'ordinary\n' > "$t/clean/note.txt"

    # The same gate as the device run: only a finished scan may print the verdict.
    verdict() {
        local dir=$1
        local part
        if ! part="$(finished_hits "$dir" "xai-" /nonexistent)"; then
            echo "FAIL scan did not finish (unreadable path or scanner error)" >&2
            return 1
        fi
        if [[ -z "$part" ]]; then
            echo "PASS M1-KEY-01: $phrase"
        else
            echo "FAIL M1-KEY-01: $phrase"
        fi
    }
    assert_no_verdict() {
        local name=$1 dir=$2 log rc
        log="$(mktemp)"
        set +e
        verdict "$dir" >"$log" 2>&1
        rc=$?
        set -e
        if (( rc == 0 )); then
            echo "self-test FAIL: $name was treated as a finished scan" >&2
            cat "$log" >&2
            exit 1
        fi
        if grep -F -q "$phrase" "$log"; then
            echo "self-test FAIL: $name printed '$phrase'" >&2
            cat "$log" >&2
            exit 1
        fi
        rm -f "$log"
    }
    assert_no_verdict "unreadable directory" "$t/tree"
    assert_no_verdict "unreadable file" "$t/files"
    mkdir -p "$t/notadir"
    printf 'x\n' > "$t/notadir/f"
    # A file where a directory is required is a scanner error, same as an unreadable folder.
    assert_no_verdict "path that is not a directory" "$t/notadir/f"
    mkdir -p "$t/leak" "$t/prefix"
    printf 'xx xai-yy\n' > "$t/leak/note.txt"
    [[ "$(finished_hits "$t/leak" "xai-" /nonexistent)" == *note.txt* ]]
    python3 -I - "$t/prefix" <<'EOF'
import os, sys
d = sys.argv[1]
def put(name, data):
    open(os.path.join(d, name), "wb").write(b"a\0b " + data + b" c")
put("p8", b"xai-")
put("p16", "xai-".encode("utf-16-le"))
put("near", b"xai_")
EOF
    got="$(hits "$t/prefix" "xai-" /nonexistent | cut -d' ' -f1 | sort -u | tr '\n' ' ')"
    [[ "$got" == "p16 p8 " ]]
    log="$(verdict "$t/clean")"
    [[ "$log" == "PASS M1-KEY-01: $phrase" ]]
    vos_lib_self_test # the shared verdict: FAIL beats SKIP (exit 3), a missing key is a SKIP (#312)
    echo "self-test PASS"
}

if [[ "${SELF_TEST:-}" == 1 ]]; then
    self_test
    exit
fi
# pull_dir PACKAGE DIR NAME: copies DIR (as the app, through run-as) to $work/NAME.
pull_dir() {
    mkdir -p "$work/$3"
    if ! adb exec-out run-as "$1" tar -cf - -C "$2" . | tar -xf - -C "$work/$3"; then
        echo "FAIL could not read $1:$2" >&2
        exit 1
    fi
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
if ! adb shell run-as com.vos.session test -s files/keys/xai.bin; then
    # A precondition, not a defect (a fresh device, #312): with no key there is nothing to leak.
    vos_verdict M1-KEY-01 "$fails" "the session holds no sealed key yet (run one voice or typed turn first)"
fi
echo "PASS the session holds a sealed key (there is a key to leak)"

# 1. The installed actuator APK(s), every entry.
mkdir -p "$work/apk"
apks=$(adb shell pm path com.vos.actuator | tr -d '\r' | sed -n 's/^package://p')
for p in $apks; do adb pull "$p" "$work/apk/$(basename "$p")" >/dev/null; done
entries=0
for a in "$work"/apk/*.apk; do
    d="$work/apk-x/$(basename "$a" .apk)"
    mkdir -p "$d"
    if ! unzip -q -o "$a" -d "$d"; then
        echo "FAIL could not read APK entries in $(basename "$a")" >&2
        exit 1
    fi
    entries=$((entries + $(unzip -Z1 "$a" | wc -l)))
done
if ! apk_hits="$(finished_hits "$work/apk-x")"; then
    echo "FAIL actuator APK scan did not finish (unreadable path or scanner error)" >&2
    exit 1
fi
echo "actuator APK: $(echo "$apks" | wc -w) file(s), $entries entries, hits: ${apk_hits:-none}"
check "M1-KEY-01: no key in the actuator APK" "[[ -z \"\$apk_hits\" ]]"

# 2. The actuator's data: credential-encrypted, device-encrypted, and external files.
pull_dir com.vos.actuator /data/data/com.vos.actuator ce
pull_dir com.vos.actuator /data/user_de/0/com.vos.actuator de
if adb shell test -d /sdcard/Android/data/com.vos.actuator; then
    if ! adb pull /sdcard/Android/data/com.vos.actuator "$work/ext" >/dev/null; then
        echo "FAIL could not read the actuator external files dir" >&2
        exit 1
    fi
fi
mkdir -p "$work/ext"
for d in ce de ext; do echo "actuator $d: $(find "$work/$d" -type f | wc -l) files"; done
data_hits=""
for d in ce de ext; do
    if ! part="$(finished_hits "$work/$d")"; then
        echo "FAIL actuator data scan did not finish (unreadable path or scanner error)" >&2
        exit 1
    fi
    if [[ -n "$part" ]]; then
        data_hits+="$(printf '%s\n' "$part" | sed "s|^|$d/|")"$'\n'
    fi
done
echo "actuator data hits: ${data_hits:-none}"
check "M1-KEY-01: no key in the actuator's data" "[[ -z \"\$data_hits\" ]]"

# 3. Beyond the row: the session's data holds the key only sealed; logcat holds no copy.
pull_dir com.vos.session /data/data/com.vos.session session
if ! session_hits="$(finished_hits "$work/session")"; then
    echo "FAIL session data scan did not finish (unreadable path or scanner error)" >&2
    exit 1
fi
echo "session data: $(find "$work/session" -type f | wc -l) files, hits: ${session_hits:-none}"
check "the session holds no plaintext key at rest" "[[ -z \"\$session_hits\" ]]"
if [[ -s "$keyfile" ]]; then
    adb logcat -d > "$work/logcat.txt"
    check "the key is not in logcat" "! grep -q -F -f '$keyfile' '$work/logcat.txt'"
fi

vos_verdict M1-KEY-01 "$fails"
