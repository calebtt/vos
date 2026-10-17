# Nav eval results log

Running, append-only log of every attempted nav-eval run (see [TASKS.md](TASKS.md)).
Rows are written by `~/vos-nav-evals/export_to_vos.py <run_dir>`; never edit old rows,
add a correction row instead. Times are America/Chicago. Mock runs are harness smoke
tests, not model results. Latencies are medians per task. Task records in the VOS
task-record shape (TARGET-PLAN §3.8) are on the desktop in `~/vos-task-data/records/<run-id>.jsonl`; failure
screenshots are on the desktop in `~/vos-task-data/failures/<run-id>/<task>/`. Screenshots, raw model output and task records are training data and stay local, never in GitHub (Caleb, 2026-10-17).

## Run summaries (newest first)

<!-- SUMMARIES:BEGIN -->
### 20261017-115627_A_phone

- Started: 2026-10-17 11:57 CT · model: LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) · backend: phone · mode: A
- Result: **2/3 passed**, 0 not run/skipped · tokens: 14,633 · model p50 39.8 s · step p50 44.2 s
- Note: LFM mode A, part 2 (3 low-risk navigation tasks, max 6 steps).
- Failure screenshots: 3 on the desktop in `~/vos-task-data/failures/20261017-115627_A_phone/`

### 20261017-114231_A_phone

- Started: 2026-10-17 11:43 CT · model: LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) · backend: phone · mode: A
- Result: **0/3 passed**, 0 not run/skipped · tokens: 14,699 · model p50 42.6 s · step p50 46.4 s
- Note: LFM mode A (text list), part 1. ~50 s per step. Stopped by the operator during battery_saver_on because the model was toggling unrelated switches (Battery percentage, Battery share) and had entered Modes > Create your own mode; both switches were put back off by hand, no mode was created.
- Failure screenshots: 9 on the desktop in `~/vos-task-data/failures/20261017-114231_A_phone/`

### 20261017-111851_C_phone

- Started: 2026-10-17 11:39 CT · model: Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) · backend: phone · mode: C
- Result: **6/11 passed**, 1 not run/skipped · tokens: 54,708 · model p50 14.5 s · step p50 21.9 s
- Note: Bigger on-device model, mode C, the 9 tasks Gemma E2B ran plus Spotify. 6/9 on the comparable set. Low-memory killer killed the model when Spotify opened, so the Spotify tasks did not run on E4B (run stopped).
- Failure screenshots: 14 on the desktop in `~/vos-task-data/failures/20261017-111851_C_phone/`

### 20261017-014749_C_xai

- Started: 2026-10-17 01:48 CT · model: grok-4.7 (xAI API) · backend: xai · mode: C
- Result: **14/23 passed**, 5 not run/skipped · tokens: 246,875 · model p50 2.6 s · step p50 6.8 s
- Note: Same suite on the Grok API (14/23; 6/9 on the tasks Gemma also ran). Some API calls hung 20-78 s; 120 s read timeout. Spotify tasks after pause skipped at Caleb's request (music at 2 AM).
- Failure screenshots: 32 on the desktop in `~/vos-task-data/failures/20261017-014749_C_xai/`

### 20261017-011437_C_phone

- Started: 2026-10-17 01:14 CT · model: Gemma 4 E2B (LiteRT-LM, phone GPU) · backend: phone · mode: C
- Result: **2/9 passed**, 1 not run/skipped · tokens: 68,665 · model p50 8.2 s · step p50 12.9 s
- Note: Headline Gemma run (2/9). Killed at the time limit on the first Spotify task, so there is no summary.json; rebuilt from result.json and step files.
- Failure screenshots: 27 on the desktop in `~/vos-task-data/failures/20261017-011437_C_phone/`

### 20261017-010641_C_phone

- Started: 2026-10-17 01:06 CT · model: Gemma 4 E2B (LiteRT-LM, phone GPU) · backend: phone · mode: C
- Result: **0/7 passed**, 0 not run/skipped · tokens: 40,455 · model p50 6.0 s · step p50 10.2 s
- Note: Second try after the launcher fix. Stopped by the operator at settings_search_battery_saver to fix row labels (b3b4305).
- Failure screenshots: 27 on the desktop in `~/vos-task-data/failures/20261017-010641_C_phone/`

