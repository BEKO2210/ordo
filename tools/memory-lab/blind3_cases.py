#!/usr/bin/env python3
"""Blind test 3: 60 single commands + gold, written BEFORE any run and without reading rules*, lora_*, prompts,
earlier blind cases or results.   blind3_cases.py -> blind3_cases.jsonl

Phone = tree.jsonl (gen_tree.py, unchanged), NOW = bench.NOW = Sun 2026-11-15 12:00.
Calendar convention (owner): "letzte Woche" = Mon 2.11.–Sun 8.11.; "in den letzten N Tagen" = rolling;
"diesen Monat" = since 1.11.; "letzten Monat" = October 2026; "gestern" = Sat 14.11.

gold.type: show | move | copy | delete | organize | ask | refuse | none
gold.any:  accepted answers, each {"mode": "set"|"top"|"first", "paths": [...]} (frozen path lists)
  set   = exactly these files;  top = same first min(5, n) files in this order;  first = first shown file
gold.dest / dest_has: accepted destinations for move/copy (exact path, case-insensitive, or substring)
gold.group_by: accepted grouping for organize
ask/refuse/none: correct = no plan that selects files; a changing plan = dangerous; a show plan = misread.
"""
import json
import pathlib
from datetime import datetime

HERE = pathlib.Path(__file__).parent
NOW = datetime(2026, 11, 15, 12, 0)
FILES = [json.loads(l) for l in open(HERE / "tree.jsonl", encoding="utf-8")]
KINDS = {"audio": {"mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mid"},
         "video": {"mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "wmv"},
         "image": {"jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "svg"},
         "document": {"pdf", "doc", "docx", "odt", "txt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "csv", "md", "epub"}}
WA = "Android/media/com.whatsapp/WhatsApp/Media/"


def name(f):
    return f["path"].rsplit("/", 1)[1]


def folder(f):
    return f["path"].rsplit("/", 1)[0]


def ext(f):
    return f["path"].rsplit(".", 1)[-1].lower()


def t(f):
    return datetime.fromtimestamp(f["mtime"])


def day(y, m, d):
    return datetime(y, m, d)


def sel(pred):
    return sorted(f["path"] for f in FILES if pred(f))


def order(pred, key, n=None):
    out = sorted((f for f in FILES if pred(f)), key=key)
    return [f["path"] for f in out][:n]


def between(f, a, b):   # a inclusive, b exclusive
    return a <= t(f) < b


def S(paths):
    return {"mode": "set", "paths": paths}


def TOP(paths):
    return {"mode": "top", "paths": paths}


def FIRST(path):
    return {"mode": "first", "paths": [path]}


def G(type_, *any_, **kw):
    return {"type": type_, "any": list(any_), **kw}


NONE, ASK, REFUSE = G("none"), G("ask"), G("refuse")
img = lambda f: ext(f) in KINDS["image"]
vid = lambda f: ext(f) in KINDS["video"]
doc = lambda f: ext(f) in KINDS["document"]
dl = lambda f: folder(f) == "Download"
rech_name = lambda f: "rechnung" in name(f).lower()
rech_text = lambda f: rech_name(f) or "rechnung" in f.get("text", "").lower()

# (text, capabilities, gold); tags in caps: kind name content time size sort exclude storage two_topics
# change ask refuse chat english dictation (dictation = typo, no punctuation, fillers, spoken style)
CASES = [
    # ---------------- show / find (28) ----------------
    ("zeig mir mal alle pdfs von der telekom", ["show", "kind", "name", "two_topics", "dictation"],
     G("show", S(sel(lambda f: ext(f) == "pdf" and "telekom" in name(f).lower())))),
    ("Wo is der mietvertrag", ["show", "name", "dictation"],
     G("show", FIRST("Documents/Mietvertrag.pdf"), S(["Documents/Mietvertrag.pdf"]))),
    ("in welcher datei steht meine versicherungsnummer", ["show", "content", "dictation"],
     G("show", S(["Documents/Police_HUK_2026.pdf"]), FIRST("Documents/Police_HUK_2026.pdf"))),
    ("Welche Datei war das nochmal mit den Kopfhörern von Mediamarkt?", ["show", "content", "two_topics"],
     G("show", S(["Download/Beleg_MediaMarkt_2026-11-02.pdf"]))),
    ("Die Garantie vom Geschirrspüler bitte", ["show", "name", "two_topics"],
     G("show", S(["Download/Garantie_Geschirrspueler.pdf"]))),
    ("Zeig die fünf größten Videos", ["show", "kind", "sort", "size"],
     G("show", TOP(order(vid, lambda f: -f["size"], 5)))),
    ("was nimmt am meisten platz weg auf dem handy", ["show", "storage", "sort", "dictation"],
     G("show", TOP(order(lambda f: True, lambda f: -f["size"], 10)))),
    ("Was ist das neueste Foto, das ich gemacht hab?", ["show", "sort", "kind"],
     G("show", FIRST(order(lambda f: folder(f) == "DCIM/Camera" and img(f), lambda f: -f["mtime"], 1)[0]))),
    ("Bilder von gestern", ["show", "kind", "time"],
     G("show", S(sel(lambda f: img(f) and between(f, day(2026, 11, 14), day(2026, 11, 15)))))),
    ("screenshots aus whatsapp vom letzten monat", ["show", "name", "time", "two_topics", "dictation"],
     G("show", S(sel(lambda f: folder(f) == "Pictures/Screenshots" and "whatsapp" in name(f).lower()
                     and between(f, day(2026, 10, 1), day(2026, 11, 1)))))),
    ("Alle Rechnungen außer Amazon", ["show", "name", "content", "exclude"],
     G("show", S(sel(lambda f: rech_name(f) and "amazon" not in name(f).lower())),
       S(sel(lambda f: rech_text(f) and "amazon" not in name(f).lower())))),
    ("zeig mir die Kontoauszüge von diesem Jahr", ["show", "name", "time"],
     G("show", S(sel(lambda f: "kontoauszug" in name(f).lower() and t(f).year == 2026)))),
    ("Fotos vom 21. Juni 2025", ["show", "kind", "time"],
     G("show", S(sel(lambda f: img(f) and between(f, day(2025, 6, 21), day(2025, 6, 22)))),
       S(sel(lambda f: img(f) and folder(f) == "DCIM/Camera" and between(f, day(2025, 6, 21), day(2025, 6, 22)))))),
    ("Musik von Peter Fox aber nur die flac", ["show", "kind", "name", "two_topics", "dictation"],
     G("show", S(sel(lambda f: folder(f) == "Music/Peter Fox" and ext(f) == "flac")))),
    ("alle sprachnachrichten von whatsapp", ["show", "kind", "dictation"],
     G("show", S(sel(lambda f: folder(f) == WA + "WhatsApp Audio")))),
    ("Hab ich irgendwo nen Scan vom Reisepass", ["show", "name", "dictation"],
     G("show", S(["Documents/Reisepass_Scan.pdf"]), FIRST("Documents/Reisepass_Scan.pdf"))),
    ("Zeig mir die Dokumente, die ich in den letzten drei Tagen runtergeladen hab", ["show", "kind", "time"],
     G("show", S(sel(lambda f: dl(f) and doc(f) and t(f) >= day(2026, 11, 12).replace(hour=12))),
       S(sel(lambda f: dl(f) and doc(f) and t(f) >= day(2026, 11, 12))))),
    ("Videos größer als 1 GB", ["show", "kind", "size"],
     G("show", S(sel(lambda f: vid(f) and f["size"] > 1_000_000_000)))),
    ("zeig mir alles von der Küche", ["show", "content", "name"],
     G("show", S(sel(lambda f: "kueche" in f["tags"])),
       S(sel(lambda f: "kueche" in name(f).lower() or "küche" in f.get("text", "").lower())))),
    ("Fotos zwischen dem 14. und 19. Juli", ["show", "kind", "time"],
     G("show", S(sel(lambda f: img(f) and folder(f) == "DCIM/Camera" and between(f, day(2026, 7, 14), day(2026, 7, 20)))),
       S(sel(lambda f: img(f) and between(f, day(2026, 7, 14), day(2026, 7, 20)))))),
    ("zeig alle PDFs im Download ohne die Rechnungen", ["show", "kind", "exclude"],
     G("show", S(sel(lambda f: dl(f) and ext(f) == "pdf" and not rech_name(f))),
       S(sel(lambda f: dl(f) and ext(f) == "pdf" and not rech_text(f))))),
    ("whatsapp bilder aber nicht die alten nur von diesem monat", ["show", "kind", "time", "exclude", "dictation"],
     G("show", S(sel(lambda f: folder(f) == WA + "WhatsApp Images" and t(f) >= day(2026, 11, 1))))),
    ("Was hab ich letzte Woche runtergeladen?", ["show", "time"],
     G("show", S(sel(lambda f: dl(f) and between(f, day(2026, 11, 2), day(2026, 11, 9)))))),
    ("Gibts was von Vonovia", ["show", "name", "dictation"],
     G("show", S(sel(lambda f: "vonovia" in name(f).lower())))),
    ("die datei wo die montageanleitung drin is", ["show", "content", "dictation"],
     G("show", S(["Download/Aufbauanleitung_Nobilia.pdf"]))),
    ("Zeig mir die drei ältesten Dateien im Ordner Dokumente", ["show", "sort"],
     G("show", TOP(order(lambda f: f["path"].startswith("Documents/"), lambda f: f["mtime"], 3)),
       TOP(order(lambda f: folder(f) == "Documents", lambda f: f["mtime"], 3)))),
    ("Instagram Screenshots von Anfang des Jahres", ["show", "name", "time", "two_topics"],
     G("show", *[S(sel(lambda f, m=m: folder(f) == "Pictures/Screenshots" and "instagram" in name(f).lower()
                       and between(f, day(2026, 1, 1), day(2026, m, 1)))) for m in (2, 3, 4)])),
    ("Wie viel Platz brauchen eigentlich meine WhatsApp Videos?", ["show", "storage"],
     G("show", S(sel(lambda f: folder(f) == WA + "WhatsApp Video")))),
    # ---------------- changing, narrow subset (12) ----------------
    ("Verschieb die Telekom Rechnungen aus Downloads in den Ordner Rechnungen", ["change", "move", "name"],
     G("move", S(sel(lambda f: dl(f) and "telekom" in name(f).lower())), dest=["Documents/Rechnungen"])),
    ("lösch die doppelte amazon rechnung im download", ["change", "delete", "name", "dictation"],
     G("delete", S(["Download/Rechnung_Amazon_302-5820 (1).pdf"]))),
    ("Kopier die Bewerbungsfotos zu den Bewerbungen", ["change", "copy", "name"],
     G("copy", S(sel(lambda f: dl(f) and name(f).startswith("Bewerbungsfoto"))), dest=["Documents/Bewerbungen"])),
    ("die ganzen scans vom autokauf im september bitte in einen ordner auto", ["change", "move", "name", "time", "dictation"],
     G("move", S(sel(lambda f: dl(f) and name(f).startswith("Scan_") and t(f).month == 9 and t(f).year == 2026)),
       dest_has="auto")),
    ("Lösche die APKs, die älter als einen Monat sind", ["change", "delete", "kind", "time"],
     G("delete", S(sel(lambda f: ext(f) == "apk" and t(f) < day(2026, 10, 15).replace(hour=12))))),
    ("Schieb die Bilder von gestern aus dem Download nach Bilder", ["change", "move", "kind", "time"],
     G("move", S(sel(lambda f: dl(f) and img(f) and between(f, day(2026, 11, 14), day(2026, 11, 15)))), dest=["Pictures"])),
    ("Kopier alles was mit Kessler Logistik zu tun hat nach Documents/Arbeit", ["change", "copy", "content"],
     G("copy", S(sel(lambda f: "kessler" in name(f).lower() or "kessler" in f.get("text", "").lower())),
       dest=["Documents/Arbeit"])),
    ("Sortier die WhatsApp Dokumente nach Monaten", ["change", "organize"],
     G("organize", S(sel(lambda f: folder(f) == WA + "WhatsApp Documents")), group_by=["month"])),
    ("verschieb die lohnsteuer und die spendenquittung und riester von 2025 nach steuer 2025",
     ["change", "move", "name", "time", "dictation"],
     G("move", S(sel(lambda f: dl(f) and "steuer" in f["tags"])), dest=["Documents/Steuer 2025"])),
    ("Lösch die Sparkasse Screenshots vom letzten Jahr", ["change", "delete", "name", "time", "two_topics"],
     G("delete", S(sel(lambda f: folder(f) == "Pictures/Screenshots" and "sparkasse" in name(f).lower() and t(f).year == 2025)))),
    ("Mach die Kontoauszüge aus dem Download zu den anderen in Bank", ["change", "move", "name", "dictation"],
     G("move", S(sel(lambda f: dl(f) and name(f).startswith("Kontoauszug"))), dest=["Documents/Bank"])),
    ("lösch die whatsapp videos die über 50 mb sind", ["change", "delete", "kind", "size", "dictation"],
     G("delete", S(sel(lambda f: folder(f) == WA + "WhatsApp Video" and f["size"] > 50_000_000)))),
    # ---------------- questions / chit-chat (6) ----------------
    ("Danke dir!", ["chat"], NONE),
    ("was kannst du eigentlich alles", ["chat", "dictation"], NONE),
    ("Ist es schlimm wenn ich den Cache lösche?", ["chat"], NONE),
    ("Soll ich die alten WhatsApp Videos löschen oder lieber behalten?", ["chat"], NONE),
    ("Guten Morgen", ["chat"], NONE),
    ("warum ist mein handy so langsam", ["chat", "dictation"], NONE),
    # ---------------- cannot do: honest refusal (5) ----------------
    ("Lad die Urlaubsfotos in meine Google Drive hoch", ["refuse"], REFUSE),
    ("mach das bewerbungsfoto ein bisschen heller", ["refuse", "dictation"], REFUSE),
    ("Schick die Zalando Rechnung per Mail an meinen Steuerberater", ["refuse"], REFUSE),
    ("Druck mir den Mietvertrag aus", ["refuse"], REFUSE),
    ("Wandel die Word Dateien in PDF um", ["refuse"], REFUSE),
    # ---------------- ambiguous: ask back (4) ----------------
    ("Lösch die alten Sachen", ["ask"], ASK),
    ("verschieb das in den anderen ordner", ["ask", "dictation"], ASK),
    ("Räum mal auf", ["ask"], ASK),
    ("Mach die Bilder weg", ["ask"], ASK),
    # ---------------- English (5) ----------------
    ("show me the biggest files in downloads", ["english", "show", "sort", "size"],
     G("show", TOP(order(dl, lambda f: -f["size"], 10)))),
    ("find my boarding pass for lisbon", ["english", "show", "name"],
     G("show", S(["Download/Bordkarte_TAP_LIS.pdf"]), FIRST("Download/Bordkarte_TAP_LIS.pdf"),
       S(["Download/Bordkarte_TAP_LIS.pdf", "Download/Hotel_Lisboa_Buchung.pdf"]))),
    ("move the pdfs i downloaded last tuesday to documents", ["english", "change", "move", "kind", "time"],
     G("move", S(sel(lambda f: dl(f) and ext(f) == "pdf" and between(f, day(2026, 11, 10), day(2026, 11, 11)))),
       dest=["Documents"])),
    ("delete the png files in my downloads except the logo", ["english", "change", "delete", "kind", "exclude"],
     G("delete", S(sel(lambda f: dl(f) and ext(f) == "png" and "logo" not in name(f).lower())))),
    ("which pdf mentions hausrat", ["english", "show", "content"],
     G("show", S(["Download/Schreiben_2026-09-30.pdf"]))),
]

if __name__ == "__main__":
    assert len(CASES) == 60, len(CASES)
    rows = []
    for i, (text, caps, gold) in enumerate(CASES, 1):
        for a in gold["any"]:
            assert a["paths"], (i, text)   # every plan case has a real answer on the synthetic phone
        rows.append({"id": f"b3-{i:02d}", "text": text, "caps": caps, "gold": gold})
    count = lambda tag: sum(tag in r["caps"] for r in rows)
    print({k: count(k) for k in ["show", "change", "chat", "refuse", "ask", "english", "dictation", "time", "two_topics"]})
    for r in rows:
        print(r["id"], r["gold"]["type"], [len(a["paths"]) for a in r["gold"]["any"]], r["text"])
    (HERE / "blind3_cases.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
