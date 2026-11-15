#!/usr/bin/env python3
"""Build the S-23 translation memory.

Reads the diff of every S-23 translation commit (old side = its parent, new
side = the commit itself, so other work does not mix in) and writes one row
per changed line pair whose old side carries Chinese text.

Output columns (tab separated):
    file     path in the new tree
    kind     comment | string | dict | doc
    context  the key of a dictionary entry, or the nearest function, HTML id
             or spec key
    zh       the old (Chinese) line, stripped
    en       the new (English) line, stripped

Python 3 standard library only.
"""

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tm_common as tm  # noqa: E402


def parse_diff(text):
    """Yield (file, old_line_no, old_text, new_text) for each changed line
    pair. Pairs are formed per contiguous edit region: the deleted lines and
    the added lines of a region are matched in order, and the old line number
    of each deleted line is kept."""
    pairs = []
    cur_file = None
    old_line = 0
    in_hunk = False
    region_dels = []
    region_adds = []

    def flush():
        if not region_dels:
            return
        for (old_no, del_txt), add_txt in zip(region_dels, region_adds):
            pairs.append((cur_file, old_no, del_txt, add_txt))
        del region_dels[:]
        del region_adds[:]

    for line in text.split("\n"):
        if line.startswith("diff --git "):
            flush()
            in_hunk = False
            m = re.match(r"diff --git a/(.*) b/(.*)", line)
            cur_file = m.group(2) if m else None
            continue
        if line.startswith("@@"):
            flush()
            in_hunk = True
            m = re.match(r"@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@", line)
            if m:
                old_line = int(m.group(1))
            continue
        if not in_hunk or cur_file is None:
            continue
        if line.startswith("-"):
            region_dels.append((old_line, line[1:]))
            old_line += 1
        elif line.startswith("+"):
            region_adds.append(line[1:])
        elif line.startswith("\\"):
            pass
        else:  # a context line (or a blank context line)
            flush()
            old_line += 1

    flush()
    return pairs


def old_lines(repo, commit, path):
    """The full old-side content of path at commit, or None if unreadable."""
    try:
        return tm._git(repo, ["show", "%s:%s" % (commit, path)])
    except RuntimeError:
        return None


def build_rows(repo, commit):
    """Return the rows (dicts) contributed by one S-23 commit."""
    parent = tm._git(repo, ["rev-parse", "%s^" % commit])[0]
    diff = "\n".join(tm._git(repo, ["diff", "-U3", parent, commit]))
    rows = []
    scanners = {}
    for file, old_no, old_text, new_text in parse_diff(diff):
        if not tm.has_chinese(old_text):
            continue
        if file not in scanners:
            content = old_lines(repo, parent, file)
            if content is None:
                content = [old_text]
            scanners[file] = tm.Scanner(file, content)
        idx = old_no - 1
        if not (0 <= idx < scanners[file].n):
            idx = 0
        kind, context = scanners[file].classify(idx)
        rows.append({
            "file": file,
            "kind": kind,
            "context": context,
            "zh": old_text.strip(),
            "en": new_text.strip(),
            "_sort": (file, old_no),
        })
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=os.getcwd(),
                    help="path to the git repo (default: current directory)")
    ap.add_argument("--ref", default="HEAD",
                    help="ref whose first-parent history is scanned (default: HEAD)")
    ap.add_argument("--out", default=None,
                    help="output TSV path (default: <repo>/tools/i18n/s23-zh-en.tsv)")
    ap.add_argument("--commits", default=None,
                    help="comma separated commit hashes to use instead of auto-detect")
    args = ap.parse_args(argv)

    if args.commits:
        commits = [c.strip() for c in args.commits.split(",") if c.strip()]
    else:
        commits = tm.find_s23_commits(args.repo, args.ref)

    rows = []
    for c in commits:
        rows.extend(build_rows(args.repo, c))

    # dedupe identical rows, keep per-file line order
    seen = set()
    unique = []
    for r in sorted(rows, key=lambda r: r["_sort"]):
        key = (r["file"], r["kind"], r["context"], r["zh"], r["en"])
        if key in seen:
            continue
        seen.add(key)
        unique.append(r)

    out = args.out or os.path.join(args.repo, "tools", "i18n", "s23-zh-en.tsv")
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    for r in unique:
        r.pop("_sort", None)
    tm.write_tsv(out, unique)

    counts = {}
    for r in unique:
        counts[r["kind"]] = counts.get(r["kind"], 0) + 1
    print("wrote %s" % out)
    print("commits: %d" % len(commits))
    print("rows: %d" % len(unique))
    for k in sorted(counts):
        print("  %-8s %d" % (k, counts[k]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
