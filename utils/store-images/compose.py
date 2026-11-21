#!/usr/bin/env python3
"""Play Store screenshots and feature graphic for Ordo: real device screenshots in a device frame on a soft Ordo
blue, one short headline per image. Reproducible: the same raw shots give the same images.

Usage: compose.py <raw dir> <repo root>
<raw dir>/<en|de>/<name>.png are raw screenshots from capture.sh (only taken while Ordo is in front, demo media only):
phone shots without prefix, 10-inch shots as tablet-<name>.png, 7-inch shots as seven-<name>.png. A shot that is
missing in the raw dir is taken from docs/screenshots/<en|de>/ (older phone captures with demo data).
Writes fastlane/metadata/android/<locale>/images/{phone,sevenInch,tenInch}Screenshots/*.jpg and featureGraphic.png.

Rules followed (Play Console help "Add preview assets", read 21.11.2026): 9:16 at 1080x1920 and 16:9 at 1920x1080,
the UI is the main thing, the headline takes far less than 20 % of the image, no small text, no "best", "free" or
call to action, no pure white or black background, and no app icon on the feature graphic (it would repeat the icon).
Font: Geologica (OFL), the font of Ordo's website (docs/fonts).
"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RAW, ROOT = sys.argv[1], sys.argv[2]
FONT = os.path.join(ROOT, "docs/fonts/geologica-latin.woff2")
INK, BLUE = (13, 23, 54), (29, 78, 216)
BG_TOP, BG_BOTTOM, GLOW = (238, 243, 255), (205, 220, 254), (96, 140, 250)
# The glow behind the device takes a colour from what the screen shows, so the row of thumbnails is not monotone.
TINT = {"music": (236, 110, 180), "lyrics": (236, 110, 180), "library": (150, 120, 250), "sound": (60, 196, 230),
        "ai": (60, 196, 230), "video": (250, 170, 90), "more": (250, 170, 90), "picture": (250, 170, 90)}

HEAD = {
    "en": {
        "suite": "Files, music and video. *One app.*",
        "music": "Your music in *full colour*",
        "video": "Films with *subtitles that fit*",
        "sound": "Shape *your sound*",
        "picture": "*HDR films* on any screen",
        "ai": "Just say *what you need*",
        "storage": "See what fills *your storage*",
        "lyrics": "Sing along with *synced lyrics*",
        "library": "All your music, *beautifully sorted*",
        "files": "Every file, *in order*",
        "more": "Picture and sound, *your way*",
    },
    "de": {
        "suite": "Dateien, Musik und Video. *Eine App.*",
        "music": "Deine Musik in *voller Farbe*",
        "video": "Filme mit *passenden Untertiteln*",
        "sound": "Forme *deinen Klang*",
        "picture": "*HDR-Filme* auf jedem Bildschirm",
        "ai": "Sag einfach, *was du brauchst*",
        "storage": "Sieh, was deinen *Speicher füllt*",
        "lyrics": "Sing mit – *Songtexte im Takt*",
        "library": "Deine Musik, *schön sortiert*",
        "files": "Jede Datei *in Ordnung*",
        "more": "Bild und Ton, *wie du willst*",
    },
}
LOCALE = {"en": "en-US", "de": "de-DE"}
PHONE = ["suite", "music", "video", "sound", "lyrics", "ai", "storage", "library"]
# Tablets: no storage (a tablet shows its owner's folders), no lyrics or signal path (too small to read there).
TABLET = ["library", "music", "video", "more", "sound"]
SEVEN = ["library", "music", "video", "sound", "lyrics"]
SHOT = {"picture": "signal"}  # headline -> raw shot, where they differ


def font(size, weight=b"Bold"):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f


def background(w, h, glow, name=""):
    mask = Image.linear_gradient("L").resize((w, h))
    im = Image.composite(Image.new("RGB", (w, h), BG_BOTTOM), Image.new("RGB", (w, h), BG_TOP), mask)
    layer = Image.new("L", (w, h), 0)
    ImageDraw.Draw(layer).ellipse(glow, fill=150)
    layer = layer.filter(ImageFilter.GaussianBlur(min(w, h) // 5))
    return Image.composite(Image.new("RGB", (w, h), TINT.get(name, GLOW)), im, layer)


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


# Galaxy Tab S9 Ultra with gesture handle: as it is (280 dpi) and at 600 dp wide like a 7-inch tablet (493 dpi).
TABLET_BARS = {"tablet": (50, 44), "seven": (80, 60)}


def raw(lang, name):
    """A raw shot with the system bars cut off (they show the capture device's own icons and clock)."""
    p = os.path.join(RAW, lang, name + ".png")
    if not os.path.exists(p):
        p = os.path.join(ROOT, "docs/screenshots", lang, name.split("-")[-1] + ".png")
        if not os.path.exists(p):
            return None
    im = Image.open(p).convert("RGB")
    w, h = im.size
    # Status bar on top and navigation bar or gesture handle at the bottom, in pixels of the capture (measured).
    bars = {(1080, 2280): (63, 126),   # phone layout, upright, three-button navigation (W90)
            (2280, 1080): (64, 44),    # phone layout, landscape, gesture handle (Tab S9 Ultra)
            (540, 1200): (66, 30)}     # docs/screenshots (emulator)
    top, bottom = TABLET_BARS.get(name.split("-")[0]) or bars.get((w, h), (0, 0))
    return im.crop((0, top, w, h - bottom))


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


def phone_image(lang, name):
    W, H = 1080, 1920
    im = background(W, H, (90, 700, 990, 1900), name)
    top = headline(im, HEAD[lang][name], 80, 118, W - 160, 92) + 70
    if name == "suite":
        files, music, film = raw(lang, "files"), raw(lang, "music"), raw(lang, "video")
        for shot, x, angle in ((files, 70, 6), (music, 530, -6)):
            dev, pad = device(shot, 470, 40, 14)
            put(im, dev, pad, x, top + 10, angle)
        dev, pad = device(film, 900, 34, 14)
        put(im, dev, pad, (W - dev.width + 2 * pad) // 2, H - dev.height + 2 * pad - 90)
        return im
    if name == "video":  # two landscape player shots
        stacked(im, top, [raw(lang, "video"), raw(lang, "more")], 1150, 36, 14, shift=150)
        return im
    shot = raw(lang, name)
    if name == "ai":  # the older emulator capture: start at the top of the AI sheet, not at the empty screen above
        shot = shot.crop((0, round(shot.height * 0.22), shot.width, shot.height))
    dev, pad = device(shot, 800, 58, 18)
    put(im, dev, pad, (W - dev.width + 2 * pad) // 2, top)  # runs off the bottom edge like a phone in the hand
    return im


def stacked(im, top, shots, width, radius, bezel, shift=24):
    """Landscape shots one above the other, staggered, centred in the space below the headline."""
    W, H = im.size
    devs = [device(s, width, radius, bezel) for s in shots]
    gap = 70
    y = top + (H - top - sum(d.height - 2 * p for d, p in devs) - gap * (len(devs) - 1)) // 2
    for (dev, pad), dx in zip(devs, (-shift, shift)):
        put(im, dev, pad, (W - dev.width + 2 * pad) // 2 + dx, y)
        y += dev.height - 2 * pad + gap


def seven_image(lang, name):
    W, H = 1080, 1920
    im = background(W, H, (90, 700, 990, 1900), name)
    top = headline(im, HEAD[lang][name], 80, 118, W - 160, 92) + 70
    if name == "video":  # the film is landscape on a 7-inch tablet too
        stacked(im, top, [raw(lang, "seven-video"), raw(lang, "seven-more")], 1000, 24, 18)
        return im
    dev, pad = device(raw(lang, "seven-" + SHOT.get(name, name)), 860, 30, 26)
    put(im, dev, pad, (W - dev.width + 2 * pad) // 2, top)
    return im


def tablet_image(lang, name):
    W, H = 1920, 1080
    im = background(W, H, (760, 80, 1900, 1080), name)
    d = ImageDraw.Draw(im)
    lines = len(wrap(d, HEAD[lang][name], font(80), 540))
    headline(im, HEAD[lang][name], 110, (H - lines * 91) // 2 - 10, 540, 80, align="left")
    dev, pad = device(raw(lang, "tablet-" + SHOT.get(name, name)), 1100, 26, 24)
    put(im, dev, pad, W - (dev.width - 2 * pad) - 90, (H - dev.height + 2 * pad) // 2)
    return im


def feature(lang):
    """1024x500: the music player and a film side by side under one line; no icon (Play shows it next to this)."""
    W, H = 1024, 500
    im = background(W, H, (300, 120, 1000, 600))
    # Not the first screenshot's line: Play shows both side by side and asks not to repeat a message.
    tag = {"en": "Music, films and files. *Offline.*", "de": "Musik, Filme und Dateien. *Offline.*"}[lang]
    headline(im, tag, 50, 46, W - 100, 50)
    dev, pad = device(raw(lang, "video"), 560, 18, 8)
    put(im, dev, pad, 380, 170)
    dev, pad = device(raw(lang, "music"), 170, 16, 7)
    put(im, dev, pad, 160, 150, 4)
    return im


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.convert("RGB").save(path, quality=93, optimize=True)


for lang in HEAD:
    if not os.path.isdir(os.path.join(RAW, lang)):
        continue
    base = os.path.join(ROOT, "fastlane/metadata/android", LOCALE[lang], "images")
    have = lambda n: os.path.exists(os.path.join(RAW, lang, n + ".png"))
    sets = {"phoneScreenshots": (PHONE, phone_image),
            "tenInchScreenshots": ([n for n in TABLET if have("tablet-" + SHOT.get(n, n))], tablet_image),
            "sevenInchScreenshots": ([n for n in SEVEN if have("seven-" + SHOT.get(n, n))], seven_image)}
    for kind, (names, make) in sets.items():
        if not names:  # no raw shots for this device class: its old images stay
            continue
        d = os.path.join(base, kind)
        for f in os.listdir(d) if os.path.isdir(d) else []:
            os.remove(os.path.join(d, f))
        for i, name in enumerate(names, 1):
            save(make(lang, name), os.path.join(d, f"{i:02d}_{name}.jpg"))
    save(feature(lang), os.path.join(base, "featureGraphic.png"))
    print(lang, "done")
