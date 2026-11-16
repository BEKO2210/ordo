#!/usr/bin/env python3
"""Training data for the LoRA (Nachtraining): synthetic phones + target plans sampled from schema L.

    lora_data.py specs N SEED   -> lora/specs.jsonl (plan specs with description for the wording LLMs, gold, target)
    lora_data.py selftest       -> every spec type: gold set non-empty, target reproduces it on its phone

The phones here are NOT the lab phone (gen_tree.py): other folders, other names, other dates, other sizes.
Today is the lab's fixed day (bench.NOW) because rules2 reads time relative to it.
"""
import json
import os
import pathlib
import random
import sys
from datetime import datetime, timedelta

import bench
import phase0 as p0
import rules2 as R
import rules3
from p0_cases import G, P

HERE = pathlib.Path(__file__).parent
OUT = HERE / os.environ.get("LORA_DIR", "lora")   # run 4: LORA_DIR=lora2 (clean data, local models only)
NOW = datetime.fromtimestamp(bench.NOW)
TODAY = NOW.date()
WA = "Android/media/com.whatsapp/WhatsApp/Media/"
MB = 1_000_000

BRANDS = ("Vodafone O2 1und1 Congstar Otto IKEA Zalando MediaMarkt Saturn Allianz HUK Ergo DEVK AOK Barmer DAK Stadtwerke "
          "EnBW Vattenfall Lichtblick DeutscheBahn Flixbus Lufthansa Eurowings Sparkasse Volksbank ING DKB Comdirect N26 "
          "Netflix Spotify Disney Apple Samsung Xiaomi Lidl Aldi Rewe Edeka dm Rossmann Hornbach Obi Decathlon Check24 "
          "Booking Airbnb Ryanair Tchibo Thalia Hugendubel Fressnapf Zooplus Tesla Shell Aral ADAC").split()
PERSONS = ("Mama Papa Oma Opa Lena Max Tim Sarah Ahmet Ayse Luca Mia Noah Emma Ben Hannah Paul Lea Elias Marie Finn Jonas Clara "
           "Leon Sophie Felix Nele Tom Anna Jan Laura Yusuf Elif Mehmet Lukas Julia Niklas Selin Moritz Tante Onkel Chef").split()
PLACES = ("Rom Paris Wien Prag Mallorca Kreta Gardasee Ostsee Nordsee Sylt Amsterdam Barcelona Istanbul Antalya Tirol Bodensee "
          "Dresden Leipzig Köln München Freiburg Hamburg Berlin Kopenhagen London Lissabon Malta Sardinien Toskana Harz Allgäu").split()
SUBJECTS = ("Rechnung Vertrag Kontoauszug Lohnabrechnung Mietvertrag Zeugnis Lebenslauf Anschreiben Bewerbung Arztbrief Rezept "
            "Impfpass Führerschein Ticket Bordkarte Hotelbuchung Garantie Bedienungsanleitung Kündigung Steuerbescheid "
            "Nebenkostenabrechnung Stromrechnung Elternbrief Hausaufgaben Protokoll Angebot Quittung Mahnung Überweisung "
            "Versicherungsschein Krankmeldung Attest Befund Röntgenbild Zahnarzt Urlaubsantrag Arbeitsvertrag Kaufvertrag "
            "Fahrzeugschein TÜV Reisepass Personalausweis Geburtsurkunde Heiratsurkunde Stundenplan Klausur Referat "
            "Speiseplan Einkaufsliste Packliste Kochrezept Trainingsplan Wahlbenachrichtigung Bescheinigung").split()
EVENTS = ("Hochzeit Geburtstag Taufe Abiball Grillfest Silvesterparty Klassenfahrt Firmenfeier Weihnachtsfeier Konzert "
          "Fußballspiel Einschulung Umzug Wanderung Radtour Flohmarkt Sommerfest Kindergeburtstag Jubiläum Junggesellenabschied").split()
CONTENT_WORDS = ("IBAN Kündigungsfrist Steuernummer Mahngebühr Selbstbeteiligung Kaution Ratenzahlung Widerruf Diagnose Überstunden "
                 "Mietminderung Probezeit Gutschrift Lastschrift Versicherungsnummer Zählerstand Rückerstattung Impfung").split()
