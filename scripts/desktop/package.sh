#!/usr/bin/env bash
# Builds the desktop delivery of Dispofold: the Tauri program window (desktop-shell/) with the bundled Java runtime
# and the Java application next to it.
# Usage: scripts/desktop/package.sh <version> linux|windows|macos <output-dir>
#   linux:   Dispofold-<version>-linux-amd64.deb and Dispofold-<version>-linux-x86_64.rpm (built on Linux)
#   windows: Dispofold-<version>-windows-setup.exe (NSIS, per user) and Dispofold-<version>-windows-portable.zip,
#            cross-built on Linux with cargo-xwin
#   macos:   Dispofold-<version>-macos.dmg (built on a mac)
# Needs: JDK 21 (java on PATH, for Maven and the manifest), Node.js 22, Rust with cargo; Linux: the WebKitGTK 4.1
# development packages, rpmbuild; windows: the x86_64-pc-windows-msvc target, cargo-xwin 0.23.1, clang, lld,
# llvm, nsis, zip. Downloads (JDKs, Windows SDK) are cached in $DISPOFOLD_CACHE (default ~/.cache/dispofold).
# The version of the program window, the application and the packages is <version>; macOS refuses a bundle version
# starting with 0, so 0.y.z is packaged there as 1.y.z (the application inside keeps 0.y.z).
set -euo pipefail
VERSION=${1:?version x.y.z}
TARGET=${2:?linux|windows|macos}
OUT=${3:?output directory}
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must be x.y.z"; exit 1; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SHELL_DIR=$ROOT/desktop-shell
CACHE=${DISPOFOLD_CACHE:-$HOME/.cache/dispofold}
STAGE=$SHELL_DIR/stage/$TARGET
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

case "$TARGET" in
  linux|windows) [ "$(uname -s)" = Linux ] || { echo "$TARGET packages are built on Linux"; exit 1; } ;;
  macos) [ "$(uname -s)" = Darwin ] || { echo "macos packages are built on a mac"; exit 1; } ;;
  *) echo "unknown target $TARGET"; exit 1 ;;
esac

# 1. Interface and desktop build of the backend (H2, loopback, launcher jar)
(cd "$ROOT/frontend" && npm ci --no-audit --no-fund && npm run build)
(cd "$ROOT/backend" && ./mvnw -B -q package -DskipTests -Dquarkus.profile=desktop \
  -Dquarkus.application.version="$VERSION")

# 2. Program files next to the window: app/ (launcher, bundled application with its version file), runtime/
rm -rf "$STAGE"
mkdir -p "$STAGE/app"
cp "$ROOT/backend/target/dispofold-launcher.jar" "$STAGE/app/"
cp -r "$ROOT/backend/target/quarkus-app" "$STAGE/app/app"
# Version file of the bundled application (unsigned: the installed program is trusted)
java -cp "$STAGE/app/dispofold-launcher.jar" app.dispofold.desktop.update.SignTool manifest "$STAGE/app/app" "$VERSION" \
  "$STAGE/app/bundle.json"
"$ROOT/scripts/desktop/build-runtime.sh" "$TARGET" "$STAGE/runtime"

# 3. Package configuration: version and the files that go next to the program
bundle_version=$VERSION
[ "$TARGET" = macos ] && [[ "$VERSION" == 0.* ]] && bundle_version="1.${VERSION#0.}"
cat > "$STAGE/tauri.package.json" <<JSON
{
  "version": "$bundle_version",
  "bundle": {
    "resources": {
      "stage/$TARGET/runtime/": "runtime/",
      "stage/$TARGET/app/": "app/",
      "../LICENSE": "LICENSE",
      "../THIRD_PARTY.md": "THIRD_PARTY.md"
    }
  }
}
JSON

# 4. Program window and packages. No build paths in the binaries (panic locations, debug info).
cd "$SHELL_DIR"
npm ci --no-audit --no-fund
export RUSTFLAGS="--remap-path-prefix=$HOME/.cargo/registry/src=/cargo --remap-path-prefix=$(rustc --print sysroot)=/rust --remap-path-prefix=$ROOT=/src"
tauri() { npx tauri build --ci --config "$STAGE/tauri.package.json" "$@"; }
no_build_paths() { # no_build_paths <binary>
  if LC_ALL=C grep -a -q -F -e "$HOME" -e "$ROOT" "$1"; then echo "build paths found in $1"; exit 1; fi
}
case "$TARGET" in
  linux)
    tauri --bundles deb,rpm
    no_build_paths target/release/dispofold
    cp target/release/bundle/deb/Dispofold_"${VERSION}"_amd64.deb "$OUT/Dispofold-$VERSION-linux-amd64.deb"
    cp target/release/bundle/rpm/Dispofold-"${VERSION}"-1.x86_64.rpm "$OUT/Dispofold-$VERSION-linux-x86_64.rpm"
    ;;
  windows)
    # Windows SDK and CRT pinned; the cache is reused between builds
    export XWIN_CACHE_DIR=$CACHE/xwin XWIN_VERSION=17 XWIN_SDK_VERSION=10.0.26100 XWIN_CRT_VERSION=14.44.17.14
    tauri --runner cargo-xwin --target x86_64-pc-windows-msvc --bundles nsis
    rel=target/x86_64-pc-windows-msvc/release
    no_build_paths "$rel/dispofold.exe"
    cp "$rel/bundle/nsis/Dispofold_${VERSION}_x64-setup.exe" "$OUT/Dispofold-$VERSION-windows-setup.exe"
    # Portable: the PORTABLE marker in the program folder keeps the data in daten/ next to the program
    zipdir=$(mktemp -d)
    trap 'rm -rf "${zipdir:?}"' EXIT
    mkdir -p "$zipdir/Dispofold"
    cp "$rel/dispofold.exe" "$ROOT/LICENSE" "$ROOT/THIRD_PARTY.md" "$zipdir/Dispofold/"
    cp -r "$STAGE/runtime" "$STAGE/app" "$zipdir/Dispofold/"
    printf 'Portable Installation: Daten liegen im Ordner daten/ neben dem Programm.\r\n' > "$zipdir/Dispofold/PORTABLE"
    rm -f "$OUT/Dispofold-$VERSION-windows-portable.zip"
    (cd "$zipdir" && zip -q -r -9 "$OUT/Dispofold-$VERSION-windows-portable.zip" Dispofold)
    ;;
  macos)
    tauri --bundles dmg
    no_build_paths target/release/dispofold
    cp target/release/bundle/dmg/Dispofold_"${bundle_version}"_*.dmg "$OUT/Dispofold-$VERSION-macos.dmg"
    ;;
esac
ls -l "$OUT"
