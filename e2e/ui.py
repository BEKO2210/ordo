#!/usr/bin/env python3
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
"""Tiny adb UI driver for Ordo's device tests. No dependencies beyond adb.

    ui.py SERIAL tap TARGET [ID]   tap a node; TARGET is a visible text, a content description or a resource id
    ui.py SERIAL longtap TARGET [ID]
    ui.py SERIAL has TARGET [ID]   exit 0 if a node matches
    ui.py SERIAL checked TEXT      exit 0 if the check box after the node with that text is ticked
    ui.py SERIAL summary TITLE TEXT exit 0 if a preference row has that summary
    ui.py SERIAL visible ID        exit 0 if that view of the front activity's window is shown (fast; no popups/dialogs)
    ui.py SERIAL wait TARGET [S]   wait up to S seconds (default 10) for a node
    ui.py SERIAL waitgone TARGET [S] wait up to S seconds (default 10) for a node to disappear
    ui.py SERIAL bounds TARGET [ID] print "x1 y1 x2 y2" for every matching node
    ui.py SERIAL texts             print "text|resource-id|content-desc" per node
    ui.py SERIAL shot FILE         screenshot as PNG (only while Ordo is in front and unlocked)

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
        # A busy app (e.g. right after an install) can make the dump give up on waiting for idle: try again.
        for _ in range(3):
            xml = subprocess.run(ADB + ["exec-out", "uiautomator dump /data/local/tmp/ordo-e2e.xml >/dev/null && "
                                        "cat /data/local/tmp/ordo-e2e.xml"], capture_output=True, text=True).stdout
            if "<node" in xml:
                break
            time.sleep(1)
        os.makedirs(os.path.dirname(DUMP), exist_ok=True)
        with open(DUMP, "w") as f:
            f.write(xml)
    # File names on two lines carry zero-width spaces after their separators (WordBreaks.breakableName).
    return [(html.unescape(t).replace("​", ""), rid, html.unescape(d), int(x1), int(y1), int(x2), int(y2))
            for t, rid, d, x1, y1, x2, y2 in NODE.findall(xml)]


# Remembered tap positions per device, screen size and screen (activity): with E2E_FAST=1 a tap goes straight to the
# remembered spot instead of reading the whole screen first (a dump takes 2-3 s). `has`, `wait` and `texts` always
# read the screen, so every check still sees the real state. Delete the cache file if the layout changed.
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "ordo-e2e", re.sub(r"[^A-Za-z0-9]", "_", sys.argv[1]) + ".tsv")


def screen_key():
    # Size, rotation and front screen in one round trip: a remembered spot is only valid for the same layout.
    out = sh("wm size; dumpsys window 2>/dev/null | grep -m1 -o 'mCurrentRotation=[^ ]*'; "
             "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity")
    size = re.search(r"(\d+x\d+)\s*$", out.split("\n")[0] if out else "")
    rotation = re.search(r"mCurrentRotation=(\S+)", out)
    top = re.search(r"topResumedActivity=\S+ \S+ (\S+)", out)
    return " ".join(m.group(1) if m else "?" for m in (size, rotation, top))


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
            global FOUND_BY_TEXT
            FOUND_BY_TEXT = text in wanted and name not in wanted and desc not in wanted
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


# Set by find(): the match was a visible text (a file or folder name), not a control's id or label.
FOUND_BY_TEXT = False


def in_scroller(point):
    """True when the point lies inside a scrollable view of the last screen read: such a spot moves with scrolling."""
    with open(DUMP) as f:
        boxes = re.findall(r'<node [^>]*scrollable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', f.read())
    return any(int(x1) <= point[0] <= int(x2) and int(y1) <= point[1] <= int(y2) for x1, y1, x2, y2 in boxes)


def need(target, inside=None):
    point = find(target, inside)
    if point is None:
        sys.exit(f"not found: {target}")
    return point


def visible(name):
    """True when the view with that resource id and all its parents are VISIBLE in the front activity's own window.

    Reads `dumpsys activity <front activity>`: ~0.2 s against 2.4 s for a uiautomator dump, but it sees neither popups
    nor dialogs, so use it only for views of the activity's window, such as the side menu. (`dumpsys activity top`
    is no shortcut: it dumps every app and stops short when another one hangs.)"""
    top = re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity"))
    if not top:
        return False
    chain = []  # (indent, visible) of the open ancestors
    for line in sh("dumpsys", "activity", top.group(1)).split("View Hierarchy:")[-1].splitlines():
        m = re.match(r"( *)\S+\{[0-9a-f]+ ([VIG])", line)
        if not m:
            continue
        indent = len(m.group(1))
        while chain and chain[-1][0] >= indent:
            chain.pop()
        chain.append((indent, m.group(2) == "V"))
        if re.search(r" \S+:id/" + re.escape(name) + r"[ }]", line):
            return all(v for _, v in chain)
    return False


def forget_screen():
    try:
        os.remove(DUMP)
    except OSError:
        pass


command = sys.argv[2]
if command in ("tap", "longtap"):
    # The read before a tap must not pass for the screen after it: forget it now and again once tapped.
    forget_screen()
if command == "tap" and os.environ.get("E2E_FAST") == "1":
    target = "\t".join(sys.argv[3:5]).replace("\t", "@")
    key = screen_key()
    point = cache_load().get((key, target))
    if point is None:
        point = need(sys.argv[3], *sys.argv[4:5])
        # Only fixed spots are remembered: a target in a list or a scrolling page is looked up every time.
        # Visible texts such as folder names move when the content changes (a row inserted above): only ids
        # and control labels are remembered.
        if not in_scroller(point) and not FOUND_BY_TEXT:
            cache_store(key, target, point)
    sh("input", "tap", str(point[0]), str(point[1]))
    forget_screen()
    time.sleep(0.5)
elif command == "tap":
    x, y = need(sys.argv[3], *sys.argv[4:5])
    sh("input", "tap", str(x), str(y))
    forget_screen()
    time.sleep(1.2)
elif command == "longtap":
    x, y = need(sys.argv[3], *sys.argv[4:5])
    sh("input", "swipe", str(x), str(y), str(x), str(y), "900")
    forget_screen()
    time.sleep(1.2)
elif command == "visible":
    sys.exit(0 if visible(sys.argv[3]) else 1)
elif command == "checked":
    # New visual choices expose the labelled row itself as a radio button. Older controls expose a text node
    # followed by a separate checkable node, so accept both accessibility shapes.
    nodes(fresh=True)
    with open(DUMP) as f:
        tags = re.findall(r"<node [^>]*>", f.read())
    wanted = sys.argv[3].split("|")
    def label(tag):
        text = html.unescape(re.search(r'text="([^"]*)"', tag).group(1))
        desc = html.unescape(re.search(r'content-desc="([^"]*)"', tag).group(1))
        return text, desc
    matches = [i for i, tag in enumerate(tags) if any(value in wanted for value in label(tag))]
    if not matches:
        sys.exit(f"not found: {sys.argv[3]}")
    # A node that is itself checkable wins: a summary showing the same word (the accent row names the chosen
    # colour) must not send the search to the next checkable node of another option.
    start = next((i for i in matches if 'checkable="true"' in tags[i]), matches[0])
    box = tags[start] if 'checkable="true"' in tags[start] else next(
        (tag for tag in tags[start + 1:] if 'checkable="true"' in tag), ""
    )
    sys.exit(0 if 'checked="true"' in box else 1)
elif command == "summary":
    # Preference title and summary are separate accessibility nodes. Keep the assertion tied to its row instead of
    # accepting an identical value (notably "Off") from another preference elsewhere on the screen.
    all_nodes = nodes(fresh=True)
    titles = sys.argv[3].split("|")
    expected = sys.argv[4].split("|")
    start = next((i for i, (value, rid, *_rest) in enumerate(all_nodes)
                  if value in titles and rid.split("/")[-1] == "title"), None)
    if start is None:
        sys.exit(f"not found: {sys.argv[3]}")
    for value, rid, *_rest in all_nodes[start + 1:]:
        name = rid.split("/")[-1]
        if name == "title":
            break
        if name == "summary":
            sys.exit(0 if value in expected else 1)
    sys.exit(1)
elif command == "has":
    sys.exit(0 if find(sys.argv[3], *sys.argv[4:5]) else 1)
elif command == "wait":
    deadline = time.time() + float(sys.argv[4] if len(sys.argv) > 4 else 10)
    while time.time() < deadline:
        if find(sys.argv[3], fresh=True):
            sys.exit(0)
        time.sleep(0.5)
    sys.exit(f"timed out waiting for: {sys.argv[3]}")
elif command == "waitgone":
    deadline = time.time() + float(sys.argv[4] if len(sys.argv) > 4 else 10)
    while time.time() < deadline:
        if not find(sys.argv[3], fresh=True):
            sys.exit(0)
        time.sleep(0.5)
    sys.exit(f"timed out waiting for disappearance of: {sys.argv[3]}")
elif command == "bounds":
    wanted = sys.argv[3].split("|")
    inside = sys.argv[4] if len(sys.argv) > 4 else None
    for text, rid, desc, x1, y1, x2, y2 in nodes():
        if inside and rid.split("/")[-1] != inside:
            continue
        if text in wanted or desc in wanted or rid.split("/")[-1] in wanted:
            print(x1, y1, x2, y2)
elif command == "texts":
    print("\n".join(f"{t}|{rid.split('/')[-1]}|{d}" for t, rid, d, *_ in nodes() if t or d))
elif command == "shot":
    # Privacy: only Ordo's own screens, never another app, the launcher or a lock screen (lib.sh ordo_front).
    state = sh("dumpsys activity activities | grep -m1 topResumedActivity; dumpsys window | grep -m1 isKeyguardShowing; "
               "dumpsys window | grep -m1 mCurrentFocus")
    pkg = os.environ.get("PKG", "com.beko2210.ordo")
    # The focused window too: Samsung's app drawer can cover Ordo while Ordo stays resumed (lib.sh ordo_front).
    if (not re.search(r"topResumedActivity=.* " + re.escape(pkg) + "/", state) or "isKeyguardShowing=true" in state
            or not re.search(r"mCurrentFocus=.* " + re.escape(pkg) + "/", state)):
        print(f"SKIP shot (not Ordo): {sys.argv[3]}")
        sys.exit(1)
    with open(sys.argv[3], "wb") as file:
        file.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
else:
    sys.exit(__doc__)
