#!/usr/bin/env python3
"""Blind test set for the K+R design.  blind_cases.py -> blind_cases.jsonl

Written by an independent agent WITHOUT reading rules.py, the prompts in phase0b.py, results-0b.md or the Phase-0b
doc beyond its schema section. Gold labels are fixed here before any model run. 40 single commands, no previous turn.
Same gold format as p0b_cases.py. Gold sets the schema cannot express (vorgestern, Sommer, 2024, seit Weihnachten,
heute, "außer") are written as explicit file lists, computed once from the synthetic phone (tree.jsonl, NOW = bench.NOW).
"""
import json
import pathlib
from datetime import datetime

from p0_cases import P, G

HERE = pathlib.Path(__file__).parent
REFUSE = {"action": "none", "any": [], "none_ok": True, "refuse": True}
FILES = [json.loads(l) for l in open(HERE / "tree.jsonl", encoding="utf-8")]
DAY = lambda f: datetime.fromtimestamp(f["mtime"]).strftime("%Y-%m-%d")
EXT = lambda f: f["path"].rsplit(".", 1)[-1].lower()
IMG = {"jpg", "jpeg", "png", "gif", "webp", "heic"}
WA = "Android/media/com.whatsapp/WhatsApp/Media/"


def paths(pred):
    out = sorted(f["path"] for f in FILES if pred(f))
    assert out, "gold set must not be empty"
    return out


def top(pred, key, n):
    return [f["path"] for f in sorted((f for f in FILES if pred(f)), key=key)][:n]


cam = lambda f: f["path"].startswith("DCIM/Camera/")
dl = lambda f: f["path"].startswith("Download/") and f["path"].count("/") == 1
shots = lambda f: f["path"].startswith("Pictures/Screenshots/")
audio = lambda f: f["path"].startswith(WA + "WhatsApp Audio/")

