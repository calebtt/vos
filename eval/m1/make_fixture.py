"""Speak a test question as 16 kHz mono s16le PCM, through the xAI realtime API (eval fixture).

Usage: GROK_API_KEY=... python3 -I eval/m1/make_fixture.py "What time is it?" out.pcm

Needs the `websockets` package. The key is read from the environment and never printed. The model
is told to read the text aloud verbatim, and the transcript it reports must match the text (case
and punctuation aside); a voice model sometimes answers the question instead, so it retries up to
three times and fails rather than write a wrong fixture.

Verified audio is cached (#313) under VOS_FIXTURE_CACHE_DIR (default eval/runs/fixture-cache,
gitignored), keyed by a sha256 over the text and every synthesis input (model URL, voice, audio
format, instructions, prompt template, cache schema version). A cache hit needs neither the key nor
`websockets`. VOS_FIXTURE_CACHE=0 bypasses the cache (no read, no write).
"""

import asyncio
import base64
import contextlib
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path

URL = "wss://api.x.ai/v1/realtime?model=grok-voice-3.0"
FMT = {"type": "audio/pcm", "rate": 16000}
VOICE = None  # None: the server's default voice (not sent). A name pins one; it is in the cache key.
INSTRUCTIONS = ("You are a text-to-speech engine. Speak the user's text exactly, word for word, "
                "with nothing added.")
PROMPT = 'Read this aloud exactly, word for word, and say nothing else: "{text}"'

# Bump when the cache layout or the meaning of an entry changes; every old entry then misses.
CACHE_VERSION = 1
DEFAULT_CACHE_DIR = Path(__file__).resolve().parents[2] / "eval" / "runs" / "fixture-cache"


async def synth(text):
    import websockets  # only on a cache miss: a hit needs neither the package nor the key

    headers = {"Authorization": "Bearer " + os.environ["GROK_API_KEY"]}
    session = {"instructions": INSTRUCTIONS, "audio": {"input": {"format": FMT}, "output": {"format": FMT}}}
    if VOICE is not None:
        session["voice"] = VOICE
    async with websockets.connect(URL, additional_headers=headers, max_size=None) as ws:
        await ws.send(json.dumps({"type": "session.update", "session": session}))
        prompt = PROMPT.format(text=text)
        await ws.send(json.dumps({"type": "conversation.item.create", "item": {
            "type": "message", "role": "user", "content": [{"type": "input_text", "text": prompt}]}}))
        await ws.send(json.dumps({"type": "response.create"}))
        pcm = bytearray()
        said = ""
        while True:
            m = json.loads(await asyncio.wait_for(ws.recv(), 30))
            if m["type"] == "response.output_audio.delta":
                pcm += base64.b64decode(m["delta"])
            elif m["type"] == "response.output_audio_transcript.done":
                said = m["transcript"]
            elif m["type"] == "error":
                raise SystemExit("vendor error: " + json.dumps(m.get("error")))
            elif m["type"] == "response.done":
                return bytes(pcm), said


def words(s):
    return re.sub(r"[^a-z0-9 ]", "", s.lower()).split()


def cache_key(text):
    """sha256 over a canonical JSON of everything that shapes the audio, so any change misses."""
    spec = {"cache_version": CACHE_VERSION, "url": URL, "voice": VOICE, "format": FMT,
            "instructions": INSTRUCTIONS, "prompt": PROMPT, "text": text}
    canon = json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()


def cache_dir():
    """The cache directory, or None when VOS_FIXTURE_CACHE=0."""
    if os.environ.get("VOS_FIXTURE_CACHE", "1") == "0":
        return None
    return Path(os.environ.get("VOS_FIXTURE_CACHE_DIR") or DEFAULT_CACHE_DIR)


def write_atomic(path, data):
    """Write to a temp file in the same directory, then os.replace: readers never see a partial file."""
    path = Path(path)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix="." + path.name + ".", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise


def cache_load(d, key):
    """The cached PCM, or None if absent or damaged (its sidecar's length and sha256 must match)."""
    try:
        meta = json.loads((d / (key + ".json")).read_text(encoding="utf-8"))
        pcm = (d / (key + ".pcm")).read_bytes()
    except (OSError, ValueError):
        return None
    if (not isinstance(meta, dict) or meta.get("cache_version") != CACHE_VERSION or not pcm
            or meta.get("bytes") != len(pcm) or meta.get("sha256") != hashlib.sha256(pcm).hexdigest()):
        return None
    return pcm


def cache_store(d, key, text, pcm):
    """Store verified audio: the PCM first, then the sidecar that vouches for it."""
    meta = {"cache_version": CACHE_VERSION, "bytes": len(pcm), "sha256": hashlib.sha256(pcm).hexdigest(),
            "text": text}
    d.mkdir(parents=True, exist_ok=True)
    write_atomic(d / (key + ".pcm"), pcm)
    write_atomic(d / (key + ".json"), (json.dumps(meta, sort_keys=True) + "\n").encode("utf-8"))


def synthesize_verified(text):
    for attempt in range(1, 4):
        pcm, said = asyncio.run(synth(text))
        print(f"fixture attempt {attempt} says: {said}", file=sys.stderr)
        if words(said) == words(text) and pcm:
            return pcm
    raise SystemExit("the voice model would not read the fixture text verbatim")


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2:
        raise SystemExit(__doc__)
    text, out = argv
    d = cache_dir()
    key = cache_key(text)
    pcm = None
    if d is None:
        print("fixture cache: off (VOS_FIXTURE_CACHE=0)", file=sys.stderr)
    else:
        pcm = cache_load(d, key)
        print(f"fixture cache: {'hit' if pcm else 'miss'} {key[:16]}", file=sys.stderr)
    if pcm is None:
        if "GROK_API_KEY" not in os.environ:
            raise SystemExit(__doc__)
        pcm = synthesize_verified(text)
        if d is not None:
            try:
                cache_store(d, key, text, pcm)
            except OSError as e:
                print(f"fixture cache: not stored ({type(e).__name__}); continuing", file=sys.stderr)
    write_atomic(out, pcm)
    print(f"fixture: {len(pcm)} bytes ({len(pcm) / 32000:.2f} s)", file=sys.stderr)


if __name__ == "__main__":
    main()
