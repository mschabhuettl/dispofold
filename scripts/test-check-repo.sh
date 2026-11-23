#!/usr/bin/env bash
# Self-test of the company agreement check in scripts/check-repo.sh: the current tree passes, a planted hint in
# user-facing text fails, the generic sentences stay allowed. Runs in a temporary worktree of HEAD, so that the
# shared checkout is not touched. The planted words are assembled at run time, so that this file does not contain
# them.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
WORK=$(mktemp -d)
git -C "$ROOT" worktree add -q --detach "$WORK/tree" HEAD
trap 'git -C "$ROOT" worktree remove --force "$WORK/tree"; rm -rf "${WORK:?}"' EXIT
cd "$WORK/tree"

noun="Betriebs""vereinbarung"
message="Company agreement named as a source"
fail=0

if ! "$ROOT/scripts/check-repo.sh" > "$WORK/out" 2>&1; then
  echo "The current tree fails:"; cat "$WORK/out"; fail=1
fi

# file|text, one hint per case; \n is a line break, as in the wrapped guide.
planted=(
  "docs/anleitung/fragen.md|Das gilt laut $noun."
  "docs/anleitung/fragen.md|Die Werte stammen aus einer $noun für die Leitstelle."
  "docs/regeln.md|Standard nach der ""B""V."
  "docs/anleitung/einstellungen.md|Das gilt laut\n$noun der Leitstelle."
  "frontend/src/lib/texts.js|// Werte aus unserer $noun"
  "plugins/beispiel/docs/beispiel.md|Siehe $noun vom 1. März."
  "docs/anleitung/einstellungen.md|Standard 11 h ($noun)."
  "docs/anleitung/fragen.md|Das gilt laut Vereinbarung mit dem Betriebsrat."
)
for case in "${planted[@]}"; do
  file=${case%%|*}
  printf '\n%b\n' "${case#*|}" >> "$file"
  if "$ROOT/scripts/check-repo.sh" > "$WORK/out" 2>&1 || ! grep -q "$message" "$WORK/out"; then
    echo "Not caught in $file: ${case#*|}"; fail=1
  fi
  git checkout -q -- "$file"
done

# The generic sentences stay allowed.
printf '\n%s\n' "Gesetz und $noun" "Einzelheiten einer $noun." "Das entscheidet die $noun." \
  "Offene Punkte für die $noun" "" "1. Der erste Punkt." "" "## Gesetz und $noun" "" "Der Text." \
  "Grenzen aus Kollektivvertrag und $noun – mehr dazu …" >> docs/anleitung/fragen.md
if ! "$ROOT/scripts/check-repo.sh" > "$WORK/out" 2>&1; then
  echo "Generic sentences fail:"; cat "$WORK/out"; fail=1
fi
git checkout -q -- docs/anleitung/fragen.md

if [ "$fail" -ne 0 ]; then
  echo "test-check-repo: FAILED"; exit 1
fi
echo "test-check-repo: OK"
