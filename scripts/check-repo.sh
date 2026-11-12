#!/usr/bin/env bash
# Repository hygiene checks, run in CI and before every push.
# 1. No data files (SP5 tables, backups, spreadsheets, CSV) outside tests/fixtures/synthetic/.
# 2. Synthetic fixtures say so in their file name.
# 3. No traces of code generators or steering files in history, file list or content.
# 4. No names of other hosting platforms in commit messages or files.
# 5. No mention of the old planning system in commit messages or files.
# 6. No organisation, region, previous project or font names.
# 7. No private domain, LAN address or account name of the owner's own hosting.
# The search patterns use character classes so that this file does not match itself.
set -euo pipefail
export LC_ALL=C.UTF-8
cd "$(git rev-parse --show-toplevel)"
# File names with umlauts come out unquoted, so that the extension checks below see them (for this process only).
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.quotePath GIT_CONFIG_VALUE_0=false
fail=0

data_files=$(git ls-files | grep -i -E '\.(dbf|cdx|fpt|bak|zip|7z|xlsx?|xlsm|csv|ods)$' \
  | grep -v '^tests/fixtures/synthetic/' || true)
if [ -n "$data_files" ]; then
  echo "Data files outside tests/fixtures/synthetic/:"; echo "$data_files"; fail=1
fi

# The file name itself must say so (the folder name does not count).
unmarked=$(git ls-files tests/fixtures/synthetic/ | grep -v -i -E '/[^/]*synthetic[^/]*$' || true)
if [ -n "$unmarked" ]; then
  echo "Fixtures without 'synthetic' in the file name:"; echo "$unmarked"; fail=1
fi

words='[c]laude|[a]nthropic|[c]o-authored|[g]enerated with|\b[a]i\b|[l]lm|[c]odex'
if git log --format='%an %ae %cn %ce%n%B' | grep -i -E "$words"; then
  echo "Forbidden words in commit metadata"; fail=1
fi
if git ls-files | grep -i -E '[b]rief|[c]laude|[a]gents\.md|\.[c]laude'; then
  echo "Steering files are tracked"; fail=1
fi
# The first list is searched in binary files too (e.g. metadata in images). The others only in text files, where
# a match cannot be chance bytes; the two-letter word only in capitals, since the solver's Java package starts
# with its lower-case form.
if git grep -i -n -E '[c]laude|[a]nthropic|[c]hatgpt|[o]penai|[l]lm' \
  || git grep -I -i -n -E '[c]odex|[c]o-authored|[g]enerated with' || git grep -I -n -E '\b[A]I\b'; then
  echo "Forbidden words in tracked files"; fail=1
fi

# The tool knows only its own data format; the old planning system is not part of this repository.
# (This file names data file types above on purpose and is therefore not searched.)
legacy='\b[s]p5|[s]chichtplaner|\b[d]bf\b'
if git log --format='%B' | grep -n -i -E "$legacy"; then
  echo "Old planning system named in commit messages"; fail=1
fi
if git grep -n -i -E "$legacy" -- . ':!scripts/check-repo.sh'; then
  echo "Old planning system named in tracked files"; fail=1
fi

# Neutral product: no organisation, region or previous project name, no previous fonts.
names='[s]hiftfold|[a]t\.[r]ls|[S]teiermark|[R]ote[sn]? [K]reuz|[R]ed [C]ross|[Ö]RK|[r]oteskreuz|[r]ls-|[S]ource Sans|[s]ource-sans|[D]unant'
abbrev='\b[R]LS\b'
if git log --format='%B' | grep -n -i -E "$names" || git log --format='%B' | grep -n -E "$abbrev"; then
  echo "Old names in commit messages"; fail=1
fi
if git grep -n -i -E "$names" || git grep -n -E "$abbrev"; then
  echo "Old names in tracked files"; fail=1
fi

# The demo world is invented: no area ids, shift codes or area names of a real dispatch centre.
# Whole words and case sensitive, so that ordinary words do not match; binary files are left to check-images.sh.
codes='\b([N]CT|[K]CTS?|[A]END|[N]EM|[O]vD|[O]VD|[D]P|[N]-CT|[K]-CT)\b|(^|[^[:alnum:]])[Ä]ND\b'
areas='[N]otruf Calltaking|[K]rankentransport Calltaking|[O]ffizier vom Dienst|[Ä]rztenotdienst|[N]otarzt-Einsatzmittel'
if git log --format='%B' | grep -n -E "$codes|$areas"; then
  echo "Real codes or area names in commit messages"; fail=1
fi
if git grep -I -n -E "$codes|$areas"; then
  echo "Real codes or area names in tracked files"; fail=1
fi

# The owner's own hosting stays out of the repository. The public account name (as in ghcr.io/<account>) is
# allowed; any other form of the name, such as the private domain, and LAN addresses are not.
private='(^|[^m])[s]chabhuettl|\b192\.168\.[0-9]'
if git log --format='%B' | grep -n -i -E "$private"; then
  echo "Private hosts or account in commit messages"; fail=1
fi
if git grep -I -n -i -E "$private"; then
  echo "Private hosts or account in tracked files"; fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "check-repo: FAILED"; exit 1
fi
echo "check-repo: OK"
