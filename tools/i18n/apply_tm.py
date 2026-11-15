#!/usr/bin/env python3
"""Apply the S-23 translation memory to a tree.

For every row, a line in the same file is replaced by the row's English text
when the line's Chinese text matches the row exactly (stripped) and the line
classifies to the same kind. Rows that match no line are reported as a list for
a person, because the upstream line they came from has moved.

Dry run is the default: nothing on disk is changed. Pass --write to apply.

Python 3 standard library only.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tm_common as tm  # noqa: E402


def apply_to_tree(repo, rows, write=False):
    """Apply rows to the tree at repo. Returns (applied, unmatched).

    applied:   list of (row, [line numbers, 1-based]) that were replaced
    unmatched: list of row dicts that matched no line

    Every file is classified once, on its original content, so a dry run and a
    write report the same rows. Replacements are collected per file and written
    in one pass.
    """
    from collections import defaultdict

    rows_by_file = defaultdict(list)
    for row in rows:
        rows_by_file[row["file"]].append(row)

    applied = []
    unmatched = []
    for rel, file_rows in rows_by_file.items():
        path = os.path.join(repo, rel)
        if not os.path.isfile(path):
            unmatched.extend(file_rows)
            continue
        with open(path, encoding="utf-8") as f:
            lines = f.read().split("\n")
        scanner = tm.Scanner(rel, lines)
        new_lines = list(lines)
        for row in file_rows:
            hits = [
                i for i, line in enumerate(lines)
                if line.strip() == row["zh"] and scanner.classify(i)[0] == row["kind"]
            ]
            if not hits:
                unmatched.append(row)
                continue
            applied.append((row, [i + 1 for i in hits]))
            if write:
                for i in hits:
                    indent = lines[i][:len(lines[i]) - len(lines[i].lstrip())]
                    new_lines[i] = indent + row["en"]
        if write:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write("\n".join(new_lines))

    return applied, unmatched


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tm", default=None,
                    help="path to the TSV memory (default: tools/i18n/s23-zh-en.tsv next to this script)")
    ap.add_argument("--repo", default=os.getcwd(),
                    help="the tree to apply to (default: current directory)")
    ap.add_argument("--write", action="store_true",
                    help="apply the changes (default is a dry run that changes nothing)")
    args = ap.parse_args(argv)

    default_tm = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                              "s23-zh-en.tsv")
    tm_path = args.tm or default_tm
    rows = tm.read_tsv(tm_path)

    applied, unmatched = apply_to_tree(args.repo, rows, write=args.write)

    mode = "WROTE" if args.write else "DRY RUN"
    print("%s: %d rows, %d applied, %d unmatched" % (
        mode, len(rows), len(applied), len(unmatched)))
    if unmatched:
        print("\nunmatched rows (the upstream line moved; review by hand):")
        for row in unmatched:
            print("  %-6s %s" % (row["kind"], row["file"]))
            print("    zh: %s" % row["zh"][:80])
    return 0 if not unmatched else 3


if __name__ == "__main__":
    sys.exit(main())
