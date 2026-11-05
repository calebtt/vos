#!/usr/bin/env bash
# M2-AUDIT-01 (#154, TARGET-PLAN §5.8): the audit log is hash-chained (each line's "prev" is the
# SHA-256 of the line before it as stored). Change one byte of an audit line and chain verification
# fails at that line.
#
# --self-test (no device): the host tool (ipc's AuditVerify, run through ./gradlew :ipc:auditVerify on
# the JVM) writes a 6-record chain the way JsonlAudit does. The untouched file must verify, and each
# copy with one byte changed must fail at exactly that line: a byte of each line's body, and a digit
# of each line's prev hash (the newest line is covered by the writer's head).
#
# --device: pulls the actuator's audit through run-as (read only: the device's file is never
# changed), verifies it, then changes one byte of a chained line in the middle of a local copy and
# requires verification to fail at that line. Installs nothing, calls no vendor, makes no sound.
#
# Usage: eval/m2/audit-01.sh --self-test | --device
#   ANDROID_SERIAL picks the device. Device results: $VOS_EVAL_OUT, else
#   $VOS_EVAL_ROOT/<date>/m2-audit-01-<time>, else eval/runs/ in this checkout (gitignored).
#   Paths must not contain spaces (Gradle's --args splits on them).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/eval/m1/lib.sh" # vos_run_dir

mode=""
for a in "$@"; do
    case "$a" in
        --self-test) mode=self ;;
        --device) mode=device ;;
        *) mode=""; break ;;
    esac
done
if [[ -z $mode ]]; then echo "usage: $0 --self-test | --device" >&2; exit 2; fi

# The host tool: "verify [--head HEX] FILE..." or "generate FILE N" (see AuditVerify).
tool() { "$ROOT/gradlew" -p "$ROOT" -q --console=plain :ipc:auditVerify --args="$*"; }

# tamper SRC DST LINE SPOT: DST is SRC with one byte of line LINE (1-based) changed. SPOT is a column
# in the line's body, "prev" (a digit of its prev hash, kept hex), or "mid" (the line's middle byte).
tamper() {
    python3 -I - "$@" <<'PY'
import sys
src, dst, line, spot = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
b = bytearray(open(src, "rb").read())
start = 0
for _ in range(line - 1):
    start = b.index(b"\n", start) + 1
end = b.index(b"\n", start)
if spot == "prev":
    at = b.index(b'"prev":"', start) + len(b'"prev":"') + 20
    assert at < end
    b[at] = ord("1") if b[at] == ord("0") else ord("0")
else:
    at = start + ((end - start) // 2 if spot == "mid" else int(spot))
    assert at < end
    b[at] ^= 0x01
    assert b[at] != 0x0A
open(dst, "wb").write(bytes(b))
PY
}

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

if [[ $mode == self ]]; then
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    head="$(tool generate "$work/audit.jsonl" 6 | sed -n 's/^head //p')"
    check "the tool wrote a 6-line chain and named its head" "[[ \${#head} == 64 && \$(wc -l < '$work/audit.jsonl') == 6 ]]"
    files=() lines=()
    for line in 1 2 3 4 5 6; do
        for spot in 20 prev; do
            tamper "$work/audit.jsonl" "$work/t-$line-$spot.jsonl" "$line" "$spot"
            files+=("$work/t-$line-$spot.jsonl") lines+=("$line")
        done
    done
    # Exit 1 is expected (the tampered copies fail); the verdicts are in the output.
    # shellcheck disable=SC2034 # read by the check strings below, through eval
    verdicts="$(tool verify --head "$head" "$work/audit.jsonl" "${files[@]}" || true)"
    check "the untouched chain verifies" "grep -qF '$work/audit.jsonl: OK records=6 legacy=0' <<<\"\$verdicts\""
    for i in "${!files[@]}"; do
        check "one byte changed in line ${lines[$i]} ($(basename "${files[$i]}" .jsonl)) fails at line ${lines[$i]}" \
            "grep -qF '${files[$i]}: FAIL line ${lines[$i]}: ' <<<\"\$verdicts\""
    done
    if (( fails == 0 )); then echo "self-test PASS"; else echo "self-test FAIL ($fails)"; exit 1; fi
    exit 0
fi

# --device: read only on the phone; every change is to a local copy.
vos_run_dir m2-audit-01
echo "M2-AUDIT-01 on $(adb get-serialno); results in $out"
adb exec-out run-as com.vos.actuator cat files/audit/audit.jsonl > "$out/audit.jsonl"
check "pulled the actuator's audit ($(wc -l < "$out/audit.jsonl") lines)" "[[ -s '$out/audit.jsonl' ]]"
original="$(tool verify "$out/audit.jsonl" || true)"
echo "$original" | sed 's/^/  /'
check "the device's audit chains (legacy lines before the chain are allowed)" \
    "grep -qF '$out/audit.jsonl: OK' <<<\"\$original\""
# The target: a chained line whose next line is chained too, near the middle (the next link is what
# shows which line changed; the newest line would need the writer's head).
target="$(awk 'BEGIN { last = -1 } /"prev":"/ { if (last == NR - 1) c[++n] = last; last = NR }
    END { if (n) print c[int((n + 1) / 2)] }' "$out/audit.jsonl")"
if [[ -z $target ]]; then
    check "the audit has two adjacent chained lines to tamper with" "false"
else
    tamper "$out/audit.jsonl" "$out/audit-tampered.jsonl" "$target" mid
    tampered="$(tool verify "$out/audit-tampered.jsonl" || true)"
    echo "$tampered" | sed 's/^/  /'
    check "one byte changed in line $target of a copy: verification fails at line $target" \
        "grep -qE '^  line $target: ' <<<\"\$tampered\" && ! grep -qE '^  line $target: ' <<<\"\$original\""
fi
if (( fails == 0 )); then echo "M2-AUDIT-01: PASS"; else echo "M2-AUDIT-01: FAIL ($fails)"; exit 1; fi
