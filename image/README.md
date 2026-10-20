# image — the VOS Image (Tier P)

TARGET-PLAN §4.4. GrapheneOS's own signed full OTA for one device, plus a VOS overlay on the `product` partition, re-signed with the owner's AVB and OTA keys by [avbroot](https://github.com/chenxiaolong/avbroot) `--rootless`. Graphene's code is unmodified and nothing is built from source.

| Path | What |
|---|---|
| `build.sh` | Builds a VOS Image for one codename (`panther` for 1.0, `akita` in the post-1.0 port, decision 40). No root needed. |
| `keys.sh` | Makes the owner keys in `~/.vos-keys/` once (Caleb, by hand; §5.4). |
| `overlays/<name>/` | A tree that mirrors `/product`. Files are added; nothing of Graphene's is replaced. |
| `pins/` | Graphene's OTA certificates, pinned (threat A6). |

## Build

```bash
image/build.sh --device panther \
  --ota /media/caleb/UStor2/vos/firmware/panther/grapheneos/<build>/panther-ota_update-<build>.zip \
  --overlay image/overlays/s01-hello \
  --add priv-app/VosHello/VosHello.apk=<built apk>
```

`--add <path under /product>=<file>` puts a build output into the overlay without committing it. Output: `/media/caleb/UStor2/vos/out/images/<YYYY-MM>/vos-<device>-<build>-<overlay>-<git>.zip` and a `.manifest.txt` beside it (source OTA hash, pins, owner key fingerprints, every added file's hash, avbroot version, git commit). `--out` overrides the path.

What it does, in order:

1. Checks the OTA is for `--device` and verifies it against `pins/grapheneos-ota-<device>.crt`.
2. Extracts `product` and strips its AVB footer.
3. Graphene's `product` is ext4 with `shared_blocks` and almost no free space. It grows the image by the overlay size + 64 MB (`resize2fs`) and unshares the blocks (`e2fsck -E unshare_blocks`).
4. Adds the overlay with `debugfs`: `root:root`, `0755` dirs, `0644` files, SELinux label `u:object_r:system_file:s0`, the AOSP 2009-01-01 timestamp. It refuses to replace any existing file, reads every file back, and requires a clean `e2fsck -fn`.
5. Rebuilds the dm-verity hash tree and FEC (`avbroot avb pack --recompute-size`).
6. `avbroot ota patch --rootless --replace product`, signed with `~/.vos-keys` (`--keys` overrides). avbroot prompts for each passphrase.
7. Verifies the output against the owner OTA cert and AVB key, and checks its `product` is the one just built.

Work space: `$VOS_IMAGE_WORK`, else `/mnt/vos-build/image-work` when it exists (§7), else a temp dir; about 3 GB, wiped at the end. A panther build takes under a minute on caleb-Z790.

**Limit (v0):** growing by less than one ext4 block group adds no inodes, so an overlay can add only ~39 files and directories to Graphene's `product`. Fine for the hello image and the first real overlay; a larger overlay needs v1 (grow by a block group, or rebuild the filesystem).

## Flashing

First flash, update path, and the relock are in [docs/spikes/S-01.md](../docs/spikes/S-01.md). The unlock and the lock each wipe the phone. Phone A is never the first device for any flash (§7).
