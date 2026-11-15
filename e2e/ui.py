#!/usr/bin/env python3
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
"""Tiny adb UI driver for Ordo's device tests. No dependencies beyond adb.

    ui.py SERIAL tap TARGET [ID]   tap a node; TARGET is a visible text, a content description or a resource id
    ui.py SERIAL longtap TARGET
    ui.py SERIAL has TARGET [ID]   exit 0 if a node matches
    ui.py SERIAL wait TARGET [S]   wait up to S seconds (default 10) for a node
    ui.py SERIAL bounds TARGET     print "x1 y1 x2 y2" for every matching node
    ui.py SERIAL texts             print "text|resource-id|content-desc" per node
    ui.py SERIAL shot FILE         screenshot as PNG

TARGET may list alternatives with "|" (e.g. "Aussehen|Appearance"), so one test runs in German and English.
With ID only nodes with that resource id count: for a text that stands on the screen twice, as an entry of the
side menu and on the page behind it.
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


# The last screen read, reused by checks that follow it within 2 s with no action in between (a dump takes 2-3 s).
# Every tap here and every `input`/`am` call through lib.sh's `a` deletes it, so a check never sees a stale screen.
DUMP = os.path.join(os.path.expanduser("~"), ".cache", "ordo-e2e", re.sub(r"[^A-Za-z0-9]", "_", sys.argv[1]) + ".xml")


def nodes(fresh=False):
    try:
        reuse = not fresh and time.time() - os.path.getmtime(DUMP) < 2
    except OSError:
        reuse = False
    if reuse:
        with open(DUMP) as f:
            xml = f.read()
    else:
        # One round trip: over Tailscale every adb call costs latency.
        xml = subprocess.run(ADB + ["exec-out", "uiautomator dump /data/local/tmp/ordo-e2e.xml >/dev/null && "
                                    "cat /data/local/tmp/ordo-e2e.xml"], capture_output=True, text=True).stdout
        os.makedirs(os.path.dirname(DUMP), exist_ok=True)
        with open(DUMP, "w") as f:
            f.write(xml)
    return [(html.unescape(t), rid, html.unescape(d), int(x1), int(y1), int(x2), int(y2))
            for t, rid, d, x1, y1, x2, y2 in NODE.findall(xml)]


# Remembered tap positions per device, screen size and screen (activity): with E2E_FAST=1 a tap goes straight to the
# remembered spot instead of reading the whole screen first (a dump takes 2-3 s). `has`, `wait` and `texts` always
# read the screen, so every check still sees the real state. Delete the cache file if the layout changed.
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "ordo-e2e", re.sub(r"[^A-Za-z0-9]", "_", sys.argv[1]) + ".tsv")


def screen_key():
    size = sh("wm", "size").strip().split()[-1]
    top = re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity"))
    return size + " " + (top.group(1) if top else "?")


def cache_load():
    try:
        with open(CACHE) as f:
            rows = [line.rstrip("\n").split("\t") for line in f]
        return {(r[0], r[1]): (int(r[2]), int(r[3])) for r in rows if len(r) == 4}
    except OSError:
        return {}


def cache_store(key, target, point):
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    entries = cache_load()
    entries[(key, target)] = point
    with open(CACHE, "w") as f:
        f.writelines(f"{k}\t{t}\t{x}\t{y}\n" for (k, t), (x, y) in entries.items())


def find(target, inside=None, fresh=False):
    wanted = target.split("|")
    for text, rid, desc, x1, y1, x2, y2 in nodes(fresh):
        name = rid.split("/")[-1]
        if inside and name != inside:
            continue
        if text in wanted or desc in wanted or name in wanted:
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def need(target, inside=None):
    point = find(target, inside)
    if point is None:
        sys.exit(f"not found: {target}")
    return point


command = sys.argv[2]
if command in ("tap", "longtap"):
    try:
        os.remove(DUMP)
    except OSError:
        pass
if command == "tap" and os.environ.get("E2E_FAST") == "1":
    target = "\t".join(sys.argv[3:5]).replace("\t", "@")
    key = screen_key()
    point = cache_load().get((key, target))
    if point is None:
        point = need(sys.argv[3], *sys.argv[4:5])
        cache_store(key, target, point)
    sh("input", "tap", str(point[0]), str(point[1]))
    time.sleep(0.5)
elif command == "tap":
    x, y = need(sys.argv[3], *sys.argv[4:5])
    sh("input", "tap", str(x), str(y))
    time.sleep(1.2)
elif command == "longtap":
    x, y = need(sys.argv[3])
    sh("input", "swipe", str(x), str(y), str(x), str(y), "900")
    time.sleep(1.2)
elif command == "has":
    sys.exit(0 if find(sys.argv[3], *sys.argv[4:5]) else 1)
elif command == "wait":
    deadline = time.time() + float(sys.argv[4] if len(sys.argv) > 4 else 10)
    while time.time() < deadline:
        if find(sys.argv[3], fresh=True):
            sys.exit(0)
        time.sleep(0.5)
    sys.exit(f"timed out waiting for: {sys.argv[3]}")
elif command == "bounds":
    wanted = sys.argv[3].split("|")
    for text, rid, desc, x1, y1, x2, y2 in nodes():
        if text in wanted or desc in wanted or rid.split("/")[-1] in wanted:
            print(x1, y1, x2, y2)
elif command == "texts":
    print("\n".join(f"{t}|{rid.split('/')[-1]}|{d}" for t, rid, d, *_ in nodes() if t or d))
elif command == "shot":
    with open(sys.argv[3], "wb") as file:
        file.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
else:
    sys.exit(__doc__)
