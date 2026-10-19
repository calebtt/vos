# Spike probes: S-07 and S-08 (throwaway)

These are debug-only test APKs for [S-07](../../docs/spikes/S-07.md) (a mic FGS started from a summon) and
[S-08](../../docs/spikes/S-08.md) (PCM pipe from actuator to session). They have their own Gradle build. This build is **not**
in the root `settings.gradle.kts`, so root CI and `ci/policy_check.py` never build or check these APKs.

Ground rules: the probes make no sound (no playback, TTS or tones). They have no INTERNET, no telephony and no alarms. Mic audio
is read and then thrown away; it is never stored. The only permissions are `RECORD_AUDIO`, `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MICROPHONE`, plus the S-08 signature permission between the two S-08 apps.

Run every command from the repo root. Each block restores the phone at the end. `ANDROID_HOME` must be set, or copy the root
`local.properties` into `spikes/probes/`.

## Build

```sh
./gradlew -p spikes/probes assembleDebug
S07_APK=spikes/probes/probe-s07/build/outputs/apk/debug/probe-s07-debug.apk
S08A_APK=spikes/probes/probe-s08-actuator/build/outputs/apk/debug/probe-s08-actuator-debug.apk
S08S_APK=spikes/probes/probe-s08-session/build/outputs/apk/debug/probe-s08-session-debug.apk
```

Time marker for collecting logs without clearing logcat (use one per run):

```sh
T0="$(adb shell date '+%m-%d %H:%M:%S.000')"
```

## S-07: summon to first non-zero mic buffer

> **Run notes (2026-10-18, Phone B, Graphene user build):**
> - `cmd voiceinteraction show` / `hide` fail with `SecurityException: requires ACCESS_VOICE_INTERACTION_SERVICE` on a user build. The assist-gesture leg needs a real human gesture.
> - `cmd statusbar click-tile` only reaches the tile while QS is expanded (`cmd statusbar expand-settings`). The run used real `input tap`s on the tile's bounds from `uiautomator dump`.
> - `adb logcat -t "$T0"` with a space in `$T0` is mangled by the remote shell. Use `adb logcat -d -s VOS_S07` on a fresh install instead.

App `com.vos.spike.s07` has a VoiceInteractionService (so it can hold the ASSISTANT role), a session, a stub
recognition service, a QS tile `.ProbeTile` and a microphone FGS `.MicFgs`. Each summon runs one trial:

1. The handler (`VoiceInteractionSession.onShow` or `TileService.onClick`) records `t_event = SystemClock.uptimeMillis()` and calls `startForegroundService`.
2. `MicFgs` calls `startForeground(MICROPHONE)` and records `t_fgs`.
3. It opens `AudioRecord` (16 kHz mono, VOICE_RECOGNITION), records `t_first_nonzero` at the first buffer with a non-zero sample, and stops after about 1 s.

The trial is logged as one line:

```
VOS_S07 trial source=<show|tile> locked=<bool> t_event=.. t_fgs=.. t_first_nonzero=.. latency_ms=.. ok=<bool> err=<msg|none>
```

When `ok=false`, `err` is one of:

- `ForegroundServiceStartNotAllowedException:...` or `SecurityException:...`: the FGS was refused.
- `no_nonzero_sample_in_1000ms`: the mic was silenced.
- `busy`: a summon arrived while a trial was still running.

`locked` is the `KeyguardManager.isKeyguardLocked()` value at the handler. If `startForeground` is refused, the platform may crash
the probe process right after the line is logged (startForegroundService without startForeground). That's expected. The next
summon restarts the process.

