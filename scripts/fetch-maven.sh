#!/usr/bin/env bash
# Downloads the Maven of the wrapper (backend/mvnw) unless it is there already, with up to 5 attempts: the wrapper
# itself tries once, so a transient error of Maven Central would fail the job. Run before the first ./mvnw in CI
# (the workflows also cache ~/.m2/wrapper/dists). The waits grow to 80 s, about 3 minutes in all.
# A wrong address or checksum still fails, after the last attempt.
set -euo pipefail
cd "$(dirname "$0")/../backend"
for attempt in 1 2 3 4 5; do
  ./mvnw -B -q --version >/dev/null && exit 0
  echo "Maven download failed (attempt $attempt of 5)" >&2
  [ "$attempt" = 5 ] || sleep $((attempt * 20))
done
exit 1
