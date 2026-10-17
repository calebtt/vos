# AndroidWorld → VOS: what to adapt (2026-10-17)

Sources: `google-research/android_world` at `30583e2` (shallow clone), `~/vos-nav-evals` (code only), TARGET-PLAN decisions 22–28. "(check)" marks facts not verified here; web lookups were unavailable.

## TL;DR

- **Reuse:** task templates, random parameters and seeds, and the `is_successful` checks of the roughly 30 tasks that need no root (System, Markor, Files, Browser).
- **Skip:** the `env/` runtime (emulator, gRPC, a11y forwarder, root, snapshots), the per-step M3A summary call on-device, pinned APKs, and SMS/phone tasks.
- **Blockers:** 64 of the 116 tasks read app SQLite under `/data/data`, and every task sets the clock. Both need `adb root`, which a Graphene user build lacks. Some setups wipe real contacts and alarms.
- **Effort:** minimal port (about 30 tasks) ≈ 4–5 days; fuller adoption (75–90 tasks) ≈ 3–4 weeks total.
- **First step:** a 1-day adapter spike that runs the 16 System tasks through `--verify-tasks` (§6).

## 1. Reuse, adapt, or skip

| Part | Verdict | Why |
|---|---|---|
| Tasks: `registry.py` `_TASKS` (91) + `task_evals/information_retrieval/proto/tasks.textproto` (25 IR) | **Adapt a subset** | Templates and seeded parameters give thousands of variants against the harness's 27 tasks. Most tasks need root or the emulator (§2). |
| Non-root checks: `single/system.py` (`settings get`), `single/markor.py` + `common_validators/file_validators.py`, `single/files.py`, `single/browser.py`, `single/camera.py`, `single/audio_recorder.py`, `common_validators/contacts_validators.py` (`content query`) | **Reuse as-is** behind a shim | Plain shell, file, and a11y reads. |
| SQLite checks (`task_evals/utils/sqlite_utils.py`: calendar, expense, recipe, retro_music, vlc, osmand, tasks, opentracks, joplin) and `sms_validators.py` | **Adapt or skip** | They pull `/data/data/<pkg>/databases/*`, which needs root. |
| M3A/T3A prompts (`agents/m3a.py:28-201`, `agents/t3a.py`) and actions (`env/json_action.py`) | **Adapt pieces** | Numeric-index marks have the digit-collision flaw from DIAGNOSIS-2026-10-17 §3. The harness already has tap-by-text, letter marks, `set_toggle`, and `done`+answer. Keep a mapping from AW actions to harness actions for comparability. Borrow a few GUIDANCE lines (already achieved → done, autocomplete lists, text selection). |
| Summary memory (`m3a.py:172-201`, `:568-606`) | **Skip on-device** | It adds a second multimodal call per step, roughly doubling E4B latency. Harness history v2 (state diffs) does the job for free. It could be an option for escalated cloud steps or offline AppMemory write-back. |
| `env/` (`android_world_controller.py`, `env_launcher.py`, `interface.py`, `adb_utils.py`, `setup_device/`) | **Skip the runtime; lift helpers** | Built on `android_env` with emulator gRPC 8554 (`android_world_controller.py:305-329`), the `A11yGrpcWrapper` forwarder (`:140-150`, `google/apps/java/.../AccessibilityForwarder.kt`), and `adb root` (`adb_utils.py:1752`). `vosnav/device.py` already covers the phone. Lift `representation_utils.py:178` `xml_dump_to_ui_elements`. |

AW scores a task only when the agent says done (`suite_utils.py:261`), the same as the harness's strict pass, so the numbers are comparable. `TaskEval.complexity` (`task_eval.py:52-62`, about 2× human steps) is a good source of `max_steps`.

## 2. What changes on a Pixel 7 running GrapheneOS

**No root (biggest change).**
- These break without root:
  - `datetime_utils.set_datetime`, called by every task through `task_eval.py:146`.
  - `utils/app_snapshot.py`, which copies `/data/data` and runs `chmod 777`.
  - `file_utils.tmp_directory_from_device`.
  - `adb_utils.execute_sql_command`.
  - The OsmAnd `chcon` (`apps.py:586`).
- **Fix:** make the time set a no-op, and rebase `device_constants.DT` (2023-10-15) to "now". Replace snapshots with the harness's setup and cleanup.
- **For SQLite apps, pick one:**
  - (a) Build the apps debuggable from current upstream and read their DBs with `run-as <pkg>`, which works without root.
  - (b) Use the app's own export to shared storage.
  - (c) Skip the task.

  Don't root the phone; decision 4 rejects root.

