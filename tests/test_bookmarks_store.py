"""AI editing must preserve canonical identities, source versions and other contexts."""
from copy import deepcopy
from io import BytesIO
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/"tools"))
from bookmarks_store import apply_model, encode_xml, load_source, parse_state, read_json, validate, write_state


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.path = self.project/".idea/bookmarks.xml"
        self.path.parent.mkdir()
        self.note = '# 描述\n\n**共享** <tag> & "quoted"\n\n```java\nreader.poll();\n```\n'
        self.model = {"formatVersion": 2,
                      "bookmarks": [{"id": "bookmark", "provider": "com.intellij.ide.bookmark.providers.LineBookmarkProvider",
                                     "attributes": {"url": "file://$PROJECT_DIR$/Sample.java", "line": "0", "lineText": "class Sample {"},
                                     "type": "LETTER_A", "descriptionMarkdown": self.note}],
                      "groups": [{"id": "root", "parentId": None, "name": "业务", "isDefault": True, "bookmarkIds": ["bookmark"]},
                                 {"id": "child", "parentId": "root", "name": "入口", "isDefault": False, "bookmarkIds": ["bookmark"]}]}
        root = ET.Element("project", version="4")
        state = write_state(self.model, "component")
        state.set("name", "BookmarksManager")
        root.append(state)
        ET.SubElement(root, "component", name="Unrelated", value="keep")
        self.path.write_bytes(encode_xml(root))

    def test_xml_roundtrip_preserves_markdown_and_identity(self):
        model, _, _ = load_source(self.project)
        model.pop("_source")
        self.assertEqual(self.model, model)

    def test_full_platform_serializer_empty_legacy_field_is_accepted(self):
        state = write_state(self.model, "ManagerState")
        for group in state.findall("./option[@name='groups']/GroupState"):
            ET.SubElement(group, "option", name="bookmarks")
        self.assertEqual(self.model, parse_state(state))

    def test_apply_changes_one_record_preserves_refs_and_other_components(self):
        model, _, original = load_source(self.project)
        model["bookmarks"][0]["descriptionMarkdown"] += "\nNew note"
        model["groups"][1]["name"] = "新入口"
        result = apply_model(self.project, model)
        self.assertEqual(original, Path(result["backup"]).read_bytes())
        updated, root, _ = load_source(self.project)
        self.assertEqual(model["bookmarks"], updated["bookmarks"])
        self.assertEqual(model["groups"], updated["groups"])
        self.assertEqual("keep", root.find("./component[@name='Unrelated']").get("value"))
        self.assertFalse(self.path.with_name("bookmarks.xml.ai.lock").exists())

    def test_stale_export_cannot_overwrite_new_changes(self):
        model, _, _ = load_source(self.project)
        self.path.write_bytes(self.path.read_bytes()+b"\n")
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "changed after export"):
            apply_model(self.project, model)
        self.assertEqual(before, self.path.read_bytes())

    def test_git_branch_changes_reject_an_unchanged_file(self):
        subprocess.run(["git", "init", "--quiet", "--initial-branch=first", str(self.project)], check=True)
        model, _, _ = load_source(self.project)
        subprocess.run(["git", "-C", str(self.project), "symbolic-ref", "HEAD", "refs/heads/second"], check=True)
        with self.assertRaisesRegex(ValueError, "Git branch/commit changed"):
            apply_model(self.project, model)

    def test_other_source_path_is_rejected(self):
        model, _, _ = load_source(self.project)
        model["_source"]["project"] = "/another/project"
        with self.assertRaisesRegex(ValueError, "path/project mismatch"):
            apply_model(self.project, model)

    def test_archive_update_preserves_other_context_and_expands_macro(self):
        archive = self.project/"project.contexts.zip"
        root = ET.Element("context")
        ET.SubElement(root, "unrelated", value="keep")
        ET.SubElement(root, "bookmarks").append(write_state(self.model, "ManagerState"))
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as package:
            package.comment = b"archive-comment"
            package.writestr("/__branch_context_feature/work", encode_xml(root))
            package.writestr("/__branch_context_other", b"other branch bytes")
        model, _, original = load_source(self.project, archive, "feature/work")
        model["bookmarks"][0]["descriptionMarkdown"] = "Updated branch note"
        result = apply_model(self.project, model, archive, "feature/work")
        self.assertEqual(original, Path(result["backup"]).read_bytes())
        with zipfile.ZipFile(archive) as package:
            self.assertEqual(b"other branch bytes", package.read("/__branch_context_other"))
            self.assertEqual(b"archive-comment", package.comment)
            self.assertEqual("keep", ET.fromstring(package.read("/__branch_context_feature/work")).find("unrelated").get("value"))
        saved, _, _ = load_source(self.project, archive, "feature/work")
        self.assertEqual("Updated branch note", saved["bookmarks"][0]["descriptionMarkdown"])
        self.assertEqual(self.project.resolve().as_uri()+"/Sample.java", saved["bookmarks"][0]["attributes"]["url"])

    def test_missing_archive_branch_never_creates_a_context(self):
        archive = self.project/"contexts.zip"
        with zipfile.ZipFile(archive, "w") as package:
            package.writestr("other", "unchanged")
        before = archive.read_bytes()
        with self.assertRaisesRegex(ValueError, "missing or duplicated"):
            load_source(self.project, archive, "missing")
        self.assertEqual(before, archive.read_bytes())

    def test_duplicate_membership_rejected(self):
        self.model["groups"][0]["bookmarkIds"].append("bookmark")
        with self.assertRaisesRegex(ValueError, "Duplicate or dangling"):
            validate(self.model)

    def test_missing_membership_target_rejected(self):
        self.model["groups"][0]["bookmarkIds"].append("missing")
        with self.assertRaisesRegex(ValueError, "Duplicate or dangling"):
            validate(self.model)

    def test_orphan_bookmark_rejected(self):
        for group in self.model["groups"]:
            group["bookmarkIds"].clear()
        with self.assertRaisesRegex(ValueError, "without group references"):
            validate(self.model)

    def test_parent_cycle_rejected(self):
        self.model["groups"][0]["parentId"] = "child"
        with self.assertRaisesRegex(ValueError, "group cycle"):
            validate(self.model)

    def test_duplicate_location_rejected_even_with_different_id_and_text(self):
        record = deepcopy(self.model["bookmarks"][0])
        record["id"] = "different"
        record["attributes"]["lineText"] = "different text"
        self.model["bookmarks"].append(record)
        with self.assertRaisesRegex(ValueError, "Duplicate bookmark location"):
            validate(self.model)

    def test_duplicate_sibling_names_rejected(self):
        self.model["groups"].append({**self.model["groups"][1], "id": "another-child"})
        with self.assertRaisesRegex(ValueError, "sibling"):
            validate(self.model)

    def test_different_parent_allows_same_name(self):
        self.model["groups"].append({"id": "other-root", "name": "其他", "parentId": None, "isDefault": False, "bookmarkIds": []})
        self.model["groups"].append({**self.model["groups"][1], "id": "another-child", "parentId": "other-root"})
        validate(self.model)

    def test_unknown_fields_cannot_silently_disappear(self):
        self.model["bookmarks"][0]["futureProperty"] = "preserve?"
        with self.assertRaisesRegex(ValueError, "Invalid bookmark fields"):
            validate(self.model)

    def test_duplicate_json_keys_rejected(self):
        source = self.project/"bad.json"
        source.write_text('{"formatVersion":2,"formatVersion":1}')
        with self.assertRaisesRegex(ValueError, "Duplicate JSON key"):
            read_json(source)

    def test_apply_cli_requires_closed_project_flag(self):
        command = [sys.executable, str(Path(__file__).resolve().parents[1]/"tools/bookmarks_store.py"), "apply", "--project", str(self.project), "--input", "missing.json"]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--project-closed", result.stderr)


if __name__ == "__main__":
    unittest.main()
