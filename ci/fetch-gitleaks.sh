#!/usr/bin/env bash
# Fetch a pinned gitleaks release into .tools/gitleaks (gitignored) and verify its SHA-256.
# Used by CI and for local runs; ci/policy_check.py looks for .tools/gitleaks first.
set -euo pipefail

VERSION="8.28.0"
SHA256="a65b5253807a68ac0cafa4414031fd740aeb55f54fb7e55f386acb52e6a840eb"  # gitleaks_8.28.0_linux_x64.tar.gz
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/.tools"
TARBALL="gitleaks_${VERSION}_linux_x64.tar.gz"

if [[ -x "$DEST/gitleaks" ]] && "$DEST/gitleaks" version 2>/dev/null | grep -q "$VERSION"; then
  echo "gitleaks $VERSION already in $DEST"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL -o "$work/$TARBALL" "https://github.com/gitleaks/gitleaks/releases/download/v${VERSION}/${TARBALL}"
echo "${SHA256}  $work/$TARBALL" | sha256sum -c -
tar -xzf "$work/$TARBALL" -C "$work" gitleaks
mkdir -p "$DEST"
install -m 0755 "$work/gitleaks" "$DEST/gitleaks"
"$DEST/gitleaks" version
