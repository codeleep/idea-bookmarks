#!/usr/bin/env python3
"""Require an explicit matching version tag on an already merged main commit."""
import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def require_version_tag(ref, version):
    expected = f"refs/tags/v{version}"
    if ref != expected:
        raise ValueError(f"Release requires the matching version tag: {expected}")
    return f"v{version}"


def validate_checkout(ref, version, target, directory=ROOT):
    require_version_tag(ref, version)

    def git(*args):
        return subprocess.check_output(["git", "-C", str(directory), *args], text=True, stderr=subprocess.PIPE).strip()

    if git("rev-parse", "HEAD") != target or git("rev-parse", f"{ref}^{{commit}}") != target:
        raise ValueError("Version tag must resolve to the exact checked-out build commit")
    if subprocess.run(["git", "-C", str(directory), "merge-base", "--is-ancestor", target,
                       "refs/remotes/origin/main"], capture_output=True).returncode:
        raise ValueError("Version tag must point to a commit already merged into origin/main")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default=os.environ.get("GITHUB_REF", ""))
    parser.add_argument("--target", default=os.environ.get("GITHUB_SHA", ""))
    args = parser.parse_args()
    version = ET.parse(ROOT / "plugin/plugin.xml").getroot().findtext("version")
    validate_checkout(args.ref, version, args.target)
    print(f"RELEASE_TAG_VERIFIED: v{version} at {args.target}")


if __name__ == "__main__":
    main()
