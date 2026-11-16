#!/usr/bin/env python3
"""Synthetic phone storage (no real data) + usage history.

    gen_tree.py            -> tree.jsonl (path, size, mtime, lat, lon, tags) and history.jsonl

Paths are relative to /storage/emulated/0. Fixed seed, so every run gives the same phone.
`tags` is ground truth for the bench only; the graph builder never reads it.
"""
import json
import random
from datetime import datetime, timedelta

R = random.Random(2210)
NOW = datetime(2026, 11, 15, 12, 0)
HOME = (48.7758, 9.1829)   # Stuttgart
LISBON = (38.7223, -9.1393)
HAMBURG = (53.5511, 9.9937)
LUDWIGSBURG = (48.8975, 9.1922)
files = []


def add(path, when, size, gps=None, tags=()):
    lat = lon = None
    if gps:
        lat, lon = round(gps[0] + R.uniform(-.02, .02), 5), round(gps[1] + R.uniform(-.02, .02), 5)
    files.append({"path": path, "size": int(size), "mtime": int(when.timestamp()), "lat": lat, "lon": lon,
                  "tags": list(tags)})


def stamp(d, fmt):
    return d.strftime(fmt)


def at(day, h0=8, h1=22):
    return day + timedelta(hours=R.randint(h0, h1 - 1), minutes=R.randint(0, 59), seconds=R.randint(0, 59))


MB = 1_000_000
# --- DCIM/Camera: everyday photos; quiet days around the trips keep their name prefixes unique
LIS = [datetime(2026, 7, d) for d in range(14, 20)]
HAM = [datetime(2025, 8, 2), datetime(2025, 8, 3)]
WED = datetime(2025, 6, 21)
quiet = {datetime(2026, 7, d).date() for d in range(10, 14)} | {datetime(2025, 8, d).date() for d in (1, 4, 5, 6, 7, 8, 9)}
day = datetime(2024, 1, 1)
while day < NOW - timedelta(days=1):
    special = day in LIS or day in HAM or day == WED
    if day.date() not in quiet and not special:
        for _ in range(R.choice([0, 0, 1, 1, 2, 3, 5])):
            t = at(day)
            add(f"DCIM/Camera/IMG_{stamp(t, '%Y%m%d_%H%M%S')}.jpg", t, R.uniform(2, 6) * MB,
                HOME if R.random() < .7 else None)
        if R.random() < .06:
            t = at(day)
            add(f"DCIM/Camera/VID_{stamp(t, '%Y%m%d_%H%M%S')}.mp4", t, R.uniform(40, 600) * MB, HOME)
    day += timedelta(days=1)
for d, gps, n, nv, tag in [(d, LISBON, 30, 2, "lissabon") for d in LIS] + \
        [(d, HAMBURG, 30, 2, "hamburg") for d in HAM] + [(WED, LUDWIGSBURG, 120, 3, "hochzeit")]:
    for i in range(n):
        t = at(d, 9, 23)
        add(f"DCIM/Camera/IMG_{stamp(t, '%Y%m%d_%H%M%S')}.jpg", t, R.uniform(2, 6) * MB, gps, [tag, "photo"])
    for i in range(nv):
        t = at(d, 9, 23)
        add(f"DCIM/Camera/VID_{stamp(t, '%Y%m%d_%H%M%S')}.mp4", t, R.uniform(60, 700) * MB, gps, [tag, "video"])

# --- Screenshots (Samsung naming: app at the end)
apps = ["WhatsApp"] * 5 + ["Instagram"] * 4 + ["Chrome"] * 4 + ["Maps"] * 2 + ["DB Navigator", "Sparkasse", "YouTube"]
for _ in range(600):
    t = at(datetime(2024, 1, 1) + timedelta(days=R.randint(0, 1048)))
    a = R.choice(apps)
    add(f"Pictures/Screenshots/Screenshot_{stamp(t, '%Y%m%d_%H%M%S')}_{a}.jpg", t, R.uniform(.2, 1.5) * MB,
        tags=["insta"] if a == "Instagram" else [])
for i in range(15):
    t = at(datetime(2024, 3, 1) + timedelta(days=R.randint(0, 900)))
    add(f"Pictures/Wallpapers/wallpaper_{i + 1}.jpg", t, R.uniform(1, 4) * MB)

