#!/usr/bin/env bash
# Prints the Rust crates of the program window as a Markdown table (Crate | Version | Lizenz) for THIRD_PARTY.md:
# normal and build dependencies for Linux, Windows and macOS, from desktop-shell/Cargo.lock.
# Needs: cargo, python3.
set -euo pipefail
cd "$(dirname "$0")/../../desktop-shell"
for target in x86_64-unknown-linux-gnu x86_64-pc-windows-msvc aarch64-apple-darwin x86_64-apple-darwin; do
  cargo metadata --format-version 1 --locked --filter-platform "$target"
  echo
done | python3 -I -c '
import json, sys
rows = {}
for line in sys.stdin:
    if not line.strip():
        continue
    meta = json.loads(line)
    packages = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = meta["resolve"]["root"]
    seen, stack = set(), [root]
    while stack:
        node = stack.pop()
        if node in seen:
            continue
        seen.add(node)
        for dep in nodes[node]["deps"]:
            if any(k["kind"] in (None, "build") for k in dep["dep_kinds"]):
                stack.append(dep["pkg"])
    for node in seen - {root}:
        p = packages[node]
        rows[(p["name"], p["version"])] = p["license"] or "siehe Crate"
print("| Crate | Version | Lizenz |")
print("|---|---|---|")
for (name, version), licence in sorted(rows.items()):
    print(f"| {name} | {version} | {licence} |")
'
