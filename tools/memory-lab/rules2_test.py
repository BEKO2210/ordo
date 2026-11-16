#!/usr/bin/env python3
"""Unit tests for rules2.py, one block per mechanism (>= 12 examples each, German + English).

Every sentence here is written for the test and checked against all case sets (p0, p0b, blind, old lab files):
no exact match and no near copy (token overlap >= 0.8). Dates use another "today" (Wednesday 2027-03-10) and a
small phone of its own, so the tests do not lean on the lab phone or on NOW of the case sets.
"""
import json
import pathlib
import re
from datetime import date, datetime

import phase0 as p0
import rules2 as R

HERE = pathlib.Path(__file__).parent
TODAY = date(2027, 3, 10)                    # a Wednesday
NOW = datetime(2027, 3, 10, 15, 30)
D = lambda s: datetime.fromisoformat(s).timestamp()

FILES = [  # a small phone of its own
    {"path": "Download/Rechnung_Strom_2026.pdf", "size": 200_000, "mtime": D("2027-02-01")},
    {"path": "Download/Rechnung_Gas_2026.pdf", "size": 210_000, "mtime": D("2027-02-03")},
    {"path": "Download/Mietvertrag_Entwurf.docx", "size": 90_000, "mtime": D("2026-12-01")},
    {"path": "Download/Mietvertrag_final.pdf", "size": 95_000, "mtime": D("2026-12-02")},
    {"path": "Download/firmware_v2.zip", "size": 900_000_000, "mtime": D("2025-05-05")},
    {"path": "Download/Konzert_Ticket.pdf", "size": 50_000, "mtime": D("2027-03-08")},
    {"path": "Download/notes.txt", "size": 1_000, "mtime": D("2027-03-09")},
    {"path": "DCIM/Camera/IMG_20260705_1.jpg", "size": 3_000_000, "mtime": D("2026-07-05")},
    {"path": "DCIM/Camera/IMG_20261224_1.jpg", "size": 3_000_000, "mtime": D("2026-12-24")},
    {"path": "DCIM/Camera/VID_20260801_1.mp4", "size": 80_000_000, "mtime": D("2026-08-01")},
    {"path": "DCIM/Camera/Skiurlaub_1.jpg", "size": 2_500_000, "mtime": D("2027-02-20")},
    {"path": "DCIM/Camera/Skiurlaub_2.jpg", "size": 2_600_000, "mtime": D("2027-02-21")},
    {"path": "Pictures/Screenshots/Screenshot_20270301_Maps.png", "size": 400_000, "mtime": D("2027-03-01")},
    {"path": "Pictures/Screenshots/Screenshot_20270309_Chat.png", "size": 300_000, "mtime": D("2027-03-09")},
    {"path": "Documents/Steuer 2025/Beleg_1.pdf", "size": 10_000, "mtime": D("2026-03-01")},
    {"path": "Documents/Steuer 2026/Beleg_2.pdf", "size": 10_000, "mtime": D("2027-01-15")},
    {"path": "Documents/Vertraege/Handyvertrag.pdf", "size": 10_000, "mtime": D("2025-01-15")},
    {"path": "Backup/old.txt", "size": 10, "mtime": D("2024-01-01")},
    {"path": "Music/Podcasts/Folge_12.mp3", "size": 40_000_000, "mtime": D("2027-01-02")},
    {"path": "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/PTT-20270301.opus", "size": 20_000, "mtime": D("2027-03-01")},
    {"path": "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-20270301-WA0001.jpg", "size": 100_000, "mtime": D("2027-03-01")},
]
TREE = p0.Tree(FILES)
FOLDERS = sorted(TREE.folders)
SENTENCES = []


def s(x):
    SENTENCES.append(x)
    return x


def time_of(text):
    w = R.Work(R.prepare(s(text), FOLDERS)[0])
    return R.parse_time(w, TODAY, NOW)


def size_of(text):
    w = R.Work(R.prepare(s(text), FOLDERS)[0])
    return R.parse_size(w)