### 20261017-010120_C_phone

- Started: 2026-10-17 01:01 CT · model: Gemma 4 E2B (LiteRT-LM, phone GPU) · backend: phone · mode: C
- Result: **0/5 passed**, 0 not run/skipped · tokens: 29,369 · model p50 5.4 s · step p50 10.0 s
- Note: After the start-inside-app fix. Stopped by the operator at open_wifi_settings to fix the harness.
- Failure screenshots: 19 on the desktop in `~/vos-task-data/failures/20261017-010120_C_phone/`

### 20261017-005602_C_phone

- Started: 2026-10-17 00:56 CT · model: Gemma 4 E2B (LiteRT-LM, phone GPU) · backend: phone · mode: C
- Result: **0/14 passed**, 0 not run/skipped · tokens: 25,744 · model p50 5.2 s · step p50 10.3 s
- Note: Tasks started on the home screen. Gemma tapped launcher icons (GitHub, Calendar, Brave, and the Phone dialer; no call was placed) and the allowlist aborted. Led to start-inside-app and the launcher tap block (bdd48fd, 903b50d).
- Failure screenshots: 32 on the desktop in `~/vos-task-data/failures/20261017-005602_C_phone/`

### 20261017-003010_C_phone

- Started: 2026-10-17 00:30 CT · model: Gemma 4 E2B (LiteRT-LM, phone GPU) · backend: phone · mode: C
- Result: **0/2 passed**, 0 not run/skipped · tokens: 10,835 · model p50 4.3 s · step p50 8.4 s
- Note: First real phone run, two tasks. Harness before the agent prompt fixes.
- Failure screenshots: 8 on the desktop in `~/vos-task-data/failures/20261017-003010_C_phone/`

### 20261017-002137_C_mock (mock)

- Started: 2026-10-17 00:21 CT · model: mock (scripted) · backend: mock · mode: C
- Result: **2/3 passed**, 0 not run/skipped · tokens: 17 · model p50 0.0 s · step p50 4.6 s
- Note: Harness smoke test with the scripted mock backend. Not a model result.

### 20261017-002109_B_mock (mock)

- Started: 2026-10-17 00:21 CT · model: mock (scripted) · backend: mock · mode: B
- Result: **2/3 passed**, 0 not run/skipped · tokens: 17 · model p50 0.0 s · step p50 4.4 s
- Note: Harness smoke test with the scripted mock backend. Not a model result.

### 20261017-002041_A_mock (mock)

- Started: 2026-10-17 00:20 CT · model: mock (scripted) · backend: mock · mode: A
- Result: **2/3 passed**, 0 not run/skipped · tokens: 17 · model p50 0.0 s · step p50 4.4 s
- Note: Harness smoke test with the scripted mock backend. Not a model result.

<!-- SUMMARIES:END -->

## Log

<!-- LOG:BEGIN (append new rows at the end) -->

