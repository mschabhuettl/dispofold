#!/usr/bin/env bash
# Builds the signed, platform independent update bundle dispofold-<version>-update.zip.
# The private key comes from the environment (UPDATE_SIGNING_KEY, Base64 PKCS#8) or a key file.
# Usage: UPDATE_SIGNING_KEY=... scripts/desktop/update-bundle.sh <version> <output-dir> [keyfile]
# Run it after scripts/desktop/package.sh, which leaves the desktop build in backend/target.
set -euo pipefail
VERSION=${1:?version x.y.z}
OUT=${2:?output directory}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "${WORK:?}"' EXIT
KEY=${3:-}
if [ -z "$KEY" ]; then
  KEY="$WORK/key"
  (umask 077 && printf '%s' "${UPDATE_SIGNING_KEY:?UPDATE_SIGNING_KEY missing}" > "$KEY")
fi
mkdir -p "$OUT"
java -cp "$ROOT/backend/target/dispofold-launcher.jar" app.dispofold.desktop.update.SignTool bundle "$KEY" \
  "$ROOT/backend/target/quarkus-app" "$VERSION" "$OUT"
