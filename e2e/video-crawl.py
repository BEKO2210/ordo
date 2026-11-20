#!/usr/bin/env python3
# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
"""Crawler for Ordo Video (owner, 20.11.2026: "press every button, also rotate"). Called by video-crawl.sh.

  video-crawl.py SERIAL PKG DIR ORIENTATION(0|1)

On every screen (library, its settings, the paused player, every sheet of the player's ⋮ menu and toolbar) it taps
each clickable and long-clickable node of Ordo's window once, then returns to that screen (back, or opening it
again). After every step: Ordo still in front (or a known exit such as picture-in-picture), no new FATAL/ANR in the
log, media volume still 0. Destructive or outward steps are skipped and listed. It never swipes on the video
surface: lists and sheets are scrolled only inside their own scrollable node, and the volume is checked right after.
Prints PASS/FAIL/SKIP/INFO lines like the shell tests; exit 1 on any FAIL, 2 when the volume rose (aborted).
"""
import html
import re
import subprocess
import sys
import time

SERIAL, PKG, DIR, ORIENT = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
ADB = ["adb", "-s", SERIAL]
PLAYER = "me.zhanghai.android.files.viewer.video.VideoPlayerActivity"
LIBRARY = "me.zhanghai.android.files.viewer.video.VideoLauncherActivity"
# Never pressed: they delete, share outside, connect to a TV, write files or reset settings.
DESTRUCTIVE = re.compile(r"L(ö|oe)schen|Delete|Papierkorb|Trash|Teilen|Share|Fernseher|Cast|Mit anderer App|"
                         r"Open with|Standbild speichern|Save frame|Zur(ü|ue)cksetzen|Reset|Entfernen|Remove|"
                         r"Als CSV|Senden|Send", re.I)
