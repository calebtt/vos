# Turn-detection pause sweep (Phone B, #247)

This sweep picks the end-of-turn values for the owner's patience levels (decision 31, §9 Q33). Until it runs, `session/src/main/res/raw/model_policy.json` holds **provisional** values on `xai:grok-voice-3.0`, under `turn_detection`:

| patience | silence_duration_ms | prefix_padding_ms |
|---|---|---|
| standard | 600 | 300 |
| long | 1200 | 300 |
| extra_long | 2000 | 400 |

The sweep measures, for each candidate silence:
- how often a mid-sentence pause cuts the owner off (a false cut-in);
- how long the owner waits for the reply (speech end to first reply audio).

The values it picks replace the provisional ones in `model_policy.json`. Drop `"provisional": true` and the `_comment` in the same change, and keep the "never `idle_timeout_ms`" rule.

## Ground rules
- **Device:** Phone B (Pixel 7), debug builds of the actuator and session. The debug overrides below are ignored in release builds.
- **Daytime only (08:00–22:00):** a turn with `--ez speaker_file true` plays nothing, but use the same daytime window as the other eval runners (`vos_daytime_only` in `eval/m1/lib.sh`).
- **One device:** `ANDROID_SERIAL` picks it, as in every `eval/m1` runner.
- **Paid API calls:** the session must hold a sealed xAI key (`vos_seal_key_if_missing` in `eval/m1/lib.sh`). Each trial is one real `grok-voice-3.0` turn. Fixture synthesis is also a paid call: make the fixtures once and reuse them.

## Setup (once, before any trial)
Same order as `eval/m1/voice-roundtrip.sh`. From the repo root:

```bash
ROOT="$(pwd)"
source "$ROOT/eval/m1/lib.sh"
./gradlew :actuator:assembleDebug :session:assembleDebug
vos_install_apps
vos_grant_mic   # needed even when the mic is a file (foreground service)
vos_seal_key_if_missing
vos_clear_debug_overrides   # drops a stale files/debug/chat_voice (e.g. debug:echo from turn-pipes)
```

Without the mic grant the summon is refused and the trial produces nothing. Without clearing `chat_voice`, a leftover `debug:echo` override makes every trial skip xAI.

## 1. Build a pause fixture (once)
A trial needs owner audio with natural mid-sentence pauses of 0.5–1.5 s. Build it from short spoken clips joined by silence.

1. Synthesize the clips with `eval/m1/make_fixture.py`. It writes 16 kHz mono s16le PCM and checks the transcript.

   ```bash
   # needs GROK_API_KEY in the environment (never printed) and python3 with websockets
   d=eval/runs/pause-fixture; mkdir -p "$d"
   python3 -I eval/m1/make_fixture.py "Set a reminder for tomorrow" "$d/a.pcm"
   python3 -I eval/m1/make_fixture.py "to call the dentist" "$d/b.pcm"
   python3 -I eval/m1/make_fixture.py "about moving my appointment." "$d/c.pcm"
   ```

2. Join them with chosen pauses: 32,000 bytes of zeros per second at 16 kHz s16le. This makes one fixture per pause length, so a trial can test one pause at a time:

   ```bash
   for gap in 0.5 0.8 1.2 1.5; do
     python3 -I - "$d/a.pcm" "$d/b.pcm" "$d/c.pcm" "$gap" "$d/pause-$gap.pcm" <<'PY'
   import sys
   a, b, c, gap, out = sys.argv[1:]
   z = bytes(int(16000 * float(gap)) * 2)
   open(out, "wb").write(open(a, "rb").read() + z + open(b, "rb").read() + z + open(c, "rb").read())
   PY
   done
   ```

   `eval/m1/chat.sh` joins clips the same way.

`eval/runs/` is gitignored: never commit fixtures.

## 2. Each trial
Use at least three `silence_duration_ms` values per patience level, bracketing the provisional value. For example: standard 500/600/800, long 1000/1200/1500, extra_long 1600/2000/2500. Run each value against each pause fixture a few times.

