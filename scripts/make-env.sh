#!/usr/bin/env bash
# Writes a .env with random secrets and two users (planer, leser) for a test stack, for example in CI.
# Not for production: there, choose the values yourself (see README).
# Usage: scripts/make-env.sh <tag> [file]; prints "user password" for planer on stdout.
set -euo pipefail
TAG=${1:?tag}
OUT=${2:-.env}
cd "$(git rev-parse --show-toplevel)"
rand() { openssl rand -hex "$1"; }
hash() {
  if command -v htpasswd >/dev/null; then
    htpasswd -nbBC 10 x "$1" | cut -d: -f2
  else
    docker run --rm httpd:2.4-alpine@sha256:2b10bc3893685578fd196102382b988821403107f288d3c37ad30f3b282c5e7e \
      htpasswd -nbBC 10 x "$1" | cut -d: -f2
  fi
}
PLANNER_PW=$(rand 12)
VIEWER_PW=$(rand 12)
USERS="planer:$(hash "$PLANNER_PW"):planner,leser:$(hash "$VIEWER_PW"):viewer"
sed -e "s/^TAG=.*/TAG=$TAG/" \
    -e "s/^DB_PASSWORD=.*/DB_PASSWORD=$(rand 24)/" \
    -e "s/^SESSION_ENCRYPTION_KEY=.*/SESSION_ENCRYPTION_KEY=$(rand 32)/" \
    -e "s|^APP_USERS=.*|APP_USERS='$USERS'|" \
    .env.example > "$OUT"
chmod 600 "$OUT"
echo "planer $PLANNER_PW"
