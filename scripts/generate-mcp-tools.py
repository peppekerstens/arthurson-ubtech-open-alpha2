#!/usr/bin/env python3
"""
Generate MCP tool definitions from the OpenAPI spec + the sync table (v2 fully automatic generation).

Sources:
  openapi/open-alpha2-openapi.yml (parameter constraints: type/enum/minimum/maximum/default)
  openapi/mcp-openapi-sync.yml     (tool list: description/alias/required/mcp_only)

Output:
  app/src/main/java/com/open/alpha2/McpToolsGenerated.java
  - buildTools() returns a JSONArray, each element {name, description, inputSchema}
  - XiaozhiBridge.xiaozhiMcpBridge().listTools() calls it directly; no hand-written org.json
  - the callTool() dispatch logic stays hand-written (the fuzzy match / the hardware direct drive do not derive from the spec)

Usage:
  python scripts/generate-mcp-tools.py           # write the file
  python scripts/generate-mcp-tools.py --check    # CI: compare the committed file; drift is exit 1
"""
import argparse
import pathlib
import sys

import yaml

import yaml12  # YAML 1.2 booleans: "on" stays a string (alpha2-brain#29)

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi" / "open-alpha2-openapi.yml"
SYNC = ROOT / "openapi" / "mcp-openapi-sync.yml"
OUT = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "McpToolsGenerated.java"

VALID_TYPES = {"string", "integer", "boolean", "number"}


def resolve_params(params, spec):
    out = []
    for p in params or []:
        if "$ref" in p:
            ref = p["$ref"]
            parts = ref.lstrip("#/").split("/")
            cur = spec
            for part in parts:
                cur = cur[part]
            out.append(cur)
        else:
            out.append(p)
    return out


def java_str(s: str) -> str:
    # Java double-quote string literal escaping (the file is UTF-8 itself; Chinese stays as-is)
    return '"' + (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", '\\n" +\n        "')
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    ) + '"'


