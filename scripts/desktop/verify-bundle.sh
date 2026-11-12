#!/usr/bin/env bash
# Verifies an update bundle against the public key compiled into the built program: signature, manifest and the
# hash of every file. Exits non-zero when the bundle does not verify.
#
# Usage: scripts/desktop/verify-bundle.sh <dispofold-x.y.z-update.zip>
#   LAUNCHER_JAR  the built launcher (default backend/target/dispofold-launcher.jar, made by ./mvnw package)
set -euo pipefail
[ $# -eq 1 ] || { echo "usage: $0 <bundle.zip>" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JAR=${LAUNCHER_JAR:-$ROOT/backend/target/dispofold-launcher.jar}
[ -f "$JAR" ] || { echo "verify-bundle: launcher jar missing: $JAR (build the backend first)" >&2; exit 1; }
[ -f "$1" ] || { echo "verify-bundle: bundle missing: $1" >&2; exit 1; }
java -cp "$JAR" app.dispofold.desktop.update.SignTool verify "$1"
