#!/usr/bin/env python3
"""
Consistency check of the web-side spec copies (openapi/ is the single source of truth).

Background: the browser takes the spec from the same machine -- `/openapi.yml` (docs UI + apis.yml +
llms.txt), `/.well-known/openapi.json` (the docs page link, for AI discovery),
`/asyncapi.yml` (apis.yml + llms.txt). The copies under `app/src/main/assets/web/`
are copies of `openapi/` (plus a dotless `well-known/` version, kept in case the surface needs it).
Before, the sync was all by hand -- the asyncapi copy once kept dead binder events
(asr_result/speech_ready) after the source was cleaned, and nobody noticed.


Rules:
- A yml copy must be byte-identical to the source (a hand-edited copy is drift).
- A json copy must be semantically equal to the source after parsing (a format conversion; a byte-by-byte comparison is not possible).


Usage:
  python scripts/check-spec-copies.py         # check; drift is exit 1 (suitable for CI)
  python scripts/check-spec-copies.py --fix   # byte-copy the source over the drifted
                                              # yml copies (the json format settings of the original producer are
                                              # unknown; do not touch, report only)

"""
import difflib
import json
import pathlib
import sys

import yaml

import yaml12  # YAML 1.2 booleans: "on" stays a string (alpha2-brain#29)

ROOT = pathlib.Path(__file__).resolve().parents[1]
WEB = ROOT / "app" / "src" / "main" / "assets" / "web"
SPEC = ROOT / "openapi"

# (source, copy): yml compared byte by byte
YML_PAIRS = [
    (SPEC / "open-alpha2-openapi.yml", WEB / "openapi.yml"),
    (SPEC / "open-alpha2-openapi.yml", WEB / ".well-known" / "openapi.yml"),
    (SPEC / "open-alpha2-openapi.yml", WEB / "well-known" / "openapi.yml"),
    (SPEC / "asyncapi.yml", WEB / "asyncapi.yml"),
]

# (source yml, copy json): compared after parsing
JSON_PAIRS = [
    (SPEC / "open-alpha2-openapi.yml", WEB / ".well-known" / "openapi.json"),
    (SPEC / "open-alpha2-openapi.yml", WEB / "well-known" / "openapi.json"),
]


def check_yml(src: pathlib.Path, dst: pathlib.Path) -> bool:
    a = src.read_bytes()
    if not dst.exists():
        print(f"DIFF {dst.relative_to(ROOT)}: missing")
        return False
    b = dst.read_bytes()
    if a == b:
        print(f"OK   {dst.relative_to(ROOT)}")
        return True
    print(f"DIFF {dst.relative_to(ROOT)} (source {src.relative_to(ROOT)}):")
    try:
        diff = difflib.unified_diff(
            a.decode("utf-8").splitlines(), b.decode("utf-8").splitlines(),
            fromfile="spec", tofile="copy", lineterm="", n=1)
        shown = 0
        for line in diff:
            print("    " + line)
            shown += 1
            if shown >= 30:
                print("    ... (truncated)")
                break
    except UnicodeDecodeError:
        print("    (not UTF-8; no diff shown)")
    return False


def check_json(src: pathlib.Path, dst: pathlib.Path) -> bool:
    want = yaml12.load(src.read_text(encoding="utf-8"))
    if not dst.exists():
        print(f"DIFF {dst.relative_to(ROOT)}: missing")
        return False
    got = json.loads(dst.read_text(encoding="utf-8"))
    if want == got:
        print(f"OK   {dst.relative_to(ROOT)} (semantic)")
        return True
    print(f"DIFF {dst.relative_to(ROOT)} (semantic, not equal to the source)")
    if isinstance(want, dict) and isinstance(got, dict):
        for k in sorted(set(want) | set(got)):
            if want.get(k) != got.get(k):
                print(f"    top-level key differs: {k}")
    return False


def main() -> int:
    fix = "--fix" in sys.argv[1:]
    bad_yml = []
    for src, dst in YML_PAIRS:
        assert src.exists(), f"source gone: {src}"
        if not check_yml(src, dst):
            bad_yml.append((src, dst))
    bad_json = []
    for src, dst in JSON_PAIRS:
        if not check_json(src, dst):
            bad_json.append((src, dst))
    if fix and bad_yml:
        for src, dst in bad_yml:
            dst.write_bytes(src.read_bytes())
            print(f"FIXED {dst.relative_to(ROOT)} <= {src.relative_to(ROOT)}")
        bad_yml = []
    if fix and bad_json:
        print("the json drift does not know the format settings of the original producer; no automatic fix, handle by hand")
    if bad_yml or bad_json:
        print(f"FAIL: yml {len(bad_yml)} places, json {len(bad_json)} places")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