def java_literal(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        if v.is_integer():
            return str(int(v))
        return repr(v)
    return java_str(str(v))


def load_spec_op(spec, path, method):
    ops = spec.get("paths", {}).get(path)
    if ops is None:
        raise SystemExit(f"❌ sync points to a missing openapi path: {path}")
    op = ops.get(method.lower())
    if op is None:
        avail = [k for k in ops.keys() if isinstance(k, str)]
        raise SystemExit(f"❌ {path} has no method {method} (the spec has: {avail})")
    return op


def build_model():
    with open(SPEC, encoding="utf-8") as f:
        spec = yaml12.load(f)
    with open(SYNC, encoding="utf-8") as f:
        sync = yaml12.load(f)
    if str(sync.get("sync_version", "")).split(".")[0] != "2":
        raise SystemExit(f"❌ the mcp-openapi-sync.yml sync_version must be 2.x (for automatic generation); it is {sync.get('sync_version')}")
    mappings = sync.get("mappings", [])
    seen = set()
    tools = []
    for m in mappings:
        name = m["mcp"]
        if name in seen:
            raise SystemExit(f"❌ duplicate mcp name: {name}")
        seen.add(name)
        openapi_path = m.get("openapi")
        method = (m.get("method") or "GET").lower()
        desc = (m.get("description") or "").strip()
        if not desc:
            raise SystemExit(f"❌ {name} has no description (the LLM-facing text must sit in the sync file)")
        props = []  # list of {key,type,desc,enum,minimum,maximum,default,required}
        required = []
        if openapi_path:
            op = load_spec_op(spec, openapi_path, method)
            spec_params = {str(p["name"]): p for p in resolve_params(op.get("parameters", []), spec)}
            whitelist = m.get("params", []) or []
            wl_openapi_names = set()
            for e in whitelist:
                mcp_n = e["mcp"]
                o_n = e.get("openapi", mcp_n)
                wl_openapi_names.add(o_n)
                if o_n not in spec_params:
                    raise SystemExit(f"❌ {name}: the openapi parameter '{o_n}' is not in the spec {openapi_path} (renamed in the spec?)")
                sp = spec_params[o_n]
                schema = sp.get("schema", {}) or {}
                spec_type = schema.get("type", "string")
                # type: the sync may override; otherwise it inherits from the spec. Both present and different is an error
                sync_type = e.get("type")
                eff_type = sync_type or spec_type
                if sync_type and sync_type != spec_type:
                    raise SystemExit(
                        f"❌ {name}.{mcp_n}: the sync type {sync_type} != the spec type {spec_type} (the spec changed the type? update the sync first)")
                if eff_type not in VALID_TYPES:
                    raise SystemExit(f"❌ {name}.{mcp_n}: unsupported type {eff_type}")
                eff_desc = e.get("desc", sp.get("description", "") or "")
                # enum/minimum/maximum/default: the sync override wins; otherwise inherit from the spec
                eff_enum = e.get("enum", schema.get("enum"))
                eff_min = e.get("minimum", schema.get("minimum"))
                eff_max = e.get("maximum", schema.get("maximum"))
                eff_default = e.get("default", schema.get("default", None))
                is_req = bool(e.get("required", False))
                props.append({
                    "key": mcp_n, "type": eff_type,
                    "desc": str(eff_desc or ""),
                    "enum": list(eff_enum) if eff_enum is not None else None,
                    "minimum": eff_min, "maximum": eff_max, "default": eff_default,
                })
                if is_req:
                    required.append(mcp_n)
            # exclude must name a parameter that the spec truly has and the whitelist does not have (or it is stale)
            for ex in (m.get("exclude") or []):
                if ex not in spec_params:
                    raise SystemExit(f"❌ {name}: the exclude '{ex}' is not in the spec {openapi_path} (deleted? update the sync)")
                if ex in wl_openapi_names:
                    raise SystemExit(f"❌ {name}: the exclude '{ex}' is also in the params whitelist")
            # In the spec, not in the whitelist, and not in exclude = a miss. Error out and force a decision (add to the whitelist or add to exclude)
            uncovered = [k for k in spec_params.keys()
                         if k not in wl_openapi_names and k not in set(m.get("exclude") or [])]
            if uncovered:
                raise SystemExit(
                    f"❌ {name}: the spec {openapi_path} added the parameter {uncovered} and the sync has no decision (add to params or to exclude)")
        else:
            # openapi:null: the params must be all mcp_only style (no openapi counterpart) or empty
            for e in (m.get("params") or []):
                if "openapi" in e:
                    raise SystemExit(f"❌ {name}: openapi:null but the params list an openapi counterpart {e}")
                mcp_n = e["mcp"]
                eff_type = e.get("type", "string")
                if eff_type not in VALID_TYPES:
                    raise SystemExit(f"❌ {name}.{mcp_n}: unsupported type {eff_type}")
                props.append({
                    "key": mcp_n, "type": eff_type,
                    "desc": str(e.get("desc", "") or ""),
                    "enum": list(e["enum"]) if e.get("enum") is not None else None,
                    "minimum": e.get("minimum"), "maximum": e.get("maximum"),
                    "default": e.get("default", None),
                })
                if bool(e.get("required", False)):
                    required.append(mcp_n)
        # mcp_only: always MCP only
        seen_keys = {p["key"] for p in props}
        for e in (m.get("mcp_only") or []):
            mcp_n = e["mcp"]
            if mcp_n in seen_keys:
                raise SystemExit(f"❌ {name}: the mcp_only '{mcp_n}' has the same name as a param")
            seen_keys.add(mcp_n)
            eff_type = e.get("type", "string")
            if eff_type not in VALID_TYPES:
                raise SystemExit(f"❌ {name}.{mcp_n}: unsupported type {eff_type}")
            props.append({
                "key": mcp_n, "type": eff_type,
                "desc": str(e.get("desc", "") or ""),
                "enum": list(e["enum"]) if e.get("enum") is not None else None,
                "minimum": e.get("minimum"), "maximum": e.get("maximum"),
                "default": e.get("default", None),
            })
            if bool(e.get("required", False)):
                required.append(mcp_n)
        tools.append({"name": name, "description": desc, "props": props, "required": required,
                      "openapi": openapi_path, "method": method.upper()})
    return tools


def render_java(tools) -> str:
    L = []
    L.append("package com.open.alpha2;")
    L.append("")
    L.append("import org.json.JSONArray;")
    L.append("import org.json.JSONException;")
    L.append("import org.json.JSONObject;")
    L.append("")
    L.append("/**")
    L.append(" * MCP tool definitions (fully generated; do not edit by hand).")
    L.append(" *")
    L.append(" * Sources: openapi/open-alpha2-openapi.yml (parameter constraints) +")
    L.append(" * openapi/mcp-openapi-sync.yml (tool list/description/alias/required).")
    L.append(" * Regenerate: python scripts/generate-mcp-tools.py")
    L.append(" *")
    L.append(" * Rules:")
    L.append(" * - The inputSchema parameter constraints (type/enum/minimum/maximum/default) inherit from the spec,")
    L.append(" *   the same rule set as ApiValidator.java. A spec parameter with no sync decision fails the generation")
    L.append(" *   (see the generator uncovered check). It forces the two sides to line up.")
    L.append(" * - alias（time_ms→time、enabled→on、distance_cm→distance、speed_ms→speed）")
    L.append(" *   This side exposes only the MCP name; the callTool() side reads the MCP name (see XiaozhiBridge).")
    L.append(" * - The LED color/brightness are optional in the MCP (preset=stop needs no fill; callTool runs")
    L.append(" *   required_if). The same as the old hand-written behaviour. The spec side stays required.")
    L.append(" */")
    L.append("public final class McpToolsGenerated {")
    L.append("    private McpToolsGenerated() {}")
    L.append("")
    L.append(f"    public static final int TOOL_COUNT = {len(tools)};")
    L.append("")
    L.append("    public static JSONArray buildTools() throws JSONException {")
    L.append("        JSONArray tools = new JSONArray();")
    for i, t in enumerate(tools):
        L.append("")
        src = f" ({t['openapi']} {t['method']})" if t["openapi"] else " (no HTTP mapping)"
        L.append(f"        // {i + 1}. {t['name']}{src}")
        L.append("        {")
        L.append(f"            JSONObject t = new JSONObject();")
        L.append(f"            t.put({java_str('name')}, {java_str(t['name'])});")
        L.append(f"            t.put({java_str('description')}, {java_str(t['description'])});")
        L.append(f"            JSONObject s = new JSONObject();")
        L.append(f"            s.put({java_str('type')}, {java_str('object')});")
        L.append(f"            JSONObject props = new JSONObject();")
        for p in t["props"]:
            L.append(f"            {{")
            L.append(f"                JSONObject p = new JSONObject();")
            L.append(f"                p.put({java_str('type')}, {java_str(p['type'])});")
            if p["desc"]:
                L.append(f"                p.put({java_str('description')}, {java_str(p['desc'])});")
            if p["enum"] is not None:
                # build: new JSONArray().put("a").put("b") / .put(1) for numbers
                chain = "new org.json.JSONArray()"
                for v in p["enum"]:
                    chain += f".put({java_literal(v)})"
                L.append(f"                p.put({java_str('enum')}, {chain});")
            if p["minimum"] is not None:
                L.append(f"                p.put({java_str('minimum')}, {java_literal(p['minimum'])});")
            if p["maximum"] is not None:
                L.append(f"                p.put({java_str('maximum')}, {java_literal(p['maximum'])});")
            if p["default"] is not None:
                L.append(f"                p.put({java_str('default')}, {java_literal(p['default'])});")
            L.append(f"                props.put({java_str(p['key'])}, p);")
            L.append(f"            }}")
        L.append(f"            s.put({java_str('properties')}, props);")
        if t["required"]:
            chain = "new org.json.JSONArray()"
            for r in t["required"]:
                chain += f".put({java_str(r)})"
            L.append(f"            s.put({java_str('required')}, {chain});")
        L.append(f"            t.put({java_str('inputSchema')}, s);")
        L.append(f"            tools.put(t);")
        L.append("        }")
    L.append("        // 2026-09-09: a regeneration that misses TOOL_COUNT is a silent error; the runtime assertion pins it.")
    L.append("        if (tools.length() != TOOL_COUNT) throw new IllegalStateException(")
    L.append("                \"TOOL_COUNT=\" + TOOL_COUNT + \" but built \" + tools.length());")
    L.append("        return tools;")
    L.append("    }")
    L.append("}")
    return "\n".join(L) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="compare only; do not write the file (CI use)")
    args = ap.parse_args()
    tools = build_model()
    text = render_java(tools)
    if args.check:
        if not OUT.exists():
            print(f"❌ the generated file {OUT.relative_to(ROOT)} is missing; run python scripts/generate-mcp-tools.py first")
            return 1
        cur = OUT.read_text(encoding="utf-8").replace("\r\n", "\n")
        if cur != text:
            import difflib
            print("❌ McpToolsGenerated.java drift -- run python scripts/generate-mcp-tools.py and commit again:")
            for line in difflib.unified_diff(cur.splitlines(), text.splitlines(),
                                             "committed", "regenerated", lineterm=""):
                print(line)
            return 1
        print(f"✅ MCP tools in sync ({len(tools)} tools, all generated from the openapi+sync)")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"Wrote {OUT.relative_to(ROOT)} with {len(tools)} tools")
    return 0


if __name__ == "__main__":
    sys.exit(main())
