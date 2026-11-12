#!/usr/bin/env bash
# Builds the two images. They are named <prefix>/dispofold-<service>:<version> and :latest, with the
# prefix from IMAGE_PREFIX (default: dispofold.invalid, as in docker-compose.yml; a name that never resolves).
# Usage: scripts/release/build-images.sh <version>
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
    --output type=docker,rewrite-timestamp=true \
    -t "$PREFIX/dispofold-$service:$VERSION" -t "$PREFIX/dispofold-$service:latest" "$@"
}
# Both are built from the repository root (demo dataset, LICENSE, THIRD_PARTY.md).
build backend -f backend/Dockerfile .
build frontend -f frontend/Dockerfile .
