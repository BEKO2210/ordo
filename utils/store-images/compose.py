#!/usr/bin/env python3
"""Play Store screenshots and feature graphic for Ordo: real device screenshots in a device frame on a soft Ordo
blue, one short headline per image. Reproducible: the same raw shots give the same images.

Usage: compose.py <raw dir> <repo root> [strip dir]
What is made stands in shots.json next to this file (README.md explains every field): the sets (phone, 7-inch,
10-inch), their shots in order, and per shot the raw screenshot(s), an optional crop, the headline per language and
the glow colour. A new app in the suite is a few lines there, no code.
<raw dir>/<en|de>/<prefix><raw>.png are the screenshots from capture.sh (only taken while Ordo is in front, demo
files only). A shot whose raw file is missing is left out with a note; a set without any raw file keeps its images.
Writes fastlane/metadata/android/<locale>/images/{phone,sevenInch,tenInch}Screenshots/*.jpg and featureGraphic.png,
and with a strip dir a row of 200 px wide thumbnails per set: the size at which Play shows them first, to read.

Rules followed (Play Console help "Add preview assets", read 21.11.2026): 9:16 at 1080x1920 and 16:9 at 1920x1080,
the UI is the main thing, the headline takes far less than 20 % of the image, no small text, no "best", "free" or
call to action, no pure white or black background, and no app icon on the feature graphic (it would repeat the icon).
Font: Geologica (OFL), the font of Ordo's website (docs/fonts).
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RAW, ROOT = sys.argv[1], sys.argv[2]
STRIP = sys.argv[3] if len(sys.argv) > 3 else None
M = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "shots.json"), encoding="utf-8"))
FONT = os.path.join(ROOT, "docs/fonts/geologica-latin.woff2")
INK, BLUE = (13, 23, 54), (29, 78, 216)
BG_TOP, BG_BOTTOM = (238, 243, 255), (205, 220, 254)


def font(size, weight=b"Bold"):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f


def rgb(hex_):
    return tuple(int(hex_[i:i + 2], 16) for i in (1, 3, 5))


def background(w, h, glow, accent):
    """Soft blue from top to bottom with a glow in the shot's colour, so a row of thumbnails is not monotone."""
    mask = Image.linear_gradient("L").resize((w, h))
    im = Image.composite(Image.new("RGB", (w, h), BG_BOTTOM), Image.new("RGB", (w, h), BG_TOP), mask)
    layer = Image.new("L", (w, h), 0)
    ImageDraw.Draw(layer).ellipse(glow, fill=150)
    layer = layer.filter(ImageFilter.GaussianBlur(min(w, h) // 5))
    return Image.composite(Image.new("RGB", (w, h), rgb(accent)), im, layer)


def wrap(d, text, f, width):
    """Fewest lines that fit, and among those the most even ones (no lone word on the second line)."""
    words = text.split()
    size = lambda ws: d.textlength(" ".join(ws).replace("*", ""), font=f)
    if size(words) <= width:
        return [text]
    two = [(max(size(words[:k]), size(words[k:])), k) for k in range(1, len(words))]
    fits = [t for t in two if t[0] <= width]
    if fits:
        k = min(fits)[1]
        return [" ".join(words[:k]), " ".join(words[k:])]
    lines, cur = [], []
    for w in words:
        if cur and size(cur + [w]) > width:
            lines.append(" ".join(cur))
            cur = []
        cur.append(w)
    return lines + [" ".join(cur)]


def headline(im, text, x0, y, width, size, align="center"):
    """The headline, the part in *stars* in Ordo blue; returns the bottom y."""
    d = ImageDraw.Draw(im)
    while size > 60 and len(wrap(d, text, font(size), width)) > 2:  # never more than two lines
        size -= 4
    f = font(size)
    accent = False
    for line in wrap(d, text, f, width):
        lw = d.textlength(line.replace("*", ""), font=f)
        x = x0 + (width - lw) / 2 if align == "center" else x0
        for k, piece in enumerate(line.split("*")):
            if k:
                accent = not accent
            d.text((x, y), piece, font=f, fill=BLUE if accent else INK)
            x += d.textlength(piece, font=f)
        y += int(size * 1.14)
    return y


def raw(lang, kind, shot, name):
    """A raw shot without the system bars (they show the capture device's own icons and clock), cropped if asked."""
    p = os.path.join(RAW, lang, kind.get("prefix", "") + name + ".png")
    if not os.path.exists(p):
        return None
    im = Image.open(p).convert("RGB")
    w, h = im.size
    top, bottom = shot.get("bars") or kind["bars"]["portrait" if h > w else "landscape"]
    im = im.crop((0, top, w, h - bottom))
    if "crop" in shot:  # the part of the screen that matters, as fractions: left, top, right, bottom
        l, t, r, b = shot["crop"]
        im = im.crop((round(l * im.width), round(t * im.height), round(r * im.width), round(b * im.height)))
    return im


def device(shot, width, radius, bezel):
    """The screenshot in a slim dark device body with rounded corners and a soft shadow (RGBA, with margin pad)."""
    h = round(shot.height * width / shot.width)
    shot = shot.resize((width, h), Image.LANCZOS)
    pad = 150  # room for the whole blurred shadow, else its cut edge shows
    W, H = width + 2 * bezel, h + 2 * bezel
    out = Image.new("RGBA", (W + 2 * pad, H + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("L", out.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle([pad, pad + 26, pad + W, pad + H + 26], radius + bezel, fill=110)
    out.paste(Image.new("RGBA", out.size, (20, 40, 110, 255)), (0, 0), shadow.filter(ImageFilter.GaussianBlur(36)))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle([pad, pad, pad + W - 1, pad + H - 1], radius + bezel, fill=(16, 18, 24, 255))
    d.rounded_rectangle([pad + 1, pad + 1, pad + W - 2, pad + H - 2], radius + bezel - 1, outline=(70, 78, 96, 255), width=2)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, h - 1], radius, fill=255)
    out.paste(shot, (pad + bezel, pad + bezel), mask)
    return out, pad


def put(canvas, dev, pad, x, y, angle=0):
    if angle:
        w0, h0 = dev.size
        dev = dev.rotate(angle, resample=Image.BICUBIC, expand=True)
        x, y = x - (dev.width - w0) // 2, y - (dev.height - h0) // 2
    canvas.paste(dev, (x - pad, y - pad), dev)


def trio(im, top, shots, k):
    """The suite at a glance: two upright screens side by side, a wide film across them at the bottom."""
    W, H = im.size
    left, right, film = shots
    for shot, x, angle in ((left, round(W * 0.06), 5), (right, round(W * 0.50), -5)):
        dev, pad = device(shot, round(W * 0.44), k["radius"] * 2 // 3, k["bezel"])
        put(im, dev, pad, x, top + 10, angle)
    dev, pad = device(film, round(W * 0.84), k["radius"] * 2 // 3, k["bezel"])
    put(im, dev, pad, (W - dev.width + 2 * pad) // 2, H - dev.height + 2 * pad - round(H * 0.045))


def stack(im, top, shots, k):
    """Wide shots one above the other, whole, centred in the space below the headline."""
    W, H = im.size
    devs = [device(s, round(W * 0.9), k["radius"] * 2 // 3, k["bezel"]) for s in shots]
    gap = 70
    y = top + (H - top - sum(d.height - 2 * p for d, p in devs) - gap * (len(devs) - 1)) // 2
    for dev, pad in devs:
        put(im, dev, pad, (W - dev.width + 2 * pad) // 2, y)
        y += dev.height - 2 * pad + gap


def image(lang, kind, shot):
    """One store image, or None when a raw shot is missing."""
    shots = [raw(lang, kind, shot, n) for n in shot["raw"]]
    if None in shots:
        return None
    W, H = kind["canvas"]
    wide = W > H
    im = background(W, H, (W * 0.3, H * 0.1, W, H) if wide else (W * 0.08, H * 0.36, W * 0.92, H), shot["accent"])
    top = headline(im, shot["headline"][lang], 80, 62 if wide else 118, W - 160, kind["headline"]) + (44 if wide else 70)
    layout = shot.get("layout", "device")
    if layout == "trio":
        trio(im, top, shots, kind)
    elif layout == "stack":
        stack(im, top, shots, kind)
    else:  # one device; a tall one runs off the bottom edge like a phone in the hand
        dev, pad = device(shots[0], shot.get("width", kind["width"]), kind["radius"], kind["bezel"])
        put(im, dev, pad, (W - dev.width + 2 * pad) // 2, top)
    return im


def feature(lang):
    """1024x500: the name as a word and one promise on the left, the suite's screens on the right. No app icon:
    Play shows it next to the graphic."""
    F = M["feature"]
    W, H = 1024, 500
    im = background(W, H, (380, 60, 1100, 620), F["accent"])
    ImageDraw.Draw(im).text((56, 96), "Ordo", font=font(132), fill=INK)
    headline(im, F["headline"][lang], 60, 276, 450, 40, align="left")
    phone = M["sets"]["phoneScreenshots"]
    get = lambda n: raw(lang, phone, {}, n)
    a, b, film = (get(n) for n in F["raw"])
    if a and b and film:
        dev, pad = device(b, 190, 18, 7)
        put(im, dev, pad, 770, 96, -5)
        dev, pad = device(a, 210, 20, 7)
        put(im, dev, pad, 520, 60, 4)
        dev, pad = device(film, 400, 16, 7)
        put(im, dev, pad, 556, 286)
    return im


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.convert("RGB").save(path, quality=93, optimize=True)


def strip(images, path):
    """The set as Play's small thumbnails (200 px wide), side by side."""
    thumbs = [im.resize((200, round(im.height * 200 / im.width)), Image.LANCZOS) for im in images]
    out = Image.new("RGB", (sum(t.width + 8 for t in thumbs) + 8, max(t.height for t in thumbs) + 16), "white")
    x = 8
    for t in thumbs:
        out.paste(t, (x, 8))
        x += t.width + 8
    save(out, path)


for lang, locale in M["locales"].items():
    if not os.path.isdir(os.path.join(RAW, lang)):
        continue
    base = os.path.join(ROOT, "fastlane/metadata/android", locale, "images")
    for name, kind in M["sets"].items():
        made = []
        for shot in kind["shots"]:
            im = image(lang, kind, shot)
            if im:
                made.append((shot["id"], im))
            else:
                print(f"{lang} {name}: no raw shot for {shot['id']}, left out")
        if not made:  # no raw shots for this device class: its old images stay
            continue
        d = os.path.join(base, name)
        for f in os.listdir(d) if os.path.isdir(d) else []:
            os.remove(os.path.join(d, f))
        for i, (id_, im) in enumerate(made, 1):
            save(im, os.path.join(d, f"{i:02d}_{id_}.jpg"))
        if STRIP:
            strip([im for _, im in made], os.path.join(STRIP, f"strip-{lang}-{name}.jpg"))
    save(feature(lang), os.path.join(base, "featureGraphic.png"))
    print(lang, "done")
