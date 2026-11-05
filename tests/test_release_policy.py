"""Exercise release tag validation against real Git history, without remote writes."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from release_policy import require_version_tag, validate_checkout

VERSION = "262.11879.100.7"
REF = f"refs/tags/v{VERSION}"


class TagPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet", "--initial-branch=main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "initial")
        self.target = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", self.target)

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True, stderr=subprocess.PIPE).strip()

    def test_only_exact_version_ref_is_allowed(self):
        self.assertEqual(f"v{VERSION}", require_version_tag(REF, VERSION))
        for ref in ("", "refs/heads/main", "refs/tags/v0", "refs/tags/" + VERSION):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                require_version_tag(ref, VERSION)

    def test_matching_lightweight_tag_on_main_is_allowed(self):
        self.git("tag", f"v{VERSION}")
        validate_checkout(REF, VERSION, self.target, self.root)

    def test_matching_annotated_tag_on_main_is_allowed(self):
        self.git("-c", "tag.gpgsign=false", "tag", "-a", f"v{VERSION}", "-m", "version")
        validate_checkout(REF, VERSION, self.target, self.root)

    def test_missing_tag_cannot_publish(self):
        with self.assertRaises(subprocess.CalledProcessError):
            validate_checkout(REF, VERSION, self.target, self.root)

    def test_tag_on_unmerged_feature_commit_is_rejected(self):
        self.git("checkout", "-q", "-b", "feature/example")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "feature")
        self.git("tag", f"v{VERSION}")
        with self.assertRaisesRegex(ValueError, "merged into origin/main"):
            validate_checkout(REF, VERSION, self.git("rev-parse", "HEAD"), self.root)

    def test_checkout_and_tag_must_match_target(self):
        self.git("tag", f"v{VERSION}")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "new commit")
        with self.assertRaisesRegex(ValueError, "exact checked-out"):
            validate_checkout(REF, VERSION, self.git("rev-parse", "HEAD"), self.root)

    def test_older_merged_commit_can_be_released(self):
        self.git("tag", f"v{VERSION}")
        self.git("-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "later main")
        self.git("update-ref", "refs/remotes/origin/main", self.git("rev-parse", "HEAD"))
        self.git("checkout", "-q", self.target)
        validate_checkout(REF, VERSION, self.target, self.root)


if __name__ == "__main__":
    unittest.main()
