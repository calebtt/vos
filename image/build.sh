#!/usr/bin/env bash
# VOS Image (Tier P) builder, v0 (TARGET-PLAN §4.4, S-01 #11).
#
# Takes GrapheneOS's own signed full OTA for one device, adds an overlay to the product partition
# (new files only; nothing of Graphene's is replaced), and re-signs the OTA with the owner's AVB and
# OTA keys through avbroot --rootless. Graphene's code is unmodified and nothing is built from source.
# The recipe is the same for every codename (panther for 1.0, akita in the post-1.0 port, decision 40).
#
# Needs no root: the product image is edited as a file with e2fsprogs (resize2fs, e2fsck, debugfs).
#
# Usage:
#   image/build.sh --device panther --ota <graphene-full-ota.zip> --overlay image/overlays/s01-hello \
#       --add priv-app/VosHello/VosHello.apk=<path/to/apk> [--add ...] [--keys $HOME/.vos-keys] [--out <zip>]
#
# Paths inside /product (overlay files and --add destinations) may use only [A-Za-z0-9._-] in each
# component. Every APK added must be a signed, non-debuggable build that is not signed with an
# Android debug key (needs the Android SDK build tools: ANDROID_HOME or local.properties).
#
# Output (--out, else): $VOS_IMAGE_OUT/<YYYY-MM>/vos-<device>-<build>-<overlay>-<git>.zip, where
# VOS_IMAGE_OUT defaults to out/images/ in this checkout (gitignored). A .manifest.txt goes beside it.
#
# Keys (--keys, default $HOME/.vos-keys; made by image/keys.sh): avb.key, avb_pkmd.bin, ota.key, ota.crt.
# avbroot prompts for each key's passphrase unless VOS_AVB_PASS_FILE / VOS_OTA_PASS_FILE name files
# that hold them (test keys only; never keep the owner's passphrases in a file).
#
# Work space: $VOS_IMAGE_WORK, else /mnt/vos-build/image-work when it exists (§7), else a temp dir.
# It needs about 3 GB and is wiped at the end.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SELINUX_LABEL="u:object_r:system_file:s0" # every file under Graphene's /product carries this label
FIXED_TIME="20090101000000"               # AOSP's fixed build timestamp, as on Graphene's files
MARGIN_MB=64

die() { echo "build.sh: $*" >&2; exit 1; }
log() { echo "== $*" >&2; }

device="" ota="" overlay="" keys="$HOME/.vos-keys" out=""
adds=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --device) device="$2"; shift 2 ;;
        --ota) ota="$2"; shift 2 ;;
        --overlay) overlay="$2"; shift 2 ;;
        --add) adds+=("$2"); shift 2 ;;
        --keys) keys="$2"; shift 2 ;;
        --out) out="$2"; shift 2 ;;
        -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done
[[ -n "$device" && -n "$ota" && -n "$overlay" ]] || die "need --device, --ota and --overlay (see --help)"
name_re='^[A-Za-z0-9._-]+$'
[[ "$device" =~ $name_re ]] || die "bad --device: $device"
[[ "$(basename "$overlay")" =~ $name_re ]] || die "overlay dir name must match $name_re: $overlay"
# A path inside /product: relative, [A-Za-z0-9._-] components, none empty, "." or "..". Paths are
# interpolated into debugfs commands, so nothing that could break their quoting gets through.
valid_rel() {
    local p="$1" c parts
    [[ "$p" =~ ^[A-Za-z0-9._/-]+$ && "$p" != /* && "$p" != */ ]] || return 1
    IFS=/ read -ra parts <<<"$p"
    for c in "${parts[@]}"; do [[ -n "$c" && "$c" != . && "$c" != .. ]] || return 1; done
}
[[ -f "$ota" ]] || die "no such OTA: $ota"
[[ -d "$overlay" ]] || die "no such overlay dir: $overlay"
pin="$ROOT/image/pins/grapheneos-ota-$device.crt"
[[ -f "$pin" ]] || die "no pinned Graphene OTA cert for $device: $pin"
for k in avb.key avb_pkmd.bin ota.key ota.crt; do
    [[ -f "$keys/$k" ]] || die "missing $keys/$k (run image/keys.sh first)"
done

avbroot="${AVBROOT:-}"
if [[ -z "$avbroot" ]]; then
    if [[ -x "$ROOT/.tools/avbroot/avbroot" ]]; then avbroot="$ROOT/.tools/avbroot/avbroot"
    else avbroot="$(command -v avbroot)" || die "avbroot not found (.tools/avbroot/ or PATH)"; fi
