#!/usr/bin/env python3
"""
Check that the path/wrapper counts in README.md match the real sources.

Background (arthurson-ubtech-open-alpha2#159, D-168 rule 6, actuality):
the README stated 223 paths / 213 `/api/*` / 213 wrappers and a stale
family list. The counts must stay in sync with the spec
(openapi/open-alpha2-openapi.yml) and the generated client
(app/src/main/assets/web/api-client.js).

Counts checked:
- spec path count (all paths, one operation each)
- `/api/*` path count
- wrapper count in api-client.js (the `return { ... };` export object,
  excluding the `hw` legacy alias)
- every `family (N)` entry in the README family list (the `/api/*` bullet
  plus the passthrough families `direct`/`system`/`xiaozhi`)

Usage: python scripts/check-readme-api-counts.py
Returns 0 = counts match, 1 = drift (suitable for CI).
"""
import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi" / "open-alpha2-openapi.yml"
CLIENT = ROOT / "app" / "src/main/assets/web/api-client.js"
README = ROOT / "README.md"

def spec_paths():
    text = SPEC.read_text(encoding="utf-8")
    # top-level path keys: two-space indent, trailing colon.
    # The root path is the bare key "/" (not "/..."), so match "/<chars>" or "/".
    return [m[:-1] for m in re.findall(r"^  (/(?:\S+)?):\s*$", text, re.M)]

def client_wrappers():
    text = CLIENT.read_text(encoding="utf-8")
    m = re.search(r"return \{(.+?)\};", text, re.S)
    if not m:
        return None
    names = re.findall(r"^\s*([a-z][a-zA-Z0-9]+),\s*$", m.group(1), re.M)
    return [n for n in names if n != "hw"]  # hw is a legacy alias, not a wrapper

def fam_counts(paths):
    fam = collections.Counter()
    for p in paths:
        if not p.startswith("/api/"):
            continue
        fam[p[len("/api/"):].split("/")[0]] += 1
    return fam

def main():
    errors = []
    paths = spec_paths()
    total = len(paths)
    api = [p for p in paths if p.startswith("/api/")]
    fam = fam_counts(paths)
    wrappers = client_wrappers()
    readme = README.read_text(encoding="utf-8")

    # 1. headline counts: the bold total paths, the /api/* bullet, the bold wrappers
    if not re.search(r"\*\*%d paths\*\*" % total, readme):
        errors.append("README missing bold '**%d paths**' (spec has %d)" % (total, total))
    m = re.search(r"/api/\*` — main robot API, (\d+) paths", readme)
    if not m:
        errors.append("README missing the '/api/* — main robot API, N paths' line")
    elif int(m.group(1)) != len(api):
        errors.append("README says %d /api/* paths, spec has %d" % (int(m.group(1)), len(api)))
    nwrap = len(wrappers) if wrappers else -1
    if not re.search(r"\*\*%d typed\s+wrappers\*\*" % nwrap, readme):
        errors.append("README missing bold wrapper count '**%d typed wrappers**'" % nwrap)

    # 2. every family (N) entry in the README family list
    for name, n in re.findall(r"`([a-z_]+)` \((\d+)\)", readme):
        if name in fam and int(n) != fam[name]:
            errors.append("README family `%s` count %d, spec has %d" % (name, int(n), fam[name]))

    # 3. wrapper count must match the /api/* count (one wrapper per path)
    if wrappers is None:
        errors.append("api-client.js: could not locate the export object")
    elif len(wrappers) != len(api):
        errors.append("client has %d wrappers, spec has %d /api/* paths" % (len(wrappers), len(api)))

    if errors:
        for e in errors:
            print("DRIFT:", e)
        print("FAIL: %d drift error(s)" % len(errors))
        return 1
    print("OK: %d paths, %d /api/* paths, %d wrappers, family list in sync"
          % (total, len(api), len(wrappers)))
    return 0

if __name__ == "__main__":
    sys.exit(main())
