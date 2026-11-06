"""Reconcile a changelog-only branch and PR without overwriting existing work."""
import json
import subprocess
import sys


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def reconcile(version):
    branch = f"changelog-update-{version}"
    run("git", "check-ref-format", "--branch", branch)
    base = run("git", "rev-parse", "HEAD")
    if run("git", "diff", "--name-only", "HEAD") not in ("", "CHANGELOG.md"):
        raise RuntimeError("Unexpected tracked changes; only CHANGELOG.md may be committed")
    if not run("git", "diff", "HEAD", "--", "CHANGELOG.md"):
        print("No changelog delta; nothing to commit")
        return

    remote = run("git", "ls-remote", "--heads", "origin", f"refs/heads/{branch}")
    if remote:
        run("git", "fetch", "--no-tags", "origin", f"refs/heads/{branch}")
        # Accept only the exact generated commit shape and tree. Extra commits or
        # different content may be human work: never reset or force-push them.
        parents = run("git", "show", "-s", "--format=%P", "FETCH_HEAD")
        changed = run("git", "diff", "--name-only", base, "FETCH_HEAD")
        delta = run("git", "diff", "FETCH_HEAD", "--", ".")
        if parents != base or changed != "CHANGELOG.md" or delta:
            raise RuntimeError(f"Existing {branch} differs from the generated changelog; "
                               "preserve it and reconcile manually before retrying")
        print(f"Reusing existing branch: {branch}")
    else:
        run("git", "config", "user.email", "action@github.com")
        run("git", "config", "user.name", "GitHub Action")
        run("git", "checkout", "-b", branch)
        run("git", "add", "--", "CHANGELOG.md")
        run("git", "commit", "-m", f"Changelog update - {version}")
        run("git", "push", "--set-upstream", "origin", branch)

    prs = json.loads(run("gh", "pr", "list", "--head", branch, "--state", "all",
                         "--json", "number,state"))
    if prs:
        if any(pr["state"] in ("OPEN", "MERGED") for pr in prs):
            print(f"Changelog PR already exists for {branch}")
            return
        raise RuntimeError(f"PR for {branch} was closed; reopen or reconcile it manually")
    label = "release changelog"
    run("gh", "label", "create", label, "--description",
        "Pull requests with release changelog update", "--force")
    run("gh", "pr", "create", "--title", f"Changelog update - `{version}`",
        "--body", f"Current pull request contains patched `CHANGELOG.md` file for the `{version}` version.",
        "--label", label, "--head", branch)


if __name__ == "__main__":
    reconcile(sys.argv[1])
