# VOS devices

Enrollment record for M0-06 (#131), from TARGET-PLAN §7 "Devices". Values marked **PENDING** need Caleb with the phone or the disk in hand; nothing here was read from a device.

| Device | Codename | Role | Status | Checks |
|---|---|---|---|---|
| **Phone A:** Pixel 8a (or current Graphene daily) | `akita` | Daily phone, official GrapheneOS; Tier S from M1; VOS Image only near final, after the 14-day daily-phone soak (§9 Q1). First-class Tier P target, same recipe as panther. **Holds the only SIM** → the `sim_phone`: real-comms tests are delayed until VOS runs on it. | Enrolled (path noted) | Never the first device for any flash. Back up (Seedvault + per-app exports) before M4; the key change wipes it. Confirm device-specific spikes on `akita` after the panther smoke. |
| **Phone B:** Pixel 7 (already owned) | `panther` | **Main flash/dev rig.** Tethered to caleb-Z790 over USB with USB debugging on; default for all spikes, every flash, the debug build and CI smoke. Official Graphene for M1-NOGOOG-01 and S-25, then the VOS Image. | Enrolled | `fastboot flashing get_unlock_ability` = **PENDING** (must be `1`: Google Store/unlocked variant, not Verizon). OEM unlocking stays on. Support window ~2027 [verify]. |
| **Phone C:** mainstream non-Pixel (e.g. Samsung Galaxy A-series), Android 15+ | — | Tier S reach from M1. No SIM, so no real-comms tests. | **Deferred / not yet ordered** | Record order date and ETA in #4 when ordered. |
| Headsets | — | Current classic BT; one LE Audio reference from M4 | Classic BT owned; LE Audio PENDING order | Route type logged on every turn. |
| Workstation (caleb-Z790) | — | Build, CI runner, emulators, image builds, Wi-Fi AP for edge captures | Present | No phone control in production; ADB is development-only. |
| VPS | — | **Retired** from the phone path | Day 0 PENDING | Revoke ADB, delete the key copy, remove the phone from the tailnet ACL (`docs/runbooks/DAY0.md`). |

## Firmware trees (UStor2, `/media/caleb/UStor21/vos/`)

| Path | Contents | Status |
|---|---|---|
| `firmware/panther/grapheneos/<build>/` | Phone B: Graphene full OTA + factory image + signatures (keep last 3) | **PENDING** (Day 0 download; not checked by this change) |
| `firmware/akita/grapheneos/<build>/` | Phone A path: same layout for the Pixel 8a | **PENDING** (Day 0 download; not checked by this change) |

## How to fill in the PENDING rows (Caleb)

```bash
# Phone B, in fastboot mode (bootloader):
fastboot getvar product            # expect: panther
fastboot flashing get_unlock_ability   # expect: 1 — record the output and date here
# Firmware trees:
ls -l /media/caleb/UStor21/vos/firmware/panther/grapheneos/ /media/caleb/UStor21/vos/firmware/akita/grapheneos/
```
