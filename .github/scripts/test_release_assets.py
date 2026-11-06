"""Execute the release helper against fake gh/git commands; never contact GitHub."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HELPER = Path(__file__).with_name("release-assets.py").resolve()
FAKE_CLI = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
state_path = pathlib.Path(os.environ["RELEASE_TEST_STATE"])
s = json.loads(state_path.read_text())
a = sys.argv[1:]
s["calls"].append([pathlib.Path(sys.argv[0]).name] + a)
if pathlib.Path(sys.argv[0]).name == "git":
    if s.get("tag"):
        print(s["tag"] + "\trefs/tags/1.2.3")
        if s.get("peeled_tag"):
            print(s["peeled_tag"] + "\trefs/tags/1.2.3^{}")
elif a[0] == "api":
    print(json.dumps([s["releases"]]))
elif a[:2] == ["release", "create"]:
    s["releases"].append({"tag_name": a[2], "draft": True,
        "target_commitish": a[a.index("--target") + 1],
        "body": a[a.index("--notes") + 1], "assets": []})
elif a[:2] == ["release", "upload"]:
    r = next(r for r in s["releases"] if r["tag_name"] == a[2])
    p = pathlib.Path(a[3])
    assert p.name not in s["asset_data"], "Duplicate upload"
    s["asset_data"][p.name] = p.read_text()
    r["assets"].append({"name": p.name})
elif a[:2] == ["release", "download"]:
    name = a[a.index("--pattern") + 1]
    pathlib.Path(a[a.index("--dir") + 1], name).write_text(s["asset_data"][name])
else:
    raise AssertionError(a)
state_path.write_text(json.dumps(s))
'''


class ReleaseCommandsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("gh", "git"):
            path = self.bin / name
            path.write_text(FAKE_CLI)
            path.chmod(0o755)
        self.state_path = self.root / "state.json"
        self.state = {"calls": [], "releases": [], "asset_data": {}}
        self.distributions = self.root / "build/distributions"
        self.distributions.mkdir(parents=True)
        (self.distributions / "plugin.zip").write_text("unsigned")
        (self.root / "notes").write_text("Generated notes")
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        RELEASE_TEST_STATE=str(self.state_path), GITHUB_SHA="a" * 40)

    def invoke(self, mode="draft", succeeds=True):
        self.state_path.write_text(json.dumps(self.state))
        args = [sys.executable, str(HELPER), mode, "1.2.3"]
        if mode == "draft":
            args += ["--notes", "notes"]
        result = subprocess.run(args, cwd=self.root, env=self.env, capture_output=True, text=True)
        self.state = json.loads(self.state_path.read_text())
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)
        return result

    def test_create_targets_workflow_sha_and_preserves_manual_draft(self):
        manual = {"tag_name": "manual", "draft": True, "body": "Human notes", "assets": []}
        self.state["releases"] = [manual]
        self.invoke()
        self.assertEqual(self.state["releases"][0], manual)
        calls = self.state["calls"]
        create = next(c for c in calls if c[1:3] == ["release", "create"])
        self.assertEqual(create[create.index("--target") + 1], "a" * 40)
        self.assertFalse(any("DELETE" in c or "PATCH" in c for c in calls))

    def test_mismatched_tag_fails_before_release_mutation(self):
        self.state["tag"] = "b" * 40
        self.invoke(succeeds=False)
        self.assertEqual(len(self.state["calls"]), 1)
        self.assertEqual(self.state["releases"], [])

    def test_annotated_tag_uses_peeled_commit(self):
        self.state["tag"] = "c" * 40
        self.state["peeled_tag"] = "a" * 40
        self.invoke()
        self.assertEqual(len(self.state["releases"]), 1)

    def test_annotated_mismatched_tag_is_rejected(self):
        self.state["tag"] = "a" * 40
        self.state["peeled_tag"] = "b" * 40
        self.invoke(succeeds=False)
        self.assertEqual(len(self.state["calls"]), 1)

    def test_existing_draft_preserves_notes_and_unrelated_draft(self):
        self.invoke()
        self.state["releases"][0]["body"] = "Edited by a human"
        self.state["releases"].append({"tag_name": "other", "draft": True, "body": "Other notes"})
        before = self.state["releases"].copy()
        self.invoke()
        self.assertEqual(self.state["releases"], before)
        self.assertEqual(sum(c[1:3] == ["release", "create"] for c in self.state["calls"]), 1)

    def test_unbound_existing_draft_is_rejected(self):
        self.state["releases"] = [{"tag_name": "1.2.3", "draft": True,
                                   "target_commitish": "main", "assets": []}]
        self.invoke(succeeds=False)
        self.assertFalse(any(c[1:3] == ["release", "upload"] for c in self.state["calls"]))

    def test_signed_upload_ignores_existing_unsigned_and_is_repeatable(self):
        self.invoke()
        # Rebuilt unsigned ZIP can differ: only the signed ZIP is a publication output.
        (self.distributions / "plugin.zip").write_text("rebuilt unsigned")
        (self.distributions / "plugin-signed.zip").write_text("signed")
        self.state["calls"] = []
        self.invoke("signed")
        self.invoke("signed")
        uploads = [c for c in self.state["calls"] if c[1:3] == ["release", "upload"]]
        self.assertEqual(len(uploads), 1)
        self.assertTrue(uploads[0][4].endswith("-signed.zip"))
        self.assertFalse(any("publishPlugin" in c for c in self.state["calls"]))

    def test_existing_asset_digest_mismatch_fails(self):
        self.invoke()
        (self.distributions / "plugin.zip").write_text("different")
        result = self.invoke(succeeds=False)
        self.assertIn("Existing release asset differs", result.stderr)

    def test_missing_signed_zip_fails(self):
        self.invoke()
        self.invoke("signed", succeeds=False)


if __name__ == "__main__":
    unittest.main()
