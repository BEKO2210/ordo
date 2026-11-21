#!/usr/bin/env python3
"""Demo media for the Play screenshots: no personal files ever appear in a store image.

Usage: demo_media.py <out dir> [songs] [videos] [files]   (all three without a choice)
Writes <out>/Music/OrdoDemo/*.mp3 (fictional albums with the covers of art/covers, silent audio of real song length,
one synced .lrc), <out>/Documents/OrdoDemo/ (a trip folder with the photos of art/photos and a folder of private
papers for the vault shot, each with English and German names) and <out>/Movies/OrdoDemo/ with clips of the Blender open movies "Tears of Steel" (CC BY 3.0,
(c) Blender Foundation | mango.blender.org) and the "Sintel" trailer (CC BY 3.0, (c) Blender Foundation |
durian.blender.org), the official ToS subtitles (en, de) shifted to the clip, and an HDR10 (PQ, BT.2020) version
for the HDR shot. Needs ffmpeg with libx264, libx265 and zscale, and network access to download.blender.org.
"""
import os, re, subprocess, sys, zipfile
from PIL import Image, ImageDraw

out = sys.argv[1]
music, movies = os.path.join(out, "Music", "OrdoDemo"), os.path.join(out, "Movies", "OrdoDemo")
os.makedirs(music, exist_ok=True)
os.makedirs(movies, exist_ok=True)
ff = ["ffmpeg", "-v", "error", "-y"]
ART = os.path.join(os.path.dirname(os.path.abspath(__file__)), "art")

ALBUMS = [  # album, artist, songs (title, seconds)
    ("Midnight Drive", "Nova Reed", [("City Lights", 214), ("Afterglow", 197), ("Night Bus", 231)]),
    ("Paper Moons", "Ivy Hale", [("Paper Moons", 203), ("Golden Hour", 189)]),
    ("Coastal Lines", "Low Tide", [("Salt and Sun", 226), ("Harbour", 181)]),
    ("Neon Garden", "The Lanterns", [("Bloom", 242), ("Electric Rain", 208)]),
    ("Northern Light", "Aurora Field", [("Polar Night", 255), ("First Snow", 199)]),
    ("Slow Mornings", "June Avery", [("Coffee & Rain", 186), ("Sunday", 174)]),
    ("Glass City", "Metro Kids", [("Skyline", 219), ("Elevator", 192)]),
    ("Wild Fields", "Ember & Oak", [("Open Road", 233), ("Fireflies", 205)]),
]
LYRICS = ["City lights are calling out my name", "Every street a river, every sign a flame",
          "We drive until the morning finds us here", "Windows down, the radio is clear",
          "Hold on, hold on, the night is young", "Every song we know is still unsung"]


def cover(path, album):
    """The album's cover from art/covers (AI pictures, see art/prompts.json)."""
    Image.open(os.path.join(ART, "covers", album + ".jpg")).resize((1000, 1000), Image.LANCZOS).save(path, quality=90)


def songs():
    for i, (album, artist, tracks) in enumerate(ALBUMS):
        art = os.path.join(out, f"cover{i}.jpg")
        cover(art, album)
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


# English | German names; the folder shots show one language each.
TRIP = ("Lisbon", "Lissabon")  # one word: a command to the AI names a folder by one word
PHOTOS = {"tram": ("Tram 28", "Tram 28"), "tiles": ("Blue tiles", "Blaue Fliesen"),
          "cliffs": ("Cliffs at sunset", "Klippen im Abendlicht"), "pastry": ("Coffee break", "Kaffeepause"),
          "rooftops": ("Rooftops", "Über den Dächern"), "beach": ("Beach", "Strand"),
          "alley": ("Old town", "Altstadt"), "bridge": ("Bridge at dusk", "Brücke am Abend")}
FOLDERS = (("Maps", "Karten"), ("Tickets", "Tickets"), ("Postcards", "Postkarten"))
PAPERS_DIR = ("Papers", "Papiere")  # under .papers: capture.sh copies one out and moves the copy into the vault
PAPERS = (("Passport.pdf", "Reisepass.pdf"), ("Insurance policy.pdf", "Versicherung.pdf"),
          ("Rental contract.pdf", "Mietvertrag.pdf"), ("Tax return 2025.pdf", "Steuererklärung 2025.pdf"),
          ("Vaccination card.jpg", "Impfpass.jpg"), ("Recovery codes.txt", "Wiederherstellungscodes.txt"),
          ("Bank statement.pdf", "Kontoauszug.pdf"), ("Payslip March.pdf", "Gehaltsabrechnung März.pdf"),
          ("ID card.jpg", "Personalausweis.jpg"), ("Birth certificate.pdf", "Geburtsurkunde.pdf"),
          ("Car papers.pdf", "Fahrzeugschein.pdf"), ("Diploma.pdf", "Zeugnis.pdf"))


def page(path, seed):
    """A sheet of paper with grey lines for text: a document without any real content."""
    im = Image.new("RGB", (800, 1100), "white")
    d = ImageDraw.Draw(im)
    d.rectangle([80, 90, 380 + seed * 40, 124], fill=(70, 90, 140))
    for y in range(190, 1000, 56):
        d.rectangle([80, y, 720 - (y * (seed + 5)) % 220, y + 16], fill=(200, 205, 215))
    im.save(path)


def files():
    docs = os.path.join(out, "Documents", "OrdoDemo")
    for k in (0, 1):
        trip = os.path.join(docs, TRIP[k])
        for folder in FOLDERS:
            os.makedirs(os.path.join(trip, folder[k]), exist_ok=True)
            open(os.path.join(trip, folder[k], "1.txt"), "w").write("demo\n")
        for key, names in PHOTOS.items():
            Image.open(os.path.join(ART, "photos", key + ".jpg")).save(os.path.join(trip, names[k] + ".jpg"), quality=88)
        page(os.path.join(trip, ("Itinerary.pdf", "Reiseplan.pdf")[k]), 1)
        open(os.path.join(trip, ("Packing list.txt", "Packliste.txt")[k]), "w").write("Camera\nSunscreen\n")
        with zipfile.ZipFile(os.path.join(trip, "Budget.xlsx"), "w") as z:  # only its name and icon are shown
            z.writestr("demo.txt", "demo")
        subprocess.run(ff + ["-ss", "4", "-i", os.path.join(movies, "Tears of Steel.mp4"), "-t", "6", "-c", "copy",
                             os.path.join(trip, ("Evening walk.mp4", "Abendspaziergang.mp4")[k])], check=True)
        private = os.path.join(docs, ".papers", PAPERS_DIR[k])
        os.makedirs(private, exist_ok=True)
        for seed, names in enumerate(PAPERS):
            dst = os.path.join(private, names[k])
            if dst.endswith(".txt"):
                open(dst, "w").write("A1B2-C3D4\nE5F6-G7H8\n")
            else:
                page(dst, seed)


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


for part in sys.argv[2:] or ("songs", "videos", "files"):  # files needs the videos
    globals()[part]()
print("ok", out)
