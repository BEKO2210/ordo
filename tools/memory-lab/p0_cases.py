#!/usr/bin/env python3
"""Phase-0 cases with gold labels, written before any model run.  p0_cases.py -> p0_cases.jsonl

Sets: main (prompts may be tuned on it), holdout (never used for tuning), stale (memory older than the files).
A gold plan uses the extended schema (S) and is run on the synthetic phone to get the wanted files. Pseudo
fields only the gold may use: _dups (extra copies), _month ("2026-10"), _opened_before (days, Ordo's open log).
gold.any = accepted answers, each {"plan": p} (same files), {"top": p} (same first 5 in order), {"paths": [...]},
{"first": path} (first shown file); "none_ok" = no plan is right; "wrong" = the dropped first interpretation.
"""
import json
import pathlib

HERE = pathlib.Path(__file__).parent
DEFAULT = {"action": "none", "source": "", "destination": "", "kinds": [], "extensions": [], "name_contains": "",
           "content_contains": "", "older_than": 0, "newer_than": 0, "age_unit": "days", "larger_than": 0,
           "size_unit": "mb", "sort": "", "limit": 0, "group_by": "", "group": ""}


def P(action, source="", **kw):
    return {**DEFAULT, "action": action, "source": source, **kw}


def T(text, plan, ago_min, executed=False):
    return {"text": text, "plan": plan, "ago_min": ago_min, "executed": executed}


def G(action, *any_, **kw):
    return {"action": action, "any": list(any_), **kw}