```sh
# Check the role syntax on this build first. On API 34/35 it is:
#   add-role-holder [--user USER_ID] ROLE PACKAGE [FLAGS]
adb shell cmd role help

adb install -r "$S07_APK"
adb shell pm grant com.vos.spike.s07 android.permission.RECORD_AUDIO

# Save the current assistant state (can be empty on GrapheneOS)
adb shell cmd role get-role-holders android.app.role.ASSISTANT | tee /tmp/vos_s07_assistant_before.txt
adb shell settings get secure voice_interaction_service | tee -a /tmp/vos_s07_assistant_before.txt

# Make the probe the assistant (ASSISTANT is exclusive: this replaces the current holder)
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.vos.spike.s07
adb shell cmd role get-role-holders android.app.role.ASSISTANT          # expect: com.vos.spike.s07
adb shell settings get secure voice_interaction_service                 # expect: com.vos.spike.s07/.ProbeVoiceInteractionService

# Add the QS tile
adb shell cmd statusbar add-tile com.vos.spike.s07/.ProbeTile

# Trials, unlocked (20 per source)
T0="$(adb shell date '+%m-%d %H:%M:%S.000')"
for i in $(seq 20); do adb shell cmd voiceinteraction show; sleep 3; adb shell cmd voiceinteraction hide; sleep 2; done
for i in $(seq 20); do adb shell cmd statusbar click-tile com.vos.spike.s07/.ProbeTile; sleep 4; done

# Optional locked leg: screen off, then on, so the keyguard is up (the log records locked=true).
# First check "Screen locking sound" is off or the phone is muted (no sound at night).
#   adb shell input keyevent KEYCODE_SLEEP; sleep 2; adb shell input keyevent KEYCODE_WAKEUP
#   ...repeat the two loops above...

adb logcat -d -s VOS_S07 -t "$T0" > /tmp/vos_s07.log
python3 -I spikes/probes/summarize.py /tmp/vos_s07.log

# RESTORE
adb shell cmd statusbar remove-tile com.vos.spike.s07/.ProbeTile
# If /tmp/vos_s07_assistant_before.txt named a package ORIG:
#   adb shell cmd role add-role-holder android.app.role.ASSISTANT ORIG
# If it was empty:
#   adb shell cmd role remove-role-holder android.app.role.ASSISTANT com.vos.spike.s07
adb uninstall com.vos.spike.s07
adb shell cmd role get-role-holders android.app.role.ASSISTANT          # compare with the saved file
adb shell settings get secure voice_interaction_service
```

## S-08: PCM pipe, actuator to session

There are two APKs, signed with the same debug key from this machine:

- **`com.vos.spike.s08.actuator`** has a bound `.ActuatorService` guarded by the signature permission
  `com.vos.spike.s08.permission.BIND_ACTUATOR`. On the OPEN transaction it returns the read end of
  `ParcelFileDescriptor.createReliablePipe()`. Then it starts itself as a microphone FGS and writes each 20 ms frame (320
  samples at 16 kHz) as `[long seq][long uptimeNanos at AudioRecord.read return][PCM]`.
- **`com.vos.spike.s08.session`** has `.RunActivity`, a button-less activity that starts on its own. I chose an
  activity over `am start-foreground-service` because the session app then needs no FGS type of its own. The activity binds
  the actuator (`BIND_INCLUDE_CAPABILITIES`), stamps each frame on read and logs the added latency. It keeps the screen on.
  It is exported but guarded by `android.permission.DUMP`, so only the shell (and system) can start it.

Extra: `--ei frames N` (default 10000, which takes about 200 s; 90000 is the 30-minute soak).

An underrun is a wait of more than 40 ms for the next frame, or a gap in `seq`. `gaps` counts the seq gaps alone.

```
VOS_S08 progress frames=1000 last_ms=.. underruns=.. gaps=..             (every 1000 frames)
VOS_S08 result frames=.. p50_ms=.. p95_ms=.. max_ms=.. underruns=.. gaps=.. err=..
VOS_S08 actuator done frames=.. zero_frames=.. fgs=<bool> err=..         (zero_frames high = mic silenced)
```

```sh
# Install the actuator FIRST: it defines the signature permission the session needs.
adb install -r "$S08A_APK"
adb install -r "$S08S_APK"
adb shell pm grant com.vos.spike.s08.actuator android.permission.RECORD_AUDIO

# Phone unlocked, screen on
T0="$(adb shell date '+%m-%d %H:%M:%S.000')"
adb shell am start -W -n com.vos.spike.s08.session/.RunActivity --ei frames 10000
until adb logcat -d -s VOS_S08 -t "$T0" | grep -q 'VOS_S08 result'; do sleep 10; done
adb logcat -d -s VOS_S08 -t "$T0" > /tmp/vos_s08.log
python3 -I spikes/probes/summarize.py /tmp/vos_s08.log

# Soak (30 min): the same, with --ei frames 90000

# RESTORE
adb uninstall com.vos.spike.s08.session
adb uninstall com.vos.spike.s08.actuator
```

If the session logs `bind_failed` or `open_failed ... SecurityException`, the session was probably installed before the
actuator. Reinstall the session.

The S-08 echo/AEC leg isn't covered here, because it needs speaker playback.
