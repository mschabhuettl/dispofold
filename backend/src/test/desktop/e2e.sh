#!/usr/bin/env bash
# End-to-end check of the desktop mode (headless, Linux or macOS): launcher in --shell mode with the line protocol,
# session header, encrypted database, signed update, refused tampered bundle, rollback of a version that does not
# start, password change.
#   1. The real program window: builds the Linux deb with scripts/desktop/package.sh in a private copy, unpacks it
#      and runs its self-test headless (Xvfb, D-Bus session): open, demo data, solve, export, quit through the page.
#   2. What the window self-test does not cover, driven through the launcher's line protocol by ShellDriver.java
#      (next to this script): signed updates with a throwaway key, tampered bundle, rollback, wrong password,
#      password change, refused Host, Origin and missing session header.
#
# Usage: backend/src/test/desktop/e2e.sh
#   KEEP=1                 keep the work folder for inspection
#   DISPOFOLD_E2E_DEB=<deb> use this built deb for part 1 instead of building one (faster when iterating)
#   TMPDIR                 where the work folder goes; building the deb needs several GB (Rust release build), so
#                          point it at a disk, not a small tmpfs (e.g. TMPDIR=/var/tmp)
# Needs: JDK 21, curl, jq, zip, unzip, rsync, a built frontend (frontend/dist); a missing one fails the run.
# Builds the desktop app three times (0.9.0, 0.9.1 and a 0.9.2 with a broken config value) in a private copy of
# the repository. The release key is never used: every run makes a throwaway Ed25519 key pair and builds the
# test program with its public key (Maven property dispofold.update.public-key).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
WORK=$(mktemp -d)
LAUNCHER_PID=
cleanup() {
  if [ -n "$LAUNCHER_PID" ] && kill -0 "$LAUNCHER_PID" 2>/dev/null; then kill "$LAUNCHER_PID"; wait "$LAUNCHER_PID" || true; fi
  if [ "${KEEP:-}" = 1 ]; then echo "work folder: $WORK"; else rm -rf "${WORK:?}"; fi
}
trap cleanup EXIT
for cmd in java curl jq zip unzip rsync pgrep xvfb-run dbus-run-session dpkg-deb; do
  command -v "$cmd" >/dev/null || { echo "e2e: FAILED, $cmd is missing"; exit 1; }
done
if [ -z "${DISPOFOLD_E2E_DEB:-}" ]; then
  for cmd in cargo npm; do
    command -v "$cmd" >/dev/null || { echo "e2e: FAILED, $cmd is missing (needed to build the deb)"; exit 1; }
  done
fi
[ -f "$ROOT/frontend/dist/index.html" ] || { echo "e2e: FAILED, frontend/dist missing: run npm run build in frontend/"; exit 1; }

echo "== throwaway signing key"
KEY="$WORK/test-signing.key"
cat > "$WORK/KeyGen.java" <<'JAVA'
import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/** Writes a new Ed25519 private key (PKCS#8, Base64) to args[0] and prints the public key (X.509, Base64). */
public class KeyGen {
    public static void main(String[] args) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path file = Path.of(args[0]);
        Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }
}
JAVA
PUBLIC_KEY=$(java "$WORK/KeyGen.java" "$KEY")
[ -n "$PUBLIC_KEY" ] || { echo "e2e: FAILED, no test key"; exit 1; }

failures=0
no_process() { # no_process <pattern>: true only if pgrep ran and found nothing (exit 1); errors fail
  local code=0
  pgrep -f "$1" >/dev/null || code=$?
  [ "$code" -eq 1 ]
}
check() { # check <description> <command...>
  local what=$1; shift
  if "$@"; then echo "PASS  $what"; else echo "FAIL  $what"; failures=$((failures + 1)); fi
}

rsync -a --exclude target --exclude node_modules --exclude .git --exclude /desktop-shell/stage "$ROOT/" "$WORK/src/"

