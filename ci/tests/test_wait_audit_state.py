"""eval/m1/lib.sh vos_wait_audit_state (#310): polls the audit until the turn's state appears, or fails.

No phone: the test redefines vos_actuator_audit to read a local file, and a background writer
appends the IDLE line after the first read, as the AudioPlane does just after a turn's stop.
"""

import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TURN = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
OTHER = "11111111-2222-3333-4444-555555555555"

SCRIPT = r"""
set -euo pipefail
source "$1/eval/m1/lib.sh"
AUDIT_FILE="$2"
vos_actuator_audit() { tr -d '\r\000' < "$AUDIT_FILE"; }
if [[ -n "$5" ]]; then (sleep "$5"; printf '%s\n' "$6" >> "$AUDIT_FILE") & fi
rc=0
vos_wait_audit_state "$3" IDLE "$4" "$7" || rc=$?
wait
echo "rc=$rc"
"""


def line(turn, state):
    return '{"ts":1,"event":"audio.plane","turnId":"%s","state":"%s","mode":0}' % (turn, state)


class WaitAuditStateTest(unittest.TestCase):
    def run_wait(self, initial, seconds, late_after="", late_line=""):
        d = Path(tempfile.mkdtemp())
        audit, out = d / "audit.jsonl", d / "turn.jsonl"
        audit.write_bytes(initial)
        start = time.monotonic()
        p = subprocess.run(["bash", "-c", SCRIPT, "self-test", str(ROOT), str(audit), TURN, str(seconds),
                            late_after, late_line, str(out)], capture_output=True, text=True, timeout=30)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p.stdout.strip().splitlines()[-1], out.read_text(), time.monotonic() - start

    def test_waits_for_an_idle_line_written_after_the_first_read(self):
        first = "\n".join(line(TURN, s) for s in ("CAPTURE", "CONVERSE", "RESTORE")) + "\n"
        rc, got, _ = self.run_wait(first.encode(), 5, "0.6", line(TURN, "IDLE"))
        self.assertEqual(rc, "rc=0")
        states = [l.split('"state":"')[1].split('"')[0] for l in got.splitlines()]
        self.assertEqual(states, ["CAPTURE", "CONVERSE", "RESTORE", "IDLE"])

    def test_a_missing_idle_times_out_and_fails(self):
        first = (line(TURN, "CAPTURE") + "\n" + line(OTHER, "IDLE") + "\n").encode()
        rc, got, took = self.run_wait(first, 1)
        self.assertEqual(rc, "rc=1", "no IDLE for this turn: the wait must fail, not pass silently")
        self.assertEqual(got.splitlines(), [line(TURN, "CAPTURE")], "another turn's IDLE does not count")
        self.assertGreaterEqual(took, 1.0)

    def test_a_torn_audit_with_nuls_still_matches(self):
        first = (line(TURN, "RESTORE") + "\n").encode() + b"\0" * 64 + (line(TURN, "IDLE") + "\n").encode()
        rc, got, _ = self.run_wait(first, 1)
        self.assertEqual(rc, "rc=0")
        self.assertIn('"state":"IDLE"', got)


if __name__ == "__main__":
    unittest.main()
