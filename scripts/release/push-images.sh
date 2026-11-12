#!/usr/bin/env bash
# Pushes the images of a version to a registry (after "docker login").
# Usage: scripts/release/push-images.sh <version> <registry/namespace>
set -euo pipefail
VERSION=${1:?version}
TARGET=${2:?registry}
PREFIX=${IMAGE_PREFIX:-dispofold.invalid}
for service in backend frontend; do
  for tag in "$VERSION" latest; do
    docker tag "$PREFIX/dispofold-$service:$VERSION" "$TARGET/dispofold-$service:$tag"
    docker push "$TARGET/dispofold-$service:$tag"
  done
done