# Pressed, but they leave Ordo's window on purpose (checked to come back).
LEAVES = re.compile(r"Bild-in-Bild|Picture-in-picture|Hilfe|Help|Datenschutz|Privacy", re.I)
NODE = re.compile(r'<node [^>]*?text="([^"]*)" resource-id="([^"]*)" class="([^"]*)" package="([^"]*)" '
                  r'content-desc="([^"]*)" checkable="([^"]*)" checked="([^"]*)" clickable="([^"]*)" '
                  r'enabled="([^"]*)" focusable="[^"]*" focused="[^"]*" scrollable="([^"]*)" '
                  r'long-clickable="([^"]*)" password="[^"]*" selected="[^"]*" '
                  r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
fails = 0
skipped = set()


def sh(cmd):
    return subprocess.run(ADB + ["shell", cmd], capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout


def say(kind, msg):
    global fails
    if kind == "FAIL":
        fails += 1
    print(f"{kind} [{'quer' if ORIENT == '1' else 'hoch'}] {msg}", flush=True)


def dump():
    for _ in range(3):
        xml = subprocess.run(ADB + ["exec-out", "uiautomator dump /data/local/tmp/ordo-crawl.xml >/dev/null && "
                                    "cat /data/local/tmp/ordo-crawl.xml"], capture_output=True, text=True).stdout
        if "<node" in xml:
            break
        time.sleep(1)
    out = []
    for m in NODE.finditer(xml):
        t, rid, cls, pkg, desc, checkable, checked, click, enabled, scroll, longc, x1, y1, x2, y2 = m.groups()
        out.append(dict(text=html.unescape(t), id=rid.split("/")[-1], cls=cls.split(".")[-1], pkg=pkg,
                        desc=html.unescape(desc), checkable=checkable == "true", checked=checked == "true",
                        click=click == "true", enabled=enabled == "true", scroll=scroll == "true",
                        long=longc == "true", b=(int(x1), int(y1), int(x2), int(y2))))
    return out


def label(n):
    return n["text"] or n["desc"] or n["id"] or f'{n["cls"]}@{n["b"][0]},{n["b"][1]}'


def top():
    return re.search(r"topResumedActivity=\S+ \S+ (\S+)", sh("dumpsys activity activities | grep -m1 topResumedActivity"))


def front():
    m = top()
    return m.group(1) if m else ""


def volume():
    m = re.search(r"volume is (\d+)", sh("cmd media_session volume --stream 3 --get"))
    return int(m.group(1)) if m else -1


def crashes():
    log = sh(f"logcat -d -b crash,main,system | grep -E 'FATAL EXCEPTION|ANR in {PKG}'")
    return len([line for line in log.splitlines() if line.strip()])


baseline_crashes = crashes()


def check_state(step, allow_leave=False):
    """After each step: volume 0 (abort otherwise), no new crash, Ordo in front."""
    global baseline_crashes
    v = volume()
    if v > 0:
        sh("for i in $(seq 25); do input keyevent KEYCODE_VOLUME_DOWN; done")
        say("FAIL", f"media volume rose to {v} after: {step} (muted again, crawl stopped)")
        sys.exit(2)
    c = crashes()
    if c > baseline_crashes:
        say("FAIL", f"crash or ANR after: {step}")
        print(sh(f"logcat -d -b crash | tail -30"))
        baseline_crashes = c
    f = front()
    if not f.startswith(PKG + "/"):
        if allow_leave:
            say("INFO", f"left Ordo as expected ({f or 'nothing in front'}) after: {step}")
        else:
            say("FAIL", f"Ordo no longer in front ({f}) after: {step}")
        return False
    return True


def tap(n, long=False):
    x = (n["b"][0] + n["b"][2]) // 2
    y = (n["b"][1] + n["b"][3]) // 2
    if long:
        sh(f"input swipe {x} {y} {x} {y} 900")
    else:
        sh(f"input tap {x} {y}")
    time.sleep(1.2)


def key(n):
    return (n["id"], n["text"], n["desc"], n["cls"])


def clickables(nodes):
    return [n for n in nodes if n["pkg"] == PKG and n["enabled"] and (n["click"] or n["long"])
            and n["id"] not in ("playerView",)]  # the picture itself: taps show/hide controls only


def signature(nodes):
    return frozenset(key(n) for n in clickables(nodes))


def rotate(r):
    sh(f"settings put system accelerometer_rotation 0; settings put system user_rotation {r}")
    time.sleep(2.5)


def pause():
    sh("input keyevent KEYCODE_MEDIA_PAUSE")
    time.sleep(0.8)


def open_library():
    sh(f"am force-stop {PKG}")
    sh(f"am start -W -n {PKG}/{LIBRARY}")
    time.sleep(2.5)


def open_settings():
    open_library()
    for n in dump():
        if n["id"] == "actionSettings":
            tap(n)
            time.sleep(1)
            return


def open_player():
    sh(f"am force-stop {PKG}")
    sh(f"am start -W -a android.intent.action.VIEW -d file://{DIR} -t inode/directory "
       f"-n {PKG}/me.zhanghai.android.files.filelist.FileListActivity")
    time.sleep(2.5)
    for n in dump():
        if n["text"] == "crawl.mp4":
            tap(n)
            break
    time.sleep(3)
    pause()
    show_controls()


def show_controls():
    nodes = dump()
    if not any(n["desc"] in ("Weitere Optionen", "More options") for n in nodes):
        surface = next((n for n in nodes if n["id"] == "playerView"), None)
        if surface:
            tap(surface)  # a tap, never a swipe, on the picture


def open_menu_entry(entry):
    def go():
        open_player()
        for n in dump():
            if n["desc"] in ("Weitere Optionen", "More options"):
                tap(n)
                break
        for n in dump():
            if n["text"] == entry:
                tap(n)
                time.sleep(1)
                return
    return go


def scroll_inside(nodes, step):
    """Scroll a list or sheet by its own scrollable node, never the video surface; volume checked after."""
    lists = [n for n in nodes if n["scroll"] and n["pkg"] == PKG and n["id"] != "playerView"]
    if not lists:
        return False
    n = max(lists, key=lambda n: (n["b"][2] - n["b"][0]) * (n["b"][3] - n["b"][1]))
    # Right half on purpose: that is where the player's volume gesture lives; a sheet must keep the swipe.
    x = n["b"][0] + (n["b"][2] - n["b"][0]) * 3 // 4
    y1 = n["b"][1] + (n["b"][3] - n["b"][1]) * 3 // 4
    y2 = n["b"][1] + (n["b"][3] - n["b"][1]) // 4
    if y1 - y2 < 60:
        return False
    sh(f"input swipe {x} {y1} {x} {y2} 400")
    time.sleep(1.2)
    check_state(f"{step}: scroll inside {label(n)}")
    return True


def crawl(name, setup):
    setup()
    if not check_state(f"open {name}"):
        return
    home = dump()
    home_sig = signature(home)
    seen = set()
    pressed = 0
    for page in range(4):  # the screen and up to three scrolls of its list
        for n in clickables(dump()):
            k = key(n)
            if k in seen:
                continue
            seen.add(k)
            what = f"{name} › {label(n)}"
            if DESTRUCTIVE.search(" ".join((n["text"], n["desc"], n["id"]))):
                skipped.add(what)
                continue
            leaves = bool(LEAVES.search(" ".join((n["text"], n["desc"]))))
            was_checked = n["checked"]
            tap(n, long=n["long"] and not n["click"])
            pressed += 1
            ok = check_state(what, allow_leave=leaves)
            # Unlock a locked player (hold the unlock button).
            for m in dump():
                if m["id"] == "unlock" or m["desc"] in ("Zum Entsperren halten", "Hold to unlock"):
                    tap(m, long=True)
            if ok and n["checkable"]:
                now = next((m for m in dump() if key(m) == k), None)
                if now and now["checked"] != was_checked:
                    tap(now)  # back as it was
                    check_state(f"{what} (restored)")
            if not ok or signature(dump()) != home_sig:
                for _ in range(3):
                    if front().startswith(PKG + "/") and signature(dump()) == home_sig:
                        break
                    sh("input keyevent BACK")
                    time.sleep(1.2)
                if not front().startswith(PKG + "/") or signature(dump()) != home_sig:
                    setup()
                    for _ in range(page):
                        scroll_inside(dump(), name)
            pause() if name.startswith("player") else None
        if not scroll_inside(dump(), name):
            break
    say("PASS" if fails == 0 else "INFO", f"{name}: {pressed} controls pressed")


def rotate_with(name, setup):
    """Rotate while the screen (a sheet, or the playing film) is open: Ordo stays, nothing crashes."""
    setup()
    if name == "player (playing)":
        sh("input keyevent KEYCODE_MEDIA_PLAY")
        time.sleep(1)
    other = "0" if ORIENT == "1" else "1"
    rotate(other)
    ok = check_state(f"rotate with {name} open")
    rotate(ORIENT)
    ok = check_state(f"rotate back with {name} open") and ok
    if ok:
        say("PASS", f"rotation with {name} open")


rotate(ORIENT)
screens = [("library", open_library), ("video settings", open_settings), ("player paused", open_player)]
open_player()
for n in dump():
    if n["desc"] in ("Weitere Optionen", "More options"):
        tap(n)
        break
entries = [n["text"] for n in dump() if n["id"] == "title" and n["text"]]
sh("input keyevent BACK")
for entry in entries:
    screens.append((f"player › {entry}", open_menu_entry(entry)))
say("INFO", f"menu entries: {', '.join(entries)}")

for name, setup in screens:
    crawl(name, setup)
for name, setup in [("player (playing)", open_player)] + screens[3:]:
    if DESTRUCTIVE.search(name) or LEAVES.search(name):
        continue
    rotate_with(name, setup)

# Headset buttons (the part of "plugging in" adb can reach; a plug itself is a system broadcast).
open_player()
for k in ("HEADSETHOOK", "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS", "HEADSETHOOK", "MEDIA_PAUSE"):
    sh(f"input keyevent KEYCODE_{k}")
    time.sleep(1.2)
    check_state(f"headset key {k}")
say("PASS" if fails == 0 else "INFO", "headset keys in the player")
sh(f"am force-stop {PKG}")
for what in sorted(skipped):
    say("SKIP", f"not pressed (destructive or outward): {what}")
sys.exit(1 if fails else 0)
