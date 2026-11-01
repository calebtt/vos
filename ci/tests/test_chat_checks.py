"""eval/m1/chat_checks.py (#299): every M1-CHAT-01 check passes on a passing run and FAILs on each of its breaks.

The checks read only a run's files, so this needs no phone. A check with no break fails the self-test.
"""

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("chat_checks", ROOT / "eval" / "m1" / "chat_checks.py")
chat_checks = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(chat_checks)


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

    def test_a_run_with_no_files_passes_no_check(self):
        with tempfile.TemporaryDirectory() as d:
            _, results = chat_checks.evaluate(d)
        self.assertTrue(results)
        self.assertEqual([text for _, ok, text in results if ok], [])


if __name__ == "__main__":
    unittest.main()