APPS = "WhatsApp Instagram Maps Chrome Banking Telegram TikTok Snapchat YouTube Gmail Kalender DB_Navigator Wetter".split()
ARTISTS = "Mark Forster|Helene Fischer|Sido|Lea|Wincent Weiss|Clueso|Tim Bendzko|Silbermond|Revolverheld|Juju|Udo Lindenberg|Bausa".split("|")
USER_FOLDERS = ("Rechnungen Verträge Bewerbungen Arbeit Uni Schule Kita Auto Wohnung Versicherungen Gesundheit Arzt Rezepte "
                "Kontoauszüge Lohnabrechnungen Projekte Scans Tickets Belege Familie Garten Verein Fußball Hund Baby Umzug "
                "Präsentationen Notizen Hörbücher Klingeltöne Backup Archiv Urlaub Reisen Steuer Formulare Ausweise Zeugnisse").split()
EXT_OF = {"image": ["jpg", "png", "heic"], "video": ["mp4", "mov"], "audio": ["mp3", "m4a"], "document": ["pdf", "docx", "xlsx"],
          "archive": ["zip", "rar"]}


def rdate(r, a=datetime(2023, 1, 1), b=NOW - timedelta(hours=1)):
    return a + timedelta(seconds=r.randint(0, int((b - a).total_seconds())))


def recent_bias(r):
    """Half the files from the last 90 days, so 'gestern', 'letzte Woche' etc. find something."""
    return rdate(r, NOW - timedelta(days=r.choice([3, 10, 40, 90]))) if r.random() < .5 else rdate(r)


