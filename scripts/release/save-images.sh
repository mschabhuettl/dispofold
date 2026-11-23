#!/usr/bin/env bash
# Writes the images of a version into one compressed archive plus a SHA-256 file, next to docker-compose.yml
# and env.example. ARCH=arm64 writes the archive of the arm64 images (built with ARCH=arm64, with the database
# image for arm64) as dispofold-<version>-images-arm64.tar.gz; the SHA-256 file then lists both archives.
# Usage: [ARCH=arm64] scripts/release/save-images.sh <version> <output-dir>
set -euo pipefail
VERSION=${1:?version}
OUT=${2:?output directory}
PREFIX=${IMAGE_PREFIX:-dispofold.invalid}
mkdir -p "$OUT"
ARCH=${ARCH:-amd64}
archive="dispofold-$VERSION-images.tar.gz"
[ "$ARCH" = amd64 ] || archive="dispofold-$VERSION-images-$ARCH.tar.gz"
images=()
for service in backend frontend; do
  images+=("$PREFIX/dispofold-$service:$VERSION" "$PREFIX/dispofold-$service:latest")
done
# The database image too, so that a server without internet access can start the stack. An image saved by its
# digest has no name after docker load, so it goes into the archive under its own name, which the archive's
# env.example names in POSTGRES_IMAGE.
db_image=$(sed -n 's/.*POSTGRES_IMAGE:-\(postgres:[^}]*\)}.*/\1/p' docker-compose.yml)
[ -n "$db_image" ] || { echo "database image not found in docker-compose.yml"; exit 1; }
db_local="$PREFIX/dispofold-postgres:$VERSION"
docker pull -q --platform "linux/$ARCH" "$db_image"
docker tag "$db_image" "$db_local"
images+=("$db_local")
# only the platform of this archive (a store that keeps several platforms of one image would save them all)
docker save --platform "linux/$ARCH" "${images[@]}" | gzip -n -6 > "$OUT/$archive"
# Without the leading dot: release uploads (and shell globs) skip dot files.
cp docker-compose.yml "$OUT/"
{ sed "s/^TAG=.*/TAG=$VERSION/" .env.example
  printf '\n# Database image from the archive\nPOSTGRES_IMAGE=%s\n' "$db_local"; } > "$OUT/env.example"
(cd "$OUT" && sha256sum dispofold-"$VERSION"-images*.tar.gz docker-compose.yml env.example \
  > "dispofold-$VERSION-SHA256SUMS.txt")
ls -l "$OUT"
