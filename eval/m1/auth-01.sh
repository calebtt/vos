#!/usr/bin/env bash
# M1-AUTH-01 (#132): bind the actuator, and the session, from a foreign-signed APK and from a
# same-cert APK with the wrong package. Pass: both are refused (the system refuses the foreign bind;
# the VOS guard answers UNAUTHORIZED to the wrong package), each app's own UID is refused both by a
# raw transaction and by a direct in-process AIDL call, every guard refusal is audited with the
# caller's UID and package, the real session ↔ actuator pair still links both ways (protocol
# version handshake included), and each side sees the other die (linkToDeath). TARGET-PLAN §5.3.
#
# Debug builds only, on the emulator or Phone B (§5.3). Installs com.vos.actuator, com.vos.session,
# their androidTest APKs and the two fixtures; makes no sound and touches no user data.
#
# Usage: eval/m1/auth-01.sh [--no-build] [--keep-fixtures]
#   ANDROID_SERIAL picks the device. Results go to $VOS_EVAL_OUT if set, else to
#   $VOS_EVAL_ROOT/<date>/m1-auth-01-<time>, else under eval/runs/ in this checkout (gitignored).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build=1 keep=0
for a in "$@"; do
    case "$a" in
        --no-build) build=0 ;;
        --keep-fixtures) keep=1 ;;
        *) echo "usage: $0 [--no-build] [--keep-fixtures]" >&2; exit 2 ;;
    esac
done

stamp="$(date +%Y-%m-%d)/m1-auth-01-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
log="$out/run.log"
exec > >(tee -a "$log") 2>&1

device="$(adb get-serialno)"
echo "M1-AUTH-01 on $device ($(adb shell getprop ro.product.device) $(adb shell getprop ro.build.fingerprint)); results in $out"

if (( build )); then
    "$ROOT/gradlew" -p "$ROOT" -q --console=plain \
        :actuator:assembleDebug :actuator:assembleDebugAndroidTest \
        :session:assembleDebug :session:assembleDebugAndroidTest \
        :fixture-auth:assembleWrongpkgDebug :fixture-auth:assembleForeignDebug
fi

apk() { echo "$ROOT/$1"; }
ACTUATOR_APK=$(apk actuator/build/outputs/apk/debug/actuator-debug.apk)
ACTUATOR_TEST_APK=$(apk actuator/build/outputs/apk/androidTest/debug/actuator-debug-androidTest.apk)
SESSION_APK=$(apk session/build/outputs/apk/debug/session-debug.apk)
SESSION_TEST_APK=$(apk session/build/outputs/apk/androidTest/debug/session-debug-androidTest.apk)
WRONG_APK=$(apk eval/fixtures/auth/build/outputs/apk/wrongpkg/debug/fixture-auth-wrongpkg-debug.apk)
FOREIGN_APK=$(apk eval/fixtures/auth/build/outputs/apk/foreign/debug/fixture-auth-foreign-debug.apk)

# Both VOS apps first (each defines the permission the other uses), then the fixtures, so the
# signature permissions are decided against the installed definers.
for f in "$ACTUATOR_APK" "$SESSION_APK" "$ACTUATOR_TEST_APK" "$SESSION_TEST_APK" "$WRONG_APK" "$FOREIGN_APK"; do
    adb install -r -t "$f" >/dev/null
done

granted() { # package permission → true/false, or "not granted" when dumpsys lists no grant
    local g
    g="$(adb shell dumpsys package "$1" | grep -m1 "$2: granted=" | sed 's/.*granted=\([a-z]*\).*/\1/' || true)"
    echo "${g:-not granted}"
}
echo "grants: session SESSION=$(granted com.vos.session com.vos.permission.SESSION)" \
    "actuator ACTUATOR=$(granted com.vos.actuator com.vos.permission.ACTUATOR)" \
    "wrongpkg SESSION=$(granted com.vos.fixture.wrongpkg com.vos.permission.SESSION)" \
    "foreign SESSION=$(granted com.vos.fixture.foreign com.vos.permission.SESSION)"

for p in com.vos.actuator com.vos.session; do
    adb shell run-as "$p" rm -rf files/audit
done

uid_of() { adb shell pm list packages -U "$1" | tr -d '\r' | sed -n "s/^package:$1 uid://p"; }
UID_ACTUATOR=$(uid_of com.vos.actuator)
UID_SESSION=$(uid_of com.vos.session)
UID_WRONG=$(uid_of com.vos.fixture.wrongpkg)
UID_FOREIGN=$(uid_of com.vos.fixture.foreign)
echo "uids: actuator=$UID_ACTUATOR session=$UID_SESSION wrongpkg=$UID_WRONG foreign=$UID_FOREIGN"

