#!/usr/bin/env bash
# End-to-end check of a running stack with synthetic data:
# log in, load demo data, plan a month, check the result and the exports.
# Usage: scripts/smoke-test.sh [base-url] [user] [password] [month]
# WARNING: replaces the master data of the instance with synthetic demo data.
set -euo pipefail
BASE=${1:-http://localhost:8080}
USER_NAME=${2:-planer}
PASSWORD=${3:-bitte-aendern}
MONTH=${4:-2027-01}
LIMIT=${SMOKE_TIME_LIMIT:-60}
JAR=$(mktemp)
OUT=$(mktemp -d)
trap 'rm -f "$JAR"; rm -rf "${OUT:?}"' EXIT

anonymous=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/me")
echo "without login: $anonymous"
[ "$anonymous" = 401 ] || { echo "FAILED: the API answers without login"; exit 1; }
location=$(curl -fsS -c "$JAR" -o /dev/null -w '%{redirect_url}' \
  -d "j_username=$USER_NAME&j_password=$PASSWORD" "$BASE/j_security_check")
echo "login redirects to: $location"
[ "$location" = "$BASE/" ] || { echo "FAILED: login redirect leaves $BASE"; exit 1; }
api() { curl -fsS -b "$JAR" -H 'Content-Type: application/json' "$@"; }

echo "me: $(api "$BASE/api/me")"
echo "import: $(api -X POST "$BASE/api/import/demo" | head -c 300)"
api -X POST -d "{\"seed\":1,\"timeLimitSeconds\":$LIMIT}" "$BASE/api/plans/$MONTH/solve" >/dev/null

for _ in $(seq 1 $((LIMIT + 120))); do
  state=$(api "$BASE/api/plans/$MONTH")
  if ! echo "$state" | grep -q '"solving":true'; then
    break
  fi
  sleep 1
done
echo "$state" | grep -o '"status":"[A-Z]*"' | head -1
echo "$state" | grep -o '"score":"[^"]*"' | head -1

analysis=$(api "$BASE/api/plans/$MONTH/analysis")
hard=$(echo "$analysis" | grep -o '"hardViolations":[0-9]*' | head -1 | cut -d: -f2)
echo "hard violations: $hard"
[ "$hard" = "0" ] || { echo "FAILED: hard violations"; exit 1; }

for format in xlsx csv json; do
  curl -fsS -b "$JAR" -o "$OUT/plan.$format" "$BASE/api/plans/$MONTH/export?format=$format"
  size=$(wc -c < "$OUT/plan.$format")
  echo "export $format: $size bytes"
  [ "$size" -gt 1000 ] || { echo "FAILED: export $format too small"; exit 1; }
done
echo "smoke test: OK"
