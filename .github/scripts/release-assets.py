"""Release mutation limited to a verified source commit and digest-checked assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def releases():
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", "repos/{owner}/{repo}/releases"))
    return [release for page in pages for release in page]


def verify_tag(version, sha):
    # ls-remote does not mutate local refs; the peeled ref handles annotated tags.
    refs = dict(line.split()[::-1] for line in run(
        "git", "ls-remote", "origin", f"refs/tags/{version}", f"refs/tags/{version}^{{}}"
    ).splitlines())
    target = refs.get(f"refs/tags/{version}^{{}}", refs.get(f"refs/tags/{version}"))
    if target is not None and target != sha:
        raise RuntimeError(f"Tag {version} resolves to {target}, expected {sha}")
    return target


def upload(version, files, release):
    if not files:
        raise RuntimeError("No release ZIPs found")
    assets = {asset["name"]: asset for asset in release["assets"]}
    for path in files:
        if path.name in assets:
            # Download rather than trust optional API digest metadata (older assets lack it).
            with tempfile.TemporaryDirectory() as directory:
                run("gh", "release", "download", version, "--pattern", path.name, "--dir", directory)
                existing = Path(directory, path.name).read_bytes()
            if hashlib.sha256(existing).digest() != hashlib.sha256(path.read_bytes()).digest():
                raise RuntimeError(f"Existing release asset differs: {path.name}")
            print(f"Verified existing asset: {path.name}")
        else:
            run("gh", "release", "upload", version, str(path))
            print(f"Uploaded: {path.name}")


def draft(version, sha, notes, files):
    tag = verify_tag(version, sha)
    release = next((r for r in releases() if r["tag_name"] == version), None)
    marker = f"<!-- validated-source: {sha} -->"
    if release:
        if not release["draft"]:
            raise RuntimeError(f"Release {version} is already published")
        if tag is None and release["target_commitish"] != sha:
            raise RuntimeError("Existing untagged draft is not bound to the validated commit")
        # Do not PATCH existing notes or DELETE any drafts.
    else:
        run("gh", "release", "create", version, "--draft", "--target", sha,
            "--title", version, "--notes", notes.read_text() + "\n\n" + marker)
        release = {"assets": []}
    upload(version, files, release)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["draft", "signed"])
    parser.add_argument("version")
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    distribution = Path("build/distributions")
    if args.mode == "draft":
        draft(args.version, os.environ["GITHUB_SHA"], args.notes,
              sorted(distribution.glob("*.zip")))
    else:
        release = next(r for r in releases() if r["tag_name"] == args.version)
        upload(args.version, sorted(distribution.glob("*-signed.zip")), release)


if __name__ == "__main__":
    main()
