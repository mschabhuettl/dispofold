#!/usr/bin/env bash
# Writes the release version into every build: backend/pom.xml, frontend/package.json (and its lock file),
# desktop-shell/Cargo.toml (and its Cargo.lock entry) and desktop-shell/tauri.conf.json.
# Usage: scripts/release/set-version.sh <vMAJOR.MINOR.PATCH | MAJOR.MINOR.PATCH>
# The macOS package maps a version starting with 0 itself (see scripts/desktop/package.sh).
set -euo pipefail
TAG=${1:?version, e.g. v1.2.3}
VERSION=${TAG#v}
[[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || { echo "not a plain version: $TAG"; exit 1; }
cd "$(dirname "$0")/../.."

edit() { sed -i.bak -E "$2" "$1" && rm -f "$1.bak"; }
# project version: the <version> right after the artifactId of the backend
edit backend/pom.xml "/<artifactId>dispofold-backend<\/artifactId>/{n;s#<version>[^<]*</version>#<version>$VERSION</version>#;}"
# the first "version" of package.json and tauri.conf.json, the two root entries of the npm lock file
edit frontend/package.json "1,/\"version\": \"[^\"]*\"/s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/"
edit frontend/package-lock.json "1,12s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/"
edit desktop-shell/tauri.conf.json "1,/\"version\": \"[^\"]*\"/s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/"
edit desktop-shell/Cargo.toml "1,/^version = \"[^\"]*\"/s/^version = \"[^\"]*\"/version = \"$VERSION\"/"
edit desktop-shell/Cargo.lock "/^name = \"dispofold\"$/{n;s/^version = \"[^\"]*\"/version = \"$VERSION\"/;}"

# every place must now carry the version
check() { [ "$(grep -c -F -e "$2" "$1")" -ge "${3:-1}" ] || { echo "version not written to $1"; exit 1; }; }
check backend/pom.xml "<version>$VERSION</version>"
check frontend/package.json "\"version\": \"$VERSION\""
check frontend/package-lock.json "\"version\": \"$VERSION\"" 2
check desktop-shell/tauri.conf.json "\"version\": \"$VERSION\""
check desktop-shell/Cargo.toml "version = \"$VERSION\""
grep -A1 -x 'name = "dispofold"' desktop-shell/Cargo.lock | grep -q -x "version = \"$VERSION\"" \
  || { echo "version not written to desktop-shell/Cargo.lock"; exit 1; }
echo "version $VERSION written"
