"""Speak a test question as 16 kHz mono s16le PCM, through the xAI realtime API (eval fixture).

Usage: GROK_API_KEY=... python3 -I eval/m1/make_fixture.py "What time is it?" out.pcm

Needs the `websockets` package. The key is read from the environment and never printed. The model
is told to read the text aloud verbatim, and the transcript it reports must match the text (case
and punctuation aside); a voice model sometimes answers the question instead, so it retries up to
three times and fails rather than write a wrong fixture.
"""

import asyncio
import base64
import json
import os
import re
import sys

import websockets

URL = "wss://api.x.ai/v1/realtime?model=grok-voice-3.0"
FMT = {"type": "audio/pcm", "rate": 16000}


async def synth(text):
    headers = {"Authorization": "Bearer " + os.environ["GROK_API_KEY"]}
    async with websockets.connect(URL, additional_headers=headers, max_size=None) as ws:
        await ws.send(json.dumps({"type": "session.update", "session": {
            "instructions": "You are a text-to-speech engine. Speak the user's text exactly, word for word, "
                            "with nothing added.",
            "audio": {"input": {"format": FMT}, "output": {"format": FMT}}}}))
        prompt = f'Read this aloud exactly, word for word, and say nothing else: "{text}"'
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


def main():
    if len(sys.argv) != 3 or "GROK_API_KEY" not in os.environ:
        raise SystemExit(__doc__)
    for attempt in range(1, 4):
        pcm, said = asyncio.run(synth(sys.argv[1]))
        print(f"fixture attempt {attempt} says: {said}", file=sys.stderr)
        if words(said) == words(sys.argv[1]) and pcm:
            break
    else:
        raise SystemExit("the voice model would not read the fixture text verbatim")
    with open(sys.argv[2], "wb") as f:
        f.write(pcm)
    print(f"fixture: {len(pcm)} bytes ({len(pcm) / 32000:.2f} s)", file=sys.stderr)


if __name__ == "__main__":
    main()
