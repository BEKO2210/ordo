#!/usr/bin/env python3
"""Checks Play listing graphics against Google Play's rules; prints a Markdown table, exits 1 on any failure.

Rules (Play Console help "Add preview assets"): icon 512x512 32-bit PNG with alpha, <= 1 MB; feature graphic
1024x500 JPEG or 24-bit PNG without alpha, <= 15 MB; screenshots JPEG or 24-bit PNG without alpha, each side
320-3840 px, longer side <= 2x shorter side, <= 8 MB; phones 2-8 per language, tablets up to 8.
Usage: check.py <images dir> [...]
"""
import glob, os, sys
from PIL import Image

MB = 1024 * 1024


def check(path, kind):
    im = Image.open(path)
    w, h = im.size
    alpha = im.mode in ("RGBA", "LA", "PA") or "transparency" in im.info
    size = os.path.getsize(path)
    errs = []
    if kind == "icon":
        if (w, h) != (512, 512): errs.append("not 512x512")
        if im.format != "PNG" or not alpha: errs.append("needs 32-bit PNG with alpha")
        if size > 1 * MB: errs.append("> 1 MB")
    elif kind == "feature":
        if (w, h) != (1024, 500): errs.append("not 1024x500")
        if alpha: errs.append("has alpha")
        if size > 15 * MB: errs.append("> 15 MB")
    else:
        if alpha: errs.append("has alpha")
        if min(w, h) < 320 or max(w, h) > 3840: errs.append("side outside 320-3840")
        if max(w, h) > 2 * min(w, h): errs.append("ratio > 2:1")
        if size > 8 * MB: errs.append("> 8 MB")
        if im.format not in ("JPEG", "PNG"): errs.append("not JPEG/PNG")
    return w, h, im.mode, size, errs


def main(dirs):
    bad = 0
    print("| File | Size px | Mode | KB | Result |\n|---|---|---|---|---|")
    for d in dirs:
        files = [(os.path.join(d, "icon.png"), "icon"), (os.path.join(d, "featureGraphic.png"), "feature")]
        counts = {}
        for sub in ("phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots"):
            shots = sorted(glob.glob(os.path.join(d, sub, "*.jpg")) + glob.glob(os.path.join(d, sub, "*.png")))
            counts[sub] = len(shots)
            files += [(f, "shot") for f in shots]
        for f, kind in files:
            if not os.path.exists(f):
                print(f"| {f} | – | – | – | MISSING |"); bad += 1; continue
            w, h, mode, size, errs = check(f, kind)
            bad += bool(errs)
            print(f"| {os.path.relpath(f, os.path.dirname(os.path.dirname(d)))} | {w}×{h} | {mode} | {size // 1024} | {'OK' if not errs else 'FAIL: ' + ', '.join(errs)} |")
        if not 2 <= counts["phoneScreenshots"] <= 8: print(f"| {d} phone count {counts['phoneScreenshots']} | | | | FAIL: need 2-8 |"); bad += 1
        for sub in ("sevenInchScreenshots", "tenInchScreenshots"):
            if counts[sub] > 8: print(f"| {d} {sub} count | | | | FAIL: max 8 |"); bad += 1
    print(f"\n{'ALL OK' if not bad else str(bad) + ' FAILED'}")
    return 1 if bad else 0


if __name__ == "__main__":
    # self-check: an RGBA screenshot must fail, an RGB one must pass
    t = "/tmp/_ordo_check_selftest.png"
    Image.new("RGBA", (1080, 1920)).save(t); assert check(t, "shot")[4]
    Image.new("RGB", (1080, 2400)).save(t); assert "ratio > 2:1" in check(t, "shot")[4]
    Image.new("RGB", (1080, 1920)).save(t); assert not check(t, "shot")[4]
    os.remove(t)
    sys.exit(main(sys.argv[1:]))
