# Emulator-first testing (#347)

How VOS checks phone-dependent behaviour on the API 36 lane emulator first, then confirms it on Phone B. Approved by the owner on 2026-11-10. The lane itself (AVDs, `create-avd.sh`, `start-avd.sh`, which runners run there) is in [eval/README.md, "Emulator lane"](../../eval/README.md#emulator-lane); this page adds the methods found in a capability run on 2026-11-10 on the lane AVDs (`vos_emu_lane_api35`, `vos_emu_lane_api36`, emulator 37.1.11).

## Rules

- **Emulator results are pre-checks, never acceptance.** A PASS on the emulator says the mechanic works; the acceptance run is on Phone B. Every row below names what the phone still has to show.
- **`ANDROID_SERIAL=emulator-<port>` on every adb call** (or `adb -s emulator-<port>`), as in the lane's "Keeping the lane off Phone B".
- **Install with `adb install -r -t` only**, through `vos_install_apps` in `eval/m1/lib.sh` (see "Install order" below). No flashing, no wipes, no `-wipe-data`, no `create-avd.sh --force` as part of a test method.
- **Paid vendor calls only when voice code changed, or with the owner's go-ahead.** Keyless runs (debug echo backend, file mic and speaker) are the default.
- **The gRPC token stays private:** never print it, log it, paste it in an issue or commit it.
- Not needed: the host-audio path (`-allow-host-audio`) failed to initialise on the test desktop, and nothing here uses it.

## Install order on a fresh AVD (#341)

The session defines `com.vos.permission.ACTUATOR` and the actuator defines `com.vos.permission.SESSION`; each uses the other's. On a device with neither installed, the app installed before the other's definition exists is not granted it, and every turn then fails to bind. `vos_install_apps` installs the session, then the actuator, reads both grants back with `dumpsys package`, reinstalls an app that lacks its grant once, and returns 1 if one is still missing. On a fresh AVD the session (installed first) lacks `SESSION`, so one reinstall of the session is expected. Upgrades are not affected.

## Checks

| Check | Emulator method | Fidelity & limits | Phone confirmation still required |
|---|---|---|---|
| Turn-detection pause sweep (#247) | Mic injection (below) of the pause fixtures into a real `VOICE_COMMUNICATION` capture, with the **debug echo backend** (keyless): checks that the fixture's pauses reach the actuator intact (gaps preserved within ~10 ms) and that the trial plumbing works. A paid sweep on the emulator (real backend, vendor's turn detection) is **design only; needs the owner's go-ahead**. | API 36 only. No room acoustics, mic noise, AGC or real mic latency; the virtual mic's first-buffer timing (~460-540 ms) is not the Pixel's. The vendor's turn detection runs only on the real (paid) backend, so the keyless run says nothing about false cut-ins or wait times. | The sweep itself: [turn-detection-sweep.md](turn-detection-sweep.md) on Phone B picks the values in `model_policy.json`. |
| On-device acceptance items (#308) | The repo records no specific item list for #308, so generically: the `eval/m1` runners' on-device runs on the lane per [eval/README.md](../../eval/README.md#what-runs-there) (keyless set by default; keyed runners only under the paid-call rule), `m1/indicator.sh` (it turns VOS's accessibility service on only on an emulator and restores it), and the shellcheck nits (`shellcheck` on `eval/m1/*.sh` and `eval/emulator/*.sh`, which CI also runs; no device). | Same as the lane: API 35/36 AOSP image, not Phone B's Android 17 build; no real audio path. API 35 aliases the assistant stream to media (#312). | Each runner's acceptance run on Phone B where its Device column says so; `indicator.sh` there only if the owner already turned VOS's accessibility service on. |
| Assistant role, M1-ROLE-01 (#259) | `cmd role add-role-holder android.app.role.ASSISTANT com.vos.actuator 0` (API 35/36, no Play): `voice_interaction_service` becomes the actuator's `VosAssistService` and the audit has `role.assistant held=true`. `KEYCODE_ASSIST` opens a turn (52-148 ms to turn open, virtual timing); a second press stops it. `cmd role clear-role-holders android.app.role.ASSISTANT 0` gives `held=false` and one "VOS is not your assistant" notification. | No Google assistant holds the role at the start (the Pixel's real state). No assist gesture or power long-press, so no gesture timing. The turn-open time is virtual. | M1-ROLE-01 as written in TARGET-PLAN.md: setup taps, gestures in both navigation modes, locked and unlocked, a second role holder, and `m1/assistant.sh` (its timing to the first mic buffer means something only on the real mic). |
| Echo cancellation (#265) | Not testable: `AcousticEchoCanceler`, `NoiseSuppressor` and `AutomaticGainControl` report `isAvailable()` false on both images. A synthetic digital echo loop (speaker capture bridged into mic injection) exercises only VOS-side self-hearing and barge-in logic. | The loop is a clean digital copy, with no room, speaker or mic; its delay was not measured. Says nothing about platform AEC. | All of #265: `m1/echo-barge.sh` on Phone B (speaker and live mic, quiet room, daytime). |
| SMS / call signaling | `adb emu sms send <number> <text>` reaches the inbox (API 35/36). `adb emu gsm call/accept/cancel <number>` drives RINGING, OFFHOOK and IDLE; outgoing calls auto-answer and end with `KEYCODE_ENDCALL`. `gsm hold` fails. | Signaling only: no call audio, no carrier, no hold. | Anything that depends on call audio, the carrier or real messaging (real-comms tests are deferred per TARGET-PLAN.md). |
| Bluetooth | The virtual controller can be enabled. BLE GATT peripherals are possible through `emulated_bluetooth.proto` (untested). | Virtual only: no Bluetooth audio devices; headset, HFP and SCO need hardware. | Every headset, HFP/SCO and MFB long-press check, on Phone B with a real headset. |
| Keyguard / locked state | `locksettings set-pin <pin>`, `KEYCODE_SLEEP` / `KEYCODE_WAKEUP`, then `wm dismiss-keyguard` and `input text <pin>` to unlock. `KEYCODE_ASSIST` while locked still opened a turn. Use a throwaway PIN on the AVD only. | `locksettings clear` left `get-disabled` true; restore with `locksettings set-disabled false`. Before-first-unlock (BFU) state untested. | Locked summons and §3.6/§5.6 locked rules on Phone B (GrapheneOS keyguard, BFU after a reboot). |
| Speaker capture | gRPC `streamAudio` returns device playback (16 kHz mono): time reply start and stop, and barge-in ducking. | Digital output only, no speaker or room. | Speaker runs (`--speaker`) and any loudness or audibility check on Phone B. |
| Network delay | `adb emu network delay 300` (ms) and the emulator's speed profiles (`adb emu network speed ...`). | Shapes the emulator's link, not Wi-Fi or LTE behaviour. With the real backend it is a paid run. | Turn timing on Phone B's real network (M1-VOICE-01 is a Wi-Fi daytime smoke of the decision 31 targets). |
| Clock | Root (`adb root`), `settings put global auto_time 0`, then `date`. | AVD only; the AOSP image allows root. | Time-dependent behaviour on Phone B where the clock is not set by hand. |
| Reset between trials | `adb emu avd snapshot save NAME` once, then `adb emu avd snapshot load NAME` before each trial (~1.3-1.4 s). | Restores the whole AVD state, including installed apps and audit files: save after install and setup. `start-avd.sh` passes `-no-snapshot-save`, which affects only the quick-boot snapshot on exit. | None for the reset itself; Phone B trials reset per their runbooks. |

## How to run an injection

There is no helper script yet; one can come later. By hand:

1. **Boot without `-no-audio`.** `start-avd.sh` passes `-no-audio`, which disables the virtual mic, so start the API 36 AVD with the same flags minus `-no-audio`, plus `-grpc <port> -grpc-use-token`. Plain `-grpc` binds all interfaces with no auth; `-grpc-use-token` binds localhost and needs a bearer token, which the emulator writes to its running-instance ini file. Read it from there in the client; never print it.
2. **Use API 36.** On API 35 injection does not reach `AudioRecord` (consistent with #311).
3. **Open the mic first.** Start the turn (or whatever records), and wait for the actuator's capture log line before injecting. Injecting while no app records crashed the emulator (3 of 3).
4. **Inject** with `EmulatorController.injectAudio`: 16 kHz, signed 16-bit, mono PCM. The proto is at `$ANDROID_HOME/emulator/lib/emulator_controller.proto`. The path measured end to end was the actuator's real capture (`VOICE_COMMUNICATION`, `aec=false ns=false`) with the debug echo backend.
5. **Levels:** gain was ~1.1-1.2 on MIC and `VOICE_RECOGNITION`, and ~0.9 on `VOICE_COMMUNICATION`, so keep fixtures below about -3 dBFS peak to avoid clipping.
6. **Speaker side (optional):** `EmulatorController.streamAudio` returns playback as 16 kHz mono for timing.

## Open decisions for the owner

- **Paid emulator pause sweep.** Running the #247 sweep on the emulator with the real backend: design only, not approved. It would still be a pre-check (no room, no real mic timing), and costs vendor calls.
- **Human-recorded pause fixtures.** Whether to record real speech with real pauses for the sweep and injection, instead of or beside synthesised fixtures; where they live (off the repo) and who records them.
