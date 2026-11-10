# Offline grammar

Status: the rules are built and JVM-tested (#343). No engine is bound and nothing in the app calls
them yet. Relates to §3.3 OfflinePlane and HOLD, P5, §5.5 step 6 and spike S-15 (#25) in
`docs/TARGET-PLAN.md`.

## Contract

`OfflineRecognizer` (actuator) is the engine seam: `start(listener)`, `feed(pcm)` (16 kHz mono
16-bit), `stop()`, `close()`. It reports a `Recognition`: a list of `Hypothesis(text, confidence
0..1)` in any order, and whether the utterance is final or a partial.

`OfflineGrammar.match(recognition)` returns one `Command` or null (no match). The vocabulary is
§3.3's list, stop/pause/volume/yes/no (volume up/down as M1-OFF-01 words it), plus cancel, which
is not in the plan and was added by the brief for #343. No other variants are matched.

| Command | Matches | Partial? | Confidence |
|---|---|---|---|
| STOP | the word `stop` anywhere, in any alternative | yes | ≥ 0.5 |
| CANCEL | the word `cancel` anywhere, in any alternative | yes | ≥ 0.5 |
| PAUSE | exactly `pause` | no | ≥ 0.7 |
| VOLUME_UP / VOLUME_DOWN | exactly `volume up` / `volume down` | no | ≥ 0.7 |
| NO | exactly `no` | no | ≥ 0.7 |
| YES | exactly `yes` (raw text `yes`, `yes.` or `yes!`), and no alternative holds no/not/don't/can't/nope/nah/never/wait | no | ≥ 0.9 |

- Text is normalized first: apostrophes dropped, then accents, lower case, other punctuation is a
  space, whitespace collapsed. Words match whole, so "yesterday", "stopwatch" and "nonstop" are not
  commands, but "non-stop" is a stop (the hyphen splits it; fail-safe on purpose).
- Stop and cancel win over every other reading, in any alternative, so "yes stop" is STOP. A false
  stop costs a summon; a missed stop costs the owner control (P5).
- Other commands read only the top alternative (highest confidence; ties keep the engine's order).
- `yes` has no variants: not "yeah", "yep", "yes please", "yes but", "no yes" or "um yes". §5.5
  asks for a spoken "yes" and step 6b accepts only `yes` with an optional trailing `.` or `!`, so
  the top reading's raw text (NFKC, trimmed, lower case) must match `yes[.!]?` too: "yes?", "'yes'"
  and "yes…" are not a yes.

`OfflineGrammar.confirm(recognition)` is the catastrophic confirmation (§5.5 step 6). It returns
YES only for a strict "yes". It returns CANCEL for stop or cancel (partials included), for any
other final, and for null, which the caller passes when the window closes on silence, a timeout or
an engine error. It returns null only to keep listening, on a partial without stop or cancel. It
is stateless: the caller acts on the first non-null result and drops anything that arrives after
the window closed, so a late final from `stop()` never counts as a yes.

## Not done here

- **The engine (S-15, #25).** Binding sherpa-onnx (and `createOnDeviceSpeechRecognizer` where it
  exists), the model files, and the bar of ≥95% on stop/pause/yes/no fixtures in quiet and on a
  noisy floor. The thresholds above are provisional until S-15 measures the engine's real scores;
  S-15 should also measure whether "yes" needs a confidence margin between the top two readings.
- **Wiring.** HOLD's offline-only summon, offline "stop" during a turn (M1-STOP-01), APP_ASSIST's
  armed stop (S-31) and the confirm window. `AudioPlane` still refuses a summon during a call.
- **The wider offline set.** M1-OFF-01 also lists play, next, time, "timer 5 minutes" and "turn on
  wifi" offline. They are not in §3.3's grammar list and are not added here.
- **Other languages.** English only.
