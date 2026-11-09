#!/usr/bin/env python3
"""Probe another official SDK with a metadata-only candidate in an isolated copy.

This does not change the supported builds or publish/install the candidate in a
normal IDE profile. A passing probe is not a declaration of production support.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from build import ROOT, PLUGIN_VERSION, product_info, sdk_contents


def probe(sdk, original):
    info = product_info(sdk)
    build = info["buildNumber"]
    if info.get("productCode") not in ("IU", "IIU") or not all(part.isdigit() for part in build.split(".")):
        raise SystemExit("Expected an official IntelliJ IDEA SDK with a numeric build")
    native = sdk / "plugins/platform-bookmarks-plugin/lib/platform-bookmarks-plugin.jar"
    with zipfile.ZipFile(native) as archive:
        host = ET.fromstring(archive.read("META-INF/plugin.xml"))
    report = ROOT / "build" / f"compatibility-validation-{build}.json"
    report.unlink(missing_ok=True)
    scratch = ROOT / "build" / f"compatibility-probe-{build}"
    scratch.mkdir(parents=True, exist_ok=True)
    for folder in ("tools", "tests/runtime"):
        shutil.copytree(ROOT / folder, scratch / folder, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("__pycache__"))
    (scratch / "plugin").mkdir(exist_ok=True)
    (scratch / "dist").mkdir(exist_ok=True)
    version = build + ".1-compat"
    candidate = scratch / "dist" / f"bookmarks-desktop-{version}.zip"
    descriptor_path = "platform-bookmarks-plugin/lib/platform-bookmarks-plugin.jar"
    implementations = {}
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(candidate, "w", zipfile.ZIP_DEFLATED) as output:
        for item in source.infolist():
            payload = source.read(item.filename)
            if item.filename == descriptor_path:
                buffer = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(payload)) as jar, zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as target:
                    for entry in jar.infolist():
                        content = jar.read(entry.filename)
                        if entry.filename == "META-INF/plugin.xml":
                            metadata = ET.fromstring(content)
                            old_id = metadata.findtext("id")
                            metadata.find("id").text = host.findtext("id")
                            metadata.find("version").text = version
                            metadata.find("idea-version").set("since-build", build)
                            metadata.find("idea-version").set("until-build", build)
                            if old_id != host.findtext("id") and metadata.find(f"module[@value='{old_id}']") is None:
                                ET.SubElement(metadata, "module", value=old_id)
                            content = ET.tostring(metadata, encoding="utf-8", xml_declaration=True)
                            (scratch / "plugin/plugin.xml").write_bytes(content)
                        target.writestr(entry, content)
                payload = buffer.getvalue()
            elif "/lib/modules/" in item.filename and item.filename.endswith(".jar"):
                implementations[item.filename] = hashlib.sha256(payload).hexdigest()
            output.writestr(item, payload)
    with zipfile.ZipFile(candidate) as archive:
        assert implementations and all(hashlib.sha256(archive.read(name)).hexdigest() == digest
                                       for name, digest in implementations.items())
    delta = {"sdkVersion": info["version"], "sdkBuild": build,
             "baseArchiveSha256": hashlib.sha256(original.read_bytes()).hexdigest(),
             "candidateArchiveSha256": hashlib.sha256(candidate.read_bytes()).hexdigest(),
             "candidatePluginId": host.findtext("id"), "candidateVersion": version,
             "implementationJarsUnchanged": implementations,
             "supportedBuildsChanged": False, "normalIdeProfileChanged": False}
    (scratch / "candidate-delta.json").write_text(json.dumps(delta, indent=2) + "\n")
    subprocess.run([sys.executable, str(scratch / "tools/runtime_smoke.py"), "--idea-home", str(sdk)],
                   cwd=scratch, check=True)
    runtime = json.loads((scratch / "build/runtime-validation.json").read_text())
    assert runtime["archiveSha256"] == delta["candidateArchiveSha256"]
    delta.update(result="PASS", runtimeChecks=len(runtime["checks"]),
                 runtimeReport=str(scratch.relative_to(ROOT) / "build/runtime-validation.json"),
                 limitation="Metadata-only isolated runtime probe; licensed Java diagram GUI acceptance is not covered")
    report = ROOT / "build" / f"compatibility-validation-{build}.json"
    report.write_text(json.dumps(delta, indent=2) + "\n")
    print(f"COMPATIBILITY_PROBE_PASS: {build}; {len(runtime['checks'])} checks; {report}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--plugin-archive", type=Path,
                        default=ROOT / f"dist/bookmarks-desktop-{PLUGIN_VERSION}.zip")
    args = parser.parse_args()
    probe(sdk_contents(args.idea_home), args.plugin_archive.expanduser().resolve())
