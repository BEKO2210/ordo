#!/usr/bin/env python3
"""User wording for the training plans, written by several stronger LLMs with different personas.

    lora_words.py jobs           -> lora/jobs.jsonl  (batches: plan specs + free classes chat/refuse/ask + question pairs)
    lora_words.py run BACKEND N  -> lora/words.jsonl (appends; resumes; BACKEND = omni:<model> | codex | cursor:<model>)
Each LLM sees only facts (lora_data.describe), never a test sentence and never the target JSON.
"""
import json
import os
import pathlib
import random
import re
import subprocess
import sys
import threading
import time
import urllib.request

HERE = pathlib.Path(__file__).parent
OUT = HERE / os.environ.get("LORA_DIR", "lora")   # run 4: LORA_DIR=lora2 (clean data, local models only)
PERSONAS_DE = [
    "eine ältere Person, die per Spracheingabe diktiert: keine Satzzeichen, Zahlen oft als Wörter, Füllwörter (äh, also, mal), "
    "manchmal falsch erkannte oder getrennte Wörter (down load, whats app, p d f)",
    "ein Teenager, der schnell tippt: alles klein, Tippfehler, Abkürzungen (pls, grad, nd, wo sin), Umgangssprache",
    "eine berufstätige Person, knapp und sachlich, manchmal nur Stichworte ohne Verb-Endung",
    "eine höfliche Person in ganzen Sätzen, oft indirekt (Könntest du ..., Ich bräuchte mal ..., Wo sind eigentlich ...)",
    "eine Person mit Deutsch als Zweitsprache: einfache Grammatik, falsche Artikel, manchmal englische Wörter dazwischen",
    "eine Person aus Süddeutschland, umgangssprachlich mit leichtem Dialekt (z. B. 'Bildle', 'mal gscheit', 'weg tun'), sparsam",
    "eine gestresste Person, ungeduldig, Ausrufe, Wiederholungen, unvollständige Sätze",
    "eine Person, die ihr Problem beschreibt statt einen Befehl zu geben (z. B. 'ich find meine ... nicht mehr')",
]
PERSONAS_EN = [
    "a casual English speaker typing fast: lowercase, small typos, no punctuation",
    "a polite English speaker using full indirect sentences (could you..., I need to find...)",
    "a non-native English speaker (German), simple grammar, sometimes a German word mixed in",
    "someone dictating in English by voice: no punctuation, numbers as words, filler words",
]
FREE = {
    "refuse": [
        "Dateien in eine Zip-Datei packen oder komprimieren", "eine Zip-/Rar-Datei entpacken", "Dateien oder Ordner umbenennen",
        "eine Datei per WhatsApp, Mail oder Bluetooth an jemanden schicken oder teilen", "Dateien in die Cloud (Google Drive, Dropbox) hochladen",
        "eine Datei umwandeln (z. B. Word in PDF, Bilder in ein PDF, Video in MP3)", "mehrere PDFs oder Dateien zu einer zusammenfügen",
        "eine Datei drucken", "ein Foto bearbeiten (zuschneiden, drehen, Filter, aufhellen)", "gelöschte Dateien wiederherstellen",
        "eine App deinstallieren oder den Cache leeren", "eine Datei mit Passwort schützen oder verschlüsseln",
        "ein Video kürzen, schneiden oder verkleinern", "ein Bild als Hintergrundbild oder ein Lied als Klingelton einstellen",
        "einen Text in einer Datei ändern oder etwas in ein Dokument schreiben", "ein Dokument übersetzen",
        "das Handy mit dem PC synchronisieren", "einen QR-Code oder ein Dokument scannen",
    ],
    "chat": [
        "eine Begrüßung oder Smalltalk", "sich bedanken oder loben", "nach dem Wetter fragen", "nach der Uhrzeit oder dem Datum fragen",
        "einen Witz hören wollen", "fragen, wer oder was der Assistent ist und was er kann", "eine Allgemeinwissensfrage",
        "eine Rechenaufgabe", "eine Frage zum Akku, WLAN oder zur Bildschirmhelligkeit", "nach einem Kochrezept fragen",
        "sich über etwas beschweren, ohne eine Aufgabe zu nennen", "eine Frage zu Nachrichten, Sport oder Politik",
    ],
    "ask": [
        "etwas löschen wollen, aber weder sagen was noch wo (z. B. nur 'das da' oder 'die Sachen')",
        "Dateien verschieben oder kopieren wollen, aber kein Ziel nennen", "aufräumen wollen, ohne zu sagen welchen Ordner",
        "'die alten' Dateien löschen wollen, ohne zu sagen ab wann alt und ohne Ordner",
        "'die großen' Dateien löschen wollen, ohne Größe und ohne Ordner", "eine Datei verschieben wollen, ohne zu sagen welche",
        "'unnötige' oder 'überflüssige' Dateien löschen wollen, ohne zu sagen welche",
        "etwas mit 'den Dingern' oder 'dem Zeug' machen wollen, völlig unklar was",
    ],
}