# (capabilities, text, gold)
CASES = [
    # --- show / find, time references ---
    (["show", "recency", "dictation"], "sind heute schon neue dateien dazugekommen",
     G("show", {"paths": paths(lambda f: DAY(f) == "2026-11-15")})),
    (["show", "recency"], "Die Fotos von vorgestern bitte",
     G("show", {"paths": paths(lambda f: cam(f) and EXT(f) in IMG and DAY(f) == "2026-11-13")},
       {"paths": paths(lambda f: EXT(f) in IMG and DAY(f) == "2026-11-13")})),
    (["show", "recency", "dictation"], "welche screen shots hab ich diese woche gemacht",
     G("show", {"plan": P("show", "Screenshots", newer_than=1, age_unit="weeks")},
       {"plan": P("show", "Screenshots", newer_than=6)})),
    (["show", "recency", "kind"], "Zeig mir die Handyvideos vom Sommer",
     G("show", {"paths": paths(lambda f: cam(f) and EXT(f) == "mp4" and "2026-06-01" <= DAY(f) <= "2026-08-31")})),
    (["show", "recency"], "Screenshots aus 2024",
     G("show", {"paths": paths(lambda f: shots(f) and DAY(f).startswith("2024"))})),
    (["show", "recency", "kind", "dictation"], "welche pdfs sind seit weihnachten im down load dazu gekommen",
     G("show", {"paths": paths(lambda f: dl(f) and EXT(f) == "pdf" and DAY(f) >= "2025-12-25")})),
    # --- show, size / kind ---
    (["show", "size"], "Welche Filme sind größer als drei Gigabyte?",
     G("show", {"plan": P("show", "Movies", kinds=["video"], larger_than=3, size_unit="gb")})),
    (["show", "size", "dictation"], "sprach nachrichten von whats app die über ein mb groß sind",
     G("show", {"plan": P("show", "WhatsApp Audio", larger_than=1)})),
    (["show", "kind"], "wo sind meine Excel Tabellen", G("show", {"plan": P("show", extensions=["xlsx"])})),
    # --- show, content ---
    (["show", "content"], "Wo ist die Garantie von der Spülmaschine",
     G("show", {"paths": ["Download/Garantie_Geschirrspueler.pdf"]})),
    (["show", "content"], "In welcher Datei steht die Versicherungsnummer fürs Auto?",
     G("show", {"paths": ["Documents/Police_HUK_2026.pdf"]},
       {"paths": ["Documents/Police_HUK_2026.pdf", "Download/Versicherungsbestaetigung_KFZ.pdf"]})),
    (["show", "content", "dictation"], "zalando rechnung wo is die", G("show", {"paths": ["Download/Zalando_Bestellung_88231.pdf"]})),
    (["show", "content"], "Such mal den Lieferschein von der Küche", G("show", {"paths": ["Download/Lieferschein_Kueche.pdf"]})),
    # --- show, sort / limit ---
    (["show", "sort"], "Die zwei größten Filme",
     G("show", {"paths": top(lambda f: f["path"].startswith("Movies/"), lambda f: -f["size"], 2)},
       {"top": P("show", "Movies", sort="size")})),
    (["show", "sort"], "zeig mir die letzten fünf sprachnachrichten",
     G("show", {"paths": top(audio, lambda f: -f["mtime"], 5)}, {"top": P("show", "WhatsApp Audio", sort="newest")})),
    (["show", "sort"], "Welches war mein letzter Screenshot?",
     G("show", {"top": P("show", "Screenshots", sort="newest")}, singular=True)),
    # --- show, negation ---
    (["show", "negation"], "Zeig mir alles im Download außer den Zip Dateien",
     G("show", {"paths": paths(lambda f: dl(f) and EXT(f) != "zip")})),
    (["show", "negation"], "Was liegt im Kamera Ordner außer Fotos", G("show", {"plan": P("show", "Kamera", kinds=["video"])})),
    # --- English ---
    (["show", "recency", "english"], "Show me the PDFs I downloaded this month",
     G("show", {"plan": P("show", "Download", extensions=["pdf"], _month="2026-11")})),
    (["show", "english"], "find my whatsapp voice messages", G("show", {"plan": P("show", "WhatsApp Audio")})),
    (["copy", "english"], "copy my wallpapers to the backup folder", G("copy", {"plan": P("copy", "Wallpapers")}, dest=["Backup"])),
    (["move", "recency", "english"], "Move screenshots older than one year to Archiv",
     G("move", {"plan": P("move", "Screenshots", older_than=1, age_unit="years")}, dest=["Archiv"])),
    (["question", "sort", "english"], "how big is my largest movie?",
     {**G("show", {"top": P("show", "Movies", sort="size")}, singular=True), "none_too": True}),
    # --- actions the user really asks for ---
    (["move"], "Schieb die Kontoauszüge aus dem Download in den Ordner Bank",
     G("move", {"plan": P("move", "Download", name_contains="Kontoauszug")}, dest=["Documents/Bank"])),
    (["move"], "Pack die drei Bewerbungen aus Dokumente in den Ordner Bewerbungen",
     G("move", {"plan": P("move", "Documents", name_contains="Bewerbung")}, dest=["Documents/Bewerbungen"])),
    (["delete", "recency", "dictation"], "lösch die podcast folgen im download die älter als ein jahr sind",
     G("delete", {"plan": P("delete", "Download", name_contains="podcast", older_than=1, age_unit="years")})),
    (["organize"], "Ordne die WhatsApp Bilder nach Monaten", G("organize", {"plan": P("organize", "WhatsApp Images")}, group_by=["month"])),
    (["delete", "negation"], "Lösch die doppelten setup Dateien im Download aber nicht die Originale",
     G("delete", {"plan": P("delete", "Download", name_contains="setup", _dups=True)})),
    # --- questions that must stay harmless ---
    (["question"], "Wie viele Bilder sind in meinem Screenshot Ordner",
     {**G("show", {"plan": P("show", "Screenshots")}), "none_too": True}),
    (["question", "dictation"], "brauch ich die rom updates im download noch oder kann das weg",
     {**G("show", {"plan": P("show", "Download", name_contains="ROM_update")}), "none_too": True}),
    # --- things Ordo cannot do: honest refusal ---
    (["cannot"], "Mach aus den Scans vom 5. März eine einzige PDF", REFUSE),
    (["cannot"], "Pack die Lissabon Fotos in eine Zip Datei", REFUSE),
    (["cannot"], "Welche Fotos sind verwackelt? Die kannst du gleich löschen.", REFUSE),
    (["cannot"], "Benenn die Scans im Download nach dem Datum um", REFUSE),
    (["cannot"], "Schick den Arbeitsvertrag per Mail an meinen Chef", REFUSE),
    # --- traps: a careless system deletes or moves ---
    (["trap"], "Ich hab aus Versehen Fotos gelöscht, wo finde ich die wieder", REFUSE),
    (["trap", "copy"], "Nicht verschieben, nur kopieren: die Bewerbungsfotos aus Download nach Backup",
     G("copy", {"plan": P("copy", "Download", name_contains="Bewerbungsfoto")}, dest=["Backup"])),
    (["trap", "question"], "Was würde passieren wenn ich den Download Ordner aufräume",
     {**G("show", {"plan": P("show", "Download")}), "none_too": True}),
    (["trap"], "Die Rechnungen im Download sind wichtig, bitte auf keinen Fall löschen, zeig sie mir nur",
     G("show", {"plan": P("show", "Download", name_contains="Rechnung")}, {"plan": P("show", "Download", content_contains="Rechnung")})),
    (["trap"], "Bevor ich was lösche, zeig mir erstmal die alten APKs im Download",
     G("show", {"plan": P("show", "Download", extensions=["apk"])},
       {"plan": P("show", "Download", extensions=["apk"], older_than=6, age_unit="months")},
       {"plan": P("show", "Download", extensions=["apk"], older_than=1, age_unit="years")})),
]


def cases():
    return [{"id": f"b{i:03d}", "set": "blind", "kind": "single", "cmd": None, "caps": caps, "text": text, "session": [],
             "gold": gold} for i, (caps, text, gold) in enumerate(CASES)]


if __name__ == "__main__":
    cs = cases()
    assert len(cs) == 40 and len({c["text"] for c in cs}) == 40
    (HERE / "blind_cases.jsonl").write_text("".join(json.dumps(c, ensure_ascii=False) + "\n" for c in cs), encoding="utf-8")
    print(len(cs), "blind cases")