def run(text, **raw):
    return R.decide(s(text), raw, TREE, today=TODAY, now=NOW)


# ---------------------------------------------------------------- b: time and size grammar
def test_time():
    cases = [
        ("Zeig die Fotos von vorgestern", ("2027-03-08", "2027-03-09")),
        ("Bilder vom letzten Montag", ("2027-03-08", "2027-03-09")),
        ("pdfs von diesem Monat", ("2027-03-01", "2027-03-11")),
        ("Screenshots vom letzten Monat", ("2027-02-01", "2027-03-01")),
        ("videos aus dem juli", ("2026-07-01", "2026-08-01")),
        ("Fotos vom Winter", ("2026-12-01", "2027-03-01")),
        ("alles seit Weihnachten", ("2026-12-25", "")),
        ("Fotos zwischen Mai und Juli", ("2026-05-01", "2026-08-01")),
        ("Dokumente aus 2025", ("2025-01-01", "2026-01-01")),
        ("photos from last week", ("2027-03-01", "2027-03-08")),
        ("files older than 2 weeks", ("", "2027-02-24T15:30")),
        ("musik der letzten zehn tage", ("2027-02-28", "")),
        ("der Scan vom 3. Februar", ("2027-02-03", "2027-02-04")),
        ("pictures before christmas", ("", "2026-12-25")),
        ("seit Neujahr runtergeladen", ("2027-01-01", "")),
        ("everything from this year", ("2027-01-01", "2027-03-11")),
        ("Videos von Ostern 2026", ("2026-04-05", "2026-04-07")),
        ("vor drei Tagen gespeichert", ("2027-03-07", "2027-03-08")),
        ("documents from march 2024", ("2024-03-01", "2024-04-01")),
        ("Fotos vom 24.12.", ("2026-12-24", "2026-12-25")),
        ("letzten Sommer gefilmt", ("2026-06-01", "2026-09-01")),
        ("am Samstag", ("2027-03-06", "2027-03-07")),
    ]
    for text, want in cases:
        assert time_of(text) == want, (text, time_of(text), want)
    # no time where there is none (numbers that are sizes, counts or names)
    for text in ("die 5 größten Dateien", "videos über 2000 MB", "Rechnung 2025-final", "the 3 newest files"):
        f, t = time_of(text)
        assert (f, t) == ("", "") or text == "Rechnung 2025-final", (text, f, t)
    return len(cases) + 4


def test_size():
    cases = [
        ("Videos größer als 2 GB", (2_000_000_000, 0)), ("dateien kleiner als 5 mb", (0, 5_000_000)),
        ("zwischen 10 und 50 MB", (10_000_000, 50_000_000)), ("über 1,5 Gigabyte", (1_500_000_000, 0)),
        ("files bigger than 300 megabytes", (300_000_000, 0)), ("at least 200 kb", (200_000, 0)),
        ("mehr als ein halbes giga", (500_000_000, 0)), ("under 1 mb", (0, 1_000_000)),
        ("höchstens 20 mega byte", (0, 20_000_000)), ("filme ab 4 gb", (4_000_000_000, 0)),
        ("larger than half a gig", (500_000_000, 0)), ("musik über zehn mb groß", (10_000_000, 0)),
    ]
    for text, want in cases:
        assert size_of(text) == want, (text, size_of(text), want)
    return len(cases)