FREE2 = {
    "storq": ["fragen, was man löschen könnte, weil der Speicher voll ist", "fragen, welche Dateien am meisten Platz wegnehmen",
              "Platz auf dem Handy schaffen wollen und wissen, was weg kann"],
    "storfree": ["fragen, wie viel Speicher noch frei ist", "fragen, ob ein fast voller Speicher schlimm ist"],
    "dups": ["doppelte Dateien auf dem Handy finden wollen", "fragen, ob es Dateien doppelt gibt"],
    "dupsimg": ["doppelte Fotos finden wollen"],
    "refuse2": ["eine Datei öffnen oder ein Video/Lied abspielen", "ein Foto mit einer bestimmten Person teilen (z. B. mit Mama)",
                "eine Datei als Favorit markieren oder anpinnen", "den Inhalt einer Datei vorlesen lassen"],
}


def jobs2(seed=12):
    """Run 2: organize-only specs (contrast to 'ask') + the classes above. Job numbers start at 1000."""
    r = random.Random(seed)
    items = [{"id": row["id"], "lang": row["lang"], "desc": row["desc"], "k": 3}
             for row in (json.loads(l) for l in open(OUT / "specs_404.jsonl", encoding="utf-8"))]
    for cls, topics in FREE2.items():
        for i in range(len(topics) * (14 if cls != "refuse2" else 8)):
            items.append({"id": f"{cls}_{i:04d}", "lang": "en" if r.random() < .15 else "de", "k": 3,
                          "desc": f"Nutzer will: {topics[i % len(topics)]}"})
    r.shuffle(items)
    with open(OUT / "jobs.jsonl", "a", encoding="utf-8") as fh:
        for i, b in enumerate(items[j:j + 14] for j in range(0, len(items), 14)):
            fh.write(json.dumps({"job": 1000 + i, "items": b, "persona_de": PERSONAS_DE[i % len(PERSONAS_DE)],
                                 "persona_en": PERSONAS_EN[i % len(PERSONAS_EN)]}, ensure_ascii=False) + "\n")
    print(len(items), "items")


def jobs3():
    """Run 3: content questions + two topic words. Job numbers start at 2000."""
    items = [{"id": row["id"], "lang": row["lang"], "desc": row["desc"], "k": 3}
             for f in ("specs_505.jsonl", "specs_606.jsonl") for row in (json.loads(l) for l in open(OUT / f, encoding="utf-8"))]
    random.Random(13).shuffle(items)
    with open(OUT / "jobs.jsonl", "a", encoding="utf-8") as fh:
        for i, b in enumerate(items[j:j + 14] for j in range(0, len(items), 14)):
            fh.write(json.dumps({"job": 2000 + i, "items": b, "persona_de": PERSONAS_DE[(i + 3) % len(PERSONAS_DE)],
                                 "persona_en": PERSONAS_EN[i % len(PERSONAS_EN)]}, ensure_ascii=False) + "\n")
    print(len(items), "items")


def jobs(seed=11):
    r = random.Random(seed)
    rows = []
    for f in sorted(OUT.glob("specs_*.jsonl")):
        rows += [json.loads(l) for l in open(f, encoding="utf-8")]
    items = []
    for row in rows:
        items.append({"id": row["id"], "lang": row["lang"], "desc": row["desc"], "k": 3})
        # near miss: the same facts as a question whether one may change them -> stays a show
        if row["spec"]["action"] in ("delete", "move") and r.random() < .35:
            items.append({"id": row["id"] + "q", "lang": row["lang"], "k": 2,
                          "desc": row["desc"] + " – ABER nur als FRAGE formuliert, ob man das tun kann/sollte "
                                  "(z. B. 'Kann ich ... löschen?', 'Sollte ich ...?'), keine Aufforderung"})
    for cls, topics in FREE.items():
        for i in range(len(topics) * 9):
            items.append({"id": f"{cls}_{i:04d}", "lang": "en" if r.random() < .15 else "de", "k": 3,
                          "desc": f"Nutzer will: {topics[i % len(topics)]}" if cls != "chat" else f"Nutzer sagt: {topics[i % len(topics)]}"})
    r.shuffle(items)
    batches = [items[i:i + 14] for i in range(0, len(items), 14)]
    with open(OUT / "jobs.jsonl", "w", encoding="utf-8") as fh:
        for i, b in enumerate(batches):
            fh.write(json.dumps({"job": i, "items": b, "persona_de": PERSONAS_DE[i % len(PERSONAS_DE)],
                                 "persona_en": PERSONAS_EN[i % len(PERSONAS_EN)]}, ensure_ascii=False) + "\n")
    print(len(items), "items in", len(batches), "jobs")


