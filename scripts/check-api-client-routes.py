#!/usr/bin/env python3
"""
Check that the URL each wrapper of api-client.js calls truly has a backend endpoint.

Caller to backend route (see the MainActivity dispatch + the app-core.js helper):
  api('X')        -> /api/alpha2/X   -> handleApi case X
  sysApi('X')     -> /api/system/X   -> handleSystemApi case X
  directApi('X')  -> /api/direct/X   -> handleDirectApi case X
  xiaozhiApi('X') -> /api/xiaozhi/X  -> XiaozhiBridge.handleXiaozhiApi case X

The generator once emitted a nonsensical `api('system/...')` -> the call went to
/api/alpha2/system/... -> handleApi 404, and the drift check could not see it
(it only compares the code cases to the spec). This script adds back the client to server segment.

Usage: python scripts/check-api-client-routes.py (suitable for CI)
Returns 0 = all correct, 1 = an error is present.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "MainActivity.java"
XZ = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "XiaozhiBridge.java"
DISP = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "ApiDispatcher.java"
CLIENT = ROOT / "app" / "src" / "main" / "assets" / "web" / "api-client.js"

text = MAIN.read_text(encoding="utf-8")
xztext = XZ.read_text(encoding="utf-8")
disptext = DISP.read_text(encoding="utf-8")


def handler_cases(name: str, src: str = text, tail: str = r"\n    (?:private|public) ") -> set:
    """Extract all case "..." values (bare paths) inside a handle*Api() method body."""
    m = re.search(
        r"(?:private|public) HttpServer\.ApiResponse " + name + r"\(.*?\)\s*\{(.*?)" + tail,
        src, re.DOTALL)
    assert m, f"{name} body not found"
    return set(re.findall(r'case "([^"]+)"', m.group(1)))


cases = {
    # handleApi moved in whole to ApiDispatcher (end of file, the last method).
    "api": handler_cases("handleApi", disptext, tail=r"\n\}\s*$"),
    # handleSystemApi/directApi followed (system in the middle, direct at the end of file).
    "sysApi": handler_cases("handleSystemApi", disptext),
    "directApi": handler_cases("handleDirectApi", disptext, tail=r"\n\}\s*$"),
    "xiaozhiApi": handler_cases("handleXiaozhiApi", xztext),
}

client = CLIENT.read_text(encoding="utf-8")
# 2026-09-09: single and double quotes both count (before only single quotes; a hand-written double quote was a missed check).
calls = re.findall(r"return (api|sysApi|directApi|xiaozhiApi)\([\"']([^\"']+)[\"'], params\)", client)
assert calls, "not a single wrapper call found in api-client.js"

bad = []
for caller, rel in calls:
    if rel not in cases[caller]:
        bad.append(f"{caller}('{rel}') has no matching backend case")

print(f"client calls: {len(calls)}  api={sum(1 for c, _ in calls if c == 'api')} "
      f"sys={sum(1 for c, _ in calls if c == 'sysApi')} "
      f"direct={sum(1 for c, _ in calls if c == 'directApi')} "
      f"xiaozhi={sum(1 for c, _ in calls if c == 'xiaozhiApi')}")
if bad:
    print("❌ misrouted (the client has it, the backend does not):")
    for b in bad:
        print(f"  - {b}")
    sys.exit(1)
print("✅ all wrapper routes correct")