**Destructive setup.**
- `contacts_utils.clear_contacts` and `adb_utils.delete_contacts` run `pm clear com.android.providers.contacts`, which wipes all contacts.
- `clock._close_clock_app` runs `pm clear` on Clock, which wipes alarms.
- `AppSetup.setup` clears each app's data.
- SMS setup changes the default SMS app.

Run those tasks only in a Graphene secondary user ("vos-eval"; `--user N`; providers are per-user), never in the owner profile.

**Emulator-only features.**
- `emu gsm call` and `emu sms send` (`adb_utils.py:1308`, `:1385`) make the 6 SMS tasks and `MarkorCreateNoteAndSms` need a second SIM, so skip them. Phone tasks are already disabled upstream.
- The forwarder app is replaced by uiautomator/VosDump now (`A11yMethod.UIAUTOMATOR` already exists) and by the actuator's a11y service later.
- The 1080×2400 screen (`device_constants.py:20`) matches panther.

**Package map.**
- `_PATTERN_TO_ACTIVITY` (`adb_utils.py:35-169`) names Google apps:

| App | AW expects | GrapheneOS |
|---|---|---|
| Clock | `com.google.android.deskclock` | `com.android.deskclock` |
| Contacts | `com.google.android.contacts` | `com.android.contacts` |
| Files | `com.google.android.documentsui` | `com.android.documentsui` |
| Phone | `com.google.android.dialer` | `com.android.dialer` |
| Browser | Chrome | `app.vanadium.browser` |
| Camera | `com.android.camera2` | `app.grapheneos.camera` (check) |

- The Clock checks match Google Clock strings (`"Lap"`, `"00h 00m 00s"`), so re-verify them on AOSP DeskClock.

**Google/Play.** No task needs Play services. The browser tasks move to Vanadium; test that `file://` pages open from Download.

**App installs.** AW installs pinned APKs from `storage.googleapis.com/gresearch/android_world/` (`apps.py:39-62`). Treat AW as a labelled benchmark lane, not an M3 gate; the no-pin rule stands.

| AW app (pinned) | Tasks | Current source |
|---|---|---|
| Markor | 13 (+1) | F-Droid `net.gsantner.markor`; grant all-files access with `appops`. Best first app. |
| Simple Calendar / Gallery / Draw Pro, SMS Messenger | 8 + 9 IR, 1, 1, 6 | Fossify forks `org.fossify.*` (check); package and DB paths change |
| Pro Expense, Broccoli | 9, 13 | `com.arduia.expense` (check); Broccoli on F-Droid |
| Tasks.org, OpenTracks, Joplin | 6, 6, 4 (all IR) | F-Droid; Joplin from GitHub (check) |
| VLC, Retro Music, Audio Recorder | 2, 4, 2 | F-Droid (Retro: check) |
| OsmAnd `net.osmand` | 3 | F-Droid build is `net.osmand.plus` (check), so the paths in `osmand.py:33,282` move |
| Clipper, androidworld.apk, MiniWoB | — | Skip; Android 10+ blocks background clipboard reads |

**Upstream bug.** `setup_device/setup.py:134-137` uses undefined `apk` and `raw_env`. Pin a commit rather than track main.

**How many tasks port.** About 44 run without root in a test user. About 30 are safe on a phone that holds owner data: System, Markor, Files in a test directory, and Browser.

## 3. Mapping onto the harness and decisions 22–28

**Harness.**
- Fields map as follows: `initialize_task` → `setup`, `is_successful` → `check`, `tear_down` → `cleanup`, `interaction_cache` → `ctx["answer"]`.
- Each ported task needs a `satisfy()` for `--verify-tasks`; AW has no scripted oracles.
- Shape:
  - `vosnav/aw.py`: `AwTask(Task)` wraps a `TaskEval`.
  - An `AwEnv` shim provides `controller.execute_adb_call()` (shell, settings, pull/push) on top of `Adb`.
  - `get_state().ui_elements` comes from `xml_dump_to_ui_elements(device.uiautomator_dump())`.
- `android_env` is needed only for the `adb_pb2` protos. No emulator.

**Decision 22 (Conductor authority, actuator veto).**
- Harness `Safety` already plays the actuator veto.
- The AW delete tasks (Markor, Files, Expense, Recipe) are irreversible acts. Give them a `delete` whitelist scoped to the test folder or package and log them as confirm-required. In VOS they go through the trusted path, with an audited auto-confirm stub in eval mode.
- The Conductor never sees the AW check.

**Decision 23 (task records).** Each run is one `vos.task_record/0` with `source: eval`. `export_to_vos.py` gains `suite: androidworld`, the task class, params + seed, and the app `versionName`.

**Decision 24 (AppMemory).**
- Seeded parameters on the same apps give a clean memory-off/memory-on A/B for M3-MEM-01.
- Routes are written only from AW-verified passes, with content turned into placeholders (§3.9).

