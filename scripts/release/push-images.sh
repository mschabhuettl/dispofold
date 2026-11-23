#!/usr/bin/env bash
# Pushes the images of a version to a registry (after "docker login"), for both platforms under one name.
# Usage: scripts/release/push-images.sh <version> <registry/namespace> amd64|arm64|manifest
#   amd64, arm64: pushes the local images (built with that ARCH) as <version>-<arch> and latest-<arch>
#   manifest:     joins both under <version> and latest, so that "docker pull" takes the one of its platform
set -euo pipefail
VERSION=${1:?version}
TARGET=${2:?registry}
MODE=${3:?amd64, arm64 or manifest}
PREFIX=${IMAGE_PREFIX:-dispofold.invalid}
for service in backend frontend; do
  for tag in "$VERSION" latest; do
    if [ "$MODE" = manifest ]; then
      docker buildx imagetools create -t "$TARGET/dispofold-$service:$tag" \
        "$TARGET/dispofold-$service:$tag-amd64" "$TARGET/dispofold-$service:$tag-arm64"
    else
      docker tag "$PREFIX/dispofold-$service:$VERSION" "$TARGET/dispofold-$service:$tag-$MODE"
      docker push "$TARGET/dispofold-$service:$tag-$MODE"
    fi
  done
done