SHOW_BIG = G("show", {"top": P("show", sort="size")})
# Twenty commands: gold, capabilities, sessions
GOLD = {
    1: (["show"], SHOW_BIG, None),
    2: (["show", "recency"], G("show", {"plan": P("show", "Download", kinds=["image"], newer_than=1)}), None),
    3: (["show", "content"], G("show", {"plan": P("show", extensions=["pdf"], content_contains="Versicherung")}), None),
    4: (["show"], G("show", {"plan": P("show", _dups=True)}), None),
    5: (["show"], G("show", {"plan": P("show", kinds=["video"], larger_than=500)}), None),
    6: (["show", "content"], G("show", {"plan": P("show", content_contains="Rechnung")}), None),
    7: (["today"], G("organize", {"plan": P("organize", "Download")}, group_by=["type", "month"]), None),
    8: (["content"], G("move", {"plan": P("move", "Download", content_contains="Rechnung")}, dest_has="rechnung"), None),
    9: (["show", "recency"], G("show", {"first": "Download/Signal-Android-7.24.apk"}), None),
    10: (["show", "recency"], G("show", {"plan": P("show", "Screenshots", newer_than=1, age_unit="months")},
                               {"plan": P("show", "Screenshots", _month="2026-10")}), None),
    11: (["show", "habits"], G("show", {"plan": P("show", _opened_before=7)}), None),
    12: (["content"], G("move", {"paths": "tag:arbeit"}, dest_has="arbeit"), None),
    13: (["show", "recency"], G("show", *[{"plan": P("show", "Kamera", kinds=["image"], newer_than=n)} for n in range(2, 8)],
                               {"first": "newest:DCIM/Camera:image"}), None),
    14: (["show"], G("show", {"top": P("show", sort="size")}, {"plan": P("show", _dups=True)}), None),
    15: (["show"], G("show", {"plan": P("show", "WhatsApp Images", kinds=["image"])}), None),
    16: (["show", "session"], G("show", {"plan": P("show", "Documents/Rechnungen")}),
         [T("Zeig mir den Ordner Rechnungen", P("show", "Documents/Rechnungen"), 6),
          T("Zeig mir die größten Dateien auf meinem Handy", P("show", sort="size", limit=10), 3)]),
    17: (["show", "session"], G("show", {"paths": "tag:kueche"}, {"paths": "tag:kueche-Download/Rechnung_Kueche_MoebelBoss.pdf"}),
         [T("Zeig mir die Rechnung von der Küche", P("show", name_contains="Rechnung_Kueche"), 4)]),
    18: (["session"], G("move", {"plan": P("move", "Download", kinds=["image"])}, dest=["Pictures/Gespeichert"]),
         [T("Verschieb die PNGs aus Download nach Pictures/Gespeichert",
            P("move", "Download", destination="Pictures/Gespeichert", extensions=["png"]), 4, executed=True)]),
    19: (["show", "habits"], G("show", {"paths": ["Documents/Mietvertrag.pdf"]}), None),
    20: (["habits"], G("organize", {"plan": P("organize", "Documents")}, group_by=["month"]), None),
}
TEXT = {
    1: ["Zeig mir mal die größten Dateien auf meinem Handy.", "zeig mal was am meisten platz frisst auf dem handy",
        "Welche Dateien sind auf meinem Telefon am größten?"],
    2: ["Wo sind die Bilder, die ich gestern heruntergeladen habe?", "die fotos von gestern aus dem download wo sind die",
        "Zeig die Bilder, die gestern im Download gelandet sind"],
    3: ["Such mir alle PDFs, in denen irgendwas mit Versicherung steht.", "pdfs mit versicherungs zeug drin such mal",
        "Finde PDF Dokumente, in denen das Wort Versicherung vorkommt"],
    4: ["Welche Dateien habe ich doppelt auf dem Handy?", "hab ich irgendwelche dateien doppelt drauf",
        "Gibt es auf meinem Handy Dateien, die mehrfach da sind?"],
    5: ["Zeig mir alle Videos, die größer als 500 MB sind.", "zeig mir die video's größer 500 mega byte",
        "Liste alle Videos über 500 Megabyte auf"],
    6: ["Ich suche eine Rechnung, weiß aber nicht mehr, wie die Datei heißt.", "wo ist diese eine rechnung ich weiß den namen nicht mehr",
        "Ich brauch eine Rechnung, keine Ahnung wie die Datei heißt"],
    7: ["Räum meinen Download-Ordner auf, aber lösch nichts Wichtiges.", "mach mal ordnung im download aber nix wichtiges wegschmeißen",
        "Download aufräumen bitte, aber ja nichts löschen"],
    8: ["Verschieb alle Rechnungen aus Downloads in einen eigenen Ordner.", "die rechnung aus downloads bitte alle in nen extra ordner",
        "Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen"],
    9: ["Wo ist die APK, die ich zuletzt heruntergeladen habe?", "wo is die letzte a p k die ich geladen hab",
        "Welche APK hab ich als letztes runtergeladen?"],
    10: ["Zeig mir alle Screenshots vom letzten Monat.", "screen shots vom vergangenen monat zeigen",
         "Bildschirmfotos vom letzten Monat anzeigen"],
    11: ["Welche Dateien habe ich seit einer Woche nicht mehr geöffnet?", "was hab ich die letzte woche nicht mehr aufgemacht",
         "Zeig mir Dateien, die ich länger als eine Woche nicht geöffnet hab"],
    12: ["Mach mir einen Ordner für meine Arbeitsdokumente und sortier die passenden Dateien rein.",
         "leg nen ordner für die arbeitssachen an und pack die rein",
         "Erstell einen Ordner Arbeit und verschieb meine Arbeitsdateien dahin"],
    13: ["Such das Foto, das ich vor ein paar Tagen gemacht habe.", "das bild von vor paar tagen aus der kamera such mal",
         "Wo ist das Foto, das ich neulich gemacht hab?"],
    14: ["Was kann ich löschen, damit ich wieder mehr Speicherplatz habe?", "ich hab kein platz mehr was kann weg",
         "Speicher ist voll, was kann ich löschen?"],
    15: ["Zeig mir nur die Bilder, die ich über WhatsApp bekommen habe.", "nur whatsapp bilder bitte",
         "Zeig die Bilder von WhatsApp"],
    16: ["Öffne wieder den Ordner, in dem wir vorhin waren.", "geh zurück in den ordner von vorhin",
         "Bring mich zurück zu dem Ordner von eben"],
    17: ["Such die anderen Dateien, die zu dieser Rechnung gehören.", "zeig mir den rest von den unterlagen zu der rechnung",
         "Was gehört noch zu der Rechnung?"],
    18: ["Mach das mit den restlichen Bildern genauso wie gerade eben.", "die übrigen bilder genau so",
         "Mit den anderen Bildern bitte dasselbe"],
    19: ["Ich hab gestern eine Datei gesucht, kannst du mir die nochmal zeigen?", "die datei die ich gestern gesucht hab nochmal bitte",
         "Die Datei von meiner Suche gestern, zeig die nochmal"],
    20: ["Sortier meine Dokumente so, wie ich es normalerweise mache.", "dokumente sortieren wie sonst auch",
         "Ordne meine Dokumente wie immer"],
}