fi
for t in e2fsck resize2fs debugfs dumpe2fs unzip sha256sum; do
    command -v "$t" >/dev/null || PATH="$PATH:/usr/sbin:/sbin" command -v "$t" >/dev/null || die "missing tool: $t"
done
export PATH="$PATH:/usr/sbin:/sbin"
# Every time e2fsprogs stamps (superblock write and check times, new inodes) is the fixed AOSP time,
# so two builds of the same overlay from the same OTA give the same product image.
fixed_epoch="$(date -u -d "${FIXED_TIME:0:8} ${FIXED_TIME:8:2}:${FIXED_TIME:10:2}:${FIXED_TIME:12:2}" +%s)"
fixed_hex="$(printf '0x%08x:00000000' "$fixed_epoch")"
export E2FSPROGS_FAKE_TIME="$fixed_epoch" # libext2fs (resize2fs, debugfs)
export E2FSCK_TIME="$fixed_epoch"         # e2fsck keeps its own clock (it rewrites the backup superblocks)

# 1. The input must be Graphene's OTA for this device, signed with the pinned cert (threat A6).
meta="$(unzip -p "$ota" META-INF/com/android/metadata)"
ota_device="$(sed -n 's/^pre-device=//p' <<<"$meta")"
[[ "$ota_device" == "$device" ]] || die "OTA is for '$ota_device', not '$device'"
graphene_build="$(sed -n 's/^post-build-incremental=//p' <<<"$meta")"
[[ "$graphene_build" =~ $name_re ]] || die "bad post-build-incremental in the OTA metadata: $graphene_build"
log "verify $ota against $pin"
"$avbroot" ota verify --input "$ota" --cert-ota "$pin"

# 2. Stage the overlay: the committed tree plus build outputs from --add.
if [[ -n "${VOS_IMAGE_WORK:-}" ]]; then work_parent="$VOS_IMAGE_WORK"
elif [[ -d /mnt/vos-build/image-work && -w /mnt/vos-build/image-work ]]; then work_parent=/mnt/vos-build/image-work
else work_parent="${TMPDIR:-/tmp}"; fi
mkdir -p "$work_parent"
work="$(mktemp -d "$work_parent/vos-image-$device.XXXXXX")"
trap 'rm -rf "$work"' EXIT
[[ "$work" =~ ^[A-Za-z0-9._/-]+$ ]] || die "work dir path must match [A-Za-z0-9._/-]: $work"
stage="$work/stage"
mkdir -p "$stage"
cp -r "$overlay/." "$stage/"
for a in "${adds[@]+"${adds[@]}"}"; do
    dest="${a%%=*}" src="${a#*=}"
    [[ "$dest" != "$a" && -f "$src" ]] || die "bad --add '$a' (want product/path=existing-file)"
    valid_rel "$dest" || die "--add path must be a plain relative path inside /product: $dest"
    mkdir -p "$stage/$(dirname "$dest")"
    cp "$src" "$stage/$dest"
done
# Captured, not piped into grep -q: under pipefail an early grep exit can fail the pipeline open.
links="$(find "$stage" -type l)"
[[ -z "$links" ]] || die "overlay holds symlinks; not supported: $links"
while IFS= read -r -d '' f; do
    valid_rel "${f#"$stage"/}" || die "unsupported path in the overlay: ${f#"$stage"/}"
done < <(find "$stage" -mindepth 1 -print0)

# APKs: signed, not debuggable, not signed with an Android debug key. An image may be relocked on a
# phone with personal data; a debug-signed priv-app could be replaced by anyone holding that key.
apks="$(find "$stage" -type f -name '*.apk')"
if [[ -n "$apks" ]]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [[ -n "$sdk" ]] || sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null)"
    bt="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
    [[ -x "${bt}apksigner" && -x "${bt}aapt2" ]] || die "APKs in the overlay need the SDK build tools (ANDROID_HOME)"
    while IFS= read -r apk; do
        certs="$("${bt}apksigner" verify --print-certs "$apk" 2>&1)" || die "APK is not validly signed: ${apk#"$stage"/}"
        [[ "$certs" != *"CN=Android Debug"* ]] || die "APK is signed with an Android debug key: ${apk#"$stage"/}"
        badging="$("${bt}aapt2" dump badging "$apk")" || die "aapt2 cannot read ${apk#"$stage"/}"
        [[ "$badging" != *application-debuggable* ]] || die "APK is debuggable: ${apk#"$stage"/}"
    done <<<"$apks"
fi