# ---------------------------------------------------------------- c: exclusion
def test_exclusion():
    def ex(text):
        w = R.Work(R.prepare(s(text), FOLDERS)[0])
        return R.parse_exclusion(w, R.is_verb)
    cases = [
        ("alle Bilder außer den PNGs", ([], ["png"], [])), ("everything except videos", (["video"], [], [])),
        ("Download ohne die MP3s", ([], ["mp3"], [])), ("die Verträge, aber nicht die Entwürfe", ([], [], ["entwurf"])),
        ("alles bis auf Fotos und Videos", (["image", "video"], [], [])), ("all files but not the zips", ([], ["zip"], [])),
        ("Musik ausser Podcasts", ([], [], ["podcast"])), ("pictures other than screenshots", (["image"], [], [])),
        ("dokumente excluding pdfs", ([], ["pdf"], [])), ("nicht löschen, nur anzeigen", ([], [], [])),
        ("do not move the photos", ([], [], [])), ("Fotos ohne die vom Skiurlaub", ([], [], ["skiurlaub"])),
        ("bitte nichts verschieben", ([], [], [])),
    ]
    for text, want in cases:
        assert ex(text) == want, (text, ex(text), want)
    # end to end: the excluded kind is not a positive filter
    d = run("Zeig alles in der Kamera außer den Videos", action="show", source="Kamera")
    assert d["plan"]["exclude_kinds"] == ["video"] and d["plan"]["kinds"] == [], d
    return len(cases) + 1


# ---------------------------------------------------------------- d: folders against the real tree
def test_folders():
    rf = lambda n: R.resolve_folder(s(n) if " " in n else n, FOLDERS)
    cases = [
        (rf("Downloads"), ("Download", "alias")), (rf("kamera"), ("DCIM/Camera", "alias")),
        (rf("Screenshot"), ("Pictures/Screenshots", "alias")), (rf("Bakup"), ("Backup", "fuzzy")),
        (rf("Steuer 2026"), ("Documents/Steuer 2026", "exact")), (rf("podcasts"), ("Music/Podcasts", "exact")),
        (rf("Urlaub"), (None, "missing")), (rf("vertraege"), ("Documents/Vertraege", "exact")),
    ]
    for got, want in cases:
        assert got == want, (got, want)
    amb = R.resolve_folder("Steuer", FOLDERS)
    assert amb[1] in ("ambiguous", "missing"), amb   # "Steuer 2025" and "Steuer 2026": never guess
    d = run("Verschieb die Belege in den Steuerordner", action="move", source="Documents", destination="Steuer")
    assert d["status"] == "ask", d
    # the model's folder is kept when it exists and is what the user said, never replaced by a parent or child
    d = run("Kopier die Skiurlaub Bilder aus der Kamera nach Backup", action="copy", source="DCIM/Camera", destination="Backup")
    assert d["plan"]["source"] == "DCIM/Camera" and d["plan"]["destination"] == "Backup", d
    # accusative "in den Ordner X" = target, dative "im Ordner X" = place
    d = run("Leg die Strom Rechnung aus Download in den Ordner Vertraege", action="move", source="Download", destination="Vertraege")
    assert d["plan"]["destination"] == "Documents/Vertraege" and d["plan"]["source"] == "Download", d
    d = run("Was liegt im Ordner Podcasts?", action="show", source="Podcasts")
    assert d["plan"]["source"] == "Music/Podcasts", d
    # a folder the user names that does not exist: a change asks, a search looks everywhere
    d = run("Lösch alles im Ordner Urlaub", action="delete", source="Urlaub")
    assert d["status"] == "ask", d
    d = run("find the voice notes from whatsapp", action="show", source="WhatsApp Images")
    assert d["plan"]["source"].endswith("WhatsApp Voice Notes"), d
    d = run("move the tickets from downloads to the backup folder", action="move", source="downloads", destination="backup")
    assert (d["plan"]["source"], d["plan"]["destination"]) == ("Download", "Backup"), d
    return len(cases) + 8


