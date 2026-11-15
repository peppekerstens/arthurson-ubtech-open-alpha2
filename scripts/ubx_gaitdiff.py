#!/usr/bin/env python3
"""Original vs modified gait curve comparison: take the per-axis angle list of the main gait segment and print the phase-aligned sample values.
Use it to see whether the uniform resampling / the smoothing changed the gait shape (peaks, phase, symmetry)."""
import sys

sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read().split("if __name__")[0])

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"


def gait_frames(path):
    """Return all servo poses of the main gait segment (timeBase==20) (file order)."""
    m = parse_ubx(path)
    d = m["raw"]
    out = []
    for t in m["tracks"]:
        for da in parse_blist_frames(d, t["blist_off"]):
            if da["fh"] == 0 or da["f"] != 0:
                continue
            for (ty, data) in parse_chain(da["chain"], da["fh"]):
                if ty == 0 and len(data) == 8 and le(data, 0) != 20:
                    break
                if ty == 1:
                    for blk in parse_trackh(data):
                        for f in blk["frames"]:
                            out.append(angles_of_eblob_template(f["raw"]))
            else:
                continue
            break
    return out


JOINTS = {1: "R shld up", 4: "L shld up", 8: "R hip up", 13: "L hip up",
          9: "R knee", 14: "L knee", 10: "R ank", 15: "L ank"}


def show(path, label):
    g = gait_frames(path)
    print("##### %s frames=%d" % (label, len(g)))
    idx = [0, len(g) // 4, len(g) // 2, 3 * len(g) // 4, len(g) - 1]
    for a in sorted(JOINTS):
        vals = [g[i][a - 1] for i in idx]
        print("  #%02d%s: %s (min=%d max=%d)" %
              (a, JOINTS[a], vals, min(p[a - 1] for p in g), max(p[a - 1] for p in g)))


show(ACT + "1508999860568.ubx", "ORIG")
show(ACT + "open-1508999860568-8step-smooth2.ubx", "V4-8STEP-SMOOTH2")
