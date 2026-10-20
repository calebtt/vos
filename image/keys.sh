#!/usr/bin/env bash
# Owner AVB + OTA keys for the VOS Image (TARGET-PLAN §5.4). Caleb runs this once, by hand.
#
# Makes, in ~/.vos-keys (or $1):
#   avb.key       AVB signing key (RSA 4096, passphrase-encrypted PKCS#8)
#   avb_pkmd.bin  its AVB public key, flashed once with `fastboot flash avb_custom_key`
#   ota.key       OTA signing key (RSA 4096, passphrase-encrypted)
#   ota.crt       its certificate; recovery and update_engine trust only this after the first flash
#
# avbroot prompts for each passphrase. Keep the passphrases in the password manager, and copy the
# directory to the offline USB stick before the first flash: losing the AVB key means a wipe to
# recover a relocked phone. Never put these files on data-disk in the clear, in the repo, or in CI.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="${1:-$HOME/.vos-keys}"
avbroot="${AVBROOT:-$ROOT/.tools/avbroot/avbroot}"
[[ -x "$avbroot" ]] || avbroot="$(command -v avbroot)" || { echo "avbroot not found" >&2; exit 1; }

for f in avb.key avb_pkmd.bin ota.key ota.crt; do
    if [[ -e "$dir/$f" ]]; then
        echo "keys.sh: $dir/$f already exists; refusing to overwrite owner keys" >&2
        exit 1
    fi
done
umask 077
mkdir -p "$dir"
chmod 700 "$dir"

echo "== AVB key (you will be asked for a new passphrase)"
"$avbroot" key generate-key --key-type rsa4096 --output "$dir/avb.key"
echo "== OTA key (you will be asked for a new passphrase)"
"$avbroot" key generate-key --key-type rsa4096 --output "$dir/ota.key"
echo "== AVB public key (enter the AVB passphrase)"
"$avbroot" key encode-avb --key "$dir/avb.key" --output "$dir/avb_pkmd.bin"
echo "== OTA certificate (enter the OTA passphrase)"
"$avbroot" key generate-cert --key "$dir/ota.key" --subject "CN=VOS OTA" --output "$dir/ota.crt"
chmod 600 "$dir"/*

echo
echo "AVB public key SHA-256: $(sha256sum < "$dir/avb_pkmd.bin" | cut -d' ' -f1)"
echo "OTA cert SHA-256:       $(openssl x509 -in "$dir/ota.crt" -noout -fingerprint -sha256 | cut -d= -f2)"
echo
echo "Next: copy $dir to the offline USB stick and store both passphrases in the password manager."
