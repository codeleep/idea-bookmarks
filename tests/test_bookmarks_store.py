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
from bookmarks_store import apply_model, encode_xml, load_source, parse_state, read_json, source_lines, validate, validate_locations, write_state


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.path = self.project/".idea/bookmarks.xml"
        self.path.parent.mkdir()
        (self.project/"Sample.java").write_bytes(b"class Sample {\n}\n")
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

    def test_exact_anchors_preserve_spaces_tabs_trailing_spaces_and_chinese(self):
        for source in ("    public void run() {", "\t\t调用();", "    中文 = true;  \t", "\t// 中文注释  "):
            with self.subTest(source=repr(source)):
                (self.project/"Sample.java").write_bytes((source+"\r\n").encode("utf-8"))
                self.model["bookmarks"][0]["attributes"]["lineText"] = source
                before = deepcopy(self.model)
                report = validate_locations(self.model, self.project)
                self.assertEqual(1, report["checkedLineBookmarks"])
                self.assertEqual(1, report["checkedTextAnchors"])
                self.assertEqual(before, self.model)

    def test_all_whitespace_trimming_is_rejected_with_precise_location(self):
        source = "    \t调用();  \t"
        (self.project/"Sample.java").write_bytes(("class Sample {\n"+source+"\n}").encode())
        attributes = self.model["bookmarks"][0]["attributes"]
        attributes["line"] = "1"
        for operation in (str.strip, str.lstrip, str.rstrip):
            with self.subTest(operation=operation.__name__):
                attributes["lineText"] = operation(source)
                with self.assertRaises(ValueError) as error:
                    validate_locations(self.model, self.project)
                for expected in ("bookmark", "Sample.java", "line=1 (IDEA 2)", "lineText mismatch", repr(source)):
                    self.assertIn(expected, str(error.exception))

    def test_omitted_anchor_still_requires_real_file_and_valid_line(self):
        attributes = self.model["bookmarks"][0]["attributes"]
        del attributes["lineText"]
        report = validate_locations(self.model, self.project)
        self.assertEqual(1, report["checkedLineBookmarks"])
        self.assertEqual(0, report["checkedTextAnchors"])
        self.assertEqual(1, report["lineBookmarksWithoutText"])
        attributes["line"] = "99"
        with self.assertRaisesRegex(ValueError, "bookmark.*Sample.java.*line=99.*outside"):
            validate_locations(self.model, self.project)
        attributes["line"] = "0"
        (self.project/"Sample.java").unlink()
        with self.assertRaisesRegex(ValueError, "bookmark.*Sample.java.*does not exist"):
            validate_locations(self.model, self.project)

    def test_literal_absolute_vfs_url_and_unicode_path(self):
        source = self.project/"中文 源码.java"
        source.write_text("\t中文();  \n", encoding="utf-8")
        self.model["bookmarks"][0]["attributes"] = {"url": "file://"+source.as_posix(), "line": "0", "lineText": "\t中文();  "}
        self.assertEqual(1, validate_locations(self.model, self.project)["checkedLineBookmarks"])
        self.model["bookmarks"][0]["attributes"]["url"] = source.as_uri()
        with self.assertRaisesRegex(ValueError, "does not exist"):
            validate_locations(self.model, self.project)

    def test_unresolved_macro_and_directory_line_are_rejected(self):
        self.model["bookmarks"][0]["attributes"]["url"] = "file://$UNKNOWN_DIR$/Sample.java"
        with self.assertRaisesRegex(ValueError, "bookmark.*line=0.*Unresolved path macro"):
            validate_locations(self.model, self.project)
        self.model["bookmarks"][0]["attributes"]["url"] = "file://"+self.project.as_posix()
        with self.assertRaisesRegex(ValueError, "regular file"):
            validate_locations(self.model, self.project)

    def test_file_other_provider_and_nonlocal_bookmarks_keep_their_semantics(self):
        item = self.model["bookmarks"][0]
        for attributes in ({"url": "file://$PROJECT_DIR$"}, {"url": "file://$PROJECT_DIR$", "line": "-1", "lineText": "ignored"}):
            item["attributes"] = attributes
            self.assertEqual(1, validate_locations(self.model, self.project)["skippedFileBookmarks"])
        item["attributes"] = {"url": "jar:///library.jar!/Example.java", "line": "0", "lineText": "opaque"}
        self.assertEqual(1, validate_locations(self.model, self.project)["skippedNonLocalBookmarks"])
        item["provider"] = "example.other.Provider"
        item["attributes"] = {"line": "0", "lineText": "opaque"}
        self.assertEqual(1, validate_locations(self.model, self.project)["skippedOtherProviders"])

    def test_document_line_boundaries_and_bom_match_idea_without_splitlines(self):
        self.assertEqual(["\t中文  ", "next", ""], source_lines(b"\xef\xbb\xbf"+"\t中文  \r\nnext\r".encode()))
        self.assertEqual(["x\u2028y\fz\u0085w", ""], source_lines("x\u2028y\fz\u0085w\n".encode()))
        self.assertEqual(["    中文  ", ""], source_lines("    中文  \n".encode("utf-16")))
        self.assertEqual([""], source_lines(b""))
        self.model["bookmarks"][0]["attributes"].update(line="2", lineText="")
        self.assertEqual(1, validate_locations(self.model, self.project)["checkedLineBookmarks"])

    def test_anchor_containing_line_separator_is_not_silently_trimmed(self):
        for suffix in ("\n", "\r\n", "\r"):
            with self.subTest(suffix=repr(suffix)):
                self.model["bookmarks"][0]["attributes"]["lineText"] = "class Sample {"+suffix
                with self.assertRaisesRegex(ValueError, "lineText mismatch"):
                    validate_locations(self.model, self.project)

    def test_bad_anchor_apply_fails_before_backup_and_preserves_everything(self):
        model, _, original = load_source(self.project)
        model["bookmarks"][0]["attributes"]["lineText"] = "wrong"
        original_model = deepcopy(model)
        with self.assertRaisesRegex(ValueError, "lineText mismatch"):
            apply_model(self.project, model)
        self.assertEqual(original, self.path.read_bytes())
        self.assertEqual(original_model, model)
        self.assertFalse((self.path.parent/"bookmarks-backups").exists())
        self.assertFalse(self.path.with_name("bookmarks.xml.ai.lock").exists())

    def test_all_bad_anchors_are_reported(self):
        extra = deepcopy(self.model["bookmarks"][0])
        extra.update(id="second", type="DEFAULT")
        extra["attributes"].update(line="1", lineText="wrong second")
        self.model["bookmarks"][0]["attributes"]["lineText"] = "wrong first"
        self.model["bookmarks"].append(extra)
        self.model["groups"][0]["bookmarkIds"].append("second")
        with self.assertRaises(ValueError) as error:
            validate_locations(self.model, self.project)
        self.assertIn("failed (2)", str(error.exception))
        self.assertIn("Bookmark 'bookmark'", str(error.exception))
        self.assertIn("Bookmark 'second'", str(error.exception))

    def test_line_number_syntax_matches_idea_and_errors_include_location(self):
        for value in (" 0", "0 ", "1_0", "-2", "2147483648"):
            with self.subTest(line=value):
                self.model["bookmarks"][0]["attributes"]["line"] = value
                with self.assertRaises(ValueError) as error:
                    validate_locations(self.model, self.project)
                for expected in ("Bookmark 'bookmark'", "Sample.java", repr(value)):
                    self.assertIn(expected, str(error.exception))

    def test_validate_cli_checks_locations_and_can_infer_source_project(self):
        model, _, _ = load_source(self.project)
        path = self.project/"plan.json"
        path.write_text(json.dumps(model))
        command = [sys.executable, str(Path(__file__).resolve().parents[1]/"tools/bookmarks_store.py"), "validate", "--input", str(path)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('"checkedLineBookmarks": 1', result.stdout)
        model["bookmarks"][0]["attributes"]["lineText"] = "wrong"
        path.write_text(json.dumps(model))
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("lineText mismatch", result.stderr)
        del model["_source"]
        path.write_text(json.dumps(model))
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--project is required", result.stderr)

    def test_archive_validates_target_branch_without_checkout(self):
        def git(*args):
            return subprocess.check_output(["git", "-C", str(self.project), *args], text=True, stderr=subprocess.PIPE).strip()
        git("init", "--quiet", "--initial-branch=archive-branch")
        git("add", "Sample.java")
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "source")
        git("checkout", "-qb", "other-branch")
        (self.project/"Sample.java").write_text("different current source\n")
        archive = self.project/"contexts.zip"
        root = ET.Element("context"); ET.SubElement(root, "bookmarks").append(write_state(self.model, "ManagerState"))
        with zipfile.ZipFile(archive, "w") as package:
            package.writestr("/__branch_context_archive-branch", encode_xml(root))
        model, _, _ = load_source(self.project, archive, "archive-branch")
        before = (self.project/"Sample.java").read_bytes()
        apply_model(self.project, model, archive, "archive-branch")
        self.assertEqual("other-branch", git("branch", "--show-current"))
        self.assertEqual(before, (self.project/"Sample.java").read_bytes())
        model["bookmarks"][0]["attributes"]["lineText"] = "different current source"
        with self.assertRaisesRegex(ValueError, "lineText mismatch"):
            validate_locations(model, self.project, "archive-branch")


if __name__ == "__main__":
    unittest.main()
