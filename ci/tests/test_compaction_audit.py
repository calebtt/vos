"""eval/m1/compaction_audit.py (#334): the counts-only rule of context-budget.sh accepts the audit chain's
prev and nothing else new, on both compaction events, and context-budget.sh's own summary uses it.

Needs no phone: the summary step of context-budget.sh is run on a run directory written here.
"""

import contextlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("compaction_audit", ROOT / "eval" / "m1" / "compaction_audit.py")
compaction_audit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(compaction_audit)

COMPACT, FAILED = compaction_audit.COMPACT, compaction_audit.FAILED


def summary(audit_lines):
    """context-budget.sh's summary step (its python heredoc, as the script runs it) on a run whose
    session audit is audit_lines: {key: value} from its compactions line."""
    script = re.search(r"> \"\$out/summary\.txt\" <<'PY'\n(.*?)\nPY\n",
                       (ROOT / "eval" / "m1" / "context-budget.sh").read_text(), re.S).group(1)
    with tempfile.TemporaryDirectory() as out:
        d = Path(out)
        (d / "session.txt").write_text("")
        (d / "transcripts.jsonl").write_text("")
        (d / "conversation.json").write_text("")
        (d / "session-audit.jsonl").write_text("".join(json.dumps(a) + "\n" for a in audit_lines))
        p = subprocess.run([sys.executable, "-I", "-", out, "450", str(ROOT / "eval" / "m1")], input=script,
                           capture_output=True, text=True, timeout=60)
    assert p.returncode == 0, p.stderr
    line = next(line for line in p.stdout.splitlines() if line.startswith("compactions="))
    return dict(kv.split("=", 1) for kv in line.split())


class CompactionAuditTest(unittest.TestCase):
    def test_self_test_passes(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = compaction_audit.self_test()
        self.assertEqual(rc, 0, err.getvalue() + out.getvalue())
        self.assertIn("self-test PASS", out.getvalue())

    def test_chained_compaction_lines_are_counts_only(self):  # the 2026-11-06 emulator run's shape (#334)
        got = summary([{"ts": 1, "event": "model.pick", "turnId": "x", "prev": "0" * 64},  # not a compaction
                       COMPACT, FAILED, {k: v for k, v in COMPACT.items() if k != "prev"}])
        self.assertEqual((got["compactions"], got["failed"], got["counts_only"], got["not_counts"]),
                         ("2", "1", "True", "none"))

    def test_content_on_either_event_is_not_counts_only(self):
        for base in (COMPACT, FAILED):
            got = summary([COMPACT, {**base, "summary": "The owner's sister is Dana."}])
            self.assertEqual((got["counts_only"], got["not_counts"]), ("False", "summary"), base["event"])

    def test_a_malformed_prev_on_either_event_is_not_counts_only(self):
        for base in (COMPACT, FAILED):
            for prev in (base["prev"].upper(), base["prev"][:63], "Dana", None):
                got = summary([{**base, "prev": prev}, FAILED])
                self.assertEqual((got["counts_only"], got["not_counts"]), ("False", "prev:malformed"),
                                 f"{base['event']} prev={prev!r}")

    def test_a_rule_that_accepts_any_prev_fails_the_self_test(self):
        real = compaction_audit.CHAIN_PREV
        try:
            compaction_audit.CHAIN_PREV = re.compile(r".*", re.S)
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(compaction_audit.self_test(), 1)
        finally:
            compaction_audit.CHAIN_PREV = real


if __name__ == "__main__":
    unittest.main()
