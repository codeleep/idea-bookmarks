#!/usr/bin/env python3
"""Run the unmodified official IDE with separate development user directories."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile
from build import ROOT, PLUGIN_VERSION, product_info, sdk_contents


def launch(sdk):
    info = product_info(sdk)
    sandbox = ROOT / "build/development-sandbox"
    for name in ("config", "system", "plugins", "log", "project"):
        (sandbox / name).mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(ROOT / f"dist/bookmarks-desktop-{PLUGIN_VERSION}.zip") as package:
        package.extractall(sandbox / "plugins")
    properties = sandbox / "idea.properties"
    properties.write_text("\n".join(f"idea.{name}.path={sandbox / name}" for name in ("config", "system", "plugins", "log")) + "\nidea.initially.ask.config=false\nconsent.confirmation.enabled=false\n")
    env = os.environ.copy()
    env["IDEA_PROPERTIES"] = str(properties)
    native = sdk / "MacOS/idea" if os.uname().sysname == "Darwin" else sdk / "bin/idea.sh"
    print(f"Starting official IDEA {info['version']}; sandbox: {sandbox}", flush=True)
    raise SystemExit(subprocess.call([str(native), "dontReopenProjects", str(sandbox / "project")], env=env))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", required=True, type=Path)
    launch(sdk_contents(parser.parse_args().idea_home))