**Decision 25 (done condition).**
- `is_successful` is the hidden ground truth (VERIFIED). Don't give it to the agent as `success_when`. The Planner writes its own predicate, and we log how often it agrees with the AW check; that measures decision 25 directly.
- `*Verify` tasks (for example `SystemWifiTurnOnVerify`) start satisfied on purpose. The harness fails them as `precondition_already_true`, so add an `expect_already_true` flag.

**Decision 26 (`ui_step` escalation).**
- Use `complexity` for step caps.
- `stuck_escalation` (`agent.py:376`) is unchanged.
- The long Markor and Expense tasks will exercise bounded escalation.

**Decision 27 (`set_toggle` / `tap_seq`).**
- The Wi-Fi and Bluetooth tasks exercise `set_toggle`.
- `ClockTimerEntry`, expense amounts, and `BrowserMultiply` are `tap_seq` cases.
- Brightness max/min is a slider, which no tool covers yet (gap: `ui.set_slider`?).

**Decision 28 (screen settled).**
- AW waits with fixed sleeps:
  - `base_agent.py:52`: 1.0 s.
  - `m3a.py:342,540`: 2.0 s.
  - `interface.py:243-290`: three equal element lists, 0.5 s apart.
  - `apps.py`: sleeps of 2–10 s.
- Drop them, and gate on `wait_settled` now and `screen.settled` later.
- AW supplies M3-SETTLE-01 fixtures:
  - `ClockStopWatchRunning`: the digits change constantly, so they must be tagged always-moving or the screen never settles.
  - VLC and Retro playback.
  - The OsmAnd map view.

## 4. License and notice obligations

- android_world is Apache-2.0, with **no NOTICE file**.
- When we copy code:
  - Ship the license text (`third_party/android_world/LICENSE`).
  - Keep the `The android_world Authors` headers.
  - Mark changed files "Modified by VOS" (§4(b)).
  - Record the commit.
  - Do not imply Google endorsement.
- The MiniWoB HTML is MIT (`apps/java/com/google/androidenv/miniwob/app/assets/html/LICENSE`). We skip it.
- `requirements.txt` lists `fuzzywuzzy` and `python-Levenshtein` (GPL-2.0), but no code imports them (`utils/fuzzy_match_lib.py` uses `difflib`), so don't install them. `android_env` is Apache-2.0.
- Task app licenses:
  - GPL-3.0: Fossify, Broccoli, Tasks.org, Retro Music, OsmAnd.
  - GPL-2.0+: VLC.
  - Apache-2.0: Markor, OpenTracks, Audio Recorder.
  - AGPL (check): Joplin.
  - Unknown (check): Pro Expense.
- Installing these apps for our own tests creates no obligation. Redistributing APKs or debuggable rebuilds would require source offers.
- Don't vendor the APKs or the GCS data (receipts, videos, the `.obf` map). Their terms aren't stated, and OSM maps need ODbL attribution.

## 5. Effort

**Minimal port** (about 30 tasks: System 16, Markor ~12, Files 2, Browser 3):

| Work | Days |
|---|---|
| Shim, wrapper, package map, root no-ops | 1.5 |
| Test user, F-Droid Markor, `appops`, safety allowlist; add brightness to `capture_snapshot` | 0.5 |
| `satisfy()` oracles and a clean `--verify-tasks` | 1.5 |
| Export fields, first Grok and E4B runs | 1 |
| **Total** | **≈ 4–5** |

**Fuller adoption** (75–90 tasks), on top of the minimal port:

| Work | Days |
|---|---|
| SQLite apps: debuggable builds or export oracles, schema checks against the forks | 5–6 |
| Date rebasing for the calendar, expense, and IR tasks | 2 |
| Clock, Camera, Audio, and Contacts in the test user | 1–2 |
| M3A baseline backend | 1–2 |
| Planner-agreement scoring, AppMemory hooks | 2 |
| **Total** | **≈ 11–14 more (3–4 weeks overall)** |

This excludes moving onto the actuator (M3 work) and the SMS tasks.

## 6. Recommended first step

A one-day spike in `~/vos-nav-evals`:

1. Vendor android_world at `30583e2` under `third_party/android_world/` with its LICENSE and modified-file headers.
2. Write `vosnav/aw.py` and make the time set and snapshot restore no-ops.
3. Run `--verify-tasks` on the 14 System tasks plus the 2 composites in the owner profile. They change only global settings, which the harness snapshot restores once brightness is added.

**Exit:**
- All tasks verify with no settings drift.
- One mode-C run each on Grok and E4B, exported to RESULTS-LOG.

**Next:** Markor in a "vos-eval" secondary user.
