"""Release integrity, retry and version immutability checks without GitHub writes."""
from io import BytesIO
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from ci_build import sha256
from publish_release import validate_assets, publish, find_release

VERSION = "262.11879.100.2"
COMMIT = "a" * 40


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.plugin = self.root / f"bookmarks-desktop-{VERSION}.zip"
        self.sources = self.root / f"idea-bookmarks-{VERSION}-sources.zip"
        descriptor = f"<idea-plugin><version>{VERSION}</version></idea-plugin>"
        jar = BytesIO()
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("META-INF/plugin.xml", descriptor)
        with zipfile.ZipFile(self.plugin, "w") as z:
            z.writestr("platform-bookmarks-plugin/lib/platform-bookmarks-plugin.jar", jar.getvalue())
        with zipfile.ZipFile(self.sources, "w") as z:
            z.writestr("idea-bookmarks/plugin/plugin.xml", descriptor)
        self.reports = self.root / "reports"
        self.reports.mkdir()
        (self.reports / "SHA256SUMS").write_text("".join(f"{sha256(p)}  {p.name}\n" for p in (self.plugin, self.sources)))
        (self.reports / "build-report.json").write_text(json.dumps({"runtimeValidated": True, "sha256": sha256(self.plugin)}))
        self.runtime = {"result": "PASS", "checks": ["runtime check"], "pluginVersion": VERSION, "archiveSha256": sha256(self.plugin)}
        (self.reports / "runtime-validation.json").write_text(json.dumps(self.runtime))
        self.assets, _ = validate_assets(self.root, VERSION)
        self.notes = self.root / "notes.md"
        self.notes.write_text("release notes")
        self.uploaded = {"assets": [{"name": p.name, "size": p.stat().st_size, "digest": f"sha256:{sha256(p)}"} for p in self.assets]}
        self.published = {**self.uploaded, "draft": False, "html_url": "https://github.com/owner/repo/releases/tag/v" + VERSION}

    def call_publish(self, ref="refs/heads/main"):
        return publish("owner/repo", COMMIT, ref, self.assets, self.notes, VERSION)

    def test_verified_assets_are_selected(self):
        assets, runtime = validate_assets(self.root, VERSION)
        self.assertEqual(5, len(assets))
        self.assertEqual("PASS", runtime["result"])

    def test_corrupt_plugin_is_rejected(self):
        with self.plugin.open("ab") as stream:
            stream.write(b"corruption")
        with self.assertRaisesRegex(ValueError, "checksum"):
            validate_assets(self.root, VERSION)

    def test_failed_runtime_is_rejected(self):
        self.runtime["result"] = "FAIL"
        (self.reports / "runtime-validation.json").write_text(json.dumps(self.runtime))
        with self.assertRaisesRegex(ValueError, "runtime verification"):
            validate_assets(self.root, VERSION)

    def test_mismatched_tag_does_not_write(self):
        with patch("publish_release.gh") as command, patch("publish_release.api") as api:
            with self.assertRaisesRegex(ValueError, "Tag must match"):
                self.call_publish("refs/tags/v0.0.0")
            command.assert_not_called()
            api.assert_not_called()

    def test_published_version_is_never_overwritten(self):
        with patch("publish_release.find_release", return_value=self.published), patch("publish_release.gh") as command:
            self.assertEqual(self.published, self.call_publish())
            command.assert_not_called()

    def test_incomplete_published_release_is_not_overwritten(self):
        incomplete = {**self.published, "assets": []}
        with patch("publish_release.find_release", return_value=incomplete), patch("publish_release.gh") as command:
            with self.assertRaisesRegex(ValueError, "incomplete"):
                self.call_publish()
            command.assert_not_called()

    def test_existing_tag_cannot_be_retargeted(self):
        with patch("publish_release.find_release", return_value=None), patch("publish_release.api", side_effect=[{"object": {}}, {"sha": "b" * 40}]), patch("publish_release.gh") as command:
            with self.assertRaisesRegex(ValueError, "another commit"):
                self.call_publish()
            command.assert_not_called()

    def test_existing_untagged_draft_can_follow_verified_commit(self):
        draft = {"id": 123, "draft": True, "target_commitish": "b" * 40}
        with patch("publish_release.find_release", return_value=draft), patch("publish_release.api", side_effect=[None, self.uploaded, self.published]) as api, patch("publish_release.gh") as command:
            self.assertEqual(self.published, self.call_publish())
            self.assertEqual(["edit", "upload", "edit"], [call.args[1] for call in command.call_args_list])
            self.assertIn("--target", command.call_args_list[0].args)
            self.assertEqual("repos/owner/repo/releases/123", api.call_args_list[1].args[0])

    def test_draft_is_found_when_by_tag_endpoint_returns_not_found(self):
        draft = {"id": 123, "draft": True, "tag_name": f"v{VERSION}"}
        with patch("publish_release.api", side_effect=[None, [draft]]) as api:
            self.assertEqual(draft, find_release("owner/repo", f"v{VERSION}"))
            self.assertIn("releases?", api.call_args_list[1].args[0])

    def test_upload_digest_failure_leaves_release_unpublished(self):
        self.uploaded["assets"][0]["digest"] = "sha256:incorrect"
        draft = {"id": 123, "draft": True}
        with patch("publish_release.find_release", side_effect=[None, draft]), patch("publish_release.api", side_effect=[None, self.uploaded]), patch("publish_release.gh") as command:
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                self.call_publish()
            self.assertEqual(["create", "upload"], [call.args[1] for call in command.call_args_list])

    def test_new_release_is_published_only_after_asset_verification(self):
        draft = {"id": 123, "draft": True}
        with patch("publish_release.find_release", side_effect=[None, draft]), patch("publish_release.api", side_effect=[None, self.uploaded, self.published]) as api, patch("publish_release.gh") as command:
            self.assertEqual(self.published, self.call_publish())
            self.assertEqual(["create", "upload", "edit"], [call.args[1] for call in command.call_args_list])
            self.assertIn("--draft", command.call_args_list[0].args)
            self.assertIn("--draft=false", command.call_args_list[-1].args)
            self.assertEqual("repos/owner/repo/releases/123", api.call_args_list[1].args[0])


if __name__ == "__main__":
    unittest.main()
