#!/usr/bin/env python3
"""Phase-0b cases.  p0b_cases.py -> p0b_holdout.jsonl (NEW holdout, gold written before any run) + p0b_regress.jsonl

holdout2b: 41 fresh single commands in Belkis' style (colloquial, dictation errors, some English). Never used to
tune anything. regress: the old lab's commands/holdout/holdout2 (today's actions), converted to Phase-0 gold.
Extra gold keys: "none_too" = no plan / a refusal is also right; "refuse" = only an honest "kann ich noch nicht" is
right (the command needs memory Ordo does not have). Pseudo field _today = since midnight (only code can set it).
"""
import json
import pathlib

from p0_cases import P, G

HERE = pathlib.Path(__file__).parent
REFUSE = {"action": "none", "any": [], "none_ok": True, "refuse": True}
NONE = {"action": "none", "any": [], "none_ok": True}

# (capabilities, text, gold)
HOLDOUT = [
    (["show", "sort"], "zeig mir die 5 größten videos", G("show", {"top": P("show", kinds=["video"], sort="size")})),
    (["show", "recency", "english"], "Where are my screenshots from last week?",
     G("show", {"plan": P("show", "Screenshots", newer_than=1, age_unit="weeks")})),
    (["show", "recency"], "welche pdfs hab ich diese woche runtergeladen",
     G("show", {"plan": P("show", "Download", extensions=["pdf"], newer_than=1, age_unit="weeks")})),
    (["show"], "zeig mal alle musik dateien größer als 30 mega byte", G("show", {"plan": P("show", kinds=["audio"], larger_than=30)})),
    (["show"], "wo sind meine whats app videos",
     G("show", {"plan": P("show", "WhatsApp Video", kinds=["video"])}, {"plan": P("show", "WhatsApp Video")})),
    (["show"], "such die datei mit kaufvertrag im namen", G("show", {"plan": P("show", name_contains="Kaufvertrag")})),
    (["show", "content", "english"], "find all files that mention Kessler", G("show", {"plan": P("show", content_contains="Kessler")})),
    (["show", "content"], "In welchen Dokumenten steht was von Nobilia?", G("show", {"plan": P("show", content_contains="Nobilia")})),
    (["show", "sort"], "zeig mir das neueste foto aus der kamera",
     G("show", {"top": P("show", "Kamera", kinds=["image"], sort="newest")}, singular=True)),
    (["show", "sort"], "was ist die größte datei im download ordner", G("show", {"top": P("show", "Download", sort="size")}, singular=True)),
    (["show", "recency"], "die bilder der letzten drei tage", G("show", {"plan": P("show", kinds=["image"], newer_than=3)})),
    (["show"], "zeig alle zip dateien die größer als ein giga sind",
     G("show", {"plan": P("show", extensions=["zip"], larger_than=1, size_unit="gb")})),
    (["show"], "liste die pdfs im ordner dokumente",
     G("show", {"plan": P("show", "Documents", extensions=["pdf"])}, {"plan": P("move", "Documents", extensions=["pdf"])})),
    (["show"], "wo ist die apk von signal", G("show", {"paths": ["Download/Signal-Android-7.24.apk"]})),
    (["show", "recency"], "zeig mir was ich heute runtergeladen hab", G("show", {"paths": ["Download/Signal-Android-7.24.apk"]})),
    (["show", "sort", "english"], "the newest pdf in downloads",
     G("show", {"top": P("show", "Download", extensions=["pdf"], sort="newest")}, singular=True)),
    (["today"], "verschieb alle pdfs aus download in den ordner dokumente",
     G("move", {"plan": P("move", "Download", extensions=["pdf"])}, dest=["Documents"])),
    (["today"], "kopier die bilder aus whatsapp images nach backup",
     G("copy", {"plan": P("copy", "WhatsApp Images", kinds=["image"])}, dest=["Backup"])),
    (["today"], "lösch im download alle apks die älter als ein jahr sind",
     G("delete", {"plan": P("delete", "Download", extensions=["apk"], older_than=1, age_unit="years")})),
    (["today"], "räum den screen shot ordner auf nach monaten", G("organize", {"plan": P("organize", "Screenshots")}, group_by=["month"])),
    (["today", "english"], "Move all mp3s from Download to Music", G("move", {"plan": P("move", "Download", extensions=["mp3"])}, dest=["Music"])),
    (["today"], "lösche die zip dateien im download die größer als 1 gb sind",
     G("delete", {"plan": P("delete", "Download", extensions=["zip"], larger_than=1, size_unit="gb")})),
    (["today"], "kopiere alle videos größer 2 gb aus movies nach backup",
     G("copy", {"plan": P("copy", "Movies", kinds=["video"], larger_than=2, size_unit="gb")}, dest=["Backup"])),
    (["today"], "sortier den download ordner nach dateityp", G("organize", {"plan": P("organize", "Download")}, group_by=["type"])),
    (["question"], "Wie viel Speicher hab ich noch frei?", {**G("show", {"top": P("show", sort="size")}), "none_too": True}),
    (["question"], "kann ich die alten whatsapp videos löschen?",
     {**G("show", {"plan": P("show", "WhatsApp Video", kinds=["video"])}, {"plan": P("show", "WhatsApp Video")}), "none_too": True}),
    (["question"], "Was ist in meinem Download Ordner?", G("show", {"plan": P("show", "Download")})),
    (["question"], "räum download auf aber lösch bloß nichts", G("organize", {"plan": P("organize", "Download")}, group_by=["type", "month"])),
    (["question"], "hey wie gehts dir", NONE),
    (["question"], "benenne die datei notizen.txt in ideen.txt um", NONE),
    (["question"], "schick die urlaubsfotos an mama", NONE),
    (["question"], "brauch ich die setup apks noch?",
     {**G("show", {"plan": P("show", "Download", extensions=["apk"], name_contains="setup")},
          {"plan": P("show", "Download", name_contains="setup")}), "none_too": True}),
    (["memory"], "welche dateien hab ich lange nicht mehr angeschaut", REFUSE),
    (["memory"], "mach das gleiche nochmal mit den videos", REFUSE),
    (["memory"], "sortier die fotos so wie letztes mal", REFUSE),
    (["show", "content"], "pdfs in denen hausrat steht", G("show", {"plan": P("show", extensions=["pdf"], content_contains="Hausrat")})),
    (["show", "content"], "ich such die rechnung von mediamarkt weiß aber nicht wie die datei heisst",
     G("show", {"plan": P("show", content_contains="MediaMarkt")})),
    (["show", "content"], "zeig mir dokumente über die krankenversicherung",
     G("show", {"plan": P("show", content_contains="Krankenversicherung")})),
    (["show", "sort"], "zeig mir die drei neuesten screenshots", G("show", {"top": P("show", "Screenshots", sort="newest")})),
    (["show", "sort"], "die 10 größten bilder auf dem handy", G("show", {"top": P("show", kinds=["image"], sort="size")})),
    (["show", "recency"], "fotos vom letzten monat aus der kamera",
     G("show", {"plan": P("show", "Kamera", kinds=["image"], newer_than=1, age_unit="months")},
       {"plan": P("show", "Kamera", kinds=["image"], _month="2026-10")})),
]


