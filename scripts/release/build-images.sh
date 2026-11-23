#!/usr/bin/env bash
# Builds the two images. They are named <prefix>/dispofold-<service>:<version> and :latest, with the
# prefix from IMAGE_PREFIX (default: dispofold.invalid, as in docker-compose.yml; a name that never resolves).
# ARCH=amd64 or arm64 builds them for that platform (default: the machine's); the application and the static files
# are built on the machine's platform, only the last stage runs for the target (emulated by QEMU when it differs).
# Usage: [ARCH=arm64] scripts/release/build-images.sh <version>
set -euo pipefail
VERSION=${1:?version}
PREFIX=${IMAGE_PREFIX:-dispofold.invalid}
cd "$(git rev-parse --show-toplevel)"
# Timestamps from the commit, so that builds of the same commit give the same images.
SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct)}
export SOURCE_DATE_EPOCH
# Rewriting timestamps needs a BuildKit container builder; the default Docker builder cannot do it.
docker buildx inspect dispofold-release >/dev/null 2>&1 || docker buildx create --name dispofold-release --driver docker-container >/dev/null
build() {
  local service=$1
  shift
  docker buildx build --builder dispofold-release --build-arg SOURCE_DATE_EPOCH --provenance=false \
    --output type=docker,rewrite-timestamp=true ${ARCH:+--platform "linux/$ARCH"} \
    -t "$PREFIX/dispofold-$service:$VERSION" -t "$PREFIX/dispofold-$service:latest" "$@"
}
# Both are built from the repository root (demo dataset, LICENSE, THIRD_PARTY.md). MAVEN_SEED, if set, is a Maven
# repository the backend build starts from (the release passes the runner's; see backend/Dockerfile).
build backend -f backend/Dockerfile ${MAVEN_SEED:+--build-context "m2seed=$MAVEN_SEED"} .
build frontend -f frontend/Dockerfile .