SESSION_T=com.vos.session.test/com.vos.eval.auth.AuthProbe
ACTUATOR_T=com.vos.actuator.test/com.vos.eval.auth.AuthProbe
WRONG_T=com.vos.fixture.wrongpkg/com.vos.eval.auth.AuthProbe
FOREIGN_T=com.vos.fixture.foreign/com.vos.eval.auth.AuthProbe

fails=0
results="$out/results.tsv"
printf 'case\texpect\toutcome\tpass\tuid\tdetail\n' > "$results"
field() { sed -n "s/^INSTRUMENTATION_RESULT: $1=//p" <<<"$2" | tr -d '\r' | head -1; }
record() { # name raw
    local pass outcome
    pass="$(field pass "$2")"; outcome="$(field outcome "$2")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$(field expect "$2")" "$outcome" "${pass:-false}" \
        "$(field uid "$2")" "$(field detail "$2")" >> "$results"
    if [[ "$pass" == "true" ]]; then echo "PASS $1 ($outcome)"; else echo "FAIL $1 (${outcome:-no result})"; fails=$((fails + 1)); fi
}
run_case() { # name component target expect [via [repeat]]
    record "$1" "$(adb shell am instrument -w -r -e target "$3" -e expect "$4" -e via "${5:-link}" \
        -e repeat "${6:-0}" "$2")"
}

# via: link = PeerLink with the protocol-version handshake; raw = a raw transact(), so the guard in
# onTransact runs; aidl = Stub.asInterface(), a direct Java call on the Stub in the same process.
run_case session-to-actuator "$SESSION_T" actuator ok link 200
run_case actuator-to-session "$ACTUATOR_T" session ok link 200
run_case wrongpkg-to-actuator "$WRONG_T" actuator unauthorized raw
run_case wrongpkg-to-session "$WRONG_T" session unauthorized raw
run_case wrongpkg-link-to-actuator "$WRONG_T" actuator unauthorized link
run_case foreign-to-actuator "$FOREIGN_T" actuator bind_refused
run_case foreign-to-session "$FOREIGN_T" session bind_refused
run_case actuator-to-own-service "$ACTUATOR_T" actuator unauthorized raw
run_case session-to-own-service "$SESSION_T" session unauthorized raw
run_case actuator-to-own-service-in-process "$ACTUATOR_T" actuator unauthorized aidl
run_case session-to-own-service-in-process "$SESSION_T" session unauthorized aidl

# linkToDeath, both ways: the probe links, reports "linked", and the peer is force-stopped.
death_case() { # name component target package-to-stop
    local f="$out/$1.raw"
    adb shell am instrument -w -r -e target "$3" -e expect died "$2" > "$f" &
    local pid=$!
    for _ in $(seq 1 100); do grep -q 'state=linked' "$f" 2>/dev/null && break; sleep 0.2; done
    adb shell am force-stop "$4"
    wait "$pid" || true
    record "$1" "$(cat "$f")"
}
death_case session-sees-actuator-die "$SESSION_T" actuator com.vos.actuator
death_case actuator-sees-session-die "$ACTUATOR_T" session com.vos.session

# Audit: every guard refusal has a line with the caller's UID, package and reason. The foreign
# fixture never reaches the guard (the system refuses its bind), so it must have no lines.
check_audit() { # app-package caller-uid caller-package reason
    local lines
    lines="$(adb shell run-as "$1" cat files/audit/audit.jsonl 2>/dev/null || true)"
    if grep -F "\"uid\":$2," <<<"$lines" | grep -F "\"$3\"" | grep -qF "\"reason\":\"$4\""; then
        echo "PASS audit $1 has uid $2 ($3): $4"
    else
        echo "FAIL audit $1 lacks uid $2 ($3): $4"; fails=$((fails + 1))
    fi
}
check_audit com.vos.actuator "$UID_WRONG" com.vos.fixture.wrongpkg "package mismatch"
check_audit com.vos.session "$UID_WRONG" com.vos.fixture.wrongpkg "package mismatch"
check_audit com.vos.actuator "$UID_ACTUATOR" com.vos.actuator "package mismatch"
check_audit com.vos.session "$UID_SESSION" com.vos.session "package mismatch"
for p in com.vos.actuator com.vos.session; do
    adb shell run-as "$p" cat files/audit/audit.jsonl > "$out/audit-$p.jsonl" 2>/dev/null || true
    if grep -q "\"uid\":$UID_FOREIGN," "$out/audit-$p.jsonl"; then
        echo "FAIL audit $p has a line for the foreign fixture (its bind should never reach the guard)"; fails=$((fails + 1))
    fi
done

if (( ! keep )); then
    for p in com.vos.fixture.wrongpkg com.vos.fixture.foreign com.vos.actuator.test com.vos.session.test; do
        adb uninstall "$p" >/dev/null || true
    done
fi

if (( fails == 0 )); then echo "M1-AUTH-01: PASS"; else echo "M1-AUTH-01: FAIL ($fails)"; exit 1; fi
