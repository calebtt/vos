# Pinned upstream certificates (TARGET-PLAN §4.4 step 1, threat A6)

Public certificates only. Never put a private key here.

| File | What | SHA-256 fingerprint | Source |
|---|---|---|---|
| `grapheneos-ota-panther.crt` | GrapheneOS OTA signing cert trusted by recovery, Pixel 7 `panther` (CN=GrapheneOS, valid 2022-10-14 → 2050-03-01 UTC) | `AD:E7:36:A7:D8:E6:2E:1F:58:15:6B:40:18:CE:91:F8:98:AA:73:65:C0:2A:1C:CF:44:72:E5:7D:B3:07:A3:4B` | Extracted 2026-10-18 from `panther-install-2026101600.zip` (factory image, signature verified with Graphene's `allowed_signers`, key `SHA256:AhgHif0mei+9aNyKLfMZBh2yptHdw/aN7Tlh/j2eFwM`): `vendor_boot.img` → ramdisk → `system/etc/security/otacerts.zip` → `keys/releasekey.x509.pem`. Byte-identical (DER) to `META-INF/com/android/otacert` in `panther-ota_update-2026101600.zip`. |
| `grapheneos-ota-akita.crt` | Same, Pixel 8a `akita` (CN=GrapheneOS, valid 2024-05-11 → 2051-09-27 UTC) | `6A:38:C3:D9:86:14:D8:3C:1E:61:60:82:A1:4F:87:8E:91:2E:60:13:B1:F3:09:6C:D5:9F:38:7F:DA:AD:C3:E9` | Same method from `akita-install-2026101600.zip`; byte-identical to the otacert in `akita-ota_update-2026101600.zip`. |

Tools: avbroot 3.37.0 (release zip verified with chenxiaolong's SSH key, `SHA256:Ct0HoRyrFLrnF9W+A/BKEiJmwx7yWkgaW/JvghKrboA`), `avbroot boot unpack` + `avbroot cpio unpack`.

Re-check a pin:

```bash
openssl x509 -in image/pins/grapheneos-ota-panther.crt -noout -fingerprint -sha256
avbroot ota verify --input <panther-ota_update-BUILD.zip> --cert-ota image/pins/grapheneos-ota-panther.crt
```

If Graphene ever rotates a device's OTA key, a new pin needs a new factory-image extraction and a change-log row; an OTA that fails against the pin is not used.
