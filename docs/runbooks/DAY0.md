# Day 0 runbook (#9, feeds M0-03 and M0-06)

For Caleb, by hand. **No agent or CI runs any step here.** From TARGET-PLAN §8 (Day 0), §5.4 Keys, §7 Disk and Devices, and issue #9. Tick each box in #9 as you go. Never paste a key or token into a terminal command line, a chat, an issue, or this repo.

## 1. Rotate the xAI key; prove the old key is dead (M0-03)

1. In the xAI console, create a new key, then revoke the old one.
2. Store the new key only in the place the plan allows (entered into the session at runtime, M1-KEY-01). Do not put it in the repo or on the VPS.
3. Save the **old** key to a file readable only by you, outside the repo: `install -m 600 /dev/null ~/old-xai-key.txt`, then paste it in with an editor.
4. Check it is rejected without printing it (the key goes through a bash builtin and a curl header file, never argv):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     -H @<(printf 'Authorization: Bearer %s\n' "$(tr -d '\n' < ~/old-xai-key.txt)") \
     https://api.x.ai/v1/models
   ```

   Pass: `401` (record the exact code; any 2xx means the key is still live). Then `shred -u ~/old-xai-key.txt`.

## 2. Rotate the VPS `SERVER_TOKEN`; prove the old token is rejected (M0-03)

1. On the VPS, replace `SERVER_TOKEN` in its `.env` with a new random value (`openssl rand -hex 32`, typed into the editor on the VPS) and restart the service.
2. Delete the old xAI key from the VPS `.env` (§8: "delete the key from the VPS `.env`").
3. From the workstation, with the old token saved the same way (`~/old-vps-token.txt`, mode 600):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     -H @<(printf 'Authorization: Bearer %s\n' "$(tr -d '\n' < ~/old-vps-token.txt)") \
     https://<vps-host>/<authenticated-endpoint>
   ```

   Pass: `401` or `403`. Then `shred -u ~/old-vps-token.txt`.
4. Remove the phone from the tailnet ACL (§7 Devices: VPS row).

## 3. Revoke ADB authorizations on Phone A (M0-03)

On Phone A: Settings › System › Developer options › **Revoke USB debugging authorizations**; turn **Wireless debugging** off. If developer options are not needed on Phone A, turn them off. (Phone B stays tethered with USB debugging on for development, §7.)

## 4. Pin UStor2 in fstab (§7)

```bash
sudo blkid /dev/nvme1n1p2            # copy the UUID
sudo cp /etc/fstab /etc/fstab.bak-$(date +%F)
# add one line with sudoedit /etc/fstab:
#   UUID=<nvme1n1p2-uuid> /media/caleb/UStor21 ntfs3 uid=1000,gid=1000,nosuid,nodev,nofail 0 0
sudo findmnt --verify                # must report no errors
sudo mount -a && findmnt /media/caleb/UStor21
```

The loop-mounted ext4 build volume (§7) can follow on Mon 10-12; it is not part of Day 0.

## 5. One-time archive of old work, with SHA-256 (§7 Layout)

```bash
mkdir -p /media/caleb/UStor21/vos/archive
tar --zstd -cf /media/caleb/UStor21/vos/archive/2026-10-10-pre-vos.tar.zst -C ~ voice-agent argoos
sha256sum /media/caleb/UStor21/vos/archive/2026-10-10-pre-vos.tar.zst | tee /media/caleb/UStor21/vos/archive/2026-10-10-pre-vos.tar.zst.sha256
chmod a-w /media/caleb/UStor21/vos/archive/2026-10-10-pre-vos.tar.zst
```

The archive contains old secrets (the old key was compiled into the old APK). It stays off the repo and off any cloud. Record the SHA-256 in #9.

## 6. Download GrapheneOS for `panther` and `akita`; verify signatures (§7, §4.4 step 1)

For each codename in `panther akita`, using the current stable build number `<build>` from grapheneos.org/releases:

```bash
c=panther   # then akita
d=/media/caleb/UStor21/vos/firmware/$c/grapheneos/<build>
mkdir -p "$d" && cd "$d"
curl -fLO https://releases.grapheneos.org/factory.pub        # Graphene's factory signing key; compare with grapheneos.org/install
curl -fLO https://releases.grapheneos.org/$c-install-<build>.zip
curl -fLO https://releases.grapheneos.org/$c-install-<build>.zip.sig
signify -Cqp factory.pub -x $c-install-<build>.zip.sig && echo verified
curl -fLO https://releases.grapheneos.org/$c-ota_update-<build>.zip   # full OTA, not incremental
sha256sum $c-install-<build>.zip $c-ota_update-<build>.zip | tee SHA256SUMS
```

Pass: `verified` for each factory image. Keep the last 3 builds per codename. Then update `docs/DEVICES.md` (firmware rows).

## 7. Pin Graphene's OTA certificate into `image/pins/` (§4.4 step 1, threat A6)

1. From the **verified** factory image, extract the OTA certificate Graphene's recovery trusts (`otacerts.zip` in the system image's `etc/security/`), one per codename.
2. Save it as `image/pins/grapheneos-ota-<codename>.crt` and record `openssl x509 -in <crt> -noout -fingerprint -sha256` in `image/pins/README.md`.
3. Verify the downloaded full OTA against the pinned cert (avbroot: `avbroot ota verify --input <ota>.zip --cert-ota image/pins/grapheneos-ota-<codename>.crt` [verify flags against the avbroot version used]).
4. Commit the pins (they are public certificates; `.gitignore` allows `image/pins/`). Run `./gradlew policyCheck` first so gitleaks scans them.

## 8. Devices and orders (M0-06)

- Phone B: `fastboot flashing get_unlock_ability` → must be `1`; record in `docs/DEVICES.md`.
- Phone C (non-Pixel, Android 15+): order; record date and ETA in #4.
- LE Audio headset (Q12): order; record date and ETA in #4.

## Done when (from #9)

Old xAI key → 401; old VPS token rejected; ADB authorizations revoked; VPS key copy deleted; orders placed with dates; mount pinned; archive SHA-256 recorded; both firmware trees started and verified; OTA cert committed to `image/pins/`.
