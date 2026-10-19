# Day 0 runbook (#9, feeds M0-03 and M0-06)

For Caleb, by hand, except steps marked **done by agent** (run 2026-10-18 on Caleb's go-ahead: "just have Claude proceed with what it can"; no flashing, no secrets touched). From TARGET-PLAN §8 (Day 0), §5.4 Keys, §7 Disk and Devices, and issue #9. Tick each box in #9 as you go. Never paste a key or token into a terminal command line, a chat, an issue, or this repo.

## 1. Rotate the xAI key; prove the old key is dead (M0-03)

1. In the xAI console, create a new key, then revoke the old one.
2. Store the new key only in the place the plan allows (entered into the session at runtime, M1-KEY-01). Do not put it in the repo.
3. Save the **old** key to a file readable only by you, outside the repo: `install -m 600 /dev/null ~/old-xai-key.txt`, then paste it in with an editor.
4. Check it is rejected without printing it (the key goes through a bash builtin and a curl header file, never argv):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     -H @<(printf 'Authorization: Bearer %s\n' "$(tr -d '\n' < ~/old-xai-key.txt)") \
     https://api.x.ai/v1/models
   ```

   Pass: `401` (record the exact code; any 2xx means the key is still live). Then `shred -u ~/old-xai-key.txt`.

## 2. VPS (retired)

VPS retired; no VPS secrets remain; if a VPS is ever found still running, shut it down.

## 3. Revoke ADB authorizations on Phone A (M0-03)

On Phone A: Settings › System › Developer options › **Revoke USB debugging authorizations**; turn **Wireless debugging** off. If developer options are not needed on Phone A, turn them off. If Phone A is still in a tailnet, remove it. (Phone B stays tethered with USB debugging on for development, §7.)

## 4. Pin UStor2 in fstab (§7)

Needs sudo (Caleb): passwordless sudo is not available to agents. Status 2026-10-18: not pinned; currently automounted at this path.

```bash
sudo blkid /dev/nvme1n1p2            # expect UUID="AA947A61947A2FC5"
sudo cp /etc/fstab /etc/fstab.bak-$(date +%F)
# add one line with sudoedit /etc/fstab:
#   UUID=AA947A61947A2FC5 /media/caleb/UStor2 ntfs3 uid=1000,gid=1000,nosuid,nodev,nofail 0 0
sudo findmnt --verify                # must report no errors
sudo mount -a && findmnt /media/caleb/UStor2
```

The loop-mounted ext4 build volume (§7) can follow on Mon 10-12; it is not part of Day 0.

## 5. One-time archive of old work, with SHA-256 (§7 Layout)

**Status 2026-10-18: done by agent**, with secrets and build outputs excluded (SHA-256 and exclusion list in `docs/DEVICES.md`). The command below is the original recipe; the run added `--exclude` rules for `.env*`, keystores/keys, `local.properties`, the old `Config.kt`, `build/`, `.gradle/`, APK/AAB and `.venv`.

```bash
mkdir -p /media/caleb/UStor2/vos/archive
tar --zstd -cf /media/caleb/UStor2/vos/archive/2026-10-10-pre-vos.tar.zst -C ~ voice-agent argoos
sha256sum /media/caleb/UStor2/vos/archive/2026-10-10-pre-vos.tar.zst | tee /media/caleb/UStor2/vos/archive/2026-10-10-pre-vos.tar.zst.sha256
chmod a-w /media/caleb/UStor2/vos/archive/2026-10-10-pre-vos.tar.zst
```

The 2026-10-18 archive excludes the known secret files and the APKs that had the old key compiled in, but old history may still hold traces, so it stays off the repo and off any cloud. SHA-256 recorded in `docs/DEVICES.md` and #9.

## 6. Download GrapheneOS for `panther` and `akita`; verify signatures (§7, §4.4 step 1)

**Status 2026-10-18: done by agent** (builds 2026101600 and 2026101601 for both codenames, all verified; records in `docs/DEVICES.md`).

For each codename in `panther akita`, using the current stable build number `<build>` from grapheneos.org/releases:

```bash
c=panther   # then akita
d=/media/caleb/UStor2/vos/firmware/$c/grapheneos/<build>
mkdir -p "$d" && cd "$d"
curl -fLO https://releases.grapheneos.org/allowed_signers  # Graphene's factory-image key; compare with grapheneos.org/install/cli
curl -fLO https://releases.grapheneos.org/$c-install-<build>.zip
curl -fLO https://releases.grapheneos.org/$c-install-<build>.zip.sig
ssh-keygen -Y verify -f allowed_signers -I contact@grapheneos.org -n "factory images" -s $c-install-<build>.zip.sig < $c-install-<build>.zip
curl -fLO https://releases.grapheneos.org/$c-ota_update-<build>.zip   # full OTA, not incremental
sha256sum $c-install-<build>.zip $c-ota_update-<build>.zip | tee SHA256SUMS
```

Pass: `Good "factory images" signature for contact@grapheneos.org` for each factory image, with the Ed25519 fingerprint shown on grapheneos.org/install/cli (also recorded in `docs/DEVICES.md`) (Graphene replaced `factory.pub`/signify with `allowed_signers`/ssh-keygen). Keep the last 3 builds per codename. Then update `docs/DEVICES.md` (firmware rows).

## 7. Pin Graphene's OTA certificate into `image/pins/` (§4.4 step 1, threat A6)

**Status 2026-10-18: done by agent** (both pins committed; full OTAs pass `avbroot ota verify` against them; see `image/pins/README.md`).

1. From the **verified** factory image, extract the OTA certificate Graphene's recovery trusts (`otacerts.zip` in the system image's `etc/security/`), one per codename.
2. Save it as `image/pins/grapheneos-ota-<codename>.crt` and record `openssl x509 -in <crt> -noout -fingerprint -sha256` in `image/pins/README.md`.
3. Verify the downloaded full OTA against the pinned cert (avbroot: `avbroot ota verify --input <ota>.zip --cert-ota image/pins/grapheneos-ota-<codename>.crt` [verify flags against the avbroot version used]).
4. Commit the pins (they are public certificates; `.gitignore` allows `image/pins/`). Run `./gradlew policyCheck` first so gitleaks scans them.

## 8. Devices and orders (M0-06)

- Phone B: `fastboot flashing get_unlock_ability` → must be `1`; record in `docs/DEVICES.md`.
- Phone C (non-Pixel, Android 15+): order; record date and ETA in #4.
- LE Audio headset (Q12): order; record date and ETA in #4.

## Done when (from #9)

Old xAI key → 401; ADB authorizations revoked; orders placed with dates; mount pinned; archive SHA-256 recorded; both firmware trees started and verified; OTA cert committed to `image/pins/`.
