#!/usr/bin/env python3
"""Demo media for the Play screenshots: no personal files ever appear in a store image.

Usage: demo_media.py <out dir>
Writes <out>/Music/OrdoDemo/*.mp3 (fictional albums with generated abstract covers, silent audio of real song length,
one synced .lrc) and <out>/Movies/OrdoDemo/ with clips of the Blender open movies "Tears of Steel" (CC BY 3.0,
(c) Blender Foundation | mango.blender.org) and the "Sintel" trailer (CC BY 3.0, (c) Blender Foundation |
durian.blender.org), the official ToS subtitles (en, de) shifted to the clip, and an HDR10 (PQ, BT.2020) version
for the HDR shot. Needs ffmpeg with libx264, libx265 and zscale, and network access to download.blender.org.
"""
import os, random, re, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter

out = sys.argv[1]
music, movies = os.path.join(out, "Music", "OrdoDemo"), os.path.join(out, "Movies", "OrdoDemo")
os.makedirs(music, exist_ok=True)
os.makedirs(movies, exist_ok=True)
ff = ["ffmpeg", "-v", "error", "-y"]

ALBUMS = [  # album, artist, two colours, songs (title, seconds)
    ("Midnight Drive", "Nova Reed", (24, 32, 96), (236, 72, 153), [("City Lights", 214), ("Afterglow", 197), ("Night Bus", 231)]),
    ("Paper Moons", "Ivy Hale", (250, 204, 21), (244, 63, 94), [("Paper Moons", 203), ("Golden Hour", 189)]),
    ("Coastal Lines", "Low Tide", (14, 116, 144), (52, 211, 153), [("Salt and Sun", 226), ("Harbour", 181)]),
    ("Neon Garden", "The Lanterns", (88, 28, 135), (34, 211, 238), [("Bloom", 242), ("Electric Rain", 208)]),
    ("Northern Light", "Aurora Field", (15, 23, 42), (74, 222, 128), [("Polar Night", 255), ("First Snow", 199)]),
    ("Slow Mornings", "June Avery", (251, 146, 60), (253, 230, 138), [("Coffee & Rain", 186), ("Sunday", 174)]),
    ("Glass City", "Metro Kids", (30, 64, 175), (147, 197, 253), [("Skyline", 219), ("Elevator", 192)]),
    ("Wild Fields", "Ember & Oak", (101, 163, 13), (250, 204, 21), [("Open Road", 233), ("Fireflies", 205)]),
]
LYRICS = ["City lights are calling out my name", "Every street a river, every sign a flame",
          "We drive until the morning finds us here", "Windows down, the radio is clear",
          "Hold on, hold on, the night is young", "Every song we know is still unsung"]


def cover(path, a, b, seed):
    rnd = random.Random(seed)
    n = 1200
    im = Image.new("RGB", (n, n), a)
    d = ImageDraw.Draw(im)
    for y in range(n):  # diagonal-ish vertical blend
        t = y / n
        d.line([(0, y), (n, y)], fill=tuple(int(a[k] * (1 - t) + b[k] * t) for k in range(3)))
    for _ in range(5):
        r = rnd.randint(180, 520)
        x, y = rnd.randint(0, n), rnd.randint(0, n)
        c = b if rnd.random() < .5 else tuple(min(255, v + 60) for v in a)
        d.ellipse([x - r, y - r, x + r, y + r], fill=c)
    im = im.filter(ImageFilter.GaussianBlur(90))
    noise = Image.effect_noise((n, n), 18).convert("RGB")
    Image.blend(im, noise, 0.04).save(path, quality=92)


def songs():
    for i, (album, artist, a, b, tracks) in enumerate(ALBUMS):
        art = os.path.join(out, f"cover{i}.jpg")
        cover(art, a, b, i)
        for no, (title, secs) in enumerate(tracks, 1):
            dst = os.path.join(music, f"{artist} - {title}.mp3")
            subprocess.run(ff + ["-f", "lavfi", "-i", f"anullsrc=r=44100:cl=stereo:d={secs}", "-i", art,
                                 "-map", "0", "-map", "1", "-c:a", "libmp3lame", "-b:a", "64k", "-c:v", "mjpeg",
                                 "-disposition:v", "attached_pic", "-id3v2_version", "3",
                                 "-metadata", f"title={title}", "-metadata", f"artist={artist}",
                                 "-metadata", f"album={album}", "-metadata", f"track={no}",
                                 "-metadata", "date=2026", "-metadata", "genre=Pop", dst], check=True)
        os.remove(art)
    with open(os.path.join(music, "Nova Reed - City Lights.lrc"), "w") as f:
        for k, line in enumerate(LYRICS):
            s = 8 + k * 6
            f.write(f"[{s // 60:02d}:{s % 60:02d}.00]{line}\n")


def shift(src, dst, off):
    t = subprocess.run(["curl", "-sf", src], capture_output=True, check=True).stdout.decode("utf-8-sig")
    def f(m):
        h, mi, s, ms = map(int, m.groups())
        v = max(0, h * 3600000 + mi * 60000 + s * 1000 + ms - off * 1000)
        return "%02d:%02d:%02d,%03d" % (v // 3600000, v // 60000 % 60, v // 1000 % 60, v % 1000)
    open(dst, "w", encoding="utf-8").write(re.sub(r"(\d\d):(\d\d):(\d\d),(\d\d\d)", f, t))


def videos():
    base = "https://download.blender.org"
    tos = os.path.join(movies, "Tears of Steel.mp4")
    subprocess.run(ff + ["-ss", "20", "-i", f"{base}/demo/movies/ToS/tears_of_steel_720p.mov", "-t", "50",
                         "-c:v", "libx264", "-crf", "20", "-preset", "fast", "-pix_fmt", "yuv420p", "-c:a", "aac",
                         "-movflags", "+faststart", tos], check=True)
    for lang in ("en", "de"):
        shift(f"{base}/demo/movies/ToS/subtitles/TOS-{lang}.srt", tos[:-4] + f".{lang}.srt", 20)
    subprocess.run(ff + ["-i", tos, "-t", "25", "-vf",
                         "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,"
                         "zscale=t=linear:npl=203,format=gbrpf32le,"
                         "zscale=p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le",
                         "-c:v", "libx265", "-preset", "fast", "-crf", "20", "-tag:v", "hvc1", "-x265-params",
                         "log-level=error:hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:"
                         "colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)"
                         "WP(15635,16450)L(10000000,50):max-cll=1000,400",
                         "-c:a", "aac", os.path.join(movies, "Tears of Steel (HDR10).mp4")], check=True)
    subprocess.run(["curl", "-sfo", os.path.join(movies, "Sintel (Trailer).mp4"),
                    f"{base}/durian/trailer/sintel_trailer-1080p.mp4"], check=True)


songs()
videos()
print("ok", out)
