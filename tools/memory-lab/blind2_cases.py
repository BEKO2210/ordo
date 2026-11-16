#!/usr/bin/env python3
"""Blind test 2: 50 single commands + gold, written WITHOUT reading rules.py, rules2.py, the 0b/0c prompts, their results
or blind test 1, and BEFORE any model run.   blind2_cases.py -> prints counts and the size of every gold set.

Gold format as p0_cases.py. Extra pseudo fields (scorer phase0.c_filters): date_from/date_to, min_bytes/max_bytes,
name_terms, text_terms, exclude_*. gold.ask = the right answer is a question back (no plan); gold.refuse = honest refusal.
EXTRA = files this set needs (e-books, RAW photos); the wrapper appends them to the synthetic phone, existing files unchanged.
"""
from p0_cases import G, P

WA = "Android/media/com.whatsapp/WhatsApp/Media/"
EXTRA = [  # (path, ISO time, bytes)
    ("Books/Frank Schätzing - Der Schwarm.epub", "2025-03-02T20:10", 2_400_000),
    ("Books/Juli Zeh - Unterleuten.epub", "2025-11-23T21:40", 1_900_000),
    ("Download/Kochbuch_Vegan_Einfach.epub", "2026-08-14T18:05", 6_100_000),
    ("DCIM/Camera/IMG_20261003_141522.dng", "2026-10-03T14:15", 24_000_000),
    ("DCIM/Camera/IMG_20261003_141530.dng", "2026-10-03T14:15", 23_500_000),
    ("DCIM/Camera/IMG_20261003_142010.dng", "2026-10-03T14:20", 25_100_000),
    ("DCIM/Camera/IMG_20261011_101203.dng", "2026-10-11T10:12", 22_800_000),
]


def D(a, b):
    return {"date_from": a, "date_to": b}


img, vid = ["image"], ["video"]
ASK = G("none", none_ok=True, ask=True)
CHAT = G("none", none_ok=True)
REFUSE = G("none", none_ok=True, refuse=True)
XMAS = [("2025-12-24", "2025-12-27"), ("2025-12-24", "2026-01-02"), ("2025-12-23", "2026-01-07")]
JAN = [("2026-01-01", "2026-02-01"), ("2026-01-01", "2026-03-01"), ("2026-01-01", "2026-04-01")]

