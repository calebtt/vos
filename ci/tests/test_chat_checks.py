"""eval/m1/chat_checks.py (#299): every M1-CHAT-01 check passes on a passing run and FAILs on each of its breaks.

The checks read only a run's files, so this needs no phone. A check with no break fails the self-test.
"""

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("chat_checks", ROOT / "eval" / "m1" / "chat_checks.py")
chat_checks = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(chat_checks)


def run_checks(run_dir):
    """chat_checks.py RUN_DIR as chat.sh --checks runs it: (exit code, stdout lines, stderr)."""
    p = subprocess.run([sys.executable, "-I", str(ROOT / "eval" / "m1" / "chat_checks.py"), str(run_dir)],
                       capture_output=True, text=True, timeout=60)
    return p.returncode, p.stdout.splitlines(), p.stderr


class ChatChecksTest(unittest.TestCase):
    def test_every_check_fails_on_its_breaks(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = chat_checks.self_test()
        self.assertEqual(rc, 0, err.getvalue() + out.getvalue())
        self.assertIn("self-test PASS", out.getvalue())

    def test_a_check_that_cannot_fail_fails_the_self_test(self):
        real = chat_checks.evaluate

        def vacuous(d):  # forged_no_turn always passes, like the old forged_opened == 0
            summary, results = real(d)
            return summary, [(c, ok or c == "forged_no_turn", t) for c, ok, t in results]

        with mock.patch.object(chat_checks, "evaluate", vacuous), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(chat_checks.self_test(), 1)

    def test_a_check_with_no_break_fails_the_self_test(self):
        breaks = [b for b in chat_checks.BREAKS if b[0] != "rec_typed"]
        with mock.patch.object(chat_checks, "BREAKS", breaks), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(chat_checks.self_test(), 1)

    def test_a_stopped_turn_with_no_transcript_line_at_all_passes(self):
        # Stopped before the session wrote the owner's line: no VOS line either, which is what counts.
        with tempfile.TemporaryDirectory() as d:
            chat_checks.write_passing(d)
            p = Path(d) / "transcripts.jsonl"
            sid = chat_checks.IDS[chat_checks.STOP_TURN]
            p.write_text("".join(line + "\n" for line in p.read_text().splitlines() if sid not in line))
            _, results = chat_checks.evaluate(d)
        got = {c: ok for c, ok, _ in results}
        self.assertTrue(got["stop_no_vos"])
        self.assertTrue(all(got.values()), [t for _, ok, t in results if not ok])

    def test_a_run_with_no_files_passes_no_check(self):
        with tempfile.TemporaryDirectory() as d:
            _, results = chat_checks.evaluate(d)
        self.assertTrue(results)
        self.assertEqual([text for _, ok, text in results if ok], [])


    def test_a_missing_run_dir_exits_2_and_runs_no_check(self):  # #305: e.g. a typo in chat.sh --checks
        with tempfile.TemporaryDirectory() as d:
            missing = Path(d) / "chat-120000"
            rc, out, err = run_checks(missing)
        self.assertEqual(rc, 2, err)
        self.assertIn(f"no such run directory: {missing}", err)
        self.assertEqual(out, [])

    def test_a_file_is_not_a_run_dir(self):
        with tempfile.NamedTemporaryFile() as f:
            rc, out, err = run_checks(f.name)
        self.assertEqual(rc, 2, err)
        self.assertIn("no such run directory", err)
        self.assertEqual(out, [])

    def test_an_empty_run_dir_names_every_run_file_as_missing(self):  # #305: e.g. an aborted run
        with tempfile.TemporaryDirectory() as d:
            rc, out, err = run_checks(d)
        self.assertEqual(rc, 1, err)
        expected = ("facts.txt", "voice.txt", "transcripts.jsonl", "actuator-audit.jsonl", "summon.txt",
                    "summon-typed.txt", "stop.txt", "recording.txt")  # every file chat.sh writes for the checks
        self.assertEqual(out[0], "run files missing or empty (8 of 8): " + ", ".join(
            f"{name} (missing)" for name in expected))
        self.assertTrue([line for line in out if line.startswith("FAIL ")])
        self.assertEqual([line for line in out if line.startswith("PASS ")], [])

    def test_the_summary_names_each_run_file_missing_or_empty(self):  # #305: e.g. a failed pull
        with tempfile.TemporaryDirectory() as d:
            chat_checks.write_passing(d)
            rc, out, err = run_checks(d)
            self.assertEqual((rc, out[0]), (0, "run files: all 8 present"), err + "\n".join(out))
            (Path(d) / "transcripts.jsonl").write_text("")
            (Path(d) / "summon-typed.txt").write_text("\n \n")  # blank counts as empty
            (Path(d) / "stop.txt").unlink()
            rc, out, err = run_checks(d)
        self.assertEqual(rc, 1, err)
        self.assertEqual(out[0], "run files missing or empty (3 of 8): transcripts.jsonl (empty), "
                                 "summon-typed.txt (empty), stop.txt (missing)")


if __name__ == "__main__":
    unittest.main()
