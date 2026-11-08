#!/usr/bin/env python3
"""Check the action list: list the new open- items and confirm the 14xxxx items are cleared."""
import re
import urllib.request

r = urllib.request.urlopen(
    "http://127.0.0.1:8888/api/alpha2/action/list", timeout=15).read().decode("utf-8")
seen = set()
print("open entries:")
for n in re.findall(r'"id":"(open-[^"]+)"', r):
    if n not in seen:
        print("  ", n)
        seen.add(n)
print("has 1464835936024:", "1464835936024" in r)
