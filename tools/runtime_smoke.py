#!/usr/bin/env python3
"""Install the ZIP into isolated user plugins; run the official IDE unmodified."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from build import ROOT, PLUGIN_VERSION, product_info, runtime_home, sdk_contents


def smoke(sdk):
    info = product_info(sdk)
    java_home = runtime_home(sdk)
    sandbox = ROOT / "build/runtime-sandbox"
    if sandbox.exists():
        shutil.rmtree(sandbox)
    for name in ("config", "system", "log", "plugins", "helper-classes"):
        (sandbox / name).mkdir(parents=True)
    archive = ROOT / f"dist/bookmarks-desktop-{PLUGIN_VERSION}.zip"
    with zipfile.ZipFile(archive) as package:
        package.extractall(sandbox / "plugins")
    classpath = [*sorted((sdk / "lib").rglob("*.jar")),
                 sdk / "plugins/markdown/lib/intellij.markdown.jar",
                 *sorted((sandbox / "plugins/platform-bookmarks-plugin/lib").rglob("*.jar"))]
    args = ["-encoding", "UTF-8", "--release", "25", "-proc:none", "-classpath",
            os.pathsep.join(map(str, classpath)), "-d", str(sandbox / "helper-classes"),
            str(ROOT / "tests/runtime/BookmarkSmokeStarter.java")]
    arguments = sandbox / "javac.args"
    arguments.write_text("\n".join(json.dumps(value, ensure_ascii=False) for value in args))
    subprocess.run([str(java_home / "bin/javac"), "@" + str(arguments)], check=True)
    helper = sandbox / "plugins/bookmarks-validation/lib"
    helper.mkdir(parents=True)
    with zipfile.ZipFile(helper / "validation.jar", "w", zipfile.ZIP_DEFLATED) as output:
        for path in (sandbox / "helper-classes").rglob("*.class"):
            output.write(path, path.relative_to(sandbox / "helper-classes").as_posix())
        output.writestr("META-INF/plugin.xml", """<idea-plugin>
  <id>bookmarks.validation</id><name>Bookmarks validation</name><version>1</version><vendor>Local</vendor>
  <depends>com.intellij.modules.platform</depends><depends>intellij.bookmarks.plugin</depends>
  <extensions defaultExtensionNs="com.intellij"><appStarter id="bookmarks-source-smoke" implementation="bookmarks.validation.BookmarkSmokeStarter"/></extensions>
</idea-plugin>""")
    launch = next(item for item in info["launch"] if item["os"] == "macOS") if os.uname().sysname == "Darwin" else info["launch"][0]
    vm = [line.strip() for line in (sdk / "bin/idea.vmoptions").read_text().splitlines() if line.strip() and not line.startswith("#")]
    jvm_arguments = [value.replace("$APP_PACKAGE", str(sdk.parent)).replace("$IDE_HOME", str(sdk))
                     for value in launch.get("additionalJvmArguments", [])]
    command = [str(java_home / "bin/java"), *vm, *jvm_arguments,
               "-Didea.home.path=" + str(sdk), "-Djava.awt.headless=true",
               "-Dintellij.platform.load.app.info.from.resources=true", "-Didea.is.internal=true",
               "-Dconsent.confirmation.enabled=false", "-Didea.initially.ask.config=false",
               "-Didea.config.path=" + str(sandbox / "config"), "-Didea.system.path=" + str(sandbox / "system"),
               "-Didea.log.path=" + str(sandbox / "log"), "-Didea.plugins.path=" + str(sandbox / "plugins"),
               "-Dbookmarks.validation.root=" + str(sandbox), "-Dbookmarks.validation.version=" + PLUGIN_VERSION, "-cp",
               os.pathsep.join(str(sdk / "lib" / name) for name in launch["bootClassPathJarNames"]),
               launch.get("mainClass", "com.intellij.idea.Main"), "bookmarks-source-smoke"]
    log = sandbox / "process.log"
    print(f"Starting unmodified official IDEA {info['version']}; log: {log}", flush=True)
    with log.open("w") as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    if result.returncode or not (sandbox / "result.txt").is_file():
        print(log.read_text()[-18000:])
        raise SystemExit(result.returncode or 1)
    print((sandbox / "result.txt").read_text())
    passed = [line[5:] for line in log.read_text().splitlines() if line.startswith("PASS ")]
    report = {"result": "PASS", "sdkVersion": info["version"], "sdkBuild": info["buildNumber"],
              "pluginVersion": PLUGIN_VERSION, "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
              "installation": "isolated user plugins directory", "ideInstallationModified": False,
              "checks": passed}
    (ROOT / "build/runtime-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    build_report = ROOT / "build/build-report.json"
    if build_report.exists():
        value = json.loads(build_report.read_text())
        if value.get("sha256") == report["archiveSha256"]:
            value["runtimeValidated"] = True
            build_report.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", required=True, type=Path)
    smoke(sdk_contents(parser.parse_args().idea_home))