PERSONAS_DE4 = PERSONAS_DE + [
    "eine Rentnerin über 80, sehr höflich und umständlich, erklärt erst, warum sie etwas will",
    "ein zehnjähriges Kind, kurze einfache Sätze, Rechtschreibfehler",
    "ein Handwerker auf der Baustelle, diktiert schnell zwischen zwei Aufträgen, ohne Satzzeichen",
    "eine Person aus Bayern, umgangssprachlich ('dua', 'de Buidl', 'gscheid'), aber verständlich",
    "eine Person aus Berlin, schnoddrig ('ick', 'wat', 'mach ma')",
    "eine Person aus Norddeutschland, sehr knapp ('moin', 'mal eben', 'tüdelig')",
    "eine Person aus Köln oder dem Ruhrgebiet, locker ('dat', 'wat', 'ma eben')",
    "eine Person mit Türkisch als Muttersprache, die Deutsch und ein paar türkische Füllwörter mischt ('yani', 'abi')",
    "eine Person mit Russisch als Muttersprache: Artikel fehlen oft, Wortstellung ungewohnt",
    "eine Studentin, die Denglisch spricht (files, checken, downloaden, Folder)",
    "eine müde Pflegekraft nach der Nachtschicht, Tippfehler, sehr kurz",
    "ein Vater mit Baby auf dem Arm, tippt einhändig: klein, Wörter fehlen, Tippfehler",
    "eine technisch unsichere Person, die Wörter verwechselt (Ordner/Datei/App/Galerie) und nachfragt",
    "eine Person, die per Sprachassistent diktiert: Satzzeichen ausgesprochen ('komma', 'punkt', 'fragezeichen')",
]
PERSONAS_EN4 = PERSONAS_EN + [
    "a British English speaker, polite and a bit formal (could you possibly, cheers)",
    "an American teenager texting: lowercase, slang (lol, rn, pls, gonna)",
    "an Indian English speaker, polite, kindly do the needful style",
    "a busy parent typing one-handed: very short, typos, no punctuation",
]
TIME_SLANG = {"im Januar": ["Anfang des Jahres", "gleich nach Neujahr", "im Jänner"],
              "Weihnachten": ["über die Feiertage", "an Heiligabend", "über Weihnachten", "an den Weihnachtstagen"],
              "Silvester": ["an Silvester", "zum Jahreswechsel", "in der Silvesternacht"],
              "Ostern": ["über Ostern", "an den Osterfeiertagen", "am Osterwochenende"],
              "im Sommer": ["in den Sommerferien", "im Sommerurlaub", "als es so heiß war"],
              "im Dezember": ["Ende letzten Jahres", "kurz vor Weihnachten"], "neulich": ["vor kurzem", "letztens", "die Tage"],
              "gestern": ["gestern Abend", "gestern früh"], "heute": ["heute morgen", "vorhin"],
              "letzte Woche": ["vorige Woche", "letzte Woche irgendwann"], "letzten Monat": ["vorigen Monat", "im letzten Monat"]}
