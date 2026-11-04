#!/usr/bin/env python3
"""Compile the extracted desktop implementation using the target IDEA SDK.

No upstream implementation classes are copied from the SDK. The existing
Bookmarks plugin is excluded from the compilation classpath. Remote-development
sources are retained in official/ but intentionally excluded from this desktop
build; the missing split-client module is not open source in the pinned tree.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OFFICIAL = ROOT / "official"
EDITABLE = ROOT / "modules"
PLUGIN_DESCRIPTOR = ROOT / "plugin/plugin.xml"
BUILD_NUMBER = ET.parse(PLUGIN_DESCRIPTOR).getroot().find("idea-version").get("since-build")
PLUGIN_VERSION = ET.parse(PLUGIN_DESCRIPTOR).getroot().findtext("version")
MODULES = [
    ("intellij.platform.bookmarks", "core", "src/com"),
    ("intellij.platform.debugger.impl.bookmarks", "debugger", "src"),
    ("intellij.platform.vcs.log.impl.bookmarks", "vcs", "src"),
]


def sdk_contents(path):
    path = path.expanduser().resolve()
    if (path / "Contents").is_dir():
        path = path / "Contents"
    return path


def product_info(sdk):
    for path in (sdk / "Resources/product-info.json", sdk / "product-info.json"):
        if path.is_file():
            return json.loads(path.read_text())
    raise SystemExit(f"product-info.json not found: {sdk}")


def runtime_home(sdk):
    for path in (sdk / "jbr/Contents/Home", sdk / "jbr"):
        if (path / "bin/java").is_file():
            return path
    raise SystemExit("Target SDK must include its Java 25 runtime/JDK")


def run(command, log):
    print("RUN", str(command[0]), "...", flush=True)
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    with log.open("a") as stream:
        stream.write(result.stdout)
    if result.returncode:
        print(result.stdout)
        raise SystemExit(result.returncode)


def create_jar(path, classes, resources, license_files=True):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for base in (classes, resources):
            for source in sorted(base.rglob("*")):
                if source.is_file():
                    archive.write(source, source.relative_to(base).as_posix())
        if license_files:
            archive.write(ROOT / "LICENSE-APACHE-2.0.txt", "META-INF/LICENSE-APACHE-2.0.txt")
            archive.write(ROOT / "NOTICE.txt", "META-INF/NOTICE.txt")


def build(sdk):
    info = product_info(sdk)
    if info.get("buildNumber") != BUILD_NUMBER or info.get("productCode") not in ("IU", "IIU"):
        raise SystemExit(f"Expected IntelliJ IDEA {BUILD_NUMBER}, got {info.get('name')} {info.get('buildNumber')}")
    java_home = runtime_home(sdk)
    compiler_home = sdk / "plugins/Kotlin/kotlinc/lib"
    if not (compiler_home / "kotlin-compiler.jar").is_file():
        raise SystemExit(f"Bundled Kotlin compiler missing: {compiler_home}")
    output = ROOT / "build"
    output.mkdir(exist_ok=True)
    log = output / "compile.log"
    log.write_text("")
    classes_root = output / "classes"
    if classes_root.exists():
        shutil.rmtree(classes_root)
    # Keep the original implementation entirely off the compilation classpath.
    jars = sorted((sdk / "lib").rglob("*.jar"))
    markdown = sdk / "plugins/markdown/lib/intellij.markdown.jar"
    if not markdown.is_file():
        raise SystemExit("The official bundled Markdown plugin is required")
    jars.append(markdown)
    classpath = [str(p) for p in jars if "bookmark" not in p.name.lower()]
    original_prefixes = ("com/intellij/ide/bookmark/BookmarksManagerImpl.class",
                         "com/intellij/ide/bookmarks/BookmarkManager.class")
    for path in classpath:
        with zipfile.ZipFile(path) as archive:
            if any(name in archive.namelist() for name in original_prefixes):
                raise SystemExit(f"Original implementation leaked onto classpath: {path}")
    plugin = output / "plugin/platform-bookmarks-plugin"
    if plugin.exists():
        shutil.rmtree(plugin)
    (plugin / "lib/modules").mkdir(parents=True)
    compilation = []
    for name, relative, source_dir in MODULES:
        source_root = EDITABLE / relative / source_dir
        sources = sorted(p for p in source_root.rglob("*") if p.suffix in (".kt", ".java"))
        classes = classes_root / name
        classes.mkdir(parents=True)
        module_classpath = os.pathsep.join(classpath)
        args_file = output / f"{name}.kotlinc.args"
        compiler_args = [
            "-no-stdlib", "-no-reflect", "-jvm-target", "25", "-api-version", "2.4",
            "-language-version", "2.4", "-Xjvm-default=all", "-progressive",
            "-XXLanguage:+AllowEagerSupertypeAccessibilityChecks",
            "-module-name", name, "-classpath", module_classpath, "-d", str(classes),
            *map(str, sources),
        ]
        args_file.write_text("\n".join(json.dumps(arg, ensure_ascii=False) for arg in compiler_args))
        run([str(java_home / "bin/java"), "-Xmx2g", "-cp", str(compiler_home / "*"),
             "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(args_file)], log)
        java_sources = [p for p in sources if p.suffix == ".java"]
        if java_sources:
            javac_args = ["-encoding", "UTF-8", "--release", "25", "-proc:none",
                          "-classpath", os.pathsep.join([str(classes), *classpath]),
                          "-d", str(classes), *map(str, java_sources)]
            javac_file = output / f"{name}.javac.args"
            javac_file.write_text("\n".join(json.dumps(arg, ensure_ascii=False) for arg in javac_args))
            run([str(java_home / "bin/javac"), "@" + str(javac_file)], log)
        create_jar(plugin / "lib/modules" / f"{name}.jar", classes, EDITABLE / relative / "resources")
        classpath.insert(0, str(classes))
        compilation.append({"module": name, "sourceFiles": len(sources),
                            "classes": sum(1 for p in classes.rglob("*.class"))})
        print(f"OK {name}: {len(sources)} source files", flush=True)

    descriptor = ET.fromstring(PLUGIN_DESCRIPTOR.read_bytes())
    content = descriptor.find("content")
    built = {name for name, _, _ in MODULES}
    declared = {entry.attrib.get("name") for entry in content}
    if declared != built:
        raise SystemExit(f"plugin.xml content modules {declared} do not match compiled modules {built}")
    main_resources = output / "plugin-resources"
    (main_resources / "META-INF").mkdir(parents=True, exist_ok=True)
    ET.indent(descriptor)
    (main_resources / "META-INF/plugin.xml").write_bytes(ET.tostring(descriptor, encoding="utf-8", xml_declaration=True))
    empty = output / "empty"
    empty.mkdir(exist_ok=True)
    create_jar(plugin / "lib/platform-bookmarks-plugin.jar", empty, main_resources)
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    archive_path = dist / f"bookmarks-desktop-{PLUGIN_VERSION}.zip"
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for source in sorted(plugin.rglob("*")):
            if source.is_file():
                archive.write(source, source.relative_to(plugin.parent).as_posix())
    report = {"version": "2026.2.4", "build": BUILD_NUMBER, "sdk": str(sdk),
              "pluginVersion": PLUGIN_VERSION, "mode": "desktop-only",
              "compiledModules": compilation, "archive": str(archive_path),
              "sha256": hashlib.sha256(archive_path.read_bytes()).hexdigest(),
              "runtimeValidated": False,
              "excluded": ["RPC/backend/frontend", "non-public split client"],
              "reusedFromSdk": ["public bookmark API", "Favorites bridge", "Java package adapter", "Tasks branch tracker and context archive"]}
    (output / "build-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"BUILT {archive_path}")
    return plugin


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", type=Path, required=True, help="IDEA 2026.2.4 .app / Contents / Linux SDK directory")
    build(sdk_contents(parser.parse_args().idea_home))