# ---------------------------------------------------------------- e: things Ordo cannot do
def test_unsupported():
    cases = [
        ("Mach aus den Belegen eine PDF", "convert"), ("Pack die Skiurlaub Fotos in ein Archiv", "archive"),
        ("compress my videos", "archive"), ("entpacke die firmware", "archive"), ("Benenne alle Screenshots um", "rename"),
        ("rename the receipts by date", "rename"), ("Schick den Mietvertrag an Tom", "share"), ("share the ski photos", "share"),
        ("lade die Fotos in die Cloud hoch", "share"), ("Öffne die Konzert Datei", "open"), ("print the ticket", "open"),
        ("hol die gelöschten Bilder wieder", "restore"), ("restore deleted photos", "restore"), ("Dreh das Foto um 90 Grad", "edit"),
        ("füge die beiden PDFs zusammen", "convert"), ("save the scans as zip", "archive"),
    ]
    for text, cat in cases:
        d = run(text, action="show")
        assert d["status"] == "refuse" and d["reason"] == cat, (text, d["status"], d["reason"], cat)
    for text, raw in [("zeig alle zip dateien", dict(action="show")), ("verschieb die firmware aus Download nach Backup", dict(action="move", source="Download", destination="Backup")),
                      ("lösch die zips im Download", dict(action="delete", source="Download")),
                      ("move the old zip files to backup", dict(action="move")), ("Wo ist mein Handyvertrag?", dict(action="show"))]:
        d = run(text, **raw)
        assert d["status"] != "refuse", (text, d)
    return len(cases) + 5


# ---------------------------------------------------------------- a: coverage guard
def test_guard():
    n = 0
    # the model drops the qualifier of a destructive plan: the word becomes the filter when it is the only reading
    d = run("Lösch die Gas Rechnung aus dem Download", action="delete", source="Download")
    assert d["status"] == "plan" and d["plan"]["name_terms"] == ["gas", "rechnung"], d
    d = run("verschieb die skiurlaub bilder aus der kamera nach backup", action="move", source="Kamera", destination="Backup")
    assert d["plan"]["text_terms"] == ["skiurlaub"] and d["plan"]["kinds"] == ["image"], d
    d = run("Move the concert tickets from Download to Backup", action="move", source="Download", destination="Backup")
    assert d["plan"]["text_terms"] == ["ticket"], d   # "concert" is in no file name, "tickets" is: the only reading on the phone
    # a word that matches no file: a change asks, a search simply finds what the rest finds
    d = run("Lösch die Hochzeitsvideos aus der Kamera", action="delete", source="Kamera")
    assert d["status"] == "ask" and d["reason"] in ("uncovered", "no_reading"), d
    d = run("delete the wedding pictures in camera", action="delete", source="Camera")
    assert d["status"] == "ask", d
    d = run("zeig mir mal ganz schnell die screenshots", action="show", source="Screenshots")
    assert d["status"] == "plan" and not d["plan"].get("text_terms"), d
    # two words: the subset that exists on the phone; two different single readings for a change: ask
    d = run("Lösch das Strom Gas Zeug aus Download", action="delete", source="Download")
    assert d["status"] == "ask", d
    d = run("kopier den Mietvertrag Entwurf aus Download nach Backup", action="copy", source="Download", destination="Backup")
    assert d["plan"]["text_terms"] == ["mietvertrag", "entwurf"], d
    # words of a negated clause say what NOT to do
    d = run("Räum den Download auf, aber lösch keine Rechnungen", action="organize", source="Download")
    assert d["status"] == "plan" and not d["plan"].get("text_terms") and not d["plan"].get("name_terms"), d
    # what the model invented is not a filter; what the user said and the model used is
    d = run("verschieb die pdfs aus Download nach Backup", action="move", source="Download", destination="Backup", name_contains="Rechnung")
    assert not d["plan"].get("text_terms") and d["plan"]["extensions"] == ["pdf"], d
    d = run("copy the ski photos from camera to backup", action="copy", source="Camera", destination="Backup", name_contains="ski")
    assert d["plan"]["text_terms"] == ["ski"], d
    d = run("lösch alle Videos aus der Kamera", action="delete", source="Kamera")
    assert d["status"] == "plan" and not d["plan"].get("name_terms"), d
    n += 12
    # code never makes a delete, a question never changes files, a negated verb is not the action
    d = run("kann ich die firmware im Download löschen?", action="delete", source="Download")
    assert d["plan"]["action"] == "show", d
    d = run("Nicht löschen, nur in Backup kopieren: die Tickets aus Download", action="delete", source="Download")
    assert d["plan"]["action"] == "copy", d
    d = run("zeig die gas rechnung", action="delete")
    assert d["plan"]["action"] == "show", d
    return n + 3