FOLLOW = [   # (set, caps, session, text, gold)
    ("main", ["show", "session"], [T("Zeig mir alle Bilder der letzten Woche", P("show", kinds=["image"], newer_than=1, age_unit="weeks"), 2)],
     "und jetzt nur die von WhatsApp",
     G("show", {"plan": P("show", "WhatsApp Images", kinds=["image"], newer_than=1, age_unit="weeks")})),
    ("main", ["show", "session"], [T("Zeig mir die größten Videos", P("show", kinds=["video"], sort="size", limit=10), 2)],
     "die zweite davon", G("show", {"paths": "result:1:2"})),
    ("main", ["show", "session"], [T("Such den Mietvertrag", P("show", name_contains="Mietvertrag"), 8),
                                   T("Zeig mir die Screenshots vom letzten Monat", P("show", "Screenshots", newer_than=1, age_unit="months"), 4)],
     "Vorhin hab ich eine Datei gesucht, zeig mir die nochmal", G("show", {"paths": ["Documents/Mietvertrag.pdf"]})),
    ("holdout", ["show", "session"], [T("Zeig mir die Videos größer als 500 MB", P("show", kinds=["video"], larger_than=500), 3)],
     "nur die aus der Kamera", G("show", {"plan": P("show", "Kamera", kinds=["video"], larger_than=500)})),
    ("holdout", ["show", "session"], [T("Such alle PDFs mit Versicherung", P("show", extensions=["pdf"], content_contains="Versicherung"), 2)],
     "zeig mir die erste davon", G("show", {"paths": "result:1:1"})),
    ("holdout", ["session"], [T("Zeig mir die Bilder von gestern aus Download", P("show", "Download", kinds=["image"], newer_than=1), 2)],
     "schieb die in einen neuen Ordner Einrichtung",
     G("move", {"plan": P("move", "Download", kinds=["image"], newer_than=1)}, dest_has="einrichtung")),
]
# Corrections: the first turn is a wrong reading (scripted); "wrong" must not come back later.
TEL_NEW, TEL_OLD = "Download/Rechnung_Telekom_2026-11.pdf", "Download/Rechnung_Telekom_2026-10.pdf"
AMZ = ["Download/Rechnung_Amazon_302-5820.pdf", "Download/Rechnung_Amazon_302-5820 (1).pdf"]
K_TEL = T("Zeig mir die Telekom-Rechnung", P("show", name_contains="Telekom", sort="newest", limit=1), 3)
K_APK = T("Lösch die alten APKs aus Download", P("delete", "Download", extensions=["apk"], older_than=6, age_unit="months"), 2)
K_AMZ = T("Zeig mir die Amazon-Rechnung", P("show", name_contains="Rechnung_Amazon_302-5820"), 3)
CORR = [
    ("main", [K_TEL], "Nein, nicht die, die ältere",
     G("show", {"first": TEL_OLD}, {"paths": "name:Telekom-" + TEL_NEW}, wrong={"paths": [TEL_NEW]})),
    ("main", [K_TEL, T("Nein, nicht die, die ältere", P("show", "Download", name_contains="Telekom_2026-10"), 1)],
     "verschieb die nach Documents/Rechnungen",
     G("move", {"paths": [TEL_OLD]}, dest=["Documents/Rechnungen"], wrong={"paths": [TEL_NEW]})),
    ("main", [K_APK], "doch nicht löschen, nur zeigen",
     G("show", {"plan": P("show", "Download", extensions=["apk"], older_than=6, age_unit="months")},
       {"plan": P("show", "Download", extensions=["apk"])}, wrong={"action": "delete"})),
    ("main", [K_APK, T("doch nicht löschen, nur zeigen", P("show", "Download", extensions=["apk"], older_than=6, age_unit="months"), 1)],
     "und jetzt nur die über 20 MB",
     G("show", {"plan": P("show", "Download", extensions=["apk"], older_than=6, age_unit="months", larger_than=20)},
       {"plan": P("show", "Download", extensions=["apk"], larger_than=20)}, wrong={"action": "delete"})),
    ("holdout", [K_AMZ], "Nein, ich meinte die andere Rechnung",
     G("show", {"paths": ["Download/Rechnung_Amazon_302-4471.pdf"]}, {"first": "Download/Rechnung_Amazon_302-4471.pdf"},
       wrong={"paths": AMZ})),
    ("holdout", [K_AMZ, T("Nein, ich meinte die andere Rechnung", P("show", "Download", name_contains="302-4471"), 1)],
     "verschieb sie nach Documents/Rechnungen",
     G("move", {"paths": ["Download/Rechnung_Amazon_302-4471.pdf"]}, dest=["Documents/Rechnungen"], wrong={"paths": AMZ})),
    ("holdout", [T("Zeig mir die Videos aus Download", P("show", "Download", kinds=["video"]), 2)], "nein, die aus der Kamera",
     G("show", {"plan": P("show", "Kamera", kinds=["video"])}, wrong={"paths": "plan:Download:video"})),
]
# Stale memory: the memory was built before `changes`; gold is about the phone as it is now.
REN = {"op": "rename", "from": "Download/Scan_20260305_1000.pdf", "to": "Download/Mietvertrag_Garage.pdf"}
NEWBILLS = [f"Documents/Finanzen/Rechnungen/Rechnung_{n}.pdf" for n in ("Telekom_2026-09", "Stadtwerke_2026-Q2", "Amazon_302-3990")]
HABIT = {"op": "add", "paths": NEWBILLS, "moves": [{"from": "Download", "to": "Documents/Finanzen/Rechnungen", "name": p.rsplit("/", 1)[1]}
                                                   for p in NEWBILLS]}
