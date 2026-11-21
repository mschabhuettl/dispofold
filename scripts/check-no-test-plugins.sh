#!/usr/bin/env bash
# Fails if a build contains a plugin marked testOnly in plugins/<id>/plugin.json (docs/plugins.md): its classes, its
# manifest, or its texts and help pages in the front end. Usage: scripts/check-no-test-plugins.sh <folder>
# (a frontend dist/, or an unpacked desktop package or quarkus-app with jars).
set -euo pipefail
dir=${1:?folder}
repo=$(cd "$(dirname "$0")/.." && pwd)
status=0
for manifest in "$repo"/plugins/*/plugin.json; do
  id=$(basename "$(dirname "$manifest")")
  grep -q '"testOnly": *true' "$manifest" || continue
  pattern="plugin[.-]$id[.-]|app/dispofold/plugins/$id/|dispofold-plugins/$id/"
  if grep -rqE "$pattern" "$dir"; then
    echo "test-only plugin $id found in $dir" >&2
    status=1
  fi
  while IFS= read -r -d '' jar; do
    if unzip -l "$jar" | grep -qE "app/dispofold/plugins/$id/|dispofold-plugins/$id/"; then
      echo "test-only plugin $id found in $jar" >&2
      status=1
    fi
    if unzip -Z1 "$jar" | grep -q '^META-INF/resources/.*\.\(js\|html\)$' \
        && unzip -p "$jar" 'META-INF/resources/*' 2>/dev/null | grep -qaE "plugin[.-]$id[.-]"; then
      echo "test-only plugin $id found in the front end of $jar" >&2
      status=1
    fi
  done < <(find "$dir" -name '*.jar' -print0)
done
[ $status -eq 0 ] && echo "no test-only plugin in $dir"
exit $status