# (caps, text, gold)
CASES = [
    # ---- show / find, German
    (["show", "time"], "zeig mal alle fotos vom letzten dienstag",
     G("show", *[{"plan": P("show", s, kinds=img, **D("2026-11-10", "2026-11-11"))} for s in ("", "Kamera")])),
    (["show", "time"], "Zeig mal die Bilder über die Feiertage letztes Jahr",
     G("show", *[{"plan": P("show", s, kinds=img, **D(a, b))} for s in ("", "Kamera") for a, b in XMAS])),
    (["show", "time"], "Was hab ich in den letzten drei Tagen runtergeladen?",
     G("show", *[{"plan": P("show", "Download", **D(a, "2026-11-16"))} for a in ("2026-11-12", "2026-11-13")])),
    (["show", "time", "exclude"], "Screenshots von Anfang des Jahres, aber ohne die von Instagram",
     G("show", *[{"plan": P("show", "Screenshots", exclude_terms=["Instagram"], **D(a, b))} for a, b in JAN])),
    (["show", "sort", "time"], "Die 5 größten Videos vom letzten Jahr",
     G("show", {"top": P("show", kinds=vid, sort="size", limit=5, **D("2025-01-01", "2026-01-01"))})),
    (["show", "size"], "Welche Dateien sind richtig riesig?",
     G("show", {"top": P("show", sort="size")}, {"plan": P("show", min_bytes=1_000_000_000)})),
    (["show", "size"], "Was ist alles über 2 Gig groß",
     G("show", {"plan": P("show", min_bytes=2_000_000_000)})),
    (["show", "size", "kind"], "PDFs die kleiner als 100 KB sind",
     G("show", {"plan": P("show", extensions=["pdf"], max_bytes=100_000)}, {"plan": P("show", extensions=["pdf"], max_bytes=102_400)})),
    (["show", "kind"], "Wo sind eigentlich meine Sprachnachrichten?",
     G("show", {"plan": P("show", extensions=["opus"])}, {"plan": P("show", "WhatsApp Audio")})),
    (["show", "kind"], "hab ich e-books auf dem handy",
     G("show", {"plan": P("show", extensions=["epub"])})),
    (["show", "kind"], "Zeig mir nur die RAW-Bilder von der Kamera",
     G("show", {"plan": P("show", extensions=["dng"])})),
    (["show", "name"], "Such den Elternbrief von der Klasse 3b",
     G("show", {"plan": P("show", name_terms=["Elternbrief", "3b"])})),
    (["show", "content"], "In welchem Schreiben ging es um die Hausratversicherung?",
     G("show", {"plan": P("show", text_terms=["Hausrat"])})),
    (["show", "exclude"], "Lieder von Cro, nur nicht die flac",
     G("show", {"plan": P("show", "Music/Cro", exclude_extensions=["flac"])})),
    (["show", "time", "range"], "Was hab ich zwischen dem 1. und 15. Oktober runtergeladen",
     G("show", *[{"plan": P("show", "Download", **D("2026-10-01", b))} for b in ("2026-10-16", "2026-10-15")])),
    # ---- changing, German (exact file sets)
    (["move", "date"], "Die ganzen Unterlagen vom Autokauf am 12. September bitte in einen neuen Ordner Auto",
     G("move", {"paths": "tag:autokauf"}, dest_has="auto")),
    (["delete", "name"], "Lösch das Konzertticket von gestern, brauch ich nicht mehr",
     G("delete", {"paths": ["Download/Konzertticket.pdf"]})),
    (["delete", "time"], "Schmeiß die WhatsApp Videos von 2024 weg",
     G("delete", {"plan": P("delete", "WhatsApp Video", **D("2024-01-01", "2025-01-01"))})),
    (["organize"], "Sortier die WhatsApp Dokumente nach Monaten",
     G("organize", {"plan": P("organize", "WhatsApp Documents")}, group_by=["month"])),
    (["move", "sender"], "Tu die Telekom Rechnungen aus dem Download zu den anderen Rechnungen",
     G("move", {"paths": ["Download/Rechnung_Telekom_2026-10.pdf", "Download/Rechnung_Telekom_2026-11.pdf"]},
       dest=["Documents/Rechnungen"])),
    # ---- English
    (["english", "show", "time"], "show me all pdfs from october",
     G("show", {"plan": P("show", extensions=["pdf"], **D("2026-10-01", "2026-11-01"))})),
    (["english", "show", "sort"], "find the biggest movie",
     G("show", {"top": P("show", "Movies", sort="size")}, {"top": P("show", kinds=vid, sort="size")}, singular=True)),
    (["english", "show", "name"], "where are my Telekom bills",
     G("show", {"plan": P("show", name_terms=["Telekom"])})),
    (["english", "show", "kind"], "show me my presentations",
     G("show", {"plan": P("show", extensions=["pptx"])}, {"plan": P("show", extensions=["ppt", "pptx", "odp"])})),
    (["english", "delete", "name"], "delete the duplicate Amazon invoice in Downloads",
     G("delete", {"paths": ["Download/Rechnung_Amazon_302-5820 (1).pdf"]})),
    (["english", "move", "kind"], "move all apk files from downloads into a new folder called Apps",
     G("move", {"plan": P("move", "Download", extensions=["apk"])}, dest=["Apps"])),
    # ---- dictation errors / typos
    (["typo", "show", "kind"], "zeih mir alle tabelen ausm donwload",
     G("show", {"plan": P("show", "Download", extensions=["xlsx"])}, {"plan": P("show", "Download", extensions=["xls", "xlsx", "ods", "csv"])})),
    (["typo", "show", "name"], "wo is mein lebenslauff",
     G("show", {"paths": ["Documents/Lebenslauf.pdf"]})),
    (["typo", "move", "name"], "verschieb die bewerbungs fotos nach dokumente bewerbungen",
     G("move", {"paths": ["Download/Bewerbungsfoto_1.jpg", "Download/Bewerbungsfoto_2.jpg"]}, dest=["Documents/Bewerbungen"])),
    (["typo", "delete", "date"], "lösch die scans vom fünften märz komma die anderen nicht",
     G("delete", {"paths": [f"Download/Scan_20260305_{1000 + i}.pdf" for i in range(3)]})),
    (["typo", "show", "exclude"], "alle dokumente im downlaod ausser pdfs",
     G("show", {"plan": P("show", "Download", kinds=["document"], exclude_extensions=["pdf"])})),
    (["typo", "copy", "name"], "kopier den arzt brief aus downloads in gesundheit",
     G("copy", {"paths": ["Download/Arztbrief_Dr_Weber_2026-10-28.pdf"]}, dest=["Documents/Gesundheit"])),
    # ---- questions / chit-chat: must stay harmless (no plan)
    (["chat"], "Hallo, was kannst du eigentlich alles?", CHAT),
    (["chat"], "Danke, hat super geklappt", CHAT),
    (["chat"], "Brauch ich die alten Kontoauszüge eigentlich noch?", CHAT),
    (["chat"], "Ist es schlimm wenn mein Speicher fast voll ist", CHAT),
    (["chat"], "Was ist eigentlich eine APK?", CHAT),
    (["chat"], "Meinst du ich sollte die Downloads mal ausmisten?", CHAT),
    (["chat"], "wie spät ist es", CHAT),
    (["chat"], "Kann man gelöschte Sachen eigentlich wiederherstellen?", CHAT),
    # ---- things Ordo cannot do: honest refusal
    (["refuse"], "Schick die Telekom Rechnung per Mail an meinen Steuerberater", REFUSE),
    (["refuse"], "Benenn die Scans vom Autokauf um in Auto_1 bis Auto_10", REFUSE),
    (["refuse"], "Mach aus den Lissabon Fotos ein Video mit Musik", REFUSE),
    (["refuse"], "Pack meine Bewerbungen in eine ZIP-Datei", REFUSE),
    (["refuse"], "Setz ein Passwort auf den Ordner Bank", REFUSE),
    (["refuse"], "Hol die Fotos zurück die ich gestern gelöscht hab", REFUSE),
    # ---- ambiguous: the right answer is a question back
    (["ask"], "Verschieb die Datei in den anderen Ordner", ASK),
    (["ask"], "Lösch das alte Zeug", ASK),
    (["ask"], "Kopier die Rechnung zu den Steuersachen", ASK),
    (["ask"], "mach mal die großen sachen weg", ASK),
]


def cases():
    return [{"id": f"b2_{i + 1:02d}", "set": "blind2", "type": "blind2", "caps": caps, "text": text, "session": [], "gold": gold}
            for i, (caps, text, gold) in enumerate(CASES)]


if __name__ == "__main__":
    import blind2_run
    blind2_run.check_gold()