# --- WhatsApp (Android 11+ location)
WA = "Android/media/com.whatsapp/WhatsApp/Media/"
for kind, n, pre, ext, lo, hi in [("WhatsApp Images", 900, "IMG", "jpg", .05, .4),
                                  ("WhatsApp Video", 150, "VID", "mp4", 3, 80),
                                  ("WhatsApp Audio", 80, "AUD", "opus", .02, 2)]:
    seq = {}
    for _ in range(n):
        d = datetime(2024, 6, 1) + timedelta(days=R.randint(0, 531))
        seq[d] = seq.get(d, 0) + 1
        add(f"{WA}{kind}/{pre}-{stamp(d, '%Y%m%d')}-WA{seq[d]:04d}.{ext}", at(d), R.uniform(lo, hi) * MB)
wa_docs = ["Einladung Sommerfest.pdf", "Speisekarte.pdf", "Elternbrief Klasse 3b.pdf", "Dienstplan November.xlsx",
           "Flyer Flohmarkt.pdf", "Vereinsbeitrag.pdf", "Präsentation Vertrieb.pptx", "Protokoll Elternabend.docx"]
for i in range(60):
    name = wa_docs[i % len(wa_docs)].replace(".", f" {i // len(wa_docs) + 1}." if i >= len(wa_docs) else ".")
    add(f"{WA}WhatsApp Documents/{name}", at(datetime(2024, 6, 1) + timedelta(days=R.randint(0, 531))),
        R.uniform(.1, 5) * MB)

# --- Download: not yet filed things, the car purchase, tax papers, junk
D = "Download/"
new = lambda days: at(NOW - timedelta(days=days))
for name, days, tags in [
    ("Rechnung_Telekom_2026-10.pdf", 38, ["rechnung", "telekom"]),
    ("Rechnung_Telekom_2026-11.pdf", 6, ["rechnung", "telekom"]),
    ("Rechnung_Stadtwerke_2026-Q3.pdf", 21, ["rechnung", "stadtwerke"]),
    ("Rechnung_Amazon_302-4471.pdf", 15, ["rechnung"]), ("Rechnung_Amazon_302-5820.pdf", 9, ["rechnung"]),
    ("Rechnung_Amazon_302-5820 (1).pdf", 9, ["rechnung", "dup"]),
    ("Kontoauszug_2026-09.pdf", 44, ["konto"]), ("Kontoauszug_2026-10.pdf", 14, ["konto"]),
    ("Verdienstnachweis_2026-10.pdf", 16, ["gehalt"]),
    ("Arztbrief_Dr_Weber_2026-10-28.pdf", 18, ["arzt"]),
    ("Bewerbungsfoto_1.jpg", 30, ["bewfoto"]), ("Bewerbungsfoto_2.jpg", 30, ["bewfoto"]),
    ("Lohnsteuerbescheinigung_2025.pdf", 200, ["steuer"]), ("Spendenquittung_2025_DRK.pdf", 280, ["steuer"]),
    ("Riester_Bescheinigung_2025.pdf", 230, ["steuer"]),
    ("Vonovia_Mieterhoehung_2026.pdf", 60, ["vonovia"]), ("Vonovia_Nebenkosten.pdf", 120, ["vonovia"]),
    ("Bordkarte_TAP_LIS.pdf", 124, []), ("Hotel_Lisboa_Buchung.pdf", 140, []),
]:
    add(D + name, new(days), R.uniform(.05, 2) * MB, tags=tags)
car = datetime(2026, 9, 12)
for i, name in enumerate(["Scan_20260912_0901.pdf", "Scan_20260912_0903.pdf", "Scan_20260912_0906.pdf",
                          "Scan_20260912_0910.pdf", "Scan_20260912_0914.pdf", "Scan_20260912_0915.pdf",
                          "Kaufvertrag_Auto_Golf.pdf", "Fahrzeugschein_Golf.jpg", "Versicherungsbestaetigung_KFZ.pdf",
                          "Finanzierung_Autobank.pdf"]):
    add(D + name, car + timedelta(hours=9, minutes=3 * i), R.uniform(.3, 3) * MB, tags=["autokauf"])
for d in ["20260305", "20260611", "20240120"]:
    for i in range(3):
        t = datetime.strptime(d, "%Y%m%d") + timedelta(hours=10, minutes=i)
        add(D + f"Scan_{d}_{1000 + i}.pdf", t, R.uniform(.3, 3) * MB)
junk = [("document", "pdf"), ("file", "pdf"), ("Ticket_DB", "pdf"), ("setup", "apk"), ("ROM_update", "zip"),
        ("podcast_ep", "mp3"), ("image", "jpg"), ("Anleitung", "pdf"), ("Fotos_Export", "zip"), ("Brief", "docx"),
        ("app-release", "apk"), ("song", "mp3"), ("Tabelle", "xlsx"), ("video", "mp4")]