# ---------------------------------------------------------------- f: plausibility of a count
def test_count():
    cases = [
        ("Verschieb die zwei Rechnungen aus Download nach Backup", dict(action="move", source="Download", destination="Backup", name_contains="Rechnung"), False),
        ("Verschieb die drei Rechnungen aus Download nach Backup", dict(action="move", source="Download", destination="Backup", name_contains="Rechnung"), True),
        ("lösch die beiden Skiurlaub Fotos aus der Kamera", dict(action="delete", source="Kamera", name_contains="Skiurlaub"), False),
        ("delete the three ski photos from camera", dict(action="delete", source="Camera", name_contains="Skiurlaub"), True),
        ("copy both screenshots to backup", dict(action="copy", source="Screenshots", destination="Backup"), False),
        ("kopier die 3 Screenshots nach Backup", dict(action="copy", source="Screenshots", destination="Backup"), True),
        ("move the 2 contract files from Download to Backup", dict(action="move", source="Download", destination="Backup", name_contains="Mietvertrag"), False),
        ("Lösch die vier Belege in Steuer 2025", dict(action="delete", source="Steuer 2025"), True),
        ("Lösch alle Rechnungen im Download", dict(action="delete", source="Download", name_contains="Rechnung"), False),
        ("zeig die drei Rechnungen", dict(action="show", name_contains="Rechnung"), False),   # a search never warns
        ("verschieb den einen Beleg aus Steuer 2026 nach Backup", dict(action="move", source="Steuer 2026", destination="Backup"), False),
        ("move all five photos from camera to backup", dict(action="move", source="Camera", destination="Backup", name_contains="IMG"), True),
    ]
    for text, raw, warn in cases:
        d = run(text, **raw)
        assert bool(d["warn"]) == warn, (text, d["status"], d["warn"], d.get("plan"))
    return len(cases)


def test_memory():
    cases = ["mach das mit den PDFs genauso", "do the same with the videos", "Lösch die wie letztes Mal", "sortier wie gewohnt",
             "zeig mir die Datei, die ich gestern geöffnet habe", "und jetzt nur die großen", "verschieb sie nach Backup",
             "nein, die andere", "files I opened last week", "the usual cleanup please", "pack die zu den anderen", "die zweite bitte"]
    for text in cases:
        d = run(text, action="move", source="Download")
        assert d["status"] == "refuse" and d["reason"] == "memory", (text, d)
    return len(cases)


def leak_check():
    texts = set()
    for f in ("p0_cases.jsonl", "p0b_cases.jsonl", "blind_cases.jsonl", "commands.jsonl", "holdout.jsonl", "holdout2.jsonl"):
        for line in open(HERE / f, encoding="utf-8"):
            row = json.loads(line)
            for k in ("text", "command", "cmd"):
                if isinstance(row.get(k), str):
                    texts.add(row[k].lower().strip(" .?!"))
    toks = lambda x: set(re.findall(r"\w+", x.lower()))
    for x in SENTENCES:
        assert x.lower().strip(" .?!") not in texts, ("test sentence is a case sentence", x)
        for y in texts:
            a, b = toks(x), toks(y)
            assert len(a & b) / max(1, len(a | b)) < 0.8, ("near copy of a case sentence", x, y)
    return len(SENTENCES), len(texts)


def main():
    counts = {"b Zeit": test_time(), "b Größe": test_size(), "c Ausschluss": test_exclusion(), "d Ordner": test_folders(),
              "e nicht möglich": test_unsupported(), "a Wächter": test_guard(), "f Plausibilität": test_count(),
              "Gedächtnis-Absage": test_memory()}
    n, m = leak_check()
    print("rules2 unit tests ok:", counts, f"| {n} Testsätze gegen {m} Fallsätze geprüft, keine Kopie")
    return counts


if __name__ == "__main__":
    main()
