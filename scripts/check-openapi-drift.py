#!/usr/bin/env python3
"""
Check the code to spec drift: make sure that the case names of
handleApi/handleSystemApi/handleDirectApi/handleXiaozhiApi (the first three
in ApiDispatcher, the last one in XiaozhiBridge) all have a counterpart in
the openapi.
Usage: python scripts/check-openapi-drift.py
Returns 0 = no drift, 1 = drift present (suitable for CI).

2026-09 rewrite: the old version threw all four handler case names into one
flat set before comparing to the spec, and applied the "direct/" prefix
mapping only to handleDirectApi -- the handleSystemApi cases (such as
"discover", "music/seek") did not get the "system/" prefix, the
handleXiaozhiApi cases (such as "connect", "activation_status") did not get
the "xiaozhi/" prefix, and when handleApi and handleDirectApi had a same
bare case name (such as "servo/one"), the flat set mixed the cases of the
two handlers and one of them was then overwritten by mistake. These gaps
did not make the CI fail (a missed report, not an over-report), but they
produced a large number of false "extra" entries and lowered the confidence
in a manual check. So here each handler is extracted on its own, each case
gets back its correct namespace prefix, and the merged set is then compared
to the spec. This also adds a separate extraction for handleUpload/
handleStream (MainActivity, an "xxx".equals(path) if/else chain, not a
switch case) -- the old version did not scan these two methods at all, so
"upload/*" and "stream/*" were always reported as false "extra".
"""
import pathlib, re, yaml, sys
import yaml12  # YAML 1.2 booleans (alpha2-brain#29)

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi" / "open-alpha2-openapi.yml"
DISP = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "ApiDispatcher.java"
XZ = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "XiaozhiBridge.java"
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "MainActivity.java"

spec = yaml12.load(open(SPEC, encoding="utf-8"))
# The spec paths are all "/api/..." or "/upload/..." / "/stream/..." or "/", "/blockly.html";
# strip the "/api/" prefix and the leading slash of "/upload" and "/stream", to make the comparison with the case names (which have no "api/" prefix) easy.
spec_norm = set()
for p in spec['paths'].keys():
    q = p.lstrip('/')
    if q.startswith('api/'):
        spec_norm.add(q[len('api/'):])
    elif q.startswith('upload/') or q.startswith('stream/'):
        spec_norm.add(q)
    # "" (the root "/") and "blockly.html" have no matching case; skip.


def extract_method_body(text: str, method_name: str) -> str:
    """Find the content from `<method_name>(...)  {` to the next public/private method at the same indent or the end of the file."""
    m = re.search(
        r'(?:private|public)\s+HttpServer\.ApiResponse\s+' + re.escape(method_name)
        + r'\(.*?\)\s*\{(.*?)(?:\n    (?:private|public) |\n\}\s*$)',
        text, re.DOTALL)
    if not m:
        raise SystemExit(f"❌ method body not found: {method_name}() -- the drift script is out of step with the code structure; check by hand")
    return m.group(1)


def cases_in(body: str) -> set:
    return set(re.findall(r'case "([^"]+)"', body))


disp_text = DISP.read_text(encoding='utf-8')
xz_text = XZ.read_text(encoding='utf-8')
main_text = MAIN.read_text(encoding='utf-8')

# Extract each handler on its own, and add back the namespace prefix that matches the spec:
# - handleApi: the case names already match the spec "api/*" after the skin is stripped; no prefix.
# - handleSystemApi: the spec is "api/system/*", the case here has no "system/"; add it.
# - handleDirectApi: the spec is "api/direct/*", the case here has no "direct/"; add it.
# - handleXiaozhiApi: the spec is "api/xiaozhi/*", the case here has no "xiaozhi/"; add it.
handlers = [
    ("handleApi", disp_text, ""),
    ("handleSystemApi", disp_text, "system/"),
    ("handleDirectApi", disp_text, "direct/"),
    ("handleXiaozhiApi", xz_text, "xiaozhi/"),
]

# A few cases inside handleApi have no "/" (such as "status"); a hand whitelist must treat them as API paths first.
# The other cases with no "/" (preset values such as "flash" / "breathe") do not count. This whitelist
# applies to handleApi only (prefix==""): once the cases of the other three handlers get their correct
# namespace prefix ("system/", "direct/", "xiaozhi/"), they already carry a "/", so this
# whitelist built for handleApi must not be applied again (it once filtered out bare name cases
# such as "discover" and "connect" by mistake, turning them into false "extra").
BARE_SINGLETONS = {"status", "supported", "mic/start", "mic/stop"}

api_cases = set()
per_handler_cases = {}
for name, text, prefix in handlers:
    body = extract_method_body(text, name)
    raw = cases_in(body)
    if prefix == "":
        kept = set(c for c in raw if '/' in c or c in BARE_SINGLETONS)
    else:
        # A handler with a prefix: filter out only the bare values that truly are not paths (some cases
        # are parameter enums, not endpoints) -- no such handler has been seen so far,
        # so keep all bare names, treat them all as paths once the prefix is added, and let the
        # later step that compares to the spec decide whether there is drift.
        kept = raw
    prefixed = set(prefix + c for c in kept)
    per_handler_cases[name] = prefixed
    api_cases |= prefixed

# handleUpload/handleStream route in MainActivity with an "xxx".equals(path) if/else
# chain (not a switch case); the structure differs from the four handlers above, so extract them separately.
# In the spec these two path groups use the flat names "upload/audio", "stream/camera"
# (no "api/" prefix), which match the literals compared in the two methods of MainActivity.
def extract_equals_branches(text: str, method_name: str) -> set:
    m = re.search(
        r'(?:private|public)\s+\S+\s+' + re.escape(method_name)
        + r'\(.*?\)[^{]*\{(.*?)(?:\n    (?:private|public) |\n\}\s*$)',
        text, re.DOTALL)
    if not m:
        raise SystemExit(f"❌ method body not found: {method_name}() -- the drift script is out of step with the code structure; check by hand")
    return set(re.findall(r'"([^"]+)"\.equals\(path\)', m.group(1)))


upload_cases = set('upload/' + c for c in extract_equals_branches(main_text, "handleUpload"))
stream_cases = set('stream/' + c for c in extract_equals_branches(main_text, "handleStream"))
per_handler_cases["handleUpload"] = upload_cases
per_handler_cases["handleStream"] = stream_cases
api_cases |= upload_cases | stream_cases

missing = sorted(api_cases - spec_norm)
extra = sorted(spec_norm - api_cases)

print(f"spec paths (api/upload/stream): {len(spec_norm)}  code api_cases: {len(api_cases)}")
for name, _, prefix in handlers:
    print(f"  {name}: {len(per_handler_cases[name])} cases (prefix '{prefix}')")
print(f"  handleUpload: {len(per_handler_cases['handleUpload'])} cases (prefix 'upload/')")
print(f"  handleStream: {len(per_handler_cases['handleStream'])} cases (prefix 'stream/')")

if missing:
    print("❌ missing in spec (code has, spec lacks):")
    for x in missing:
        print(f"  - {x}")
else:
    print("✅ no missing in spec")

if extra:
    print("❌ extra in spec (spec has, code lacks):")
    for x in extra:
        print(f"  - {x}")
else:
    print("✅ no extra")

if missing or extra:
    sys.exit(1)
sys.exit(0)
