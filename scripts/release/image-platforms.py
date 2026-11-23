#!/usr/bin/env python3
"""Checks that an image archive (docker save, gzip) holds the three images of one platform and nothing else.

    scripts/release/image-platforms.py <archive.tar.gz> amd64|arm64

Reads the archive's index (OCI) or manifest.json (Docker) and each image's config; prints tag and architecture.
"""
import gzip
import json
import sys
import tarfile

archive, wanted = sys.argv[1], sys.argv[2]
with gzip.open(archive) as raw, tarfile.open(fileobj=raw, mode="r|") as tar:
    files = {m.name: tar.extractfile(m).read() for m in tar if m.isfile() and (
        m.name.endswith(".json") or m.name.startswith("blobs/") and m.size < 1_000_000)}
manifest = json.loads(files["manifest.json"])
found = []
for entry in manifest:
    config = json.loads(files[entry["Config"]])
    found.append((entry.get("RepoTags") or ["?"], config.get("architecture")))
    print(", ".join(entry.get("RepoTags") or ["?"]), config.get("architecture"))
services = {tag.split("/")[-1].split(":")[0] for tags, _ in found for tag in tags}
bad = [tags for tags, arch in found if arch != wanted]
missing = {"dispofold-backend", "dispofold-frontend", "dispofold-postgres"} - services
if bad or missing:
    sys.exit(f"{archive}: not {wanted}: {bad}; missing: {sorted(missing)}")
print(f"{archive}: {len(found)} images, all {wanted}")
