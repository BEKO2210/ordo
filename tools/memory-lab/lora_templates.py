#!/usr/bin/env python3
"""Run 4: programmatic wording (own templates, no model) for the weak classes: content questions, changes of a named
subset (one or two topic words), colloquial times. Same facts as the LLM wording, validated by lora_build.py like it.

    LORA_DIR=lora2 lora_templates.py   -> appends to $LORA_DIR/words.jsonl with backend "template"
"""
import json
import os
import pathlib
import random

from lora_words import TIME_SLANG

OUT = pathlib.Path(__file__).parent / os.environ.get("LORA_DIR", "lora")
VERB = {"delete": ["lösch", "lösche bitte", "entfern mal", "kannst du löschen:", "schmeiß raus:"],
        "move": ["verschieb", "verschiebe bitte", "pack mal", "tu"], "copy": ["kopier", "kopiere bitte", "mach eine Kopie von"],
        "show": ["zeig mir", "such", "wo sind", "finde mal", "ich such"]}
SORT = {"size": "größten", "newest": "neuesten", "oldest": "ältesten", "smallest": "kleinsten"}


def sentence(s, r):
    act = s["action"]
    if s.get("content"):
        start = r.choice([f"in welcher Datei steht {s['content']}", f"wo hab ich was mit {s['content']} drin",
                          f"welches Dokument enthält {s['content']}", f"such die Datei, in der {s['content']} steht"])
        parts = [start]
    else:
        what = s.get("type_say", "").split("/")[0].split(" (")[0] or r.choice(["Dateien", "Sachen"])
        if s.get("sort"):
            what = (f"{s['limit']} " if s.get("limit", 0) > 1 else "") + f"{SORT[s['sort']]} " + what
        names = s.get("name", "").split()
        named = ""
        if names:
            named = r.choice(["mit {} im Namen", "von {}", "zu {}"]).format(" und ".join(names))
        parts = [r.choice(VERB[act]), "die", what, named]
    if s.get("source_say"):
        parts.append(r.choice(["aus", "im Ordner", "in"]) + " " + s["source_say"])
    t = s.get("time")
    if t:
        parts.append(r.choice(TIME_SLANG[t]) if t in TIME_SLANG and r.random() < .5 else t)
    if s.get("size"):
        parts.append(s["size"])
    if s.get("exclude_say"):
        parts.append(r.choice(["außer", "aber nicht die", "ohne die"]) + " " + s["exclude_say"])
    if s.get("dest_say"):
        parts.append(r.choice(["nach", "in den Ordner", "rüber nach"]) + " " + s["dest_say"])
    text = " ".join(p for p in parts if p)
    return text[0].upper() + text[1:] if r.random() < .5 else text


FILES = ["Anschreiben.pdf", "Einkaufsliste.txt", "Rechnung_Telekom.pdf", "Urlaub.mp4", "Vertrag.docx", "IMG_2041.jpg", "Bewerbung.pdf",
         "Steuer2025.xlsx", "Hochzeit.mov", "Mietvertrag.pdf"]
WHO = ["Mama", "meinem Chef", "Lena", "der Versicherung", "meinem Bruder", "Ahmet"]
REFUSE = ["benenn {f} in {g} um", "nenn die Datei {f} um in {g}", "öffne {f}", "mach mal {f} auf", "spiel {f} ab",
          "schick {f} an {w}", "teil {f} mit {w}", "pack {f} in eine Zip-Datei", "entpack die Zip im Download", "druck {f} aus",
          "lad {f} in die Cloud hoch", "mach aus {f} ein PDF", "wandel {f} in Word um", "setz {f} als Hintergrundbild",
          "schütz {f} mit einem Passwort", "füg die PDFs zu einer Datei zusammen", "stell die gelöschten Fotos wieder her",
          "übersetz {f} ins Englische", "schreib in {f} meine neue Adresse rein", "markier {f} als Favorit",
          "rename {f} to {g}", "open {f}", "send {f} to my boss", "zip the photos from yesterday"]
ASK = ["lösch das", "mach das weg", "verschieb die da", "pack das woanders hin", "weg damit", "lösch die Sachen",
       "kannst du die alten Sachen löschen", "verschieb die Dateien", "kopier das mal", "schieb die rüber",
       "die großen Dinger bitte weg", "mach mal Platz, lösch was", "lösch alles Unnötige", "tu die Teile in einen anderen Ordner",
       "delete that", "move those", "get rid of the old stuff"]


def negatives(r):
    """Refusals (things Ordo cannot do) and asks (change without object/target), German and a little English."""
    words = {}
    for i in range(240):
        f = r.choice(FILES)
        g = r.choice(FILES).split(".")[0] + "." + f.split(".")[1]
        words[f"refuse_t{i:04d}"] = [r.choice(REFUSE).format(f=f if r.random() < .6 else f.split(".")[0], g=g, w=r.choice(WHO))]
    for i in range(120):
        words[f"ask_t{i:04d}"] = [r.choice(ASK) + r.choice(["", "", " bitte", "!", " mal eben"])]
    return words


def main():
    r = random.Random(77)
    words = {}
    for f in sorted(OUT.glob("specs_*.jsonl")):
        for row in map(json.loads, open(f, encoding="utf-8")):
            s = row["spec"]
            weak = s.get("content") or (s["action"] in ("delete", "move", "copy") and s.get("name")) or s.get("time") in TIME_SLANG
            if row["lang"] == "de" and s["action"] != "organize" and weak:
                words[row["id"]] = [sentence(s, r)]   # one per spec: the LLM wording stays the majority
    if os.environ.get("LORA_STRICT"):   # run 5
        words.update(negatives(r))
    with open(OUT / "words.jsonl", "a", encoding="utf-8") as fh:
        fh.write(json.dumps({"job": "template", "backend": "template", "words": words}, ensure_ascii=False) + "\n")
    print(len(words), "specs,", sum(map(len, words.values())), "sentences")


if __name__ == "__main__":
    main()
