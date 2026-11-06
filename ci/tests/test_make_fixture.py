"""eval/m1/make_fixture.py's fixture cache (#313), offline: no network, no key, no `websockets`.

synth() is replaced by a fake that counts calls, so a test can tell a cache hit from a synthesis.
"""

import contextlib
import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "eval" / "m1" / "make_fixture.py"
_spec = importlib.util.spec_from_file_location("make_fixture", SCRIPT)
make_fixture = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(make_fixture)

FAKE_KEY = "fake-test-value-never-printed"
TEXT = "What time is it?"


class FakeSynth:
    """Stands in for make_fixture.synth: returns PCM derived from the text and a transcript."""

    def __init__(self, transcript=None):
        self.calls = 0
        self.transcript = transcript

    async def __call__(self, text):
        self.calls += 1
        return ("pcm:" + text).encode("utf-8") * 8, text if self.transcript is None else self.transcript


class MakeFixtureCacheTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.cache = self.tmp / "cache"
        env = {k: v for k, v in os.environ.items() if k not in ("VOS_FIXTURE_CACHE", "GROK_API_KEY")}
        env.update(VOS_FIXTURE_CACHE_DIR=str(self.cache), GROK_API_KEY=FAKE_KEY)
        patcher = mock.patch.dict(os.environ, env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.fake = FakeSynth()
        patcher = mock.patch.object(make_fixture, "synth", self.fake)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_main(self, text=TEXT, out="out.pcm"):
        """make_fixture.main([text, out]): (output bytes, stderr)."""
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            make_fixture.main([text, str(self.tmp / out)])
        self.assertNotIn(FAKE_KEY, err.getvalue())
        return (self.tmp / out).read_bytes(), err.getvalue()

    def entry(self, text=TEXT):
        key = make_fixture.cache_key(text)
        return self.cache / (key + ".pcm"), self.cache / (key + ".json")

    def test_miss_then_store(self):
        pcm, err = self.run_main()
        self.assertEqual(self.fake.calls, 1)
        self.assertIn("fixture cache: miss", err)
        self.assertTrue(pcm)
        data, meta = self.entry()
        self.assertEqual(data.read_bytes(), pcm)
        self.assertTrue(meta.is_file())
        self.assertEqual([p.name for p in self.tmp.iterdir() if p.name.endswith(".tmp")], [])
        self.assertEqual([p.name for p in self.cache.iterdir() if p.name.endswith(".tmp")], [])

    def test_hit_reuses_the_audio(self):
        first, _ = self.run_main(out="a.pcm")
        second, err = self.run_main(out="b.pcm")
        self.assertEqual(self.fake.calls, 1)
        self.assertIn("fixture cache: hit", err)
        self.assertEqual(first, second)

    def test_text_or_voice_change_is_a_new_entry(self):
        self.run_main()
        self.run_main(text="That's all, thanks.")
        self.assertEqual(self.fake.calls, 2)
        with mock.patch.object(make_fixture, "VOICE", "another-voice"):
            _, err = self.run_main()
        self.assertEqual(self.fake.calls, 3)
        self.assertIn("fixture cache: miss", err)

    def test_every_synthesis_input_changes_the_key(self):
        base = make_fixture.cache_key(TEXT)
        self.assertEqual(base, make_fixture.cache_key(TEXT))
        self.assertNotEqual(base, make_fixture.cache_key(TEXT + " "))
        for name, value in [("URL", make_fixture.URL + "-x"), ("VOICE", "another-voice"),
                            ("FMT", {"type": "audio/pcm", "rate": 24000}),
                            ("INSTRUCTIONS", make_fixture.INSTRUCTIONS + " "),
                            ("PROMPT", make_fixture.PROMPT + " "),
                            ("CACHE_VERSION", make_fixture.CACHE_VERSION + 1)]:
            with self.subTest(name), mock.patch.object(make_fixture, name, value):
                self.assertNotEqual(base, make_fixture.cache_key(TEXT))

    def test_damaged_entry_is_a_miss_and_is_repaired(self):
        breaks = {
            "empty pcm": lambda data, meta: data.write_bytes(b""),
            "truncated pcm": lambda data, meta: data.write_bytes(data.read_bytes()[:-1]),
            "flipped byte": lambda data, meta: data.write_bytes(bytes([data.read_bytes()[0] ^ 1])
                                                                + data.read_bytes()[1:]),
            "missing pcm": lambda data, meta: data.unlink(),
            "missing sidecar": lambda data, meta: meta.unlink(),
            "empty sidecar": lambda data, meta: meta.write_text(""),
            "garbage sidecar": lambda data, meta: meta.write_text("[1, 2"),
            "sidecar not an object": lambda data, meta: meta.write_text("[]"),
        }
        good, _ = self.run_main()
        for name, damage in breaks.items():
            with self.subTest(name):
                calls = self.fake.calls
                damage(*self.entry())
                pcm, err = self.run_main()
                self.assertIn("fixture cache: miss", err)
                self.assertEqual(self.fake.calls, calls + 1)
                self.assertEqual(pcm, good)
                self.assertEqual(self.entry()[0].read_bytes(), good)
                self.assertIn("fixture cache: hit", self.run_main()[1])

    def test_bypass_neither_reads_nor_writes(self):
        self.run_main()
        with mock.patch.dict(os.environ, {"VOS_FIXTURE_CACHE": "0"}):
            _, err = self.run_main()
            self.run_main(text="That's all, thanks.")
        self.assertIn("fixture cache: off", err)
        self.assertEqual(self.fake.calls, 3)
        self.assertFalse(self.entry("That's all, thanks.")[0].exists())

    def test_miss_without_key_fails_before_synthesis(self):
        del os.environ["GROK_API_KEY"]
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            make_fixture.main([TEXT, str(self.tmp / "out.pcm")])
        self.assertEqual(self.fake.calls, 0)
        self.assertFalse((self.tmp / "out.pcm").exists())

    def test_unverified_audio_is_neither_cached_nor_written(self):
        self.fake.transcript = "It is three o'clock."
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            make_fixture.main([TEXT, str(self.tmp / "out.pcm")])
        self.assertEqual(self.fake.calls, 3)
        self.assertFalse(self.entry()[0].exists())
        self.assertFalse((self.tmp / "out.pcm").exists())

    def test_unwritable_cache_still_writes_the_fixture(self):
        self.cache.write_text("a file where the cache directory should be")
        pcm, err = self.run_main()
        self.assertTrue(pcm)
        self.assertIn("fixture cache: not stored", err)

    def test_cli_hit_needs_no_key_and_no_websockets(self):
        good, _ = self.run_main()
        env = {k: v for k, v in os.environ.items() if k != "GROK_API_KEY"}
        out = self.tmp / "cli.pcm"
        # websockets is blocked: importing it would raise, so a hit must not import it.
        code = ("import runpy, sys; sys.modules['websockets'] = None; "
                "sys.argv = sys.argv[1:]; runpy.run_path(sys.argv[0], run_name='__main__')")
        p = subprocess.run([sys.executable, "-I", "-c", code, str(SCRIPT), TEXT, str(out)],
                           env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("fixture cache: hit", p.stderr)
        self.assertEqual(out.read_bytes(), good)


if __name__ == "__main__":
    unittest.main()
