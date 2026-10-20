# VOS devices

Enrollment record for M0-06 (#131), from TARGET-PLAN §7 "Devices". Values marked **PENDING** need Caleb with the phone or the disk in hand; Phone B rows were read over adb on 2026-10-18; the other rows are not.

| Device | Codename | Role | Status | Checks |
|---|---|---|---|---|
| **Phone A:** Pixel 8a (or current Graphene daily) | `akita` | Daily phone, official GrapheneOS; Tier S from M1; VOS Image only near final, after the 14-day daily-phone soak (§9 Q1). First-class Tier P target, same recipe as panther. **Holds the only SIM** → the `sim_phone`: real-comms tests are delayed until VOS runs on it. | Enrolled (path noted) | Never the first device for any flash. Back up (Seedvault + per-app exports) before M4; the key change wipes it. Confirm device-specific spikes on `akita` after the panther smoke. |
| **Phone B:** Pixel 7 (already owned) | `panther` | **Main flash/dev rig.** Tethered to the workstation over USB with USB debugging on; default for all spikes, every flash, the debug build and CI smoke. Official Graphene for M1-NOGOOG-01 and S-25, then the VOS Image. | Enrolled; connected over USB 2026-10-18: official GrapheneOS `CP3A.261005.002.A1` (Android 17, SDK 37), incremental `2026100601` at the 21:39 CT read, auto-updated to `2026101601` at 23:11 CT (reboot reason `reboot,ota`, slot `_b` → `_a`; re-read 2026-10-20), bootloader locked, verified boot yellow (Graphene key), sandboxed Play installed, stay-awake 15 | `fastboot flashing get_unlock_ability` = **1** (checked 2026-10-18 23:15 CT; bootloader still locked, `unlocked: no`). Note: after any reboot GrapheneOS keeps USB charge-only until the owner unlocks the screen, so adb drops until Caleb enters the PIN; plan reboots for when he is nearby. OEM unlocking stays on. Support window ~2027 [verify]. |
| **Phone C:** mainstream non-Pixel (e.g. Samsung Galaxy A-series), Android 15+ | — | Tier S reach from M1. No SIM, so no real-comms tests. | **Deferred / not yet ordered** | Record order date and ETA in #4 when ordered. |
| Headsets | — | Current classic BT; one LE Audio reference from M4 | Classic BT owned; LE Audio PENDING order | Route type logged on every turn. |
| Workstation | — | Build, CI runner, emulators, image builds, Wi-Fi AP for edge captures | Present | No phone control in production; ADB is development-only. |
| VPS | — | **Retired** | Retired (2026-10-18) | VPS retired; no VPS secrets remain; if a VPS is ever found still running, shut it down. |

## Firmware trees (data-disk, `/media/caleb/data-disk/vos/`)

Downloaded 2026-10-18 23:23–23:31 CT from `releases.grapheneos.org` (agent, no flashing). Channel metadata at download time: `panther-stable`, `-beta` and `-alpha` all = `2026101600` (same for akita). Build `2026101601` (one-second-later respin, same Android build ID) is also published and is **exactly what Phone B runs** (`google/panther/panther:17/CP3A.261005.002.A1/2026101601:user/release-keys`), so both are kept. Each build dir holds the factory zip + `.sig`, the full OTA, `allowed_signers`, the channel file as fetched (`*-stable.channel-at-download`) and `SHA256SUMS`.

Verification: factory zips with `ssh-keygen -Y verify -f allowed_signers -I contact@grapheneos.org -n "factory images"` → `Good "factory images" signature … SHA256:AhgHif0mei+9aNyKLfMZBh2yptHdw/aN7Tlh/j2eFwM` (key matches grapheneos.org/install/cli). Graphene publishes no separate OTA checksums; full OTAs are verified with `avbroot 3.37.0 ota verify --cert-ota image/pins/grapheneos-ota-<codename>.crt` (whole-file signature, payload, partition hashes, AVB hash/hashtree descriptors, recovery otacerts, care map) → `OK!`. The only warning is the expected "vbmeta … parent does not list a trusted key" (no `--public-key-avb` pinned yet).

| Path | Build (Android build ID) | File | Size (bytes) | SHA-256 | Verified |
|---|---|---|---|---|---|
| `firmware/panther/grapheneos/2026101600/` | 2026101600 (CP3A.261005.002.A1) | `panther-install-2026101600.zip` | 1912734447 | `34bcd2842202aacfaa27e62b22be62884c629e2ef70eb875702486511b474bc3` | Good signature |
| | | `panther-ota_update-2026101600.zip` | 1490460566 | `a69e02de52dfd11b8ded287d3df944d3ffb4ffd5110e906f04985586502d08e5` | avbroot OK |
| `firmware/panther/grapheneos/2026101601/` | 2026101601 (CP3A.261005.002.A1) — **matches Phone B** | `panther-install-2026101601.zip` | 1912851472 | `85b1cabe5b3eedba8067602113b4226d8bbe7d1811c6cf6d0133a3480121a79f` | Good signature |
| | | `panther-ota_update-2026101601.zip` | 1490572763 | `ef19b5cee984c04d8276059a0111cd33673cfe85280050199989ad5046f906a6` | avbroot OK |
| `firmware/akita/grapheneos/2026101600/` | 2026101600 (CP3A.261005.005) | `akita-install-2026101600.zip` | 1985838580 | `4fd4ae7c1d33bcbab303f37257420c9fee3909230da8c1c30c00d8fd63c05bed` | Good signature |
| | | `akita-ota_update-2026101600.zip` | 1551286530 | `21185dc9dbbe768892774797c826e021ba881bf0670cfc1e851f5bf567d62d61` | avbroot OK |
| `firmware/akita/grapheneos/2026101601/` | 2026101601 (CP3A.261005.005) | `akita-install-2026101601.zip` | 1985916653 | `f05f305e2d7b426056cb1b63b66d26e82e0fad695162655df696a63de98da9df` | Good signature |
| | | `akita-ota_update-2026101601.zip` | 1551395386 | `090bc5d689278619e97d122a9dfe708857d87ac0769917f8528212e30dbe425a` | avbroot OK |

OTA certificate pins: `image/pins/grapheneos-ota-{panther,akita}.crt` (source and fingerprints in `image/pins/README.md`). Keep the last 3 builds per codename.

Archive (§7 Layout, DAY0 §5): `archive/2026-10-10-pre-vos.tar.zst`, created 2026-10-18 23:26 CT, 6865617 bytes, SHA-256 `abd660196f7bc1c31625cad0b12b7c12136d5b179299858ac379a25e6bf57b5e`, read-only. Contains `~/voice-agent` (a symlink to `argoos`) and `~/argoos`. Excluded (listed in `archive/2026-10-10-pre-vos.EXCLUDED.txt`): `.env*`, keystores and private keys, `local.properties` (`agentd/local.properties` existed), the old `Config.kt` that embeds the old API key, all `build/` and `.gradle/` dirs and APK/AAB outputs (they carry the compiled old key), and the 1.1 GB `.venv`. A content scan of the tarball for the old key pattern found 0 hits.

## How to fill in the PENDING rows (Caleb)

```bash
# Phone B, in fastboot mode (bootloader):
fastboot getvar product            # expect: panther
fastboot flashing get_unlock_ability   # expect: 1 — record the output and date here
# Firmware trees:
ls -l /media/caleb/data-disk/vos/firmware/panther/grapheneos/ /media/caleb/data-disk/vos/firmware/akita/grapheneos/
```