for i in range(205):
    stem, ext = junk[i % len(junk)]
    size = R.uniform(.05, 30) * MB if ext not in ("zip", "mp4") else R.uniform(20, 1800) * MB
    t = at(datetime(2024, 1, 1) + timedelta(days=R.randint(0, 1048)))
    add(D + f"{stem}_{i + 1}.{ext}", t, size, tags=["apk"] if ext == "apk" else [])
    if i % 14 == 3:   # browser double downloads
        add(D + f"{stem}_{i + 1} (1).{ext}", t + timedelta(minutes=1), size, tags=["dup"])

# --- Documents: the places habits lead to
for folder, stem, n in [("Rechnungen", "Rechnung_Telekom", 14), ("Rechnungen", "Rechnung_Stadtwerke", 8),
                        ("Rechnungen", "Rechnung_Amazon", 18), ("Bank", "Kontoauszug", 24),
                        ("Gehalt", "Verdienstnachweis", 22), ("Gesundheit", "Arztbrief", 6),
                        ("Steuer 2024", "Beleg_2024", 10), ("Steuer 2025", "Beleg_2025", 6),
                        ("Wohnung", "Vonovia_Schreiben", 4), ("Bewerbungen", "Anschreiben", 5)]:
    for i in range(n):
        t = at(NOW - timedelta(days=60 + 30 * i))
        add(f"Documents/{folder}/{stem}_{t.strftime('%Y-%m')}_{i}.pdf", t, R.uniform(.05, 1.5) * MB)
for i, name in enumerate(["Lebenslauf.pdf", "Bewerbung_Bosch.docx", "Bewerbung_Porsche.docx", "Bewerbung_SAP.docx",
                          "Mietvertrag.pdf", "Notizen.txt", "Reisepass_Scan.pdf", "Impfpass.pdf", "Rezept_Lasagne.pdf",
                          "Haushaltsplan.xlsx"]):
    add(f"Documents/{name}", at(NOW - timedelta(days=20 + 40 * i)), R.uniform(.02, 2) * MB,
        tags=["bewerbung"] if name.startswith("Bewerbung_") else [])

# --- Music, Movies, Archiv, Backup
artists = ["Cro", "Peter Fox", "Kraftklub", "Apache 207", "Nina Chuba", "AnnenMayKantereit"]
for i in range(300):
    a = artists[i % len(artists)]
    add(f"Music/{a}/{a} - Track {i // len(artists) + 1:02d}.{'flac' if i % 7 == 0 else 'mp3'}",
        at(datetime(2024, 1, 1) + timedelta(days=R.randint(0, 900))), R.uniform(3, 40) * MB)
for i in range(20):
    add(f"Movies/Film_{i + 1:02d}.{'mkv' if i % 2 else 'mp4'}", at(datetime(2024, 2, 1) + timedelta(days=R.randint(0, 900))),
        R.uniform(.7, 4.2) * 1000 * MB)
for i in range(4):
    add(f"Archiv/alt_{i}.zip", at(datetime(2024, 5, 1) + timedelta(days=100 * i)), R.uniform(10, 300) * MB)
add("Backup/README.txt", at(datetime(2025, 1, 5)), 1200)

# --- usage history: what the user did before (the only source of "habits")
history = []
for i in range(14):
    history.append({"op": "move", "from": "Download", "to": "Documents/Rechnungen",
                    "name": f"Rechnung_{['Telekom', 'Amazon', 'Stadtwerke'][i % 3]}_{i}.pdf", "t": 1_700_000_000 + i * 2_600_000})
for stem, to, n in [("Kontoauszug", "Documents/Bank", 10), ("Verdienstnachweis", "Documents/Gehalt", 10),
                    ("Arztbrief", "Documents/Gesundheit", 4), ("Vonovia", "Documents/Wohnung", 3),
                    ("Bewerbung", "Documents/Bewerbungen", 2), ("Beleg_2025", "Documents/Steuer 2025", 3)]:
    for i in range(n):
        history.append({"op": "move", "from": "Download", "to": to, "name": f"{stem}_{i}.pdf", "t": 1_700_000_000 + i * 3_000_000})
