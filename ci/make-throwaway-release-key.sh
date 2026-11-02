#!/usr/bin/env bash
# Generate a throwaway release keystore OUTSIDE the repo so CI exercises the release signing path
# and the "release cert != debug cert" check. The key is random per run and never reused or
# uploaded. The real release key lives in <key-dir>/ and is never in CI (TARGET-PLAN §5.4).
#
# Usage: ci/make-throwaway-release-key.sh <dir>   → prints VOS_RELEASE_* lines for $GITHUB_ENV
set -euo pipefail

dir="${1:?usage: $0 <dir outside the repo>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$dir"
dir="$(cd "$dir" && pwd)"
case "$dir/" in
  "$root"/*) echo "refusing to write a keystore inside the repo: $dir" >&2; exit 1 ;;
esac

store="$dir/vos-throwaway-release.p12"
pass="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
rm -f "$store"
keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$store" -storepass "$pass" \
  -alias vos-throwaway -keyalg EC -groupname secp256r1 -validity 2 \
  -dname "CN=VOS throwaway CI release key" >/dev/null 2>&1

echo "VOS_RELEASE_STORE_FILE=$store"
echo "VOS_RELEASE_STORE_PASSWORD=$pass"
echo "VOS_RELEASE_KEY_ALIAS=vos-throwaway"
echo "VOS_RELEASE_KEY_PASSWORD=$pass"
