"""Require the unprefixed release tag used by the draft workflow."""
from pathlib import Path
import re
import sys


def main():
    versions = re.findall(r"^pluginVersion\s*=\s*(\S+)\s*$",
                          Path("gradle.properties").read_text(), re.MULTILINE)
    if len(versions) != 1 or sys.argv[1:] != versions:
        raise SystemExit("Release tag must equal pluginVersion in gradle.properties "
                         "(without a v prefix)")


if __name__ == "__main__":
    main()