FREE4 = {
    "ask": FREE["ask"] + [
        "etwas 'wegmachen' oder 'aufräumen' wollen, aber unklar ob löschen oder verschieben, und ohne zu sagen was",
        "Dateien 'woanders hin' tun wollen, ohne Ziel und ohne zu sagen welche",
        "'die von gestern' verschieben wollen, ohne zu sagen welche Art von Dateien und wohin",
        "eine Sache mit 'dem Ding von neulich' machen wollen, ohne zu sagen was",
        "'alles' löschen wollen, ohne Ordner oder Art zu nennen",
        "Fotos 'sortieren' wollen, aber nicht sagen ob nach Datum, nach Ort oder in welchen Ordner",
    ],
    "refuse": FREE["refuse"] + [
        "eine Datei als Anhang an eine Mail hängen", "einen Ordner mit einem Passwort sperren oder verstecken",
        "Fotos automatisch in die Cloud sichern lassen", "ein Video in eine andere Auflösung umwandeln",
        "eine Datei an den Fernseher oder Drucker senden", "eine Erinnerung oder einen Termin anlegen",
        "Kontakte oder SMS sichern", "eine Datei auf einen USB-Stick oder den PC übertragen",
        "Gesichter auf Fotos erkennen lassen ('alle Fotos auf denen Oma ist', ohne dass Oma im Dateinamen steht)",
        "den Text auf einem Foto oder Screenshot lesen lassen", "eine App-Berechtigung ändern",
    ],
    "chat": FREE["chat"] + ["fragen, ob der Assistent Gefühle hat", "eine Geschichte erzählt bekommen wollen",
                            "einen Tipp für ein Geschenk wollen", "fragen, wie man ein Foto schöner macht (Rat, keine Aktion)"],
}