RECHNUNG_ANY = [{"plan": P("move", "Download", name_contains="Rechnung")}, {"plan": P("move", "Download", content_contains="Rechnung")}]
STALE = [   # (kind of staleness, changes, session, texts, gold, what counts as using the stale fact)
    ("moved", [{"op": "move_tag", "tag": "autokauf", "to": "Auto"}], [],
     ["Zeig mir die Unterlagen vom Autokauf", "Wo sind die Papiere vom Autokauf?"],
     G("show", {"paths": "tag:autokauf"}), {"paths_missing": True, "folder": "Download"}),
    ("folder_gone", [{"op": "rename_folder", "from": "Documents/Wohnung", "to": "Documents/Miete"}], [],
     ["Leg die Vonovia-Briefe aus Download zu den anderen Vonovia-Schreiben",
      "Pack die Vonovia-Sachen aus Download dahin, wo die anderen Vonovia-Briefe liegen"],
     G("move", {"plan": P("move", "Download", name_contains="Vonovia")}, dest=["Documents/Miete"]), {"folder": "Documents/Wohnung"}),
    ("renamed", [REN], [T("Zeig mir die Scans vom März", P("show", "Download", name_contains="Scan_202603"), 10)],
     ["Zeig mir die erste davon nochmal", "die erste nochmal bitte"],
     G("show", {"paths": ["Download/Mietvertrag_Garage.pdf"]}), {"paths_missing": True}),
    ("yesterday", [], [T("Zeig mir den Ordner Bank", P("show", "Documents/Bank"), 14 * 60 + 20)],
     ["Öffne wieder den Ordner, in dem wir vorhin waren.", "Geh zurück in den Ordner von vorhin"],
     G("show", none_ok=True), {"folder": "Documents/Bank"}),
    ("replaced", [{"op": "replace", "result": [1, 1]}], [T("Zeig mir die Bahntickets aus Download", P("show", "Download", name_contains="Ticket_DB"), 15)],
     ["Lösch die erste davon", "Die erste davon kann weg, lösch sie"],
     G("delete", none_ok=True), {"replaced": True}),
    ("habit_changed", [HABIT], [],
     ["Verschieb die neuen Rechnungen wie immer", "Rechnungen aus Download ablegen wie immer"],
     G("move", *RECHNUNG_ANY, dest=["Documents/Finanzen/Rechnungen"]), {"folder": "Documents/Rechnungen", "dest_only": True}),
]


def cases():
    out = []
    for n, (caps, gold, session) in GOLD.items():
        for i, text in enumerate(TEXT[n]):
            kind = ["original", "paraphrase", "paraphrase"][i]
            base = {"cmd": n, "kind": kind, "caps": caps, "text": text, "session": session or [], "gold": gold}
            out.append({**base, "set": "holdout" if i == 2 else "main"})
            if session:   # follow-ups also after an app restart
                out.append({**base, "set": "holdout" if i == 2 else "main", "restart": True, "caps": caps + ["restart"]})
    for s, caps, session, text, gold in FOLLOW:
        out.append({"cmd": None, "kind": "followup", "caps": caps, "text": text, "session": session, "gold": gold, "set": s})
        if text.startswith("Vorhin"):   # the in-session twin of command 19
            out.append({"cmd": None, "kind": "followup", "caps": caps + ["restart"], "text": text, "session": session,
                        "gold": gold, "set": s, "restart": True})
    for s, session, text, gold in CORR:
        out.append({"cmd": None, "kind": "correction", "caps": ["session", "correction"], "text": text, "session": session,
                    "gold": gold, "set": s})
    for kind, changes, session, texts, gold, stale in STALE:
        for text in texts:
            out.append({"cmd": None, "kind": "stale", "stale_kind": kind, "caps": ["stale"], "text": text, "session": session,
                        "changes": changes, "gold": gold, "stale": stale, "set": "stale"})
    for i, c in enumerate(out):
        c["id"] = f"{c['set'][0]}{i:03d}"
    return out


if __name__ == "__main__":
    cs = cases()
    (HERE / "p0_cases.jsonl").write_text("".join(json.dumps(c, ensure_ascii=False) + "\n" for c in cs), encoding="utf-8")
    from collections import Counter
    print(len(cs), "cases", dict(Counter(c["set"] for c in cs)), dict(Counter(c["kind"] for c in cs)))