for i in range(8):   # monthly clean-up of WhatsApp videos, each older than ~a month at deletion
    history.append({"op": "delete", "from": WA + "WhatsApp Video", "name": f"VID-x-WA{i:04d}.mp4",
                    "age_days": R.randint(31, 45), "t": 1_710_000_000 + i * 2_600_000})
for i in range(5):
    history.append({"op": "delete", "from": "Download", "name": f"setup_{i}.apk", "age_days": R.randint(1, 20),
                    "t": 1_710_000_000 + i * 4_000_000})
for i in range(3):
    history.append({"op": "organize", "from": WA + "WhatsApp Documents", "group_by": "month", "t": 1_720_000_000 + i * 5_000_000})

# --- Phase 0 (Gedächtnis): a real answer for each of Belkis' 20 commands. Own seed and appended last, so every file
# and event above stays byte-identical to the earlier lab runs. `text` = what the app could extract from a document.
R0 = random.Random(2211)
YESTERDAY = datetime(2026, 11, 14)


def add0(path, when, size, tags=(), text=None):
    files.append({"path": path, "size": int(size), "mtime": int(when.timestamp()), "lat": None, "lon": None,
                  "tags": list(tags), **({"text": text} if text else {})})


for name, h, m in [("Grundriss_Wohnung.jpg", 10, 12), ("Sofa_Angebot.jpg", 13, 40), ("Kinoplakat.jpg", 20, 5)]:
    add0(D + name, YESTERDAY + timedelta(hours=h, minutes=m), R0.uniform(.3, 3) * MB, ["dl_gestern"])
add0(D + "Konzertticket.pdf", YESTERDAY + timedelta(hours=19), .2 * MB, ["dl_gestern"])
add0(D + "Signal-Android-7.24.apk", NOW - timedelta(hours=2, minutes=30), 92 * MB, ["apk", "apk_last"])
for i, name in enumerate(["Meme_Montag.png", "Vereinslogo.png", "Stadtplan_Ausschnitt.png"]):
    add0(D + name, datetime(2026, 8, 3 + 9 * i, 18), R0.uniform(.1, 1) * MB, ["png"])
# insurance: the word is only inside two of the PDFs; a spreadsheet and a photo carry it in the name only
add0("Documents/Police_HUK_2026.pdf", datetime(2026, 1, 9, 11), .4 * MB, ["versicherung"],
     "HUK-COBURG Versicherungsschein Kfz-Haftpflichtversicherung, Versicherungsnummer 4471-22")
add0(D + "Schreiben_2026-09-30.pdf", datetime(2026, 9, 30, 9), .2 * MB, ["versicherung"],
     "Ihre Hausratversicherung: Beitragsanpassung zum 1. Januar 2027")
add0("Documents/Gesundheit/Beitragsbescheinigung_Barmer.pdf", datetime(2026, 2, 2, 8), .1 * MB, ["versicherung"],
     "Barmer Krankenversicherung, Bescheinigung über gezahlte Beiträge")
add0(D + "Versicherung_Vergleich.xlsx", datetime(2026, 6, 1, 20), .05 * MB)
add0(D + "Versicherungskarte.jpg", datetime(2026, 6, 1, 20, 5), .8 * MB)
# invoices whose file name does not say "Rechnung"
add0(D + "Beleg_MediaMarkt_2026-11-02.pdf", datetime(2026, 11, 2, 15), .1 * MB, ["rechnung_text"],
     "MediaMarkt Rechnung Nr. 88-4471, Kopfhörer, 89,00 EUR")
add0(D + "Zalando_Bestellung_88231.pdf", datetime(2026, 10, 27, 21), .1 * MB, ["rechnung_text"],
     "Zalando SE, Rechnung zu Ihrer Bestellung 88231")
# a kitchen purchase saved in one go: the invoice and the papers that belong to it
for i, (name, text) in enumerate([("Rechnung_Kueche_MoebelBoss.pdf", "Rechnung Einbauküche Nobilia, Möbel Boss"),
                                  ("Auftragsbestaetigung_Kueche.pdf", "Auftragsbestätigung Einbauküche Nobilia"),
                                  ("Lieferschein_Kueche.pdf", "Lieferschein Küche, 14 Packstücke"),
                                  ("Aufbauanleitung_Nobilia.pdf", "Montageanleitung Nobilia"),
                                  ("Garantie_Geschirrspueler.pdf", "Garantiekarte Geschirrspüler, 5 Jahre")]):
    add0(D + name, datetime(2026, 10, 20, 10, 2 * i), R0.uniform(.1, 2) * MB,
         ["kueche"] + (["rechnung"] if i == 0 else []), text)