echo "== part 1: the program window's self-test"
DEB=${DISPOFOLD_E2E_DEB:-}
if [ -z "$DEB" ]; then
  if ! "$WORK/src/scripts/desktop/package.sh" 0.9.5 linux "$WORK/deb" >"$WORK/package.log" 2>&1; then
    tail -40 "$WORK/package.log"; echo "e2e: FAILED, scripts/desktop/package.sh"; exit 1
  fi
  DEB=$(ls "$WORK"/deb/*.deb | head -n1)
fi
[ -f "$DEB" ] || { echo "e2e: FAILED, no deb"; exit 1; }
dpkg-deb -x "$DEB" "$WORK/installed"
mkdir -p "$WORK/selftest-data" "$WORK/home"
# Own HOME: the export lands in its download folder, not in the user's.
HOME="$WORK/home" xvfb-run -a dbus-run-session -- "$WORK/installed/usr/bin/dispofold" --selftest "$WORK/selftest.json" \
  --data "$WORK/selftest-data" >"$WORK/selftest.log" 2>&1 && code=0 || code=$?
check "window self-test ends with 0" test "$code" = 0
check "window self-test result ok" sh -c "jq -e '.ok == true' '$WORK/selftest.json' >/dev/null"
for step in "password requested" "backend verified" "open app" "demo data" "solve" "export" "quit" "launcher exit"; do
  check "self-test step $step passed" sh -c "jq -e --arg s '$step' 'any(.steps[]; .name == \$s and .ok)' '$WORK/selftest.json' >/dev/null"
done
echo "      self-test: $(jq -c '{steps: [.steps[] | {name, ms, ok}], plan}' "$WORK/selftest.json" 2>/dev/null)"
check "no app process left after the self-test" no_process "[d]ispofold.data-dir=$WORK/selftest-data"

echo "== part 2: launcher scenarios; build 0.9.0, 0.9.1 and broken 0.9.2 in $WORK/src"
build() { # build <version> <out>: fails the run when Maven fails
  if ! (cd "$WORK/src/backend" && ./mvnw -B -q package -DskipTests -Dquarkus.profile=desktop \
    -Dquarkus.application.version="$1" -Ddispofold.update.public-key="$PUBLIC_KEY" >"$WORK/build-$1.log" 2>&1); then
    cat "$WORK/build-$1.log"; echo "e2e: FAILED, build of $1"; exit 1
  fi
  cp -r "$WORK/src/backend/target/quarkus-app" "$2"
}
build 0.9.0 "$WORK/app-0.9.0"
cp "$WORK/src/backend/target/dispofold-launcher.jar" "$WORK/launcher.jar"
build 0.9.1 "$WORK/app-0.9.1"
props="$WORK/src/backend/src/main/resources/application.properties"
echo '%desktop.quarkus.http.idle-timeout=kaputt' >> "$props"
build 0.9.2 "$WORK/app-0.9.2"

tool() { java -cp "$WORK/launcher.jar" app.dispofold.desktop.update.SignTool "$@"; }
mkdir -p "$WORK/program" "$WORK/quelle" "$WORK/later"
cp -r "$WORK/app-0.9.0" "$WORK/program/app"
tool manifest "$WORK/program/app" 0.9.0 "$WORK/program/bundle.json"
tool bundle "$KEY" "$WORK/app-0.9.1" 0.9.1 "$WORK/quelle" >/dev/null
tool bundle "$KEY" "$WORK/app-0.9.2" 0.9.2 "$WORK/later" >/dev/null
verify() { LAUNCHER_JAR="$WORK/launcher.jar" "$ROOT/scripts/desktop/verify-bundle.sh" "$1" >/dev/null 2>&1; }
check "bundles verify with the compiled in key" verify "$WORK/quelle/dispofold-0.9.1-update.zip"
check "test program carries the test key, not the release key" \
  test "$(unzip -p "$WORK/launcher.jar" app/dispofold/desktop/update/update-public-key.txt)" = "$PUBLIC_KEY"
java "$WORK/KeyGen.java" "$WORK/other.key" >/dev/null
tool bundle "$WORK/other.key" "$WORK/app-0.9.1" 0.9.1 "$WORK/fremd" >/dev/null
check "bundle signed with another key fails verify-bundle.sh" sh -c "! LAUNCHER_JAR='$WORK/launcher.jar' '$ROOT/scripts/desktop/verify-bundle.sh' '$WORK/fremd/dispofold-0.9.1-update.zip' >/dev/null 2>&1"
# Tampered: one file of the signed 0.9.2 bundle changed
cp "$WORK/later/dispofold-0.9.2-update.zip" "$WORK/tampered.zip"
mkdir "$WORK/t" && (cd "$WORK/t" && unzip -q ../tampered.zip app/quarkus-run.jar && echo x >> app/quarkus-run.jar \
  && zip -q ../tampered.zip app/quarkus-run.jar)

BASE="$WORK/base"
DRIVER="$ROOT/backend/src/test/desktop/ShellDriver.java"
SHELL_STATE="$WORK/shell.json"
PASSWORD="e2e-Passwort-123"
# The program window is played by ShellDriver.java: line protocol, proof and challenge checks, state for curl.
start_launcher() { # start_launcher [password]
  rm -f "$SHELL_STATE" "$SHELL_STATE.quit"
  java "$DRIVER" "$SHELL_STATE" "${1:-$PASSWORD}" java -jar "$WORK/launcher.jar" --shell --program-dir "$WORK/program" \
    --data "$BASE" --app "$WORK/program" --ready-timeout 90 >>"$WORK/launcher.log" 2>&1 &
  LAUNCHER_PID=$!
  READY=0
}
shell() { jq -r "$1" "$SHELL_STATE" 2>/dev/null; }
INSTANCE="$BASE/daten/instanz.json"
SESSION=
PORT=
READY=0
wait_ready() { # wait_ready <version>: waits for the next checked ready line of that version
  for _ in $(seq 240); do
    if [ -f "$SHELL_STATE" ] && [ "$(shell .readyCount)" -gt "$READY" ] && [ "$(shell .version)" = "$1" ]; then
      READY=$(shell .readyCount); PORT=$(shell .port); SESSION=$(shell .session)
      return 0
    fi
    kill -0 "$LAUNCHER_PID" 2>/dev/null || return 1
    sleep 0.5
  done
  return 1
}
quit_window() { touch "$SHELL_STATE.quit"; wait "$LAUNCHER_PID" && code=0 || code=$?; }
api() { # api <method> <path> [curl args...]: prints the body, then the status on the last line
  local m=$1 p=$2; shift 2
  curl -s -X "$m" -H "X-Dispofold-Session: $SESSION" -H "Origin: dispofold://localhost" -w '\n%{http_code}' \
    "http://127.0.0.1:$PORT$p" "$@"
}
status() { tail -n1; }
body() { sed '$d'; }
people() { api GET /api/dataset | body | jq '.people | length'; }
lines_of() { shell "[.lines[] | select(.type == \"$1\")] | length"; }

echo "== first start with 0.9.0"
start_launcher
check "launcher starts 0.9.0, proof and challenge verified" wait_ready 0.9.0
check "identity checks passed" test "$(shell .identityOk)" = true
check "first start asked for a new password" test "$(shell '.lines[0].type + " " + (.lines[0].firstStart | tostring)')" = "needPassword true"
check "instanz.json has host, no secret or port" jq -e 'has("host") and (has("token") | not) and (has("port") | not)' "$INSTANCE"
check "database file is encrypted" sh -c "! grep -q -a 'CREATE' '$BASE/daten/db/dispofold.mv.db'"
check "without session header 401" test "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/me")" = 401
check "no cookie login any more" test "$(curl -s -o /dev/null -w '%{http_code}' -b "dispofold_k=$SESSION" "http://127.0.0.1:$PORT/api/me")" = 401
check "foreign Host 403" test "$(curl -s -o /dev/null -w '%{http_code}' -H "X-Dispofold-Session: $SESSION" -H "Host: localhost:$PORT" "http://127.0.0.1:$PORT/api/me")" = 403
check "foreign Origin 403" test "$(curl -s -o /dev/null -w '%{http_code}' -H "X-Dispofold-Session: $SESSION" -H "Origin: http://evil.example" "http://127.0.0.1:$PORT/api/me")" = 403
check "demo import" test "$(api POST /api/import/demo | status)" = 200
check "150 people stored" test "$(people)" = 150
check "/api/me says desktop" test "$(api GET /api/me | body | jq .desktop)" = true
java "$DRIVER" "$WORK/second.json" "$PASSWORD" java -jar "$WORK/launcher.jar" --shell --program-dir "$WORK/program" \
  --data "$BASE" --app "$WORK/program" >>"$WORK/launcher.log" 2>&1 && code=0 || code=$?
check "second launcher on the same data reports locked" test "$(jq -r '.lines[0].type + " " + .lines[0].error' "$WORK/second.json")" = "locked desktop.lock.held"
java -jar "$WORK/launcher.jar" --data "$BASE" --app "$WORK/program" >>"$WORK/launcher.log" 2>&1 && code=0 || code=$?
check "a start without the window is refused with 2" test "$code" = 2

echo "== update to 0.9.1 from a folder source"
FIRST_PORT=$PORT
api PUT /api/desktop/update/settings -H 'Content-Type: application/json' \
  -d "{\"source\":\"$WORK/quelle\",\"checkOnStart\":true}" >/dev/null
check "check finds 0.9.1" test "$(api GET '/api/desktop/update?check=true' | body | jq -r .available.version)" = 0.9.1
check "install answers 202" test "$(api POST /api/desktop/update/install | status)" = 202
check "restarted into 0.9.1 with a new checked handshake" wait_ready 0.9.1
check "new port after the restart, same session" test "$PORT" != "$FIRST_PORT"
check "running version is 0.9.1" test "$(api GET /api/desktop/update | body | jq -r .current)" = 0.9.1
check "data kept after update" test "$(people)" = 150
check "state.json current 0.9.1, previous 0.9.0" \
  test "$(jq -r '.current + " " + .previous' "$BASE/versionen/state.json")" = "0.9.1 0.9.0"
check "backup before 0.9.1 exists" sh -c "ls -d '$BASE'/daten/sicherungen/vor-0.9.1-* >/dev/null"

echo "== tampered bundle"
out=$(api POST /api/desktop/update/file -H 'Content-Type: application/octet-stream' --data-binary "@$WORK/tampered.zip")
echo "      answer: $(echo "$out" | body)"
check "upload of tampered bundle refused with 400" test "$(echo "$out" | status)" = 400
check "older bundle (0.9.1 again) refused" \
  test "$(api POST /api/desktop/update/file -H 'Content-Type: application/octet-stream' --data-binary "@$WORK/quelle/dispofold-0.9.1-update.zip" | status)" = 400
check "quit from the page answers 204" test "$(api POST /api/desktop/quit | status)" = 204
wait "$LAUNCHER_PID" && code=0 || code=$?
check "launcher ends with 0 after quit, exit line last" test "$code:$(shell '.lines[-1].type + " " + (.lines[-1].code | tostring)')" = "0:exit 0"
cp "$WORK/tampered.zip" "$BASE/versionen/eingang/dispofold-0.9.2-update.zip"
start_launcher
check "tampered bundle in eingang/ refused at start, 0.9.1 runs" wait_ready 0.9.1
check "launcher sent the refusal as a warning" test "$(shell '[.lines[] | select(.code == "desktop.update.refused")] | .[0].params.reason')" = desktop.bundle.changedFile
check "eingang/ is empty again" test -z "$(ls -A "$BASE/versionen/eingang")"
check "data kept after restart" test "$(people)" = 150

echo "== 0.9.2 that does not start: rollback"
check "upload of signed 0.9.2 answers 202" \
  test "$(api POST /api/desktop/update/file -H 'Content-Type: application/octet-stream' --data-binary "@$WORK/later/dispofold-0.9.2-update.zip" | status)" = 202
check "rolled back to 0.9.1" wait_ready 0.9.1
check "0.9.2 marked bad" test "$(jq -r '.bad | join(",")' "$BASE/versionen/state.json")" = 0.9.2
check "state.json current 0.9.1, no trial" test "$(jq -r '.current + " " + (.trial // "none")' "$BASE/versionen/state.json")" = "0.9.1 none"
check "data kept after rollback" test "$(people)" = 150
check "launcher sent the rollback as a warning" test "$(shell '[.lines[] | select(.code == "desktop.update.rolledBack")] | .[0].params.version')" = 0.9.2
check "starting lines for 0.9.2 and 0.9.1" test "$(shell '[.lines[] | select(.type == "starting") | .version] | join(",")')" = "0.9.1,0.9.2,0.9.1"
quit_window
check "quit from the window ends the launcher with 0" test "$code" = 0
check "no app process left" no_process "[d]ispofold.data-dir=$BASE/daten"

echo "== wrong password"
start_launcher falsch
wait "$LAUNCHER_PID" && code=0 || code=$?
check "wrong password is answered with desktop.password.wrong" test "$(shell .lastError)" = desktop.password.wrong
check "the window may quit then: exit 0" test "$code" = 0
check "wrong password marks nothing bad" test "$(jq -r '.bad | join(",")' "$BASE/versionen/state.json")" = 0.9.2

echo "== password change"
NEW_PASSWORD="e2e-Neues-Passwort-456"
DISPOFOLD_PASSWORD="$PASSWORD" DISPOFOLD_NEW_PASSWORD="$NEW_PASSWORD" java -jar "$WORK/launcher.jar" --data "$BASE" \
  --app "$WORK/program" --program-dir "$WORK/program" --change-password >"$WORK/change.log" 2>&1 && code=0 || code=$?
check "password change ends with 0" test "$code" = 0
check "salt file next to the database" test -s "$BASE/daten/db/dispofold.salt"
DISPOFOLD_PASSWORD="$NEW_PASSWORD" DISPOFOLD_NEW_PASSWORD=zu-kurz java -jar "$WORK/launcher.jar" --data "$BASE" \
  --app "$WORK/program" --program-dir "$WORK/program" --change-password >>"$WORK/change.log" 2>&1 && code=0 || code=$?
check "too short new password is refused with 2" test "$code" = 2
start_launcher "$PASSWORD"
wait "$LAUNCHER_PID" || true
check "old password is refused after the change" test "$(shell .lastError)" = desktop.password.wrong
start_launcher "$NEW_PASSWORD"
check "new password starts 0.9.1" wait_ready 0.9.1
check "data kept after the password change" test "$(people)" = 150
quit_window

echo "== launcher messages"
sed 's/^/      /' "$WORK/launcher.log"
if [ "$failures" -ne 0 ]; then echo "e2e: $failures check(s) FAILED"; exit 1; fi
echo "e2e: all checks passed"
