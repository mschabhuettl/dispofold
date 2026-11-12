#!/usr/bin/env bash
# Builds the bundled Java runtime (jlink image) for one target operating system from pinned Temurin JDKs.
# Usage: scripts/desktop/build-runtime.sh linux|windows|macos <output-dir>
#   Linux builds the linux and windows runtimes (jlink of the Linux JDK against the Windows jmods, same version);
#   macos runs on a mac. Downloads are checked against the SHA-256 below and cached in $DISPOFOLD_CACHE
#   (default ~/.cache/dispofold).
# Needs: curl, tar, unzip, sha256sum or shasum.
set -euo pipefail
TARGET=${1:?linux|windows|macos}
OUT=${2:?output directory}
CACHE=${DISPOFOLD_CACHE:-$HOME/.cache/dispofold}/jdk
VERSION=21.0.13+9
BASE="https://api.adoptium.net/v3/binary/version/jdk-${VERSION/+/%2B}"

# name|os|arch|archive|sha256
JDKS="
linux-x64|linux|x64|tar.gz|d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c
windows-x64|windows|x64|zip|a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9
mac-aarch64|mac|aarch64|tar.gz|bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a
mac-x64|mac|x64|tar.gz|9aa1d1c17ae5467a022679e7e0af5c00797d94ac462ed1a46f4db07a8a34a6dd
"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# jdk_home <name>: downloads, checks and unpacks one JDK; prints its home folder
jdk_home() {
  local line os arch ext sum dir file
  line=$(printf '%s\n' "$JDKS" | grep "^$1|") || { echo "unknown JDK $1" >&2; exit 1; }
  IFS='|' read -r _ os arch ext sum <<<"$line"
  dir="$CACHE/$1"
  if [ ! -f "$dir/.ok" ]; then
    mkdir -p "$CACHE"
    file="$CACHE/$1.$ext"
    [ -f "$file" ] && [ "$(sha256 "$file")" = "$sum" ] || curl -fsSL -o "$file" "$BASE/$os/$arch/jdk/hotspot/normal/eclipse"
    if [ "$(sha256 "$file")" != "$sum" ]; then
      echo "SHA-256 of the $1 JDK does not match the pinned value" >&2; rm -f "$file"; exit 1
    fi
    rm -rf "$dir"; mkdir -p "$dir"
    # runs inside $(...), where set -e does not hold: stop explicitly, so a broken unpack is never marked good
    if [ "$ext" = zip ]; then unzip -q "$file" -d "$dir" || exit 1; else tar -xzf "$file" -C "$dir" || exit 1; fi
    touch "$dir/.ok"
  fi
  local home
  home=$(find "$dir" -mindepth 1 -maxdepth 1 -type d -name 'jdk-*' | head -1)
  [ -d "$home/Contents/Home" ] && home="$home/Contents/Home"
  echo "$home"
}

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) host=linux-x64 ;;
  Darwin-arm64) host=mac-aarch64 ;;
  Darwin-x86_64) host=mac-x64 ;;
  *) echo "unsupported build machine $(uname -sm)" >&2; exit 1 ;;
esac
case "$TARGET" in
  linux) [ "$host" = linux-x64 ] || { echo "linux runtime is built on Linux" >&2; exit 1; }; jmods_of=linux-x64 ;;
  windows) [ "$host" = linux-x64 ] || { echo "windows runtime is built on Linux" >&2; exit 1; }; jmods_of=windows-x64 ;;
  macos) case "$host" in mac-*) jmods_of=$host ;; *) echo "macos runtime is built on a mac" >&2; exit 1 ;; esac ;;
  *) echo "unknown target $TARGET" >&2; exit 1 ;;
esac

# Modules of the launcher and the application (Quarkus, H2, POI); no Swing in shell mode, but java.desktop is
# needed by POI and the launcher's headless paths.
MODS=java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http
MODS=$MODS,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa
MODS=$MODS,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.localedata,jdk.naming.dns
MODS=$MODS,jdk.net,jdk.unsupported,jdk.zipfs

JLINK="$(jdk_home "$host")/bin/jlink" || exit 1
JMODS="$(jdk_home "$jmods_of")/jmods" || exit 1
rm -rf "$OUT"
"$JLINK" --module-path "$JMODS" --add-modules "$MODS" --strip-debug --no-header-files --no-man-pages \
  --compress=zip-6 --output "$OUT"
# jlink marks the legal files read-only; the bundler copies them again on the next build and fails otherwise
chmod -R u+w "$OUT"
grep -E '^(JAVA_VERSION|OS_NAME|OS_ARCH)=' "$OUT/release"