def regress():
    out = []
    for name in ("commands.jsonl", "holdout.jsonl", "holdout2.jsonl"):
        for i, line in enumerate(open(HERE / name, encoding="utf-8")):
            c = json.loads(line)
            g = c["gold"]
            if g["action"] == "none":
                gold = NONE
            else:
                if "tag" in g:
                    spec = {"paths": "tags:" + g["tag"] + "+" + g.get("tag2", g["tag"])}
                else:
                    spec = {"plan": P(g["action"], g["source"], extensions=g.get("extensions", []), kinds=g.get("kinds", []),
                                      name_contains=g.get("name_contains", ""), older_than=g.get("older_days", 0),
                                      larger_than=g.get("larger_mb", 0))}
                gold = G(g["action"], spec, **({"dest": g["dest"]} if "dest" in g else {}),
                         **({"group_by": [g["group_by"]]} if "group_by" in g else {}))
            out.append({"id": f"r{ {'commands.jsonl': 'c', 'holdout.jsonl': 'h', 'holdout2.jsonl': 'g'}[name] }{i:02d}", "set": "regress", "type": c["type"], "kind": "regress", "cmd": None,
                        "caps": ["today", c["type"]], "text": c["text"], "session": [], "gold": gold})
    return out


def cases():
    hold = [{"id": f"n{i:03d}", "set": "holdout2b", "kind": "single", "cmd": None, "caps": caps, "text": text, "session": [],
             "gold": gold} for i, (caps, text, gold) in enumerate(HOLDOUT)]
    return hold + regress()


if __name__ == "__main__":
    cs = cases()
    (HERE / "p0b_cases.jsonl").write_text("".join(json.dumps(c, ensure_ascii=False) + "\n" for c in cs), encoding="utf-8")
    print(len(cs), "cases:", sum(c["set"] == "holdout2b" for c in cs), "new holdout,", sum(c["set"] == "regress" for c in cs), "regression")