# 3. Extract product and strip its AVB footer (hash tree + FEC are rebuilt in step 6).
log "extract product"
"$avbroot" ota extract --input "$ota" --directory "$work/extract" --partition product
mkdir -p "$work/avb"
(cd "$work/avb" && "$avbroot" avb unpack --quiet --input "$work/extract/product.img")
raw="$work/avb/raw.img"
rm -f "$work/extract/product.img"

# 4. Graphene's product ext4 is full (under 4 MB free) and has shared_blocks (deduplicated, read-only).
#    Grow it by the overlay size plus a margin, then unshare the blocks so it can be written.
stage_mb=$(( ($(du -sb "$stage" | cut -f1) + 1048575) / 1048576 ))
log "grow product by $((stage_mb + MARGIN_MB)) MB and unshare blocks"
truncate -s "+$((stage_mb + MARGIN_MB))M" "$raw"
resize2fs "$raw" >&2
rc=0; e2fsck -fy -E unshare_blocks "$raw" >&2 || rc=$?
(( rc <= 1 )) || die "e2fsck -E unshare_blocks failed ($rc)"
[[ "$(dumpe2fs -h "$raw" 2>/dev/null)" != *shared_blocks* ]] || die "shared_blocks still set after unshare"

# 5. Add the overlay with debugfs: root:root, 0755 dirs, 0644 files, the /product SELinux label,
#    the fixed AOSP timestamp on all four inode times (crtime too, so two builds of one overlay match).
#    Existing paths may be directories we descend into, never files we replace.
printf '%s\0' "$SELINUX_LABEL" > "$work/label"
stat_of() { debugfs -R "stat \"/$1\"" "$raw" 2>/dev/null || true; }
exists() { grep -q '^Inode:' <<<"$(stat_of "$1")"; }
is_dir() { grep -q 'Type: directory' <<<"$(stat_of "$1")"; }
cmds="$work/debugfs.cmds"
: > "$cmds"
meta_cmds() { # path mode
    printf 'sif "/%s" mode 0%s\nsif "/%s" uid 0\nsif "/%s" gid 0\n' "$1" "$2" "$1" "$1"
    for f in atime mtime ctime crtime; do
        printf 'sif "/%s" %s %s\nsif "/%s" %s_extra 0\n' "$1" "$f" "$FIXED_TIME" "$1" "$f"
    done
    printf 'ea_set -f "%s" "/%s" security.selinux\n' "$work/label" "$1"
}
while IFS= read -r d; do
    rel="${d#"$stage"/}"
    if exists "$rel"; then
        is_dir "$rel" || die "overlay dir collides with a Graphene file: /product/$rel"
    else
        printf 'mkdir "/%s"\n' "$rel" >> "$cmds"
        meta_cmds "$rel" 40755 >> "$cmds"
        echo "$rel" >> "$work/new-dirs"
    fi
done < <(find "$stage" -mindepth 1 -type d | LC_ALL=C sort)
# debugfs exits 0 even when a command fails; its errors are the stderr lines after the banner.
run_debugfs() {
    debugfs -w -f "$cmds" "$raw" >/dev/null 2>"$work/debugfs.err" || { cat "$work/debugfs.err" >&2; die "debugfs failed"; }
    local rc=0
    grep -v '^debugfs [0-9]' "$work/debugfs.err" > "$work/debugfs.errors" || rc=$?
    (( rc <= 1 )) || die "cannot read debugfs stderr"
    if [[ -s "$work/debugfs.errors" ]]; then cat "$work/debugfs.err" >&2; die "debugfs reported errors"; fi
}
# Directories first, so the existence checks for files below see a stable tree.
run_debugfs
: > "$cmds"
while IFS= read -r f; do
    rel="${f#"$stage"/}"
    exists "$rel" && die "overlay file would replace a Graphene file: /product/$rel"
    printf 'write "%s" "/%s"\n' "$f" "$rel" >> "$cmds"
    meta_cmds "$rel" 100644 >> "$cmds"
done < <(find "$stage" -type f | LC_ALL=C sort)
run_debugfs

# Read every file back and compare; check the label, owner, mode and all four times of every added
# path; then require a clean read-only fsck.
check_inode() { # rel mode
    local st t
    st="$(stat_of "$1")"
    grep -q "Mode:  0*$2 " <<<"$st" || die "wrong mode on /product/$1 (want $2)"
    grep -q 'User:     0   Group:     0' <<<"$st" || die "not root:root: /product/$1"
    for t in ctime atime mtime crtime; do
        grep -q "^ *$t: $fixed_hex " <<<"$st" || die "$t on /product/$1 is not $FIXED_TIME ($fixed_hex)"
    done
    grep -q "$SELINUX_LABEL" <<<"$(debugfs -R "ea_get \"/$1\" security.selinux" "$raw" 2>/dev/null || true)" \
        || die "missing SELinux label: /product/$1"
}
while IFS= read -r f; do
    rel="${f#"$stage"/}"
    got="$(debugfs -R "cat \"/$rel\"" "$raw" 2>/dev/null | sha256sum | cut -d' ' -f1)"
    [[ "$got" == "$(sha256sum < "$f" | cut -d' ' -f1)" ]] || die "readback mismatch: /product/$rel"
    check_inode "$rel" 644
