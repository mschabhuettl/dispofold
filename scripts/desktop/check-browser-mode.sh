#!/usr/bin/env bash
# Checks the Linux browser front end of an installed or extracted Dispofold: starts the program in its default
# mode (browser), catches the launch file through $BROWSER and drives Playwright Chromium through that file, the
# shell's loopback proxy and the real backend: unlock, demo data, plan 2027-01, export, quit. Also checks that a
# used launch code, a request without the session cookie and a foreign host name are refused.
# Usage: scripts/desktop/check-browser-mode.sh <root>   (<root>/usr/bin/dispofold, e.g. dpkg-deb -x of the deb)
# Needs: xvfb-run (when no display is set), dbus-run-session, Node.js 22, frontend/node_modules with Playwright and
# its Chromium (cd frontend && npm ci && npx playwright install chromium).
set -euo pipefail
if [ -z "${DISPLAY:-}" ]; then exec xvfb-run -a "$0" "$@"; fi
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP=$(cd "${1:?root of the extracted package}" && pwd)/usr/bin/dispofold
[ -x "$APP" ] || { echo "check-browser-mode: FAILED, $APP missing"; exit 1; }
WORK=$(mktemp -d)
SHELL_PID=
cleanup() {
  # the program runs in its own process group: end it with its backend
  if [ -n "$SHELL_PID" ] && kill -0 "$SHELL_PID" 2>/dev/null; then kill -- "-$SHELL_PID" 2>/dev/null || true; sleep 2; fi
  if [ "${KEEP:-}" = 1 ]; then echo "work folder: $WORK"; else rm -rf "${WORK:?}"; fi
}
trap cleanup EXIT
mkdir -p "$WORK/state" "$WORK/run"
chmod 700 "$WORK/run"
# the program's state and launch file stay in the work folder; $BROWSER only records the launch file path
export XDG_STATE_HOME=$WORK/state XDG_RUNTIME_DIR=$WORK/run
printf '#!/bin/sh\nprintf "%%s" "$1" > "%s/launch-path.tmp" && mv "%s/launch-path.tmp" "%s/launch-path"\n' \
  "$WORK" "$WORK" "$WORK" > "$WORK/browser.sh"
chmod +x "$WORK/browser.sh"
export BROWSER=$WORK/browser.sh

setsid dbus-run-session -- "$APP" --data "$WORK/data" > "$WORK/shell.log" 2>&1 &
SHELL_PID=$!
status=0
node "$ROOT/scripts/desktop/check-browser-mode.mjs" "$WORK" || status=$?
# the program ends by itself after the quit in the page
for _ in $(seq 1 60); do kill -0 "$SHELL_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$SHELL_PID" 2>/dev/null; then
  echo "FAIL  program still running after quit"; status=1
else
  wait "$SHELL_PID" && code=0 || code=$?
  if [ "$code" = 0 ]; then echo "PASS  program ended with 0"; else echo "FAIL  program ended with $code"; status=1; fi
fi
if [ "$status" != 0 ]; then
  echo "--- program log"; grep -v -E 'dbus|portal|libEGL|^\s*$' "$WORK/shell.log" | tail -40
  echo "check-browser-mode: FAILED"; exit 1
fi
echo "check-browser-mode: OK"