| date/time (CT) | run | model | backend | mode | task | result | steps | model p50 s | step p50 s | invalid | tokens | failure reason | screenshots |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-17 00:20 | 20261017-002041_A_mock | mock (scripted) | mock | A | about_android | pass | 1 |  | 4.5 | 0 | 4 |  |  |
| 2026-10-17 00:20 | 20261017-002041_A_mock | mock (scripted) | mock | A | battery_level | pass | 1 | 0.1 | 4.3 | 0 | 4 |  |  |
| 2026-10-17 00:20 | 20261017-002041_A_mock | mock (scripted) | mock | A | open_display | fail | 2 |  | 4.2 | 0 | 9 | Check failed: com.android.settings/com.android.settings.Settings. |  |
| 2026-10-17 00:21 | 20261017-002109_B_mock | mock (scripted) | mock | B | about_android | pass | 1 |  | 4.4 | 0 | 4 |  |  |
| 2026-10-17 00:21 | 20261017-002109_B_mock | mock (scripted) | mock | B | battery_level | pass | 1 | 0.1 | 4.4 | 0 | 4 |  |  |
| 2026-10-17 00:21 | 20261017-002109_B_mock | mock (scripted) | mock | B | open_display | fail | 2 |  | 4.2 | 0 | 9 | Check failed: com.android.settings/com.android.settings.Settings. |  |
| 2026-10-17 00:21 | 20261017-002137_C_mock | mock (scripted) | mock | C | about_android | pass | 1 |  | 4.5 | 0 | 4 |  |  |
| 2026-10-17 00:21 | 20261017-002137_C_mock | mock (scripted) | mock | C | battery_level | pass | 1 | 0.1 | 4.6 | 0 | 4 |  |  |
| 2026-10-17 00:21 | 20261017-002137_C_mock | mock (scripted) | mock | C | open_display | fail | 2 |  | 4.3 | 0 | 9 | Check failed: com.android.settings/com.android.settings.Settings. |  |
| 2026-10-17 00:30 | 20261017-003010_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | open_display | fail | 6 | 4.2 | 8.6 | 1 | 5065 | Wandered into the brightness slider dialog and never reached the Display page. | 4 img, local: `~/vos-task-data/failures/20261017-003010_C_phone/open_display/` |
| 2026-10-17 00:31 | 20261017-003010_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | calc_12_times_7 | fail | 6 | 4.3 | 8.4 | 0 | 5770 | Fumbled the key sequence; 84 not on screen at the 6-step cap. | 4 img, local: `~/vos-task-data/failures/20261017-003010_C_phone/calc_12_times_7/` |
| 2026-10-17 00:56 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | about_android | fail | 1 | 7.2 | 12.6 | 0 | 1022 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/about_android/` |
| 2026-10-17 00:56 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | dark_on | fail | 1 | 5.2 | 11.1 | 0 | 1016 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/dark_on/` |
| 2026-10-17 00:56 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | dark_off | fail | 1 | 5.0 | 10.6 | 0 | 1015 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/dark_off/` |
| 2026-10-17 00:56 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | screen_timeout_2m | fail | 1 | 4.9 | 10.4 | 0 | 1011 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/screen_timeout_2m/` |
| 2026-10-17 00:57 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | open_wifi_settings | fail | 1 | 4.9 | 10.2 | 0 | 1019 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/open_wifi_settings/` |
| 2026-10-17 00:57 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | bluetooth_off | fail | 6 | 5.2 | 9.5 | 0 | 6292 | Re-tapped the switch without seeing its state; Bluetooth still on at the cap. | 4 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/bluetooth_off/` |
| 2026-10-17 00:58 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | settings_search_battery_saver | fail | 1 | 4.6 | 14.6 | 1 | 1634 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/settings_search_battery_saver/` |
| 2026-10-17 00:58 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | calc_12_times_7 | fail | 1 | 5.0 | 10.3 | 0 | 1023 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/calc_12_times_7/` |
| 2026-10-17 00:58 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | calc_45_plus_55_div_4 | fail | 1 | 4.9 | 10.3 | 0 | 1027 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/calc_45_plus_55_div_4/` |
| 2026-10-17 00:58 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | clock_timer_tab | fail | 1 | 5.1 | 10.7 | 0 | 1020 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/clock_timer_tab/` |
| 2026-10-17 00:59 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | vanadium_example | fail | 1 | 5.2 | 10.7 | 0 | 1022 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/vanadium_example/` |
| 2026-10-17 00:59 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | open_display | fail | 1 | 5.2 | 11.3 | 0 | 1010 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/open_display/` |
| 2026-10-17 00:59 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | battery_level | fail | 6 | 5.3 | 9.7 | 0 | 6607 | Never gave a done answer before the cap. | 4 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/battery_level/` |
| 2026-10-17 01:00 | 20261017-005602_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | clock_timer_3min | fail | 1 | 5.8 | 11.4 | 0 | 1026 | Safety abort: tapped a launcher icon for an app outside the allowlist (task started on the home screen). | 2 img, local: `~/vos-task-data/failures/20261017-005602_C_phone/clock_timer_3min/` |
| 2026-10-17 01:01 | 20261017-010120_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | about_android | fail | 6 | 6.0 | 10.1 | 0 | 6034 | Reached About phone area but never reported the version (did not know it was done). | 4 img, local: `~/vos-task-data/failures/20261017-010120_C_phone/about_android/` |
| 2026-10-17 01:02 | 20261017-010120_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | dark_on | fail | 6 | 5.0 | 11.5 | 3 | 7579 | Looped on the Dark theme toggle; 3 malformed outputs; night mode still off. | 4 img, local: `~/vos-task-data/failures/20261017-010120_C_phone/dark_on/` |
| 2026-10-17 01:03 | 20261017-010120_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | dark_off | fail | 6 | 5.3 | 14.7 | 4 | 8181 | Looped on the Dark theme toggle; 4 malformed outputs; night mode still on. | 4 img, local: `~/vos-task-data/failures/20261017-010120_C_phone/dark_off/` |
| 2026-10-17 01:05 | 20261017-010120_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | screen_timeout_2m | fail | 6 | 5.9 | 9.9 | 0 | 5692 | Did not select 2 minutes before the cap. | 4 img, local: `~/vos-task-data/failures/20261017-010120_C_phone/screen_timeout_2m/` |
| 2026-10-17 01:06 | 20261017-010120_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | open_wifi_settings | fail | 2 | 5.5 | 9.6 | 0 | 1883 | Run stopped by the operator (harness fix), not a model failure. | 3 img, local: `~/vos-task-data/failures/20261017-010120_C_phone/open_wifi_settings/` |
| 2026-10-17 01:06 | 20261017-010641_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | about_android | fail | 6 | 5.0 | 9.0 | 0 | 5778 | Never reported the version (did not know it was done). | 4 img, local: `~/vos-task-data/failures/20261017-010641_C_phone/about_android/` |
| 2026-10-17 01:07 | 20261017-010641_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | dark_on | fail | 6 | 6.0 | 12.2 | 3 | 7503 | Looped on the Dark theme toggle; night mode still off. | 4 img, local: `~/vos-task-data/failures/20261017-010641_C_phone/dark_on/` |
| 2026-10-17 01:09 | 20261017-010641_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | dark_off | fail | 6 | 6.3 | 13.4 | 3 | 7571 | Looped on the Dark theme toggle; night mode still on. | 4 img, local: `~/vos-task-data/failures/20261017-010641_C_phone/dark_off/` |
| 2026-10-17 01:10 | 20261017-010641_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | screen_timeout_2m | fail | 6 | 6.3 | 10.3 | 0 | 5670 | Did not select 2 minutes before the cap. | 4 img, local: `~/vos-task-data/failures/20261017-010641_C_phone/screen_timeout_2m/` |
| 2026-10-17 01:11 | 20261017-010641_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | open_wifi_settings | fail | 6 | 5.9 | 10.0 | 0 | 5756 | Opened the launcher's own settings page instead of Settings > Wi-Fi. | 4 img, local: `~/vos-task-data/failures/20261017-010641_C_phone/open_wifi_settings/` |
| 2026-10-17 01:12 | 20261017-010641_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | bluetooth_off | fail | 6 | 6.0 | 10.6 | 1 | 6293 | Re-tapped the switch without seeing its state; Bluetooth still on. | 4 img, local: `~/vos-task-data/failures/20261017-010641_C_phone/bluetooth_off/` |
| 2026-10-17 01:14 | 20261017-010641_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | settings_search_battery_saver | fail | 2 | 6.4 | 10.6 | 0 | 1884 | Run stopped by the operator (harness fix), not a model failure. | 3 img, local: `~/vos-task-data/failures/20261017-010641_C_phone/settings_search_battery_saver/` |
| 2026-10-17 01:14 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | about_android | fail | 6 | 6.1 | 11.5 | 1 | 6664 | Did not report the version; did not know it had reached the goal. | 4 img, local: `~/vos-task-data/failures/20261017-011437_C_phone/about_android/` |
| 2026-10-17 01:16 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | dark_on | pass | 6 | 8.4 | 12.6 | 0 | 7506 |  |  |
| 2026-10-17 01:17 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | open_wifi_settings | fail | 6 | 8.9 | 16.9 | 3 | 8719 | Reached a Settings sub-page (SubSettings); 3 malformed outputs. Check suspected wrong (expects WifiSettings). | 4 img, local: `~/vos-task-data/failures/20261017-011437_C_phone/open_wifi_settings/` |
| 2026-10-17 01:19 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | open_display | fail | 6 | 10.6 | 15.9 | 1 | 7700 | Reached a Settings sub-page (SubSettings). The check expects DisplaySettings, so it is suspected wrong. | 4 img, local: `~/vos-task-data/failures/20261017-011437_C_phone/open_display/` |
| 2026-10-17 01:21 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | settings_search_battery_saver | fail | 6 | 8.2 | 20.2 | 5 | 8747 | Fumbled the search text field; 5 malformed outputs; no Battery Saver result. | 4 img, local: `~/vos-task-data/failures/20261017-011437_C_phone/settings_search_battery_saver/` |
| 2026-10-17 01:23 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | battery_saver_on | fail | 12 | 8.1 | 12.5 | 2 | 13775 | Looped tapping the switch without seeing it was on/off; 2 malformed outputs; Battery Saver still off. | 4 img, local: `~/vos-task-data/failures/20261017-011437_C_phone/battery_saver_on/` |
| 2026-10-17 01:25 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | calc_12_times_7 | fail | 6 | 10.2 | 14.9 | 0 | 7110 | Fumbled the key sequence; 84 not on screen at the 6-step cap. | 3 img, local: `~/vos-task-data/failures/20261017-011437_C_phone/calc_12_times_7/` |
| 2026-10-17 01:27 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | clock_timer_tab | pass | 4 | 6.6 | 10.6 | 0 | 3782 |  |  |
| 2026-10-17 01:28 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | battery_level | fail | 4 | 8.3 | 12.6 | 0 | 4662 | Never gave a done answer with the percentage. | 4 img, local: `~/vos-task-data/failures/20261017-011437_C_phone/battery_level/` |
| 2026-10-17 01:29 | 20261017-011437_C_phone | Gemma 4 E2B (LiteRT-LM, phone GPU) | phone | C | spotify_library | not run | 1 |  |  |  |  | Run killed at the time limit during step 1 of the first Spotify task. |  |
| 2026-10-17 01:48 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | about_android | pass | 4 | 1.5 | 29.1 | 0 | 8984 |  |  |
| 2026-10-17 01:50 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | dark_on | pass | 3 | 1.5 | 5.4 | 0 | 7358 |  |  |
| 2026-10-17 01:50 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | dark_off | pass | 3 | 1.5 | 5.5 | 0 | 7358 |  |  |
| 2026-10-17 01:50 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | screen_timeout_2m | pass | 4 | 2.1 | 6.0 | 0 | 9544 |  |  |
| 2026-10-17 01:51 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | open_wifi_settings | fail | 3 | 1.9 | 6.5 | 0 | 7370 | Reached the Wi-Fi page as SubSettings. Check suspected wrong (expects WifiSettings); same for Gemma. | 4 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/open_wifi_settings/` |
| 2026-10-17 01:51 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | bluetooth_off | pass | 5 | 3.0 | 6.2 | 0 | 11540 |  |  |
| 2026-10-17 01:52 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | settings_search_battery_saver | pass | 3 | 2.4 | 5.6 | 0 | 6780 |  |  |
| 2026-10-17 01:53 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | calc_12_times_7 | pass | 6 | 1.9 | 5.8 | 0 | 14305 |  |  |
| 2026-10-17 01:53 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | calc_45_plus_55_div_4 | fail | 6 | 2.6 | 6.5 | 0 | 14335 | Multi-step sum not finished within the 6-step cap. | 4 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/calc_45_plus_55_div_4/` |
| 2026-10-17 01:54 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | clock_timer_tab | pass | 1 | 3.5 | 6.8 | 0 | 2205 |  |  |
| 2026-10-17 01:55 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | vanadium_example | pass | 3 | 20.4 | 25.2 | 0 | 7004 |  |  |
| 2026-10-17 01:56 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | open_display | fail | 2 | 7.5 | 11.2 | 0 | 4869 | Reached the Display page as SubSettings in 2 steps. Check suspected wrong (expects DisplaySettings); same for Gemma. | 2 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/open_display/` |
| 2026-10-17 01:56 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | battery_level | pass | 1 | 7.6 | 11.0 | 0 | 2408 |  |  |
| 2026-10-17 01:56 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | clock_timer_3min | fail | 5 | 7.8 | 11.8 | 0 | 11156 | Harness bug: Grok started the timer but the check missed it (notification posts only with Clock in the background), so cleanup did not delete it and it rang. Fixed in 0a3babc; task now skipped by default. | 4 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/clock_timer_3min/` |
| 2026-10-17 01:58 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | battery_saver_on | fail | 10 | 4.2 | 8.2 | 0 | 22683 | Grok API read timed out at 120 s (step 10). | 4 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/battery_saver_on/` |
| 2026-10-17 02:02 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | night_light_on | fail | 2 | 42.2 | 46.3 | 0 | 4867 | Operator pause: the run was paused to clear the ringing timer; Clock came to the foreground and the allowlist aborted. | 3 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/night_light_on/` |
| 2026-10-17 02:03 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | font_scale_115 | fail | 3 | 1.7 | 5.7 | 0 | 7238 | Grok API read timed out at 120 s (step 3). | 4 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/font_scale_115/` |
| 2026-10-17 02:07 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | files_vosnav_test | fail | 11 | 2.6 | 7.8 | 0 | 26138 | Grok API read timed out at 120 s after 11 steps in Files (it was stumbling). | 3 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/files_vosnav_test/` |
| 2026-10-17 02:13 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | calc_version | fail | 12 | 2.0 | 6.2 | 0 | 29060 | Could not find the Calculator version on the app info page within 12 steps; no answer. | 4 img, local: `~/vos-task-data/failures/20261017-014749_C_xai/calc_version/` |
| 2026-10-17 02:15 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | vanadium_search | pass | 3 | 3.3 | 6.9 | 0 | 6751 |  |  |
| 2026-10-17 02:16 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | spotify_artist | pass | 5 | 2.1 | 7.4 | 0 | 14322 |  |  |
| 2026-10-17 02:17 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | spotify_play_get_lucky | pass | 5 | 2.7 | 8.4 | 0 | 13845 |  |  |
| 2026-10-17 02:20 | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | spotify_pause | pass | 3 | 8.0 | 55.3 | 0 | 6755 |  |  |
|  | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | spotify_resume | skipped | 0 |  |  |  |  | Skipped at Caleb's request (music at 2 AM). |  |
|  | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | spotify_library | skipped | 0 |  |  |  |  | Skipped at Caleb's request (music at 2 AM). |  |
|  | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | spotify_ai_podcast | skipped | 0 |  |  |  |  | Skipped at Caleb's request (music at 2 AM). |  |
|  | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | spotify_skip_next | skipped | 0 |  |  |  |  | Skipped at Caleb's request (music at 2 AM). |  |
|  | 20261017-014749_C_xai | grok-4.7 (xAI API) | xai | C | wifi_off | skipped | 0 |  |  |  |  | Not reached; the run was stopped after the Spotify skip. |  |
| 2026-10-17 11:39 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | spotify_artist | fail | 0 |  |  | 0 |  | Not a model failure: Android's low-memory killer killed the E4B service (2.3 GB) when Spotify launched. |  |
| 2026-10-17 11:19 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | about_android | fail | 6 | 12.8 | 18.8 | 0 | 7166 | Wandered (Network, Battery) and never opened About phone; no version reported. | 4 img, local: `~/vos-task-data/failures/20261017-111851_C_phone/about_android/` |
| 2026-10-17 11:21 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | battery_level | pass | 2 | 12.5 | 18.1 | 0 | 2310 |  |  |
| 2026-10-17 11:22 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | battery_saver_on | fail | 12 | 11.3 | 17.1 | 0 | 12588 | Re-tapped the Battery Saver switch 10 times without seeing its state; ended off. | 4 img, local: `~/vos-task-data/failures/20261017-111851_C_phone/battery_saver_on/` |
| 2026-10-17 11:25 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | calc_12_times_7 | fail | 6 | 16.8 | 22.7 | 0 | 7276 | Confused element number 12 with the digit 12 and kept tapping the pi key. | 4 img, local: `~/vos-task-data/failures/20261017-111851_C_phone/calc_12_times_7/` |
| 2026-10-17 11:28 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | clock_timer_tab | pass | 3 | 14.4 | 20.2 | 0 | 2866 |  |  |
| 2026-10-17 11:29 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | dark_on | pass | 6 | 17.1 | 23.3 | 0 | 7516 |  |  |
| 2026-10-17 11:32 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | open_display | pass | 2 | 22.0 | 27.4 | 0 | 2415 |  |  |
| 2026-10-17 11:33 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | open_wifi_settings | pass | 6 | 25.5 | 30.9 | 0 | 7103 |  |  |
| 2026-10-17 11:36 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | settings_search_battery_saver | pass | 4 | 30.4 | 36.8 | 0 | 3941 |  |  |
| 2026-10-17 11:41 | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | spotify_play_get_lucky | fail | 1 | 12.0 | 142.6 | 0 | 1527 | Not run on E4B: stopped after the service restarted with the default E2B model. | 2 img, local: `~/vos-task-data/failures/20261017-111851_C_phone/spotify_play_get_lucky/` |
|  | 20261017-111851_C_phone | Gemma 4 E4B (LiteRT-LM gemma-4-E4B-it.litertlm 3.66 GB, phone GPU) | phone | C | spotify_pause | skipped | 0 |  |  |  |  | Stopped after the E4B service was killed by low memory. |  |
| 2026-10-17 11:43 | 20261017-114231_A_phone | LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) | phone | A | about_android | fail | 6 | 42.2 | 46.1 | 0 | 5577 | Opened Battery instead of About phone, then tapped the Battery share switch 5 times. | 3 img, local: `~/vos-task-data/failures/20261017-114231_A_phone/about_android/` |
| 2026-10-17 11:48 | 20261017-114231_A_phone | LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) | phone | A | battery_level | fail | 6 | 43.9 | 47.7 | 0 | 5679 | Never answered; tapped the Battery percentage switch 5 times instead of reporting the 100% it could see. | 3 img, local: `~/vos-task-data/failures/20261017-114231_A_phone/battery_level/` |
| 2026-10-17 11:53 | 20261017-114231_A_phone | LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) | phone | A | battery_saver_on | fail | 4 | 40.0 | 43.9 | 0 | 3443 | Stopped by the operator: wandered into Modes > Create your own mode. | 3 img, local: `~/vos-task-data/failures/20261017-114231_A_phone/battery_saver_on/` |
| 2026-10-17 11:57 | 20261017-115627_A_phone | LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) | phone | A | clock_timer_tab | pass | 5 | 32.8 | 38.4 | 1 | 4192 |  |  |
| 2026-10-17 12:00 | 20261017-115627_A_phone | LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) | phone | A | open_display | pass | 6 | 43.0 | 47.0 | 0 | 5722 |  |  |
| 2026-10-17 12:05 | 20261017-115627_A_phone | LFM2.5-2.6B (llama.cpp LFM2.5-2.6B-Q4_0.gguf 1.59 GB, phone CPU, 2 threads on cpus 4-5) | phone | A | open_wifi_settings | fail | 6 | 32.8 | 36.5 | 0 | 4719 | Repeated the same action (tap [3] 'Use Data Saver') and hit the step cap. Check: com.android.settings/com.android.settings.SubSettings fragment DataSaverSummary. | 3 img, local: `~/vos-task-data/failures/20261017-115627_A_phone/open_wifi_settings/` |