done < <(find "$stage" -type f)
if [[ -f "$work/new-dirs" ]]; then
    while IFS= read -r rel; do check_inode "$rel" 755; done < "$work/new-dirs"
fi
e2fsck -fn "$raw" >&2 || die "product image is not clean after the overlay"
debugfs -w -R "ssv lastcheck $FIXED_TIME" "$raw" 2>/dev/null
sb="$(TZ=UTC dumpe2fs -h "$raw" 2>/dev/null)"
want_date="$(TZ=UTC LC_ALL=C date -d "@$fixed_epoch" '+%a %b %e %H:%M:%S %Y')"
for field in "Last write time" "Last checked"; do
    grep -q "^$field: *$want_date\$" <<<"$sb" || die "superblock '$field' is not $FIXED_TIME"
done

# 6. Rebuild the AVB footer (dm-verity hash tree + FEC). Product's own header is unsigned on Graphene;
#    vbmeta carries its descriptor, and avbroot re-signs vbmeta with the owner key in step 7.
log "rebuild product hash tree"
(cd "$work/avb" && "$avbroot" avb pack --quiet --output "$work/product.img" --input-info avb.toml \
    --input-raw raw.img --recompute-size)
rm -f "$raw"

# 7. Patch and re-sign the OTA.
git_sha="$(git -C "$ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo nogit)"
git -C "$ROOT" diff --quiet HEAD 2>/dev/null || git_sha="$git_sha-dirty"
if [[ -z "$out" ]]; then
    out="${VOS_IMAGE_OUT:-$ROOT/out/images}/$(date +%Y-%m)/vos-$device-$graphene_build-$(basename "$overlay")-$git_sha.zip"
fi
mkdir -p "$(dirname "$out")"
pass_args=()
[[ -n "${VOS_AVB_PASS_FILE:-}" ]] && pass_args+=(--pass-avb-file "$VOS_AVB_PASS_FILE")
[[ -n "${VOS_OTA_PASS_FILE:-}" ]] && pass_args+=(--pass-ota-file "$VOS_OTA_PASS_FILE")
log "patch OTA -> $out"
"$avbroot" ota patch --input "$ota" --output "$out" --rootless \
    --key-avb "$keys/avb.key" --key-ota "$keys/ota.key" --cert-ota "$keys/ota.crt" \
    --replace product "$work/product.img" "${pass_args[@]+"${pass_args[@]}"}"

# 8. The output must verify against the owner's keys, and the product partition must be ours.
log "verify output against the owner keys"
"$avbroot" ota verify --input "$out" --cert-ota "$keys/ota.crt" --public-key-avb "$keys/avb_pkmd.bin"
"$avbroot" ota extract --input "$out" --directory "$work/check" --partition product
cmp -s "$work/check/product.img" "$work/product.img" || die "product in the output OTA differs from the built one"

# 9. Manifest next to the OTA (§7: out/images/ keeps the overlay manifest and logs).
{
    echo "vos_image_manifest=0"
    echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "device=$device"
    echo "graphene_build=$graphene_build"
    echo "source_ota=$(basename "$ota")"
    echo "source_ota_sha256=$(sha256sum < "$ota" | cut -d' ' -f1)"
    echo "graphene_ota_pin_sha256=$(openssl x509 -in "$pin" -noout -fingerprint -sha256 | cut -d= -f2)"
    echo "owner_ota_cert_sha256=$(openssl x509 -in "$keys/ota.crt" -noout -fingerprint -sha256 | cut -d= -f2)"
    echo "owner_avb_pkmd_sha256=$(sha256sum < "$keys/avb_pkmd.bin" | cut -d' ' -f1)"
    echo "avbroot=$("$avbroot" --version)"
    echo "vos_git=$git_sha"
    echo "overlay=$(basename "$overlay")"
    (cd "$stage" && find . -type f | LC_ALL=C sort | while IFS= read -r f; do
        echo "file=/product/${f#./} $(sha256sum < "$f" | cut -d' ' -f1)"; done)
    echo "output_sha256=$(sha256sum < "$out" | cut -d' ' -f1)"
} > "$out.manifest.txt"
log "done: $out"
cat "$out.manifest.txt"
