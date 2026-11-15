#!/usr/bin/env python3
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
"""Tiny adb UI driver for Ordo's device tests. No dependencies beyond adb.

    ui.py SERIAL tap TARGET        tap a node; TARGET is a visible text, a content description or a resource id
    ui.py SERIAL longtap TARGET
    ui.py SERIAL has TARGET        exit 0 if a node matches
    ui.py SERIAL wait TARGET [S]   wait up to S seconds (default 10) for a node
    ui.py SERIAL texts             print "text|resource-id|content-desc" per node
    ui.py SERIAL shot FILE         screenshot as PNG

TARGET may list alternatives with "|" (e.g. "Aussehen|Appearance"), so one test runs in German and English.
"""
import html
import os
import re
import subprocess
import sys
import time

ADB = [os.environ.get("ADB", "adb"), "-s", sys.argv[1]]
NODE = re.compile(r'<node [^>]*?text="([^"]*)" resource-id="([^"]*)"[^>]*?content-desc="([^"]*)"'
                  r'[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def sh(*args):
    return subprocess.run(ADB + ["shell", *args], capture_output=True, text=True).stdout


def nodes():
    sh("uiautomator", "dump", "/data/local/tmp/ordo-e2e.xml")
    xml = subprocess.run(ADB + ["exec-out", "cat", "/data/local/tmp/ordo-e2e.xml"], capture_output=True, text=True).stdout
    return [(html.unescape(t), rid, html.unescape(d), int(x1), int(y1), int(x2), int(y2))
            for t, rid, d, x1, y1, x2, y2 in NODE.findall(xml)]


def find(target):
    wanted = target.split("|")
    for text, rid, desc, x1, y1, x2, y2 in nodes():
        if text in wanted or desc in wanted or rid.split("/")[-1] in wanted:
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def need(target):
    point = find(target)
    if point is None:
        sys.exit(f"not found: {target}")
    return point


command = sys.argv[2]
if command == "tap":
    x, y = need(sys.argv[3])
    sh("input", "tap", str(x), str(y))
    time.sleep(1.2)
elif command == "longtap":
    x, y = need(sys.argv[3])
    sh("input", "swipe", str(x), str(y), str(x), str(y), "900")
    time.sleep(1.2)
elif command == "has":
    sys.exit(0 if find(sys.argv[3]) else 1)
elif command == "wait":
    deadline = time.time() + float(sys.argv[4] if len(sys.argv) > 4 else 10)
    while time.time() < deadline:
        if find(sys.argv[3]):
            sys.exit(0)
        time.sleep(0.5)
    sys.exit(f"timed out waiting for: {sys.argv[3]}")
elif command == "texts":
    print("\n".join(f"{t}|{rid.split('/')[-1]}|{d}" for t, rid, d, *_ in nodes() if t or d))
elif command == "shot":
    with open(sys.argv[3], "wb") as file:
        file.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
else:
    sys.exit(__doc__)
