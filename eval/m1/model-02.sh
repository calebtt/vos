#!/usr/bin/env bash
# M1-MODEL-02 (#144): try to bind offline_grammar or ocr_commit to a cloud backend. Pass: the router
# (ModelPolicy) rejects such a policy when it loads and never returns a cloud model for an actuator
# class, a debug override included (ModelPolicyTest); the static check (ci/policy_check.py) fails the
# shipped policy edited to bind either class to each cloud model in the registry; and the actuator
# still has 0 network flows on the device: it requests no INTERNET, and netstats holds no record for
# its UID, while the session's records show the method sees VOS traffic. TARGET-PLAN §3.2.1.
#
# Makes no sound and changes nothing on the device: it only reads dumpsys and the package list.
# Needs com.vos.actuator and com.vos.session installed (any build), and a session that has used the
# network at least once (any voice or typed turn), so netstats has VOS traffic to compare against.
#
# Usage: eval/m1/model-02.sh
#   ANDROID_SERIAL picks the device. Results go to $VOS_EVAL_OUT if set, else to
#   $VOS_EVAL_ROOT/<date>/m1-model-02-<time>, else under eval/runs/ in this checkout (gitignored).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if (( $# )); then echo "usage: $0" >&2; exit 2; fi

stamp="$(date +%Y-%m-%d)/m1-model-02-$(date +%H%M%S)"
out="${VOS_EVAL_OUT:-${VOS_EVAL_ROOT:-$ROOT/eval/runs}/$stamp}"
mkdir -p "$out"
exec > >(tee -a "$out/run.log") 2>&1

echo "M1-MODEL-02 on $(adb get-serialno) ($(adb shell getprop ro.build.fingerprint | tr -d '\r')); results in $out"

fails=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

# 1. The router: the JVM tests load policies that bind every actuator class to a cloud, a
#    session-local and an unplaced model (each rejected), and point a debug override at the cloud.
"$ROOT/gradlew" -p "$ROOT" -q --console=plain :session:testDebugUnitTest --tests com.vos.session.ModelPolicyTest
results="$ROOT/session/build/test-results/testDebugUnitTest/TEST-com.vos.session.ModelPolicyTest.xml"
cp "$results" "$out/"
check "router: ModelPolicyTest ran its actuator-class cases with 0 failures" \
    "grep -q 'name=\"anActuatorClassBoundToACloudOrSessionModelIsRejected\"' '$results' \
     && grep -q 'name=\"anOverrideCannotPointAnActuatorClassAtACloudModel\"' '$results' \
     && grep -q 'failures=\"0\" errors=\"0\"' '$results'"

# 2. The static check, on the shipped policy and on copies edited to bind offline_grammar or
#    ocr_commit to each cloud model in the registry.
static="$(python3 -I - "$ROOT" <<'EOF'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
sys.path.insert(0, str(root / "ci"))
import policy_check as pc
classes = set(pc.read_list(root / "ci" / "actuator-model-classes.txt"))
shipped = (root / "session/src/main/res/raw/model_policy.json").read_text(encoding="utf-8")
print(f"shipped fails={len(pc.check_model_policy(shipped, classes))}")
for task_class in ("offline_grammar", "ocr_commit"):
    for e in json.loads(shipped)["registry"]:
        if not e.get("where", "").startswith("cloud:"):
            continue
        p = json.loads(shipped)
        p["policy"][task_class] = [e["id"]]
        fails = pc.check_model_policy(json.dumps(p), classes)
        print(f"edited {task_class}->{e['id']} fails={len(fails)}" + "".join(f"\n  {f}" for f in fails))
EOF
)"
echo "$static"
check "static check: the shipped policy passes" "grep -q '^shipped fails=0$' <<<\"\$static\""
check "static check: offline_grammar on a cloud model fails" \
    "grep -q '^edited offline_grammar->' <<<\"\$static\" && ! grep '^edited offline_grammar->' <<<\"\$static\" | grep -q 'fails=0'"
check "static check: ocr_commit on a cloud model fails" \
    "grep -q '^edited ocr_commit->' <<<\"\$static\" && ! grep '^edited ocr_commit->' <<<\"\$static\" | grep -q 'fails=0'"

# 3. The device: the actuator requests no INTERNET, and netstats has no record for its UID.
uid_of() { adb shell pm list packages -U "$1" | tr -d '\r' | sed -n "s/^package:$1 uid://p"; }
UID_ACTUATOR=$(uid_of com.vos.actuator)
UID_SESSION=$(uid_of com.vos.session)
echo "uids: actuator=$UID_ACTUATOR session=$UID_SESSION"
check "both VOS apps are installed" "[[ -n '$UID_ACTUATOR' && -n '$UID_SESSION' ]]"
adb shell dumpsys package com.vos.actuator | tr -d '\r' > "$out/actuator-package.txt"
adb shell dumpsys netstats --full --uid | tr -d '\r' > "$out/netstats-uid.txt"
session_records=$(grep -c " uid=$UID_SESSION " "$out/netstats-uid.txt" || true)
actuator_records=$(grep -c " uid=$UID_ACTUATOR " "$out/netstats-uid.txt" || true)
echo "netstats records: session=$session_records actuator=$actuator_records"
check "the actuator requests no INTERNET permission" "! grep -q 'android.permission.INTERNET' '$out/actuator-package.txt'"
check "netstats sees VOS traffic (the session has records)" "(( session_records > 0 ))"
check "the actuator UID has 0 network records" "(( actuator_records == 0 ))"

if (( fails == 0 )); then echo "M1-MODEL-02: PASS"; else echo "M1-MODEL-02: FAIL ($fails)"; exit 1; fi
