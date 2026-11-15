#!/usr/bin/env python3
"""Anatomy check: show which servos a given .ubx moves (listed when it differs from home by 15 or more)."""
import sys

sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read().split("if __name__")[0])

HOME = [120, 120, 120, 120, 120, 120, 120, 65, 145, 140,
        120, 120, 175, 95, 100, 120, 120, 120, 120, 120]
NAMES = {1: "R shld up", 2: "R shld lr", 3: "R elb", 4: "L shld up", 5: "L shld lr",
         6: "L elb", 7: "R hip lr", 8: "R hip up", 9: "R knee", 10: "R ank up",
         11: "R ank lr", 12: "L hip lr", 13: "L hip up", 14: "L knee", 15: "L ank up",
         16: "L ank lr", 17: "R finger", 18: "L finger", 19: "head lr", 20: "head up"}

for fid in ["1464835936017", "1510818174706", "1508999860568"]:
    m = parse_ubx("C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\" + fid + ".ubx")
    d = m["raw"]
    print("===== " + fid)
    for t in m["tracks"]:
        for da in parse_blist_frames(d, t["blist_off"]):
            if da["fh"] == 0 or da["f"] != 0:
                continue
            for (ty, data) in parse_chain(da["chain"], da["fh"]):
                if ty == 1:
                    for blk in parse_trackh(data):
                        for fi, f in enumerate(blk["frames"][:3]):
                            a = angles_of_eblob_template(f["raw"])
                            dev = []
                            for i, v in enumerate(a):
                                if abs(v - HOME[i]) >= 15:
                                    dev.append(str(i + 1) + NAMES[i + 1] + str(v))
                            print("trk" + str(t["id"]) + " f" + str(fi) +
                                  " moves:" + ",".join(dev))
