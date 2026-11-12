#!/usr/bin/env bash
# Release of the current tag through the hosting's REST API, in three steps:
#   create          creates the release as a draft (or finds the existing one); run once, before any upload
#   upload <dir>    uploads the files of a directory to the draft
#   publish         turns the draft into a published release; run after every upload job succeeded
# The upload address is the release's upload_url. UPLOAD_ORIGIN (optional, any URL) replaces its scheme and host, for
# a hosting whose public name must not be used for large uploads.
# Re-running a job replaces assets of the same name instead of failing.
# If an upload is rejected as too large (HTTP 413), the file is uploaded in parts of 90 MB and the parts are
# added to the SHA-256 file. Join them with: cat <file>.part-* > <file>
# Usage: RELEASE_TOKEN=... scripts/release/publish-release.sh <api-url> <owner/repo> <tag> create|upload <dir>|publish
set -euo pipefail
API=${1:?api url}
REPO=${2:?owner/repo}
TAG=${3:?tag}
CMD=${4:?create, upload or publish}
# The token goes to curl in a header file, so that it never appears in a process listing.
HDR=$(mktemp)
trap 'rm -f "${HDR:?}"' EXIT
(umask 077 && printf 'Authorization: token %s\n' "${RELEASE_TOKEN:?RELEASE_TOKEN missing}" > "$HDR")

api() {
  curl -fsS -H @"$HDR" "$@"
}

# The release with this tag, drafts included (both hostings list drafts to users with write access).
find_release() {
  api "$API/repos/$REPO/releases?per_page=50&limit=50" | python3 -c '
import json, sys
for r in json.load(sys.stdin):
    if r.get("tag_name") == sys.argv[1]:
        print(json.dumps(r))
        break
' "$TAG"
}

field() {
  python3 -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

case "$CMD" in
  create)
    if [ -z "$(find_release)" ]; then
      body=$(printf '{"tag_name":"%s","name":"%s","draft":true,"body":"Installation und Update: docs/betrieb.md"}' \
        "$TAG" "$TAG")
      api -o /dev/null -X POST -H 'Content-Type: application/json' -d "$body" "$API/repos/$REPO/releases"
    fi
    echo "release $TAG: draft ready"
    exit 0
    ;;
  publish)
    release=$(find_release)
    [ -n "$release" ] || { echo "release $TAG not found"; exit 1; }
    id=$(echo "$release" | field id)
    api -o /dev/null -X PATCH -H 'Content-Type: application/json' -d '{"draft":false}' \
      "$API/repos/$REPO/releases/$id"
    echo "release $TAG: published"
    exit 0
    ;;
  upload) DIR=${5:?assets directory} ;;
  *) echo "unknown command $CMD"; exit 2 ;;
esac

release=$(find_release)
[ -n "$release" ] || { echo "release $TAG not found; run create first"; exit 1; }
release_id=$(echo "$release" | field id)
upload=$(echo "$release" | field upload_url)
upload=${upload%%\{*}
if [ -n "${UPLOAD_ORIGIN:-}" ]; then
  origin=$(echo "$UPLOAD_ORIGIN" | sed -E 's#^(https?://[^/]+).*#\1#')
  upload="$origin/${upload#*://*/}"
fi

# Deletes an existing asset with this name. Assets that carry their own API url are deleted there,
# the others through releases/<id>/assets/<asset id>.
remove_existing() {
  local name=$1 target
  target=$(api "$API/repos/$REPO/releases/$release_id" | python3 -c '
import json, sys
api, rel, name = sys.argv[1:4]
for a in json.load(sys.stdin).get("assets") or []:
    if a["name"] == name:
        print(a.get("url") or api + "/releases/" + rel + "/assets/" + str(a["id"]))
' "$API/repos/$REPO" "$release_id" "$name") || return 1
  if [ -n "$target" ]; then
    echo "replacing $name" >&2
    api -o /dev/null -X DELETE "$target" || return 1
  fi
}

# Prints the HTTP status of the upload (000 when the old asset could not be removed). Raw body first; a hosting
# that refuses it as a client error (4xx other than 413) gets the file as the form field "attachment".
send() {
  local file=$1 status
  remove_existing "$(basename "$file")" || { echo 000; return; }
  status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H @"$HDR" -H 'Content-Type: application/octet-stream' \
    --data-binary "@$file" "$upload?name=$(basename "$file")")
  if [[ "$status" == 4* && "$status" != 413 ]]; then
    status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H @"$HDR" -F "attachment=@$file" \
      "$upload?name=$(basename "$file")")
  fi
  echo "$status"
}

sums=$(ls "$DIR"/*SHA256SUMS.txt)
for file in "$DIR"/*; do
  [ "$file" = "$sums" ] && continue
  status=$(send "$file")
  if [ "$status" = 413 ]; then
    echo "$(basename "$file") is larger than the upload limit, uploading parts"
    split -b 90m -d "$file" "$file.part-"
    (cd "$DIR" && sha256sum "$(basename "$file")".part-* >> "$(basename "$sums")")
    for part in "$file".part-*; do
      status=$(send "$part")
      [ "$status" = 201 ] || { echo "upload of $(basename "$part") failed: HTTP $status"; exit 1; }
    done
  elif [ "$status" != 201 ]; then
    echo "upload of $(basename "$file") failed: HTTP $status"; exit 1
  fi
done
status=$(send "$sums")
[ "$status" = 201 ] || { echo "upload of $(basename "$sums") failed: HTTP $status"; exit 1; }
echo "release $TAG: assets uploaded"
