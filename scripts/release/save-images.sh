#!/usr/bin/env bash
# Writes the images of a version into one compressed archive plus a SHA-256 file, next to docker-compose.yml
# and env.example.
# Usage: scripts/release/save-images.sh <version> <output-dir>
set -euo pipefail
VERSION=${1:?version}
OUT=${2:?output directory}
PREFIX=${IMAGE_PREFIX:-dispofold.invalid}
mkdir -p "$OUT"
archive="dispofold-$VERSION-images.tar.gz"
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
docker pull -q "$db_image"
docker tag "$db_image" "$db_local"
images+=("$db_local")
docker save "${images[@]}" | gzip -n -6 > "$OUT/$archive"
# Without the leading dot: release uploads (and shell globs) skip dot files.
cp docker-compose.yml "$OUT/"
{ sed "s/^TAG=.*/TAG=$VERSION/" .env.example
  printf '\n# Database image from the archive\nPOSTGRES_IMAGE=%s\n' "$db_local"; } > "$OUT/env.example"
(cd "$OUT" && sha256sum "$archive" docker-compose.yml env.example > "dispofold-$VERSION-SHA256SUMS.txt")
ls -l "$OUT"
