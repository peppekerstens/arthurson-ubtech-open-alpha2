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

A contiguous edit region whose old and new sides have a different number of
lines (the text was re-wrapped) is written as ONE row with the lines of each
side joined, so the zh and en columns do not shift against each other. A row
whose English column is empty (the line was deleted or blanked without a
replacement) is not written to the TSV; it is listed in unpaired.txt instead.

Python 3 standard library only.
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tm_common as tm  # noqa: E402

UNPAIRED_HEADER = ["file", "kind", "context", "zh", "en", "reason"]


def parse_diff(text):
    """Yield (file, old_line_no, old_text, new_text) for each changed region.

    Pairs are formed per contiguous edit region. When the old and new sides
    have the same number of lines, the lines are matched in order, one row per
    line, and the old line number of each deleted line is kept. When the two
    sides have a different number of lines (the text was re-wrapped) a
    line-by-line match would shift the columns, so the whole region becomes
    one row with each side's lines joined.
    """
    pairs = []
    cur_file = None
    old_line = 0
    in_hunk = False
    region_dels = []
    region_adds = []

    def flush():
        if not region_dels:
            return
        if len(region_dels) == len(region_adds):
            for (old_no, del_txt), add_txt in zip(region_dels, region_adds):
                pairs.append((cur_file, old_no, del_txt, add_txt))
        else:
            joined_old = "\n".join(t for _, t in region_dels)
            joined_new = "\n".join(region_adds)
            pairs.append((cur_file, region_dels[0][0], joined_old, joined_new))
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


def write_unpaired(path, rows):
    """Write the rows that have no English text to a list for a person."""
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\t".join(UNPAIRED_HEADER) + "\n")
        for r in rows:
            cells = [r.get(k, "") for k in ["file", "kind", "context", "zh", "en"]]
            cells = [c.replace("\t", " ").replace("\n", " ") for c in cells]
            cells.append("empty English column (no translation on the new side)")
            f.write("\t".join(cells) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", default=os.getcwd(),
                    help="path to the git repo (default: current directory)")
    ap.add_argument("--ref", default="HEAD",
                    help="ref whose first-parent history is scanned (default: HEAD)")
    ap.add_argument("--out", default=None,
                    help="output TSV path (default: s23-zh-en.tsv next to this script)")
    ap.add_argument("--unpaired", default=None,
                    help="path for the rows with an empty English column "
                         "(default: unpaired.txt in the same folder as --out)")
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

    # a row with an empty English column is incomplete: list it for a person
    # instead of writing it into the memory.
    paired = [r for r in unique if r["en"].strip()]
    unpaired = [r for r in unique if not r["en"].strip()]
    for r in paired:
        r.pop("_sort", None)

    out = args.out or os.path.join(HERE, "s23-zh-en.tsv")
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    tm.write_tsv(out, paired)

    unpaired_out = args.unpaired or os.path.join(
        os.path.dirname(out) or ".", "unpaired.txt")
    write_unpaired(unpaired_out, unpaired)

    counts = {}
    for r in paired:
        counts[r["kind"]] = counts.get(r["kind"], 0) + 1
    print("wrote %s" % out)
    print("unpaired: %s (%d rows)" % (unpaired_out, len(unpaired)))
    print("commits: %d" % len(commits))
    print("rows: %d" % len(paired))
    for k in sorted(counts):
        print("  %-8s %d" % (k, counts[k]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