0. **Confirm no stale chat_voice override** (also done in Setup; repeat if you ran another eval):

   ```bash
   adb shell "run-as com.vos.session rm -f files/debug/chat_voice"
   # or: vos_clear_debug_overrides
   ```

1. **Set the patience.** The session reads it from `files/debug/patience`: `standard`, `long` or `extra_long`.

   ```bash
   adb shell "run-as com.vos.session sh -c 'mkdir -p files/debug && echo long > files/debug/patience'"
   ```

2. **Set the silence for this trial.** It overrides the policy's `silence_duration_ms`. Out-of-range values (≤ 0 or > 10000) are ignored.

   ```bash
   adb shell "run-as com.vos.session sh -c 'mkdir -p files/debug && echo 1200 > files/debug/silence_duration_ms'"
   ```

   Both files are read when each turn's backend is built, so a new value applies from the next turn.

3. **Push the fixture as the mic.** This is the same command `eval/m1/voice-roundtrip.sh` uses. It begins with `rm -rf files/debug` on the actuator, so any other actuator debug file set for the sweep is wiped each trial:

   ```bash
   adb shell "run-as com.vos.actuator sh -c 'rm -rf files/debug; umask 077 && mkdir -p files/debug && cat > files/debug/mic-inject.pcm'" < "$d/pause-1.2.pcm"
   ```

4. **Clear the log and start a turn** with the injected mic. The reply goes to a file, so no sound plays:

   ```bash
   adb logcat -c
   adb shell am start -n com.vos.actuator/.DebugSummonActivity --es op start --ez mic_file true --ez speaker_file true
   ```

5. **Wait for the turn to end** (`over after_ms=` in `VOS_VOICE`, as in voice-roundtrip), then save the log:

   ```bash
   adb logcat -d -s VOS_VOICE VOS_TURN VOS_SESSION > "trial-long-1200-gap1.2.txt"
   ```

## 3. Read the log lines (`VOS_VOICE`)
Every line names the turn (`turn <turnId>`) and the settings in use: `patience=… silence_duration_ms=… prefix_padding_ms=…`, where `vendor` means the vendor's default.

- `turn detection patience=… silence_duration_ms=… prefix_padding_ms=…`: logged once, when the session is configured. Confirm the trial's values took.
- `speech end patience=… silence_duration_ms=… prefix_padding_ms=…`: logged at each end of owner speech the vendor detected.
- `first reply audio speech_end_to_audio_ms=N patience=… silence_duration_ms=… prefix_padding_ms=…`: logged at the first audio of each reply. N is the wait from the last speech end.

## 4. What to record per trial
- **The settings:** patience, `silence_duration_ms`, `prefix_padding_ms`, the fixture's pause length, and the turnId.
- **False cut-ins:** the log has no clip-boundary timestamps, so count lines. With one joined utterance there should be exactly one `speech end` line (after the last clip). More than one `speech end`, or a `first reply audio` before the last `speech end`, is a false cut-in.
- **Speech end to first audio:** `speech_end_to_audio_ms` from the reply that answers the whole utterance.
- **The transcript:** whether the owner transcript (`files/transcripts/transcripts.jsonl` in the session) holds the whole sentence or a fragment.

Pick, per patience level, the smallest silence with no false cut-ins at that level's pauses (standard: 0.5–0.8 s; long and extra_long: up to 1.5 s), weighed against speech-end-to-first-audio.

## 5. After the sweep
1. Put the chosen values in `model_policy.json` under `xai:grok-voice-3.0` → `turn_detection`. Remove `"provisional": true` and the `_comment`, update the file's top `_comment`, and update `ModelPolicyTest`'s expected values.
2. Clear the overrides: `adb shell "run-as com.vos.session rm -f files/debug/patience files/debug/silence_duration_ms"` (`vos_clear_debug_overrides` clears only `chat_voice`).
3. Record the trial table in #247 and close it.