# work papers, none says "Arbeit" in a way a name filter would find all of them
for i, name in enumerate(["Dienstplan_KW46.pdf", "Projektplan_Q4.pptx", "Protokoll_Teammeeting_2026-11-03.docx",
                          "Reisekosten_Oktober.xlsx", "Arbeitsvertrag_Kessler.pdf", "Zeiterfassung_Oktober.xlsx"]):
    add0(D + name, NOW - timedelta(days=3 + 9 * i, hours=5), R0.uniform(.05, 3) * MB, ["arbeit"],
         f"Kessler Logistik GmbH, {name.split('_')[0]}")
# recent WhatsApp pictures (the older ones end in 2025)
for i in range(10):
    d = datetime(2026, 11, 5) + timedelta(days=i)
    add0(f"{WA}WhatsApp Images/IMG-{stamp(d, '%Y%m%d')}-WA{i + 1:04d}.jpg", d + timedelta(hours=17), R0.uniform(.05, .4) * MB,
         ["wa_recent"])
# exact copies in other folders (same bytes)
for src in ["Pictures/Wallpapers/wallpaper_3.jpg", "Music/Cro/Cro - Track 01.flac"]:
    orig = next(f for f in files if f["path"] == src)
    # older than the original, so the app's rule (keep the oldest) flags the copy outside Download
    add0(D + src.rsplit("/", 1)[1], datetime.fromtimestamp(orig["mtime"] - 86400), orig["size"], ["dup_keep"])
    orig["tags"] = orig["tags"] + ["dup_far"]
for f in files:   # the one older insurance letter: its text is known too
    if f["path"] == D + "Versicherungsbestaetigung_KFZ.pdf":
        f["text"], f["tags"] = "Versicherungsbestätigung (eVB) für die Zulassung", f["tags"] + ["versicherung"]
# Ordo's own log: documents sorted by month, files opened, searches (only used when habit learning is on)
H0 = int(NOW.timestamp())
for i in range(4):
    history.append({"op": "organize", "from": "Documents", "group_by": "month", "t": H0 - (100 - 20 * i) * 86400})
opened = [("Documents/Lebenslauf.pdf", 2), ("Documents/Haushaltsplan.xlsx", 3), ("Download/Kontoauszug_2026-10.pdf", 5),
          ("Documents/Notizen.txt", 1), ("Download/Rechnung_Telekom_2026-11.pdf", 6),
          ("Documents/Mietvertrag.pdf", 1), ("Documents/Impfpass.pdf", 3),
          ("Documents/Rezept_Lasagne.pdf", 12), ("Documents/Reisepass_Scan.pdf", 25), ("Download/Hotel_Lisboa_Buchung.pdf", 130),
          ("Download/Bordkarte_TAP_LIS.pdf", 124), ("Download/Arztbrief_Dr_Weber_2026-10-28.pdf", 17),
          ("Download/Verdienstnachweis_2026-10.pdf", 15), ("Documents/Bewerbung_Bosch.docx", 40),
          ("Download/Vonovia_Mieterhoehung_2026.pdf", 55), ("Documents/Lebenslauf.pdf", 30)]
for path, days in opened:
    history.append({"op": "open", "path": path, "t": H0 - days * 86400 - 3600})
history.append({"op": "search", "text": "Mietvertrag", "opened": "Documents/Mietvertrag.pdf", "t": H0 - 86400 + 6 * 3600})
history.append({"op": "search", "text": "Impfpass", "opened": "Documents/Impfpass.pdf", "t": H0 - 3 * 86400})
history.append({"op": "search", "text": "Telekom", "opened": "Download/Rechnung_Telekom_2026-11.pdf", "t": H0 - 6 * 86400})

if __name__ == "__main__":
    import pathlib
    here = pathlib.Path(__file__).parent
    assert len({f["path"] for f in files}) == len(files), "duplicate paths"
    # The tax papers must be the only "2025" names in Download (bench relies on it).
    assert sum("2025" in f["path"] for f in files if f["path"].startswith(D)) == 3
    (here / "tree.jsonl").write_text("".join(json.dumps(f, ensure_ascii=False) + "\n" for f in files), encoding="utf-8")
    (here / "history.jsonl").write_text("".join(json.dumps(h, ensure_ascii=False) + "\n" for h in history), encoding="utf-8")
    print(len(files), "files,", len(history), "history events,", round(sum(f["size"] for f in files) / 1e9, 1), "GB")
