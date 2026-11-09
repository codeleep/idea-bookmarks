from copy import deepcopy
from io import BytesIO
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from bookmarks_store import apply_model, encode_xml, load_source, parse_state, validate, write_state


class RelationsTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.project = Path(temp.name)
        self.path = self.project / ".idea/bookmarks.xml"
        self.path.parent.mkdir()
        (self.project / "代码.txt").write_text("    起点();  \n\t终点();\n")
        self.model = {"formatVersion": 3, "bookmarks": [
            {"id": ident, "provider": "com.intellij.ide.bookmark.providers.LineBookmarkProvider",
             "attributes": {"url": "file://$PROJECT_DIR$/代码.txt", "line": str(line), "lineText": text},
             "type": "DEFAULT", "descriptionMarkdown": "# 共享描述\n\n保留"}
            for ident, line, text in (("a", 0, "    起点();  "), ("b", 1, "\t终点();"))],
            "groups": [{"id": "g", "parentId": None, "name": "中文分组", "isDefault": True, "bookmarkIds": ["a", "b"]}],
            "relations": [{"id": "edge-1", "sourceId": "a", "targetId": "b", "kind": "CALLS", "note": "调用说明\n  保留缩进 & <tag>"}]}
        root = ET.Element("project", version="4")
        node = write_state(self.model, "component"); node.set("name", "BookmarksManager"); root.append(node)
        ET.SubElement(root, "component", name="Unrelated", value="keep")
        self.path.write_bytes(encode_xml(root))

    def test_format3_roundtrip_keeps_typed_edges_and_note(self):
        self.assertEqual(self.model, parse_state(write_state(self.model, "ManagerState")))
        model, _, _ = load_source(self.project); model.pop("_source")
        self.assertEqual(self.model, model)

    def test_relation_only_apply_has_backup_and_preserves_bookmark_source(self):
        model, _, original = load_source(self.project)
        model["relations"][0]["note"] += "\nAI 补充"
        result = apply_model(self.project, model)
        self.assertEqual(original, Path(result["backup"]).read_bytes())
        actual, root, _ = load_source(self.project)
        self.assertEqual(self.model["bookmarks"], actual["bookmarks"])
        self.assertEqual(model["relations"], actual["relations"])
        self.assertEqual("keep", root.find("./component[@name='Unrelated']").get("value"))

    def test_invalid_edges_fail_validation(self):
        for key, value in (("targetId", "missing"), ("targetId", "a"), ("kind", "UNKNOWN"), ("id", ""), ("note", 123), ("note", "\U0001f4d6" * 5001)):
            with self.subTest(key=key, value=value):
                model = deepcopy(self.model); model["relations"][0][key] = value
                with self.assertRaises(ValueError): validate(model)

    def test_duplicate_and_reverse_related_edges_are_rejected(self):
        model = deepcopy(self.model); model["relations"].append(deepcopy(model["relations"][0]))
        with self.assertRaises(ValueError): validate(model)
        model = deepcopy(self.model); model["relations"][0]["kind"] = "RELATED"
        model["relations"].append({"id": "edge-2", "sourceId": "b", "targetId": "a", "kind": "RELATED", "note": ""})
        with self.assertRaisesRegex(ValueError, "Duplicate bookmark relation"): validate(model)

    def test_format2_cannot_silently_carry_relations(self):
        model = deepcopy(self.model); model["formatVersion"] = 2
        with self.assertRaisesRegex(ValueError, "require formatVersion 3"): validate(model)
        model.pop("relations")
        self.assertEqual(model, parse_state(write_state(model, "ManagerState")))
        node = write_state(model, "ManagerState")
        parent = ET.SubElement(node, "option", name="relations")
        self.assertEqual(model, parse_state(node))
        ET.SubElement(parent, "UnknownEdge")
        with self.assertRaisesRegex(ValueError, "require formatVersion 3"): parse_state(node)

    def test_unknown_relation_fields_in_xml_are_rejected(self):
        node = write_state(self.model, "ManagerState")
        edge = node.find("./option[@name='relations']/BookmarkRelation")
        ET.SubElement(edge, "option", name="futureField", value="do-not-drop")
        with self.assertRaisesRegex(ValueError, "Unknown stored relation fields"): parse_state(node)
        node = write_state(self.model, "ManagerState")
        ET.SubElement(node.find("./option[@name='relations']"), "UnknownEdge")
        with self.assertRaisesRegex(ValueError, "Unknown stored relation element"): parse_state(node)

    def test_missing_endpoint_apply_does_not_touch_source_or_create_backup(self):
        model, _, original = load_source(self.project)
        model["relations"][0]["targetId"] = "missing"
        with self.assertRaises(ValueError): apply_model(self.project, model)
        self.assertEqual(original, self.path.read_bytes())
        self.assertFalse((self.path.parent / "bookmarks-backups").exists())

    def test_branch_archive_preserves_private_reading_review_state(self):
        archive = self.project / "contexts.zip"
        root = ET.Element("context"); bookmarks = ET.SubElement(root, "bookmarks")
        bookmarks.append(write_state(self.model, "ManagerState"))
        reading = ET.SubElement(bookmarks, "BookmarkReadingState")
        ET.SubElement(reading, "private-review", value="keep-source-baseline")
        original_reading = ET.tostring(reading)
        with zipfile.ZipFile(archive, "w") as package:
            package.writestr("/__branch_context_main", encode_xml(root)); package.writestr("/__branch_context_other", b"untouched")
        model, _, original = load_source(self.project, archive, "main")
        model["relations"][0]["kind"] = "DESIGN"
        apply_model(self.project, model, archive, "main")
        with zipfile.ZipFile(archive) as package:
            actual = ET.fromstring(package.read("/__branch_context_main"))
            self.assertEqual(ET.canonicalize(original_reading.decode(), strip_text=True), ET.canonicalize(ET.tostring(actual.find("./bookmarks/BookmarkReadingState"), encoding="unicode"), strip_text=True))
            self.assertEqual(b"untouched", package.read("/__branch_context_other"))
        restored, _, _ = load_source(self.project, archive, "main")
        self.assertEqual("DESIGN", restored["relations"][0]["kind"])


if __name__ == "__main__": unittest.main()
