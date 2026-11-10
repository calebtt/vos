# Phone B arrival runbook (#345)

For the day Phone B (Pixel 7 `panther`) comes back from the screen replacement (same unit). It covers the M0 gate spikes that have never run on the phone (S-01 #11, S-03 #12, S-27a #44), the unlock-ability re-check (M0-06, #131), Play removal for M1-NOGOOG-01 (#142) and the pause sweep (#247). Sources are the repo docs named in each step; where the repo does not say how, the step says **not yet verified**.

Every step carries one mark:

- **[desk-verified]**: S-01 steps 1–3 desk build passed on the workstation (S-01.md, "Result: desk build PASS", 2026-10-19/20, with throwaway test keys).
- **[emulator-verified 2026-11-10]**: the mechanic worked on the API 36 lane AVD (`vos_emu_lane_api36`, eval/README.md "Emulator lane") in a capability run on 2026-11-10. The emulator is not Phone B: the same step still has to pass on the phone.
- **[phone only]**: needs Phone B; not proven on the desk or the emulator (some read-only steps ran on the phone before the repair, 2026-10-18/20, and are re-run here).

## House rules (eval/README.md, S-01.md, DAY0.md)

- `ANDROID_SERIAL` is set before the first adb or eval command, every time. Never run an eval script with it unset while a phone is attached. Check the `... on <serial> (<fingerprint>)` line each runner prints first.
- Flashing, unlock, lock and every wipe are **Caleb's hands only** (S-01: "not run by CI or agents").
- Sound only 08:00–22:00 (`vos_daytime_only`). Never put a key or token on a command line, in a chat, an issue or this repo (DAY0). Keys go through `vos_seal_key_if_missing` (run-as stdin).
- Results, backups and captures stay off the repo (`<data-disk>`, `eval/runs/`). Do not write the phone's serial into any doc or issue.
- After any reboot GrapheneOS keeps USB charge-only until the owner unlocks the screen, so adb drops until Caleb enters the PIN (DEVICES.md).

## 0. Before it arrives (no phone)

S-01 steps 1–3, as `docs/spikes/S-01.md` gives them.

0.1 **Owner keys, once** [desk-verified: the build path ran with throwaway test keys only; `image/keys.sh` with the owner's passphrases has not run (S-01.md: "steps 1 and 4–8 wait for Caleb")]. `image/keys.sh` (prompts for two new passphrases; writes `<key-dir>/{avb.key,avb_pkmd.bin,ota.key,ota.crt}`). Store the passphrases in the password manager and copy `<key-dir>` to the offline USB stick before step 4.

0.2 **Hello APK** [desk-verified]:

```bash
key="$(mktemp -d)"
eval "$(ci/make-throwaway-release-key.sh "$key" | sed 's/^/export /')"
./gradlew -p spikes/probes :probe-s01-hello:assembleRelease
unset VOS_RELEASE_STORE_FILE VOS_RELEASE_STORE_PASSWORD VOS_RELEASE_KEY_ALIAS VOS_RELEASE_KEY_PASSWORD
rm -rf "$key"
```

0.3 **Image** [desk-verified for build `2026101601`]. The OTA must match the build the phone runs **on return**. With no phone yet, `adb shell getprop` has nothing to read, so pre-build against the last-known build by setting `build=2026101601` by hand instead of the first line, then **re-run this step after 1.2** if the phone reads a different build (GrapheneOS's updater installed a new build on its own before, S-01.md "Result"):

```bash
build="$(adb shell getprop ro.build.version.incremental | tr -d '\r')"   # 2026101601 on 2026-10-20
image/build.sh --device panther \
  --ota "<data-disk>/vos/firmware/panther/grapheneos/$build/panther-ota_update-$build.zip" \
  --overlay image/overlays/s01-hello \
  --add priv-app/VosHello/VosHello.apk=spikes/probes/probe-s01-hello/build/outputs/apk/release/probe-s01-hello-release.apk
vos_ota="$(ls -t "${VOS_IMAGE_OUT:-out/images}"/*/vos-panther-"$build"-s01-hello-*.zip | head -1)"   # the image just built
```

0.4 **If the build changed** [not yet verified for a new build; DAY0 §6–7 ran 2026-10-18 for 2026101600/01]: download and verify the new build's factory zip and full OTA (DAY0 §6), `avbroot ota verify` the OTA against `image/pins/grapheneos-ota-panther.crt` (DAY0 §7), add the firmware rows to `docs/DEVICES.md`, then 0.3.

## 1. First connection

1.1 **Only Phone B attached** [phone only]. Stop any lane emulator first (`adb -s emulator-5584 emu kill`, eval/README.md).

```bash
adb devices -l      # exactly one line, with product:panther ... device:panther; no emulator-NNNN line
export ANDROID_SERIAL=<the serial on that line>     # do not write it into any doc or issue
adb shell getprop ro.product.device               # expect: panther
```

1.2 **Build, boot state, bootloader, radio** [phone only]. Last read 2026-10-20 (S-01.md, DEVICES.md) in the comments:

```bash
for p in ro.build.fingerprint ro.build.version.incremental ro.bootloader gsm.version.baseband \
         ro.boot.flash.locked ro.boot.verifiedbootstate ro.boot.vbmeta.device_state ro.boot.slot_suffix; do
  printf '%s=%s\n' "$p" "$(adb shell getprop "$p" | tr -d '\r')"
done
# 2026-10-20: google/panther/panther:17/CP3A.261005.002.A1/2026101601:user/release-keys, 2026101601,
# cloudripper-17.0-15902555, g5300q-260604-260807-B-16036236, 1, yellow, locked, _a
adb shell pm list packages | grep -E 'com.google.android.gms|com.android.vending'   # sandboxed Play: present on 2026-10-18
adb shell pm list packages -3 | wc -l             # user apps still there? (decides how much of 2 is left)
```

If the incremental differs from the image built in 0.3, go back to 0.4/0.3.

1.3 **Did the repair change what the plan relies on?** [phone only]

- Still official GrapheneOS, locked, Graphene key: `ro.boot.verifiedbootstate=yellow`, `ro.boot.flash.locked=1`, fingerprint `google/panther/panther:17/...:user/release-keys` as above. Reading anything else as "the repair centre reflashed it" (e.g. `green` = stock OS) is an interpretation the repo does not state: **not yet verified**; stop and ask before going on.
- Still holds Caleb's data? If the repair reset the phone, section 2 shrinks to re-enrolling accounts; record it.
- Fingerprint/biometric: the repo records no biometric setup on Phone B and nothing about re-enrolling after a screen change. The unlock in step 5 and the lock in step 7 each wipe Phone B, so enrol fingerprints only after the final relock. Whether the new panel's under-display fingerprint sensor needs a calibration: **not yet verified** (the repo does not say).
- Touch panel: S-09 (#19, M3) and the panel-only presence rule (TARGET-PLAN §5.7) identify the touch panel by `InputDevice` ID. No earlier panel ID is recorded, so there is nothing to compare; S-09 must read it on the repaired phone.

1.4 **Unlock ability** (M0-06, #131; DAY0 §8; DEVICES.md "How to fill in") [phone only]. Read-only, no wipe; Caleb enters the PIN after the reboot back.

```bash
adb reboot bootloader
fastboot devices                       # exactly one line, the same serial as ANDROID_SERIAL
fastboot getvar product 2>&1           # expect: panther
fastboot flashing get_unlock_ability 2>&1   # expect: 1 (was 1 on 2026-10-18 23:15 CT)
fastboot getvar unlocked 2>&1          # expect: no (still locked)
fastboot reboot
```

Not `1`: stop. S-01 cannot unlock; record it in #131 and DEVICES.md before anything else.

## 2. Backup before any wipe (S-01 step 4, first half)

[phone only] for every item. The list is S-01.md "Phone B is not empty" (read 2026-10-19):

- [ ] Google Authenticator: export in the app, or move each account's OTP to another authenticator first (seeds cannot be pulled over adb). Yubico Authenticator keeps its seeds on the YubiKey.
- [ ] KeePassDX: find where the database file lives and copy it off.
- [ ] Banking and payment apps, Outlook, Teams, Discord, Telegram, Termux: whatever each needs to re-enrol after a wipe.
- [ ] Shared storage (31 GB on 2026-10-19: `DCIM`, `Audiobooks`, `Android/`) to `<data-disk>/vos/backup/phones/panther/$(date +%F)/`. S-01 names the destination, not the command; the minimal standard one is below, **not yet verified** (on recent Android much of `Android/` is not readable over adb):

  ```bash
  d="<data-disk>/vos/backup/phones/panther/$(date +%F)"; mkdir -p "$d"
  adb pull /sdcard/ "$d/"
  ```

- [ ] Count what was pulled against the phone's own `du` before the wipe.

## Order decision (Caleb, before section 3)

The plan says Phone B "runs official Graphene for M1-NOGOOG-01 and S-25, then the VOS Image" (TARGET-PLAN §7 Devices), and M1-NOGOOG-01 is "on Phone B running official Graphene with sandboxed Play absent". Section 3's unlock wipes the phone and flashes the VOS Image, so once it runs, M1-NOGOOG-01 as written needs official Graphene flashed back (another wipe). Two orders:

- **(a) Section 4 before section 3** (recommended): remove Play and run the M1 suite on official Graphene now, then flash. It matches the plan and costs no extra wipe; it delays the M0 gate spikes by the length of the M1 run.
- **(b) Section 3 first:** the wipe also removes Play (it is an ordinary app, absent by default, TARGET-PLAN "Decisions on one screen"), but the suite then runs on the VOS Image, which is not what M1-NOGOOG-01 says; record the deviation in #142.

## 3. The M0 gate spikes

### S-01 (#11), steps 4–8: `docs/spikes/S-01.md` "Procedure"

Run the command blocks from S-01.md; do not retype them from here. Critical checks:

3.1 **Step 4, bootloader and radio** [phone only]: `ro.bootloader` and `gsm.version.baseband` (read in 1.2) must match the factory image of `$build` (`bootloader-panther-*.img`, `radio-panther-*.img` in `panther-install-$build.zip`). `fastboot flashall` does not update them. If either differs, sideload the patched OTA once after step 5's first boot, as in step 8, before the relock.

3.2 **Step 5, unlock and flash** [phone only; **WIPES Phone B**]. `fastboot --version` ≥ 35. After `fastboot flashing unlock`: `fastboot getvar unlocked` → `yes`. `avb_custom_key` is written from the bootloader, not fastbootd (`fastboot reboot-bootloader` first). First boot shows the orange warning screen.

3.3 **Step 6, grants on the first unlocked boot** [phone only] (minimal setup, USB debugging on; set `ANDROID_SERIAL` again, 1.1): `adb shell dumpsys package com.vos.hello | grep -A8 'install permissions'` shows all five permissions `granted=true`. Save the excerpt for the report.

3.4 **S-03 on the same unlocked boot** (`docs/spikes/S-03.md`; S-01 step 6 says S-03 steps 3–4 run here) [phone only]:

```bash
adb shell getprop ro.control_privapp_permissions               # step 1: expect enforce (it was on stock Graphene)
adb logcat -b all -d | grep -i 'privapp\|Privileged permission' # step 3: no "not in privapp-permissions allowlist"; no crash loop
adb shell dumpsys package com.vos.hello                         # step 4: every allowlisted permission granted
```

S-03 step 2 names `/product/etc/permissions/privapp-permissions-vos.xml`; the S-01 overlay ships `privapp-permissions-vos-hello.xml` (S-01 step 3). Record the file actually on the image. S-03 step 5 (negative leg: one extra privileged permission not in the XML) needs a second image built from a changed overlay; S-03 does not say whether it runs before or after the relock. While unlocked, a reflash costs no wipe; after the relock it needs a sideload (step 8): **not yet verified** which is intended.

3.5 **Step 7, relock**, only after 3.3 passes [phone only; **WIPES Phone B again**]. Pass: yellow boot screen with the owner key's ID; `ro.boot.verifiedbootstate=yellow`, `ro.boot.flash.locked=1`. Leave OEM unlocking **on**.

3.6 **Step 8, update path** (M4-IMG-03) [phone only]: rebuild with a changed overlay against the same build, `adb reboot sideload`, `adb sideload "$vos_ota"`; boots locked with data intact. Do not sideload again until `adb logcat -s update_engine` shows the previous snapshot merge finished.

### S-27a (#44): `docs/spikes/S-27a.md`

3.7 [phone only] S-27a requires S-01 **passed** (its step 1), and S-01's pass includes the relock, so it runs after 3.5, on the owner-key image with Play absent. **Not runnable on arrival:** the repo has no injector priv-app (the S-01 hello APK has no code, so it cannot register the `AudioPolicy` of step 3) and no fixture recorder app (step 2); neither is in `spikes/probes/` or `image/overlays/`. Both have to be written and baked into a new image (which could double as step 8's changed overlay; not decided in the repo). Step 8 needs a classic BT headset with HFP active.

## 4. Play removal for M1-NOGOOG-01 (#142)

What is installed (S-01.md, 2026-10-18): sandboxed Play, `com.google.android.gms` and `com.android.vending`. The repo does not say how to remove it.

4.1 **Remove** [phone only; **not yet verified**, minimal standard steps]. Sandboxed Play is an optional, unprivileged app on GrapheneOS (TARGET-PLAN "Decisions on one screen", §4), so uninstall it like one: Settings › Apps › each Google package › Uninstall, or

```bash
adb shell pm list packages | grep -iE 'google|com\.android\.vending'    # see what is there first; GrapheneOS's sandboxed Play may include more than the two above
adb shell pm uninstall com.android.vending
adb shell pm uninstall com.google.android.gms
# and any other Google package the list showed (e.g. com.google.android.gsf: not recorded in the repo)
```

Apps that depend on Play (banking, push) may stop working; that inventory is S-25 (#35).

4.2 **Verify Play is absent** [phone only]: no Google package in `pm list packages` (the oracle M4-GOOG-01 uses, TARGET-PLAN §6 M4):

```bash
adb shell pm list packages | grep -iE 'google|com\.android\.vending'    # expect: no output
```

4.3 **Install the debug pair for the M1 suite** [phone only; the fixed install path is tested offline only, `ci/tests/test_install_apps.py`, not yet on any device]. A freshly wiped or Play-removed phone is a fresh device for VOS: install through `vos_install_apps` (session first, then actuator, then the session again on a fresh device, both cross grants checked, #341), which every `eval/m1` runner does. On a fresh device the app installed first always lacks its grant (session first: the session lacks `SESSION`), so one reinstall of the session is expected. By hand: session, actuator, session again, then check both grants:

```bash
adb install -r -t session/build/outputs/apk/debug/session-debug.apk
adb install -r -t actuator/build/outputs/apk/debug/actuator-debug.apk
adb install -r -t session/build/outputs/apk/debug/session-debug.apk
adb shell dumpsys package com.vos.actuator | grep 'com.vos.permission.ACTUATOR: granted=true'
adb shell dumpsys package com.vos.session  | grep 'com.vos.permission.SESSION: granted=true'
# on a fresh device the session line is missing until the session is reinstalled
```

4.4 **The M1 suite**: the runners in eval/README.md's table, each with its own preconditions there. Mechanics proven on the emulator, still to be proven on the phone:

| Mechanic | Emulator (API 36 lane) | Phone B |
|---|---|---|
| `cmd role add-role-holder android.app.role.ASSISTANT com.vos.actuator 0` gives the actuator the role; `KEYCODE_ASSIST` opens a turn (`m1/assistant.sh`, M1-ROLE-01; the mechanic only; the runner itself is Phone B, eval/README.md) | [emulator-verified 2026-11-10] | [phone only]: the timing to the first mic buffer means something only on the real mic (eval/README.md) |
| `cmd role clear-role-holders android.app.role.ASSISTANT 0` gives the lost-role notice. `assistant.sh --stand-in` covers the same notice through `add-role-holder`/`remove-role-holder` with a stand-in app | [emulator-verified 2026-11-10] | [phone only] |
| Keyguard set/clear through `locksettings` (locked and unlocked legs) | [emulator-verified 2026-11-10] | [phone only]: lock and unlock by hand; never put the owner's PIN on a command line |
| SMS and call signaling (`adb emu sms send`, `adb emu gsm call`) | [emulator-verified 2026-11-10] | none: on Phone B no test sends an SMS or places or answers a call (TARGET-PLAN comms device rule, M2, and eval house rule 2); Phone B has no SIM (the Pixel 8a holds the only SIM, Caleb 2026-10-18: "the only sim we have is on the pixel 8a") |
| AEC, Bluetooth headset audio | not available on the emulator | [phone only] (`m1/echo-barge.sh`, S-27a step 8) |

## 5. Pause sweep (#247): `docs/runbooks/turn-detection-sweep.md`

5.1 **Owner's go-ahead first.** Every trial is a real `grok-voice-3.0` turn and fixture synthesis is a paid call: the sweep makes **paid vendor calls** and runs only when Caleb says so.

5.2 **Preconditions** (the sweep runbook's "Ground rules" and "Setup") [phone only]: debug builds of both apps (release builds ignore the overrides); `ANDROID_SERIAL` set; 08:00–22:00; installed with `vos_install_apps` (session first, then the actuator, then the session again on a fresh device, #341); `vos_grant_mic`; a sealed key (`vos_seal_key_if_missing`); `vos_clear_debug_overrides`; the pause fixtures made once and reused. Then its sections 1–5; results to #247.

5.3 **Emulator pre-check limits** [emulator-verified 2026-11-10]: on the API 36 lane, mic injection for the sweep works only through the emulator's token-protected gRPC `injectAudio`, with the mic already open; injecting while no app is recording crashes the emulator. AEC is not available there and Bluetooth headset audio cannot be tested. The numbers that pick the values come from Phone B only.

## 6. Record results

| Result | Where |
|---|---|
| 1.2 props, 1.3 repair findings, 1.4 `get_unlock_ability` with date | `docs/DEVICES.md` Phone B row; #131 (M0-06) |
| 2 backup done (what, where on `<data-disk>`; no personal details in the repo) | S-01.md step 4 outcome; #11 |
| S-01 steps 4–8: image SHA-256 and manifest, each step's outcome, the step 6 `dumpsys` excerpt, the boot state after relock, pass or fallback | `docs/spikes/S-01.md` (its "Fill in after the flash" list); #11; the platform decision in `docs/DECISIONS.md` and #129 (M0-04) |
| S-03 steps 1–5, including which allowlist file name the image used | `docs/spikes/S-03.md`; #12; #129 |
| S-27a (or "not runnable: no injector/fixture app yet") | `docs/spikes/S-27a.md`; #44; #130 (M0-05) |
| Play removal, the order chosen (a or b), the M1 suite results | #142; each runner's own issue (eval/README.md table) |
| An install-order failure seen on the phone | #341 |
| Sweep trials and the chosen values | #247; `model_policy.json` per the sweep runbook §5 |
| Anything in this runbook that turned out wrong | #345, and fix this file |