def make_phone(seed):
    r = random.Random(seed)
    files = []

    def add(path, when, size, text=""):
        files.append({"path": path, "size": int(size), "mtime": int(when.timestamp()), "lat": None, "lon": None, "tags": [],
                      "text": text})
    cam = "DCIM/Camera"
    for _ in range(r.randint(150, 400)):
        d = recent_bias(r)
        if r.random() < .12:
            add(f"{cam}/VID_{d:%Y%m%d_%H%M%S}.mp4", d, r.uniform(20, 900) * MB)
        else:
            add(f"{cam}/IMG_{d:%Y%m%d_%H%M%S}.jpg", d, r.uniform(1.5, 9) * MB)
    shots = r.choice(["Pictures/Screenshots", "DCIM/Screenshots"])
    for _ in range(r.randint(40, 150)):
        d = recent_bias(r)
        add(f"{shots}/Screenshot_{d:%Y%m%d-%H%M%S}_{r.choice(APPS)}.{r.choice(['jpg', 'png'])}", d, r.uniform(.2, 2.5) * MB)
    wa = r.random() < .85
    if wa:
        for leaf, pre, ext, n, sz in (("WhatsApp Images", "IMG", "jpg", 200, (.1, 3)), ("WhatsApp Video", "VID", "mp4", 40, (2, 60)),
                                      ("WhatsApp Documents", "DOC", "pdf", 20, (.05, 4)), ("WhatsApp Audio", "AUD", "opus", 25, (.02, 2))):
            for i in range(r.randint(n // 3, n)):
                d = recent_bias(r)
                name = (f"{r.choice(SUBJECTS)}_{r.choice(PERSONS)}.pdf" if leaf == "WhatsApp Documents" and r.random() < .6
                        else f"{pre}-{d:%Y%m%d}-WA{i % 9999:04d}.{ext}")
                add(f"{WA}{leaf}/{name}", d, r.uniform(*sz) * MB)
        if r.random() < .5:
            for i in range(r.randint(10, 40)):
                d = recent_bias(r)
                add(f"{WA}WhatsApp Voice Notes/PTT-{d:%Y%m%d}-WA{i:04d}.opus", d, r.uniform(.01, .8) * MB)
    for _ in range(r.randint(60, 200)):   # Download: everything mixed
        d = recent_bias(r)
        k = r.random()
        if k < .35:
            subj, ent = r.choice(SUBJECTS), r.choice(BRANDS + PERSONS)
            name = r.choice([f"{subj}_{ent}_{d:%Y-%m}.pdf", f"{subj} {ent}.pdf", f"{ent}-{subj}-{d:%d%m%Y}.pdf", f"{subj}_{d:%Y%m%d}.pdf"])
            add(f"Download/{name}", d, r.uniform(.05, 5) * MB, r.choice(CONTENT_WORDS) if r.random() < .4 else "")
        elif k < .45:
            add(f"Download/{r.choice(['banking', 'wetter', 'game', 'vpn', 'scanner', 'navi', 'fitness'])}-{r.randint(1, 9)}.{r.randint(0, 20)}.apk", d, r.uniform(8, 120) * MB)
        elif k < .53:
            add(f"Download/{r.choice(EVENTS + PLACES)}_{r.choice(['Fotos', 'Bilder', 'Unterlagen', 'Daten'])}.zip", d, r.uniform(5, 900) * MB)
        elif k < .63:
            add(f"Download/{r.choice(['IMG', 'image', 'bild', 'foto'])}_{r.randint(1000, 99999)}.{r.choice(['jpg', 'png', 'webp'])}", d, r.uniform(.1, 4) * MB)
        elif k < .70:
            add(f"Download/{r.choice(ARTISTS)} - {r.choice(['Live', 'Remix', 'Demo', 'Single'])}.mp3", d, r.uniform(3, 12) * MB)
        elif k < .76:
            add(f"Download/{r.choice(['Tabelle', 'Budget', 'Haushaltsbuch', 'Liste', 'Kalkulation'])}_{r.choice(PLACES + EVENTS)}.{r.choice(['xlsx', 'ods', 'csv'])}", d, r.uniform(.01, 2) * MB)
        elif k < .81:
            add(f"Download/{r.choice(['Präsentation', 'Vortrag', 'Folien'])}_{r.choice(SUBJECTS)}.{r.choice(['pptx', 'odp'])}", d, r.uniform(1, 40) * MB)
        elif k < .86:
            add(f"Download/{r.choice(['Roman', 'Krimi', 'Ratgeber', 'Kochbuch', 'Reiseführer'])}_{r.choice(PLACES)}.epub", d, r.uniform(.5, 8) * MB)
        elif k < .92:
            add(f"Download/{r.choice(['Lebenslauf', 'Anschreiben', 'Brief', 'Notiz', 'Entwurf'])}_{r.choice(PERSONS)}.{r.choice(['docx', 'odt', 'txt'])}", d, r.uniform(.01, 1) * MB)
        else:
            add(f"Download/{r.choice(EVENTS)}_{r.randint(1, 30)}.mp4", d, r.uniform(10, 800) * MB)
    for a in r.sample(ARTISTS, r.randint(2, 6)):
        for i in range(r.randint(5, 25)):
            d = rdate(r)
            add(f"Music/{a}/{a} - Song {i + 1}.mp3", d, r.uniform(3, 11) * MB)
    for i in range(r.randint(3, 25)):
        d = rdate(r)
        add(f"Movies/{r.choice(EVENTS + PLACES)} {d.year}.mp4", d, r.uniform(200, 4000) * MB)
    if r.random() < .5:
        for i in range(r.randint(5, 30)):
            d = recent_bias(r)
            add(f"Recordings/Aufnahme_{d:%Y%m%d_%H%M}.m4a", d, r.uniform(.2, 30) * MB)
    if r.random() < .4:
        for i in range(r.randint(3, 15)):
            d = rdate(r)
            add(f"Books/{r.choice(['Roman', 'Krimi', 'Sachbuch'])} {r.choice(PLACES)}.epub", d, r.uniform(.5, 6) * MB)
    if r.random() < .3:
        for i in range(r.randint(5, 20)):
            d = rdate(r)
            add(f"Podcasts/Folge {i + 1} {r.choice(SUBJECTS)}.mp3", d, r.uniform(20, 90) * MB)
    # user folders: under Documents, top level or Pictures
    for name in r.sample(USER_FOLDERS, r.randint(5, 10)):
        where = r.choice(["Documents/", "Documents/", "", "Pictures/"] if name in ("Urlaub", "Familie", "Hund", "Baby", "Reisen") else
                         ["Documents/", "Documents/", ""])
        folder = where + name
        topic = r.choice([[name], r.sample(SUBJECTS, 3)])
        for i in range(r.randint(4, 30)):
            d = recent_bias(r)
            if where == "Pictures/" or (name in ("Urlaub", "Familie", "Hund", "Baby", "Reisen") and r.random() < .7):
                add(f"{folder}/{r.choice(PLACES + EVENTS + PERSONS)}_{i + 1:03d}.jpg", d, r.uniform(1, 8) * MB)
            else:
                subj, ent = r.choice(topic if topic != [name] else SUBJECTS), r.choice(BRANDS + PERSONS + PLACES)
                ext = r.choice(["pdf", "pdf", "pdf", "docx", "jpg", "xlsx"])
                add(f"{folder}/{subj}_{ent}_{d:%Y-%m-%d}.{ext}", d, r.uniform(.03, 6) * MB, r.choice(CONTENT_WORDS) if r.random() < .3 else "")
    # unique paths
    seen, out = set(), []
    for f in files:
        if f["path"] not in seen:
            seen.add(f["path"])
            out.append(f)
    return out


# ---------------------------------------------------------------- spec sampling
TIME_CANON = [
    lambda r: "heute", lambda r: "gestern", lambda r: "vorgestern",
    lambda r: "am " + r.choice(["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"]),
    lambda r: "diese Woche", lambda r: "letzte Woche", lambda r: "diesen Monat", lambda r: "letzten Monat",
    lambda r: "dieses Jahr", lambda r: "letztes Jahr",
    lambda r: f"letzten {r.choice([2, 3, 5, 7, 10, 14, 30])} Tage", lambda r: f"letzten {r.choice([2, 3, 4, 6])} Wochen",
    lambda r: f"letzten {r.choice([2, 3, 6, 12])} Monate",
    lambda r: f"älter als {r.choice([3, 7, 10, 30])} Tage", lambda r: f"älter als {r.choice([1, 2, 3, 6])} Wochen",
    lambda r: f"älter als {r.choice([1, 2, 3, 6, 9])} Monate", lambda r: f"älter als {r.choice([1, 2, 3])} Jahre",
    lambda r: f"jünger als {r.choice([2, 3, 7])} Tage", lambda r: f"jünger als {r.choice([1, 2])} Wochen",
    lambda r: "im " + r.choice(["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober"]),
    lambda r: "im " + r.choice(["Januar", "März", "Mai", "Juli", "August", "Oktober", "Dezember"]) + f" {r.choice([2024, 2025])}",
    lambda r: str(r.choice([2023, 2024, 2025])), lambda r: "im " + r.choice(["Sommer", "Winter", "Frühling", "Herbst"]),
    lambda r: "im " + r.choice(["Sommer", "Winter"]) + f" {r.choice([2024, 2025])}",
    lambda r: f"am {r.randint(1, 28)}.{r.choice([9, 10, 11])}.", lambda r: f"am {r.randint(1, 28)}.{r.randint(1, 12)}.{r.choice([2024, 2025])}",
    lambda r: f"seit {r.randint(1, 20)}.{r.choice([8, 9, 10])}.", lambda r: "seit " + r.choice(["Juli", "August", "September", "Oktober"]),
    lambda r: f"vor {r.randint(1, 28)}.{r.randint(1, 12)}.{r.choice([2025, 2026])}",
    lambda r: (lambda a: f"zwischen {a}.{r.choice([9, 10])}. und {a + r.randint(3, 14)}.{10}.")(r.randint(1, 14)),
    lambda r: "Weihnachten", lambda r: "Weihnachten 2024", lambda r: "Ostern", lambda r: "Silvester", lambda r: "seit Weihnachten",
    lambda r: "seit Ostern", lambda r: "neulich",
]
SIZE_CANON = [lambda r: f"über {r.choice([5, 10, 20, 50, 100, 200, 500])} MB", lambda r: f"über {r.choice([1, 2])} GB",
              lambda r: f"unter {r.choice([1, 2, 5, 10])} MB", lambda r: f"unter {r.choice([100, 200, 500])} KB",
              lambda r: f"zwischen {r.choice([1, 5, 10])} und {r.choice([50, 100])} MB"]
# type words the user says -> (kinds, extensions, German word for the description)
TYPE_WORDS = [
    (["image"], [], "Fotos/Bilder"), (["video"], [], "Videos"), (["audio"], [], "Musik/Audiodateien"),
    (["document"], [], "Dokumente"), (["archive"], [], "Archive (Zip/Rar)"),
    ([], ["pdf"], "PDFs"), ([], ["apk"], "APK-Dateien (Apps zum Installieren)"), ([], ["zip"], "Zip-Dateien"),
    ([], ["xlsx", "xls", "ods", "csv"], "Tabellen (Excel)"), ([], ["docx", "doc", "odt"], "Word-Dateien"),
    ([], ["pptx", "ppt", "odp"], "Präsentationen (PowerPoint)"), ([], ["epub"], "E-Books"), ([], ["mp3"], "MP3s"),
    ([], ["opus"], "Sprachnachrichten"), ([], ["jpg", "jpeg"], "JPGs"), ([], ["png"], "PNGs"), ([], ["mp4"], "MP4-Videos"),
    ([], ["txt"], "Textdateien"), ([], ["m4a"], "Sprachaufnahmen (m4a)"),
]
STD_SAY = {"Download": ["Download", "Downloads"], "DCIM/Camera": ["Kamera"], "DCIM/100ANDRO": ["Kamera"],
           "Pictures/Screenshots": ["Screenshots"], "DCIM/Screenshots": ["Screenshots"], "Music": ["Musik"], "Movies": ["Filme", "Movies"],
           "Documents": ["Dokumente", "Documents"], "Recordings": ["Aufnahmen"], "Books": ["Books"], "Podcasts": ["Podcasts"],
           WA + "WhatsApp Images": ["WhatsApp Images"], WA + "WhatsApp Video": ["WhatsApp Video"],
           WA + "WhatsApp Documents": ["WhatsApp Documents"], WA + "WhatsApp Audio": ["WhatsApp Audio"],
           WA + "WhatsApp Voice Notes": ["WhatsApp Voice Notes"], "Pictures": ["Bilder", "Pictures"]}
NEW_DEST = ("Alt Archiv2 Wichtig Sicherung Erledigt Familie2 Urlaubsfotos Steuerkram Uni-Kram Handwerker Bewerbung2026 "
            "Aussortiert Spiele Sammlung Favoriten Privat Belege2025 Kinderfotos Ablage Musiksammlung").split()


def say_folder(path, r):
    if path in STD_SAY:
        return r.choice(STD_SAY[path])
    return path.rsplit("/", 1)[-1]


def time_range(canon):
    w = R.Work(R.norm(canon))
    a, b = R.parse_time(w, TODAY, NOW)
    assert (a or b) and not [x for x in w.tokens() if x not in R.STOP], canon
    return a, b


def size_range(canon):
    return R.parse_size(R.Work(R.norm(canon)))


def file_entities(f):
    """Words in a file name a user could ask for (persons, brands, places, subjects, events)."""
    import re
    name = f["path"].rsplit("/", 1)[-1].rsplit(".", 1)[0]
    vocab = set(BRANDS + PERSONS + PLACES + SUBJECTS + EVENTS)
    return [x for x in re.split(r"[ _\-]+", name) if x in vocab]


def describe(s):
    """German description of a plan for the wording LLM. Facts only, no wording."""
    a = {"show": "zeigen oder suchen", "move": "verschieben", "copy": "kopieren", "delete": "löschen",
         "organize": "aufräumen: Ordner in Unterordner sortieren"}[s["action"]]
    L = [f"Aktion: {a}"]
    if s.get("source_say"):
        L.append(f"Ordner: {s['source_say']}" + (" (= WhatsApp Bilder)" if s["source_say"] == "WhatsApp Images" else ""))
    if s.get("dest_say"):
        L.append(f"Ziel: {s['dest_say']}" + (" (gibt es noch nicht)" if s.get("dest_new") else ""))
    if s.get("type_say"):
        L.append(f"Dateiart: {s['type_say']}")
    if s.get("name"):
        L.append(f"Wort im Dateinamen (Person/Firma/Ort/Thema): {s['name']}" if " " not in s["name"] else
                 f"Zwei Wörter im Dateinamen (beide nennen, KEINE Ordner): {s['name'].replace(' ', ' und ')}")
    if s.get("content"):
        L.append(f"Im Text der Datei steht: {s['content']}")
    if s.get("exclude_say"):
        L.append(f"Ausnahme (diese NICHT): {s['exclude_say']}")
    if s.get("time"):
        L.append(f"Zeit: {s['time']}")
    if s.get("size"):
        L.append(f"Größe: {s['size']}")
    if s.get("sort"):
        L.append({"size": "die größten", "newest": "die neuesten", "oldest": "die ältesten", "smallest": "die kleinsten"}[s["sort"]]
                 + (f", genau {s['limit']}" if s.get("limit", 0) > 1 else ", genau eine Datei" if s.get("limit") == 1 else ""))
    if s.get("group_by"):
        L.append("sortiert nach " + {"month": "Monat/Datum", "type": "Dateityp"}[s["group_by"]])
    return "; ".join(L)


FOCUS = ""


def sample_spec(r, phone, tree, force=None):
    """One plan spec: target (schema L), gold (lab judge format) and facts for the description."""
    folders = sorted({f["path"].rsplit("/", 1)[0] for f in phone})
    act = force or r.choices(["show", "move", "copy", "delete", "organize"], [50, 15, 8, 18, 6])[0]
    if FOCUS == "two" and act == "organize":
        act = "show"
    s = {"action": act}
    # pick an anchor file so that the plan finds something
    by_folder = {}
    for f in phone:
        by_folder.setdefault(f["path"].rsplit("/", 1)[0], []).append(f)
    anchor = r.choice(by_folder[r.choice(sorted(by_folder))])   # folders uniform, so the camera does not dominate
    if FOCUS == "content":   # run 3: questions about what is written in a file
        anchor = r.choice([f for f in phone if f.get("text")] or phone)
    elif FOCUS == "two":     # run 3: two topic words ("die Garantie von Samsung"), none of them a folder
        anchor = r.choice([f for f in phone if len(set(file_entities(f))) >= 2] or phone)
    folder = anchor["path"].rsplit("/", 1)[0]
    ext = anchor["path"].rsplit(".", 1)[-1].lower()
    use_src = act != "show" or r.random() < .55
    if use_src:
        s["source"] = folder
        s["source_say"] = say_folder(folder, r)
    if act == "organize":
        s["group_by"] = r.choice(["", "month", "type"])
        return s
    ents = file_entities(anchor)
    feats = []
    if FOCUS == "two" and len(set(ents)) >= 2:
        s["name"] = " ".join(r.sample(sorted(set(ents)), 2))
        feats.append("name")
    elif ents and r.random() < .55:
        s["name"] = r.choice(ents)
        feats.append("name")
    if r.random() < (.45 if "name" in feats else .75):
        kind = next((k for k, es in bench.KINDS.items() if ext in es), None)
        opts = [t for t in TYPE_WORDS if ext in t[1] or (kind and kind in t[0])]
        if opts:
            t = r.choice(opts)
            s["kinds"], s["extensions"], s["type_say"] = t[0], t[1], t[2].split("/")[0] if r.random() < .5 else t[2]
    if r.random() < .45:
        canon = r.choice(TIME_CANON)(r)
        s["time"] = canon
    if r.random() < .12:
        s["size"] = r.choice(SIZE_CANON)(r)
    if act == "show" and r.random() < .18:
        s["sort"] = r.choice(["size", "newest", "oldest", "smallest"])
        s["limit"] = r.choice([0, 1, 3, 5, 10])
    if anchor.get("text") and (r.random() < .5 or FOCUS == "content") and act == "show" and FOCUS != "two":
        s["content"] = anchor["text"]
        s.pop("name", None)
    if r.random() < .1:
        # exclusion: a kind or an extension or a word that occurs in the folder
        pool = tree.under(folder)
        exts = sorted({f["path"].rsplit(".", 1)[-1].lower() for f in pool} - {ext})
        if exts:
            x = r.choice(exts)
            s["exclude"] = [x]
            s["exclude_say"] = x.upper() + "-Dateien"
    if act in ("move", "copy"):
        if r.random() < .5:
            d = r.choice([f for f in folders if f != folder and not f.startswith("Android/")] or [r.choice(NEW_DEST)])
            s["dest"], s["dest_say"] = d, say_folder(d, r)
        else:
            d = r.choice(NEW_DEST)
            s["dest"], s["dest_say"], s["dest_new"] = d, d, True
    if act in ("move", "copy", "delete") and not (s.get("name") or s.get("kinds") or s.get("extensions") or s.get("time") or s.get("size")):
        s["kinds"], s["extensions"], s["type_say"] = next(([k], [], k) for k, es in bench.KINDS.items() if ext in es) if ext in set().union(*bench.KINDS.values()) else ([], [ext], ext.upper() + "-Dateien")
    return s


def target(s):
    """The plan the model should write (schema L)."""
    t = {"action": s["action"], "source": s.get("source_say", ""), "destination": s.get("dest_say", ""),
         "kinds": s.get("kinds", []), "extensions": s.get("extensions", []), "name_contains": s.get("name", ""),
         "content_contains": s.get("content", ""), "exclude": s.get("exclude", []), "time": s.get("time", ""),
         "size": s.get("size", ""), "sort": s.get("sort", ""), "limit": s.get("limit", 0), "group_by": s.get("group_by", "")}
    if t["limit"] and not t["sort"]:
        t["limit"] = 0
    return t


def gold(s, tree):
    """Gold in the lab judge format, computed from the facts, not from the target."""
    act = s["action"]
    kw = {}
    if s.get("kinds"):
        kw["kinds"] = s["kinds"]
    if s.get("extensions"):
        kw["extensions"] = s["extensions"]
    if s.get("name"):
        kw["name_terms" if act != "show" else "text_terms"] = [s["name"]]
    if s.get("content"):
        kw["text_terms"] = kw.get("text_terms", []) + [s["content"]]
    if s.get("time"):
        kw["date_from"], kw["date_to"] = time_range(s["time"])
    if s.get("size"):
        kw["min_bytes"], kw["max_bytes"] = size_range(s["size"])
    if s.get("exclude"):
        kw["exclude_extensions"] = s["exclude"]
    if s.get("sort"):
        kw["sort"], kw["limit"] = s["sort"], s.get("limit", 0)
    plan = P(act, s.get("source", ""), **kw)
    extra = {}
    if act in ("move", "copy"):
        extra["dest"] = [s["dest"]]
    if act == "organize":
        extra["group_by"] = [s.get("group_by") or "type"]
    how = "top" if s.get("sort") else "plan"
    return G(act, {how: plan}, **extra)


def gold_files(g, tree):
    return p0.wanted(g, tree, [], [], "")


def specs(n, seed, n_phones=40, force=None):
    r = random.Random(seed)
    phones = [make_phone(seed * 1000 + i) for i in range(n_phones)]
    trees = [p0.Tree(p) for p in phones]
    out = []
    tries = 0
    while len(out) < n and tries < n * 20:
        tries += 1
        pi = r.randrange(n_phones)
        s = sample_spec(r, phones[pi], trees[pi], force)
        g = gold(s, trees[pi])
        w = gold_files(g, trees[pi])
        sizes = [len(x) for _, x in w]
        if not sizes or not sizes[0]:
            continue
        if s["action"] != "show" and sizes[0] > 150:
            continue
        out.append({"id": f"s{seed}_{len(out):05d}", "phone": seed * 1000 + pi, "lang": "en" if r.random() < .15 else "de",
                    "spec": s, "desc": describe(s), "target": target(s), "gold": g, "n_files": sizes[0]})
    return out


_PHONES = {}


def phone_tree(pid):
    if pid not in _PHONES:
        _PHONES[pid] = p0.Tree(make_phone(pid))
    return _PHONES[pid]


def check(text, row, flags=""):
    """Does the target plan, read through rules3 with [flags], select the gold files for this wording on its phone?"""
    tree = phone_tree(row["phone"])
    d = rules3.decide(text, row["target"], tree, flags)
    plan = rules3.as_scored(d)
    ex = p0.execute(tree, plan, text, {}, ())
    j = p0.judge({"text": text, "gold": row["gold"]}, plan, ex, tree, [], [])
    return j["ok"] and not j["danger"], d["status"], j


def selftest():
    rows = specs(300, 7, n_phones=6)
    bad = 0
    for row in rows:
        ok, st, j = check("", row)
        if not ok:
            bad += 1
            print("FAIL", row["desc"], row["target"], j["got"], j["want"])
    from collections import Counter
    print(Counter(r["spec"]["action"] for r in rows), "bad", bad)
    assert bad <= 3, bad


if __name__ == "__main__":
    if sys.argv[1] == "selftest":
        selftest()
    elif sys.argv[1] == "specs":
        OUT.mkdir(exist_ok=True)
        FOCUS = sys.argv[5] if len(sys.argv) > 5 else ""
        rows = specs(int(sys.argv[2]), int(sys.argv[3]), force=(sys.argv[4] if len(sys.argv) > 4 and sys.argv[4] != "-" else None))
        with open(OUT / f"specs_{sys.argv[3]}.jsonl", "w", encoding="utf-8") as fh:
            for row in rows:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        print(len(rows))
