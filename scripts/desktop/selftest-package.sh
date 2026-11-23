#!/usr/bin/env bash
# Runs the self-test of an installed Dispofold program (Linux or macOS): the real chain of window, launcher, bundled
# Java runtime and backend, driven through the page (open, demo data, plan, export, quit), and takes screenshots of
# the open window. Writes into <out-dir>: selftest.json (the program's result), selftest.log, window-*.png.
# Usage: scripts/desktop/selftest-package.sh <program binary> <out-dir>
#   Linux: /usr/bin/dispofold of the installed deb or rpm, run under Xvfb and a D-Bus session (xvfb-run,
#          dbus-run-session; screenshots with ImageMagick's import when it is there)
#   macOS: Dispofold.app/Contents/MacOS/dispofold (screenshots with screencapture)
# Needs: jq. Ends with 0 only when the program ends with 0 and its result is ok.
set -euo pipefail
if [ "$(uname -s)" = Linux ] && [ -z "${DISPLAY:-}" ]; then
  exec xvfb-run -a -s "-screen 0 1440x900x24" dbus-run-session -- "$0" "$@"
fi
APP=${1:?program binary}
OUT=${2:?output directory}
[ -x "$APP" ] || { echo "selftest: FAILED, $APP missing"; exit 1; }
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "${WORK:?}"' EXIT
mkdir -p "$WORK/home" "$WORK/data"

shot() { # shot <name>
  case "$(uname -s)" in
    Darwin) screencapture -x "$OUT/window-$1.png" || true ;;
    *) if command -v import >/dev/null; then import -window root "$OUT/window-$1.png" || true; fi ;;
  esac
}

HOME="$WORK/home" "$APP" --selftest "$OUT/selftest.json" --data "$WORK/data" > "$OUT/selftest.log" 2>&1 &
PID=$!
# screenshots while the window is open: after the start, during the planning
for at in 15 40 80; do
  for _ in $(seq 1 "$at"); do kill -0 "$PID" 2>/dev/null || break 2; sleep 1; done
  shot "${at}s"
done
code=0
wait "$PID" || code=$?
cat "$OUT/selftest.log"
echo "selftest: exit code $code"
jq -c '{ok, steps: [.steps[] | {name, ms, ok}]}' "$OUT/selftest.json" 2>/dev/null || echo "selftest: no result file"
[ "$code" = 0 ] && jq -e '.ok == true' "$OUT/selftest.json" >/dev/null && echo "selftest: OK" && exit 0
echo "selftest: FAILED"
exit 1
