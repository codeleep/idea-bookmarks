#!/usr/bin/env python3
"""Package source files, build tools, docs and reviewed verification artifacts."""
from pathlib import Path
import zipfile
from build import ROOT, PLUGIN_VERSION


def package():
    destination = ROOT / f"dist/idea-bookmarks-{PLUGIN_VERSION}-sources.zip"
    destination.parent.mkdir(exist_ok=True)
    folders = ["modules", "official", "plugin", "tools", "tests", "docs", "gradle"]
    files = ["README.md", "NOTICE.txt", "LICENSE-APACHE-2.0.txt", "source-manifest.json",
             "build.gradle", "settings.gradle", "gradlew", "gradlew.bat", ".gitignore"]
    selected = [ROOT / name for name in files if (ROOT / name).is_file()]
    for folder in folders:
        selected.extend(path for path in (ROOT / folder).rglob("*")
                        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc")
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(selected):
            output.write(path, "idea-bookmarks/" + path.relative_to(ROOT).as_posix())
    print(destination)


if __name__ == "__main__":
    package()
