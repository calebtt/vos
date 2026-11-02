# Nav eval tasks

Every task the nav-eval harness (`~/vos-nav-evals`, `vosnav/tasks.py`) has defined or tried, including dropped ones. Results for each attempt are in [RESULTS-LOG.md](RESULTS-LOG.md). Device: Pixel 7 (`panther`, GrapheneOS), tethered to the desktop. Every task starts inside its own app (launcher taps are blocked), runs `setup()`, the model loop, then `cleanup()` in `finally`. The foreground app must stay on the task's allowlist or the harness presses Home and aborts.

**Strict pass** (what `success` / `strict_pass` mean): the check is false before step 1, the model declares `done` (answer tasks must also put a non-empty answer on that `done`), and the check is true at the end. `lucky_end_state` is recorded separately when the check is true at the end but the model never said `done`. `precondition_already_true` means the check was already true before step 1; that run is not a pass. `first_step_check_true` is the first step whose check succeeded.

Status: **active** = in the default suite; **skipped by default** = real task, only runs with an opt-in; **dropped** = removed on purpose, with the reason. "Check suspected wrong" marks a success check that both models failed while apparently on the right page.

| # | Task id | App | Goal in plain words | Success check | Cleanup | Safety notes | Status |
|---|---|---|---|---|---|---|---|
| 1 | about_android | Settings | Open About phone and report the Android version | Done answer contains `ro.build.version.release` | None needed | Read-only | active |
| 2 | dark_on | Settings | Turn dark theme on | Night mode is on (`cmd uimode night`) | Restore original night mode | Setup forces it off first | active |
| 3 | dark_off | Settings | Turn dark theme off | Night mode is off | Restore original night mode | Setup forces it on first | active |
| 4 | screen_timeout_2m | Settings | Set screen timeout to 2 minutes | `system screen_off_timeout` = 120000 | Restore original timeout | — | active |
| 5 | open_wifi_settings | Settings | Open the Wi-Fi settings page (don't turn Wi-Fi off) | Wi-Fi settings page is showing (activity, fragment, or title) and Wi-Fi is still enabled | Home | Must not toggle Wi-Fi | active |
| 6 | bluetooth_off | Settings | Turn Bluetooth off | `global bluetooth_on` = 0 | Restore Bluetooth state | Setup turns it on first | active |
| 7 | wifi_off | Settings | Turn Wi-Fi off, leave other radios alone | Wi-Fi disabled | Re-enable Wi-Fi and wait until enabled | Runs last (radio blip); safe for USB adb | active (not reached yet) |
| 8 | settings_search_battery_saver | Settings | Search Settings for "battery saver" | On the search screen and a Battery Saver result is visible | Home | Typing only in Settings search | active |
| 9 | calc_12_times_7 | Calculator | Compute 12×7 and report it | 84 in the answer or on screen, and the model gave that answer | Home | max_steps 12 | active |
| 10 | calc_45_plus_55_div_4 | Calculator | Compute (45+55)/4 with parentheses and report it | 25 in the answer or on screen, and the model gave that answer | Home | max_steps 14 | active |
| 11 | clock_timer_tab | Clock | Switch to the Timer tab, don't start a timer | Clock foreground and Timer tab selected | Home | Setup opens the Alarm tab (and again after launch) so the check is false before step 1. Must not start a timer | active |
| 12 | clock_timer_3min | Clock | Create and start a 3-minute timer | A DeskClock timer is pending (notification or pending `dumpsys alarm` entry) | Delete the running timer via its DELETE control until none pending | Makes noise: rang on 2026-10-17 because of a check bug (fixed in 0a3babc). Refuses to run if a timer already exists | **skipped by default** (`VOSNAV_ALLOW_SOUND_TASKS=1` to run) |
| 13 | vanadium_example | Vanadium | Open https://example.com | Vanadium foreground and "example.com" visible | Home | Browser only | active |
| 14 | vanadium_search | Vanadium | Search for the exact query "vosnav" | "vosnav" visible in the address bar or page | Home | Harmless query only | active |
| 15 | open_display | Settings | Open the Display page | Foreground activity name contains DisplaySettings | Home | Read-only | active; **check suspected wrong** (page opens as SubSettings) |
| 16 | battery_level | Settings | Report the battery percentage | Answer matches `dumpsys battery` level | None needed | Read-only | active |
| 17 | battery_saver_on | Settings | Turn on Battery Saver | `global low_power` = 1 | Restore original Battery Saver state | The control stays disabled while the phone is charging. The harness skips the task; it does not unplug or fake the battery | **skipped** — "blocked while charging" |
| 18 | night_light_on | Settings | Turn on Night Light | `secure night_display_activated` = 1 | Restore original Night Light value | — | active |
| 19 | font_scale_115 | Settings | Set font size so scale is 1.15 | `system font_scale` ≈ 1.15 | Restore font scale and device font scale | — | active |
| 20 | files_vosnav_test | Files | In Downloads, create a folder named vosnav_test | `/sdcard/Download/vosnav_test` is a directory | Remove that folder | Only touches that one folder | active |
| 21 | calc_version | Settings + Calculator | Open Calculator's app info page and report its version | Answer contains the Calculator versionName (on the app info page) | Home | Must not force-stop, disable, or uninstall | active |
| 22 | spotify_artist | Spotify | Search "Daft Punk" and open the artist page | Artist page markers (header + Popular / monthly listeners) visible | Pause playback, Home | No likes, follows, playlist edits, upsell taps, or settings changes; needs signed-in Spotify | active (spotify tag) |
| 23 | spotify_play_get_lucky | Spotify | Search and play "Get Lucky" by Daft Punk | `dumpsys media_session` Spotify state 3 and title contains Get Lucky | Pause playback, Home | Same Spotify rules; plays audio | active (spotify tag) |
| 24 | spotify_pause | Spotify | Pause playback | Spotify session state 2 (paused) | Pause playback, Home | Setup starts playback | active (spotify tag) |
| 25 | spotify_resume | Spotify | Resume the loaded track | Spotify session state 3 with a title | Pause playback, Home | Plays audio; skipped at 2 AM on 2026-10-17 at Caleb's request | active (spotify tag) |
| 26 | spotify_library | Spotify | Open the Your Library tab | "Your Library" heading visible | Pause playback, Home | Same Spotify rules | active (spotify tag) |
| 27 | spotify_ai_podcast | Spotify | Search an AI podcast ("Everyday AI") and open an episode | Episode page visible, or Everyday AI playing | Pause playback, Home | No follows or saves | active (spotify tag) |
| 28 | spotify_skip_next | Spotify | Skip to the next track | Still playing and the title changed | Pause playback, Home | Setup needs a playing queue of ≥2; plays audio | active (spotify tag) |
| 29 | (alarm_create) | Clock | Create an alarm, then delete it | — | — | Real alarms exist on the phone (7:40, 8:30, 9:00) and the Clock provider isn't exported, so cleanup can't promise to delete only the new alarm | **dropped** |
| 30 | (ring_volume) | Settings / Sound | Change ringtone volume | — | — | `settings put system volume_ring` doesn't move the stream and a `media_session` set snapped back to 5, so the change couldn't be restored reliably | **dropped** |

Ids 1–28 exist in `vosnav/tasks.py`; 29–30 were specified but never implemented (ids in parentheses are descriptive). Phone/telephony, messaging, purchases, account changes, developer options, and uninstalls are out of scope for every task.

## How to log a run

```bash
cd ~/vos-nav-evals
~/miniforge3/bin/python3 export_to_vos.py runs/<stamp>_<mode>_<backend>
```

Add a line for the run in `export_notes.json` first if you want a model name, a one-line note, or plain failure reasons; otherwise the exporter writes a default reason from the check detail.
