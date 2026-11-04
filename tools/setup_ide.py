#!/usr/bin/env python3
"""Generate project-local source roots, library sources, and build tool entries."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET
from build import ROOT, MODULES, product_info, sdk_contents


def write(path, root):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root)
    path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))


def setup(sdk):
    product_info(sdk)
    project = ET.Element("project", version="4")
    manager = ET.SubElement(project, "component", name="ProjectModuleManager")
    modules = ET.SubElement(manager, "modules")
    ET.SubElement(modules, "module", fileurl="file://$PROJECT_DIR$/idea-bookmarks.iml", filepath="$PROJECT_DIR$/idea-bookmarks.iml")
    write(ROOT / ".idea/modules.xml", project)
    module = ET.Element("module", type="JAVA_MODULE", version="4")
    component = ET.SubElement(module, "component", name="NewModuleRootManager", **{"inherit-compiler-output": "true"})
    ET.SubElement(component, "exclude-output")
    content = ET.SubElement(component, "content", url="file://$MODULE_DIR$")
    for _, name, source in MODULES:
        ET.SubElement(content, "sourceFolder", url=f"file://$MODULE_DIR$/modules/{name}/{source}", isTestSource="false")
        ET.SubElement(content, "sourceFolder", url=f"file://$MODULE_DIR$/modules/{name}/resources", type="java-resource")
    for name in (".cache", "upstream", "official", "build", "dist", ".gradle"):
        ET.SubElement(content, "excludeFolder", url="file://$MODULE_DIR$/" + name)
    ET.SubElement(component, "orderEntry", type="inheritedJdk")
    ET.SubElement(component, "orderEntry", type="sourceFolder", forTests="false")
    order = ET.SubElement(component, "orderEntry", type="module-library")
    library = ET.SubElement(order, "library", name="Official IDEA 2026.2.4 SDK (compile only)")
    classes = ET.SubElement(library, "CLASSES")
    for path in [*sorted((sdk / "lib").rglob("*.jar")), sdk / "plugins/markdown/lib/intellij.markdown.jar"]:
        if "bookmark" not in path.name.lower():
            ET.SubElement(classes, "root", url="jar://" + path.as_posix() + "!/")
    ET.SubElement(library, "JAVADOC")
    sources = ET.SubElement(library, "SOURCES")
    ET.SubElement(sources, "root", url="file://$MODULE_DIR$/official/platform/lang-api/src")
    facets = ET.SubElement(module, "component", name="FacetManager")
    facet = ET.SubElement(facets, "facet", type="kotlin-language", name="Kotlin")
    config = ET.SubElement(facet, "configuration", version="5", platform="JVM 25", allPlatforms="JVM [25]", useProjectSettings="false")
    arguments = ET.SubElement(config, "compilerArguments")
    strings = ET.SubElement(arguments, "stringArguments")
    for key, value in (("jvmTarget", "25"), ("languageVersion", "2.4"), ("apiVersion", "2.4")):
        ET.SubElement(strings, "stringArg", name=key, arg=value)
    write(ROOT / "idea-bookmarks.iml", module)
    print("Project generated. Open this directory in IDEA 2026.2.4; select a Java 25 Project SDK if prompted.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", type=Path, required=True)
    setup(sdk_contents(parser.parse_args().idea_home))
