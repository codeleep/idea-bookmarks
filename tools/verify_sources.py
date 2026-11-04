#!/usr/bin/env python3
"""Check the extracted source against the pinned upstream manifest."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify():
    manifest = json.loads((ROOT / "source-manifest.json").read_text())
    errors = []
    for entry in manifest["files"]:
        path = ROOT / "official" / entry["path"]
        if not path.is_file():
            errors.append(f"missing: {entry['path']}")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            errors.append(f"modified: {entry['path']}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"PASS: {len(manifest['files'])} files match {manifest['commit']}")


if __name__ == "__main__":
    verify()
