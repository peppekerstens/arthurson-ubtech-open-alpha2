#!/usr/bin/env python3
"""
check-openapi workflow trigger guard (zero deps beyond stdlib + pyyaml).

arthurson-ubtech-open-alpha2#159, finding 12: `app/src/test/**` was listed
twice in the push paths filter and twice in the pull_request paths filter.
The two blocks drift from each other, and a duplicated glob is a sign that a
copy-paste drifted again. This guard pins one `app/src/test/**` entry per
block so a re-duplication fails in CI.

Usage: python scripts/test-check-openapi-workflow.py   # exit 1 on drift (CI-safe)
"""
import pathlib
import sys

import yaml12  # YAML 1.2 booleans: the `on` key stays a string (alpha2-brain#29)

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "check-openapi.yml"

TEST_GLOB = "app/src/test/**"


def main() -> int:
    assert WORKFLOW.exists(), f"workflow file missing: {WORKFLOW}"
    doc = yaml12.load(WORKFLOW.read_text(encoding="utf-8"))
    on = doc["on"]

    failures = []
    for trigger in ("push", "pull_request"):
        paths = on[trigger]["paths"]
        count = paths.count(TEST_GLOB)
        if count != 1:
            failures.append(f"{trigger}.paths lists {TEST_GLOB} {count} times (want 1)")

    if failures:
        for line in failures:
            print(f"FAIL: {line}")
        return 1
    print(f"PASS: {TEST_GLOB} appears once in each of push and pull_request")
    return 0


if __name__ == "__main__":
    sys.exit(main())