def jobs4(seed=41):
    """Run 4 (clean data): all plan specs in LORA_DIR + free classes; hints for colloquial time and content questions.
    Batches of 8 (small local models lose items in long JSON)."""
    r = random.Random(seed)
    items = []
    for f in sorted(OUT.glob("specs_*.jsonl")):
        for row in (json.loads(l) for l in open(f, encoding="utf-8")):
            d = row["desc"]
            t = row["spec"].get("time")
            if t in TIME_SLANG and r.random() < .5:
                d += f" – sag die Zeit umgangssprachlich, z. B. '{r.choice(TIME_SLANG[t])}'"
            if row["spec"].get("content") and r.random() < .6:
                d += " – formuliere als Frage, in welcher Datei das steht (z. B. 'in welcher Datei steht ...', 'wo hab ich ... drin')"
            if row["spec"].get("name") and not row["spec"].get("source") and r.random() < .5:
                d += " – nenne KEINEN Ordner, nur das Thema"
            items.append({"id": row["id"], "lang": row["lang"], "desc": d, "k": 3})
            if row["spec"]["action"] in ("delete", "move") and r.random() < .3:
                items.append({"id": row["id"] + "q", "lang": row["lang"], "k": 2,
                              "desc": row["desc"] + " – ABER nur als FRAGE formuliert, ob man das tun kann/sollte "
                                      "(z. B. 'Kann ich ... löschen?', 'Sollte ich ...?'), keine Aufforderung"})
    for cls, topics in list(FREE4.items()) + list(FREE2.items()):
        n = {"refuse": 10, "chat": 6, "ask": 12}.get(cls, 10 if cls != "refuse2" else 8)
        for i in range(len(topics) * n // 2):
            items.append({"id": f"{cls}_{i:04d}", "lang": "en" if r.random() < .15 else "de", "k": 3,
                          "desc": f"Nutzer will: {topics[i % len(topics)]}" if cls != "chat" else f"Nutzer sagt: {topics[i % len(topics)]}"})
    r.shuffle(items)
    with open(OUT / "jobs.jsonl", "w", encoding="utf-8") as fh:
        for i, b in enumerate(items[j:j + 8] for j in range(0, len(items), 8)):
            fh.write(json.dumps({"job": i, "items": b, "persona_de": r.choice(PERSONAS_DE4),
                                 "persona_en": r.choice(PERSONAS_EN4)}, ensure_ascii=False) + "\n")
    print(len(items), "items")


def prompt(job):
    L = ["Du schreibst Testsätze für eine Dateimanager-App auf dem Handy. Nutzer sprechen oder tippen der App einen Befehl.",
         f"Schreibe für jeden Eintrag unten die verlangte Anzahl verschiedener Sätze, so wie {job['persona_de']} sie sagen würde.",
         f"Einträge mit Sprache EN schreibst du auf Englisch, so wie {job['persona_en']}.",
         "Regeln:",
         "- Jeder Satz enthält ALLE Fakten des Eintrags (Ordner, Ziel, Dateiart, Name, Zeit, Größe, Anzahl, Ausnahme) und KEINE zusätzlichen.",
         "- Formuliere frei und natürlich, nicht die Stichwörter abschreiben. Zeitangaben müssen genau dasselbe bedeuten.",
         "- Sag Namen so, wie Menschen reden ('die Telekom-Rechnungen', 'Fotos von Oma'), nicht 'mit X im Dateinamen'.",
         "- Ordnernamen und Namen dürfen klein geschrieben, abgekürzt oder mit einem kleinen Tippfehler sein.",
         "- Die Sätze eines Eintrags sollen sich deutlich unterscheiden (Satzbau, Wortwahl, Länge).",
         "- Kein Satz beginnt immer gleich. Keine Anführungszeichen um den Satz.",
         'Antworte NUR mit JSON: {"<id>": ["Satz 1", "Satz 2", ...], ...}', "", "Einträge:"]
    for it in job["items"]:
        L.append(f"{it['id']} | {'EN' if it['lang'] == 'en' else 'DE'} | {it['k']} Sätze | {it['desc']}")
    return "\n".join(L)


def call(backend, text):
    if backend.startswith("omni:"):
        body = {"model": backend[5:], "stream": False, "temperature": 1.0, "max_tokens": 6000,
                "messages": [{"role": "user", "content": text}]}
        req = urllib.request.Request("http://127.0.0.1:20128/v1/chat/completions", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=400) as r:
            return json.load(r)["choices"][0]["message"]["content"]
    if backend.startswith("local:"):   # run 4: a model on pop-os behind llama-server, e.g. local:8180:qwen3.5-9b
        body = {"model": "x", "stream": False, "temperature": random.choice([.7, .85, 1.0, 1.1]), "top_p": .95, "max_tokens": 3500,
                "chat_template_kwargs": {"enable_thinking": False}, "messages": [{"role": "user", "content": text}]}
        req = urllib.request.Request(f"http://127.0.0.1:{backend.split(':')[1]}/v1/chat/completions", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=600) as r:
            return json.load(r)["choices"][0]["message"]["content"]
    if backend == "codex":
        p = subprocess.run(["codex", "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only", "-"], input=text,
                           capture_output=True, text=True, timeout=900, cwd="/tmp")
        return p.stdout
    if backend.startswith("cursor:"):
        p = subprocess.run(["cursor-agent", "-p", "--trust", "--mode", "ask", "--model", backend[7:], "--output-format", "text", text],
                           capture_output=True, text=True, timeout=900, cwd="/tmp")
        return p.stdout
    raise ValueError(backend)


def parse(out):
    m = re.search(r"\{.*\}", out, re.S)
    if not m:
        return {}
    try:
        d = json.loads(m.group(0))
    except ValueError:
        return {}
    return {k: [s.strip() for s in v if isinstance(s, str) and s.strip()] for k, v in d.items() if isinstance(v, list)}


LOCK = threading.Lock()
REV = "--rev" in sys.argv or bool(os.environ.get("LORA_REV"))


def done_jobs():
    p = OUT / "words.jsonl"
    return {json.loads(l)["job"] for l in open(p, encoding="utf-8")} if p.exists() else set()


def run(backend, n, claim):
    all_jobs = [json.loads(l) for l in open(OUT / "jobs.jsonl", encoding="utf-8")]
    made = 0
    while made < n:
        with LOCK:
            todo = [j for j in all_jobs if j["job"] not in done_jobs() and j["job"] not in claim]
            if REV:   # a second process works from the end of the list
                todo.reverse()
            if not todo:
                return
            job = todo[0]
            claim.add(job["job"])
        t0 = time.time()
        try:
            d = parse(call(backend, prompt(job)))
        except Exception as e:   # free backends fail often: give the job back, wait, go on
            print(backend, job["job"], "ERR", str(e)[:120], flush=True)
            claim.discard(job["job"])
            time.sleep(30)
            continue
        ids = {it["id"] for it in job["items"]}
        got = {k: v for k, v in d.items() if k in ids}
        if len(got) < len(ids) * .6:
            print(backend, job["job"], "bad answer", len(got), flush=True)
            claim.discard(job["job"])
            time.sleep(20)
            continue
        with LOCK, open(OUT / "words.jsonl", "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"job": job["job"], "backend": backend, "words": got}, ensure_ascii=False) + "\n")
        made += 1
        print(backend, job["job"], len(got), f"{time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    if sys.argv[1] == "jobs":
        jobs()
    elif sys.argv[1] == "jobs2":
        jobs2()
    elif sys.argv[1] == "jobs4":
        jobs4()
    elif sys.argv[1] == "jobs3":
        jobs3()
    elif sys.argv[1] == "run":
        claim = set()
        threads = [threading.Thread(target=run, args=(b, int(sys.argv[2]), claim)) for b in sys.argv[3:] if b != "--rev"]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
