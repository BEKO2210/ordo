#!/usr/bin/env python3
"""Store screenshot for the music player in the style of the Play set (#47): the background is lifted from an existing
store image (its text and phone masked out and filled from the surroundings), then kicker pill, two-part headline with
the cyan-to-green accent, subline and the screenshot in a phone frame are drawn on top. 1080x1920.
Usage: store-music.py <template.jpg> <screenshot.png> <kicker> <title white> <title accent> <subline> <out.jpg>"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = "/home/belkis/ordo-team/shots/fonts/Inter.ttf"
W, H = 1080, 1920
ACCENT = [(33, 212, 235), (55, 209, 159)]


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


def background(template):
    im = np.asarray(Image.open(template).convert("RGB").resize((W, H)), dtype=np.float32)
    keep = np.ones((H, W), np.float32)
    keep[60:380, :] = 0                 # pill, headline, subline
    keep[480:1890, 200:880] = 0         # phone
    # Normalised block average: the masked areas take the mean colour of the background around them.
    B = 40
    def blocks(a):
        return a.reshape(H // B, B, W // B, B, *a.shape[2:]).mean(axis=(1, 3))
    k = blocks(keep)
    avg = blocks(im * keep[..., None]) / np.maximum(k, 1e-3)[..., None]
    # Blocks that were fully masked borrow from their neighbours.
    for _ in range(12):
        empty = k < 0.05
        if not empty.any():
            break
        pad = np.pad(avg * (~empty)[..., None], ((1, 1), (1, 1), (0, 0)))
        cnt = np.pad((~empty).astype(np.float32), 1)
        s_ = sum(pad[1 + dy:pad.shape[0] - 1 + dy, 1 + dx:pad.shape[1] - 1 + dx] for dy in (-1, 0, 1) for dx in (-1, 0, 1))
        c_ = sum(cnt[1 + dy:cnt.shape[0] - 1 + dy, 1 + dx:cnt.shape[1] - 1 + dx] for dy in (-1, 0, 1) for dx in (-1, 0, 1))
        fill = s_ / np.maximum(c_, 1e-3)[..., None]
        avg = np.where(empty[..., None], fill, avg)
        k = np.where(empty & (c_ > 0), 1.0, k)
    up = np.asarray(Image.fromarray(avg.clip(0, 255).astype(np.uint8)).resize((W, H), Image.BICUBIC), dtype=np.float32)
    filled = up
    smooth = np.asarray(Image.fromarray(filled.clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(60)))
    return Image.fromarray(smooth)


def gradient_text(img, xy, text, f):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).text(xy, text, font=f, fill=255)
    x0, _, x1, _ = mask.getbbox()
    grad = Image.new("RGB", img.size)
    gd = ImageDraw.Draw(grad)
    for x in range(x0, x1 + 1):
        t = (x - x0) / max(1, x1 - x0)
        gd.line([(x, 0), (x, H)], fill=tuple(int(a + (b - a) * t) for a, b in zip(*ACCENT)))
    img.paste(grad, (0, 0), mask)


def phone(shot, width):
    im = Image.open(shot).convert("RGB")
    h = int(im.height * width / im.width)
    im = im.resize((width, h), Image.LANCZOS)
    bezel, radius = 16, 62
    frame = Image.new("RGBA", (width + 2 * bezel, h + 2 * bezel), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], radius + bezel,
                                            fill=(22, 27, 34, 255), outline=(70, 82, 98, 255), width=3)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, h - 1], radius, fill=255)
    frame.paste(im, (bezel, bezel), mask)
    return frame


def compose(template, shot, kicker, white, accent, sub, out):
    img = background(template).convert("RGBA")
    d = ImageDraw.Draw(img)
    # Kicker pill
    fk = font(30, b"SemiBold")
    label = " ".join(kicker.upper())  # letter spacing
    label = kicker.upper()
    tw = d.textlength(label, font=fk) + len(label) * 2
    pw, ph = tw + 92, 56
    px, py = (W - pw) / 2, 96
    d.rounded_rectangle([px, py, px + pw, py + ph], ph / 2, fill=(20, 92, 107, 150), outline=(41, 150, 170), width=2)
    d.ellipse([px + 26, py + ph / 2 - 6, px + 38, py + ph / 2 + 6], fill=ACCENT[0])
    x = px + 52
    for ch in label:
        d.text((x, py + 11), ch, font=fk, fill=ACCENT[0])
        x += d.textlength(ch, font=fk) + 2
    # Headline: white part, then the accent part (same line when it fits, else below)
    ft = font(86, b"Bold")
    line = white + " " + accent
    y = 186
    if d.textlength(line, font=ft) <= W - 120:
        x0 = (W - d.textlength(line, font=ft)) / 2
        d.text((x0, y), white, font=ft, fill=(255, 255, 255))
        gradient_text(img, (x0 + d.textlength(white + " ", font=ft), y), accent, ft)
        y += 110
    else:
        d.text(((W - d.textlength(white, font=ft)) / 2, y), white, font=ft, fill=(255, 255, 255))
        y += 100
        gradient_text(img, ((W - d.textlength(accent, font=ft)) / 2, y), accent, ft)
        y += 110
    fs = font(38, b"Regular")
    d = ImageDraw.Draw(img)
    words, lines, cur = sub.split(), [], ""
    for w_ in words:
        test = (cur + " " + w_).strip()
        if d.textlength(test, font=fs) <= W - 220:
            cur = test
        else:
            lines.append(cur)
            cur = w_
    lines.append(cur)
    y += 8
    for l in lines:
        d.text(((W - d.textlength(l, font=fs)) / 2, y), l, font=fs, fill=(176, 190, 200))
        y += 50
    top = max(500, int(y) + 60)
    pwid = int((H - 58 - top) / (2400 / 1080)) - 32
    p = phone(shot, min(600, pwid))
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([(W - p.width) // 2 + 14, top + 36, (W + p.width) // 2 - 14, top + p.height + 30],
                                             90, fill=(0, 0, 0, 160))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(36)))
    img.alpha_composite(p, ((W - p.width) // 2, top))
    img.convert("RGB").save(out, quality=92)


if __name__ == "__main__":
    compose(*sys.argv[1:8])
    print("ok", sys.argv[7])
