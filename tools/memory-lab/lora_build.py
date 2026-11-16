#!/usr/bin/env python3
"""Validate the LLM wording, remove anything close to a test sentence, split, write the training files.

    lora_build.py   -> lora/train.jsonl, lora/val.jsonl, lora/build.md (counts, class mix, leak check, drop reasons)
Every kept example: the target plan is valid schema L (same check as the GBNF grammar), and read through rules3
without the safety net ("model alone") it selects exactly the gold files on its own synthetic phone; the wording
carries every fact (name, folders, time, size, count, exclusion, action) by mechanical checks.
"""
import difflib
import json
import os
import pathlib
import random
import re
from collections import Counter, defaultdict

import bench
import lora_data as D
import phase0 as p0
import rules2 as R
import rules3

HERE = pathlib.Path(__file__).parent
OUT = HERE / os.environ.get("LORA_DIR", "lora")   # run 4: LORA_DIR=lora2 (clean data, local models only)
NUMWORD = r"\b(\d+|ein|eine|einen|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|elf|zwölf|zwanzig|dreißig|fünfzig|hundert|" \
          r"one|two|three|four|five|six|seven|eight|nine|ten|twelve|twenty|thirty|fifty|hundred|halbe[ns]?|half)\b"
TIMEISH = r"(heute|gestern|vorgestern|woche|monat|jahr|tag|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|" \
          r"januar|februar|märz|april|mai|juni|juli|august|september|oktober|november|dezember|sommer|winter|frühling|herbst|" \
          r"weihnacht|ostern|silvester|neujahr|seit|vor |älter|jünger|neulich|kürzlich|letzt|today|yesterday|week|month|year|day|" \
          r"monday|tuesday|wednesday|thursday|friday|saturday|summer|winter|spring|autumn|fall|christmas|easter|since|older|newer|" \
          r"recent|ago|last|20\d\d|\d+\.\d+)"
EXCLW = r"(außer|ausser|ohne|nicht|kein|bis auf|lass|behalt|except|but not|excluding|without|not the|other than|apart from|leave|keep|no )"
SAY_ALIAS = {"download": ["download", "down load", "runtergelad", "heruntergelad"], "downloads": ["download", "down load"],
             "kamera": ["kamera", "camera", "galerie", "cam"], "screenshots": ["screenshot", "bildschirm", "screen shot"],
             "musik": ["musik", "music", "lieder", "songs"], "movies": ["movie", "film"], "filme": ["film", "movie"],
             "aufnahmen": ["aufnahme", "recording", "aufgenommen"], "dokumente": ["dokument", "document", "doku"],
             "documents": ["dokument", "document", "doku"], "bilder": ["bilder", "pictures", "bild"], "pictures": ["pictures", "bilder"]}


STRICT = bool(os.environ.get("LORA_STRICT"))   # run 5: type check + negative classes twice; off = run 2/4 builds unchanged
TYPEW = (r"(foto|fotto|bild|pic|photo|image|img|jpg|jpeg|png|heic|selfie|screenshot|video|vid|film|movie|clip|mp4|mov|musik|music|"
         r"audio|lied|song|mp3|m4a|opus|sprach|voice|aufnahm|recording|dokument|document|doku|docs?\b|pdf|word|docx|excel|xlsx|"
         r"tabelle|sheet|csv|präsentation|praesentation|powerpoint|pptx|folie|slides|e-?book|epub|buch|bücher|book|apk|app|zip|rar|"
         r"archiv|archive|txt|text|datei ?typ)")


# classes without a phone: the target is fixed (run 2 added storage questions, duplicates, refusal "open")
FIXED = {"chat": {"action": "none"}, "refuse": {"action": "none"}, "refuse2": {"action": "none"}, "ask": {"action": "ask"},
         "storq": {"action": "show", "sort": "size"}, "storfree": {"action": "none"}, "dups": {"action": "show"},
         "dupsimg": {"action": "show", "kinds": ["image"]}}


def fold(s):
    return p0.fold(re.sub(r"[^\wäöüß ]+", " ", s.lower()))


def fuzzy_in(word, text):
    """word (or a 1-2 edit variant / its first 5 letters) occurs in text; spaces and case ignored."""
    w, t = fold(word).replace(" ", ""), fold(text)
    if not w:
        return True
    if w in t.replace(" ", ""):
        return True
    toks = t.split()
    for tok in toks + [a + b for a, b in zip(toks, toks[1:])]:
        if len(w) >= 5 and (tok.startswith(w[:5]) or w.startswith(tok[:5]) and len(tok) >= 5):
            return True
        if len(w) >= 4 and difflib.SequenceMatcher(None, w, tok).ratio() >= .8:
            return True
    return False


def folder_said(say, text):
    if not say:
        return True
    low = fold(text)
    if say.lower().startswith("whatsapp"):
        return "whatsapp" in low.replace(" ", "") or "whats app" in low or "wa " in low
    alias = SAY_ALIAS.get(say.lower())
    if alias and any(a in low for a in alias):
        return True
    return all(fuzzy_in(x, text) for x in say.split() if len(x) >= 3)


def wording_ok(text, s, question=False):
    """-> '' if the sentence carries all facts of spec s, else the reason."""
    t = R.norm(text)
    if len(text) < 6 or len(text) > 260 or "\n" in text:
        return "length"
    if s.get("name") and not all(fuzzy_in(x, text) for x in s["name"].split()):
        return "name missing"
    if s.get("content") and not fuzzy_in(s["content"], text):
        return "content missing"
    if not folder_said(s.get("source_say"), text):
        return "source missing"
    if not question and not folder_said(s.get("dest_say"), text):
        return "dest missing"
    if s.get("time"):
        w = R.Work(t)
        got = R.parse_time(w, D.TODAY, D.NOW)
        if any(got) and got != D.time_range(s["time"]):
            return "time differs"
        if not any(got) and not re.search(TIMEISH, t):
            return "time missing"
    elif any(R.parse_time(R.Work(t), D.TODAY, D.NOW)) and not s.get("name"):
        return "time added"
    if STRICT and (s.get("kinds") or s.get("extensions")) and not re.search(TYPEW, t):
        return "type missing"   # run 5: a kind/extension in the target must be said (run 4 learnt "document" from nothing)
    if os.environ.get("LORA_STRICT") == "2" and s.get("size") and any(R.parse_size(R.Work(t))) and R.parse_size(R.Work(t)) != D.size_range(s["size"]):
        return "size differs"   # LORA_STRICT=2, prepared for a run 6: run 5 confused "größer als" and "unter"
    if s.get("size") and not (re.search(NUMWORD, t) and re.search(r"(mb|gb|kb|mega|giga|kilo|byte)", t)):
        return "size missing"
    if s.get("limit", 0) > 1 and not re.search(NUMWORD, t):
        return "count missing"
    if s.get("exclude") and not re.search(EXCLW, t):
        return "exclusion missing"
    said = R.actions_said(text)
    act = s["action"]
    if question or act == "show":
        if said & {"delete", "move", "copy"}:
            return "change said in a show"
    elif said & {"delete"} and act != "delete":
        return "delete said, other action meant"   # polite requests and "weg damit" stay: the net may downgrade them, the model must not
    return ""


def case_texts():
    """Every sentence of every existing case set: p0 (incl. the 20 originals), p0b (holdout + today's), blind 1, blind 2."""
    import blind2_cases
    import blind_cases
    import p0_cases
    import p0b_cases
    texts = set()
    for c in p0_cases.cases() + p0b_cases.cases() + blind_cases.cases():
        texts.add(c["text"])
        for t in c.get("turns", []) or []:
            texts.add(t["text"] if isinstance(t, dict) else t)
    for c in blind2_cases.cases():
        texts.add(c["text"])
    for f in ("commands.jsonl", "holdout.jsonl", "holdout2.jsonl"):
        for l in open(HERE / f, encoding="utf-8"):
            texts.add(json.loads(l)["text"])
    return sorted(texts)


def blind3_texts():
    """Blind 3 sentences, only for counting leaks: never printed or written anywhere (blind 3 must stay blind)."""
    try:
        return [json.loads(l)["text"] for l in open(HERE / "blind3_cases.jsonl", encoding="utf-8")]
    except Exception as e:   # no blind 3 module on this machine: say so instead of silently skipping
        raise SystemExit(f"blind3 leak check impossible: {type(e).__name__}")


UMLAUT = str.maketrans({"ä": "a", "ö": "o", "ü": "u", "ß": "ss", "Ä": "A", "Ö": "O", "Ü": "U"})
FILLERS_DE = ["mal", "bitte", "äh", "ähm", "also", "halt", "eben", "doch mal", "schnell"]


def noisy(text, r):
    """Run 4: programmatic noise on a kept sentence (typing/dictation artefacts). The facts stay; the result is validated again."""
    t = text
    for _ in range(r.choice([1, 1, 2, 3])):
        k = r.random()
        w = t.split()
        if k < .15:
            t = t.lower()
        elif k < .27:
            t = t.translate(UMLAUT) if r.random() < .5 else t.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        elif k < .40:
            t = re.sub(r"[.,!?;:]", "", t)
        elif k < .50:
            t = t.replace(",", " komma").replace("?", " fragezeichen").replace(".", " punkt") if r.random() < .6 else t.rstrip(".!? ") + ("" if t.rstrip(".!? ").endswith("punkt") else " punkt")
        elif k < .65 and len(w) > 2:   # one typo: swap, drop or double a letter in a word of >= 5 letters
            i = r.choice([i for i, x in enumerate(w) if len(x) >= 5] or [0])
            x = w[i]
            if len(x) >= 5:
                j = r.randrange(1, len(x) - 2)
                x = r.choice([x[:j] + x[j + 1] + x[j] + x[j + 2:], x[:j] + x[j + 1:], x[:j] + x[j] + x[j:]])
            w[i] = x
            t = " ".join(w)
        elif k < .78 and len(w) > 2:
            i = r.randrange(0, len(w))
            w.insert(i, r.choice(FILLERS_DE) if not re.search(r"\b(the|please|my|all)\b", t.lower()) else r.choice(["um", "pls", "like", "just"]))
            t = " ".join(w)
        elif k < .88 and len(w) > 3:   # dictation: merge two words or split a long one
            i = r.randrange(0, len(w) - 1)
            if r.random() < .5:
                w[i:i + 2] = [w[i] + w[i + 1]]
            elif len(w[i]) >= 8:
                w[i:i + 1] = [w[i][:len(w[i]) // 2], w[i][len(w[i]) // 2:]]
            t = " ".join(w)
        else:
            t = t[:1].lower() + t[1:]
    return t


def words(s):
    return set(fold(s).split())


def leak(text, tests, index):
    """Closest test sentence: (score, test). Score = max(word Jaccard, char ratio)."""
    ws = words(text)
    cands = set()
    for x in ws:
        cands |= index.get(x, set())
    best = (0.0, "")
    for i in cands:
        t = tests[i]
        tw = words(t)
        j = len(ws & tw) / max(1, len(ws | tw))
        if j < .35:
            continue
        sc = max(j, difflib.SequenceMatcher(None, fold(text), fold(t)).ratio())
        if sc > best[0]:
            best = (sc, t)
    return best


def main():
    specs = {}
    for f in sorted(OUT.glob("specs_*.jsonl")):
        for l in open(f, encoding="utf-8"):
            row = json.loads(l)
            specs[row["id"]] = row
    gens = [json.loads(l) for l in open(OUT / "words.jsonl", encoding="utf-8")]
    tests = case_texts()
    index = defaultdict(set)
    for i, t in enumerate(tests):
        for x in words(t):
            if len(x) >= 3:
                index[x].add(i)
    b3 = blind3_texts()
    b3_index = defaultdict(set)
    for i, t in enumerate(b3):
        for x in words(t):
            if len(x) >= 3:
                b3_index[x].add(i)
    rn = random.Random(4)
    NOISE = float(os.environ.get("LORA_NOISE", "0"))   # run 4: share of kept sentences that get one noisy variant
    drops, kept, leaks, seen = Counter(), [], [], set()
    for g in gens:
        for sid, sents in g["words"].items():
            sents = [re.sub(r"\s+", " ", x.strip().strip('"„“')) for x in sents if isinstance(x, str)]
            sents += [noisy(x, rn) + "\x00" for x in sents if rn.random() < NOISE]   # marker: noise variant
            for text in sents:
                src = g["backend"] + ("+noise" if text.endswith("\x00") else "")
                text = text.rstrip("\x00")
                key = fold(text)
                if key in seen:
                    drops["duplicate"] += 1
                    continue
                seen.add(key)
                sc, near = leak(text, tests, index)
                if sc >= .7:
                    drops["near a test sentence"] += 1
                    leaks.append((sc, text, near))
                    continue
                if leak(text, b3, b3_index)[0] >= .7:   # count only, never shown
                    drops["near a blind-3 sentence (count only)"] += 1
                    continue
                if sid.split("_")[0] in FIXED:
                    cls = sid.split("_")[0]
                    tgt = dict(FIXED[cls])
                    if R.actions_said(text) & {"delete"} and cls.startswith("refuse"):
                        drops["refuse with delete verb"] += 1
                        continue
                    kept.append({"id": sid, "group": sid, "cls": cls, "text": text, "target": tgt, "backend": src})
                    continue
                base = sid[:-1] if sid.endswith("q") else sid
                if base not in specs:
                    drops["unknown id"] += 1
                    continue
                row = specs[base]
                question = sid.endswith("q")
                s = dict(row["spec"])
                if question:
                    s = {k: v for k, v in s.items() if k not in ("dest", "dest_say", "dest_new")}
                    s["action"] = "show"
                    row = {**row, "spec": s, "target": D.target(s), "gold": D.gold(s, None)}
                why = wording_ok(text, row["spec"] if not question else {**row["spec"]}, question)
                if why:
                    drops[why] += 1
                    continue
                ok, status, j = D.check(text, row, "")
                if not ok:
                    drops["target misses gold"] += 1
                    continue
                cls = ("question" if question else s["action"])
                kept.append({"id": sid, "group": base, "cls": cls, "text": text, "target": row["target"], "backend": src,
                             "net_status": D.check(text, row, rules3.NET)[1]})
    # schema check (= what the GBNF grammar allows)
    for k in kept:
        j = json.loads(rules3.plan_json(k["target"]))
        assert list(j) == [f for f in rules3.FIELDS_L if f in j] and j["action"] in ("show", "move", "copy", "delete", "organize", "none", "ask")
    groups = sorted({k["group"] for k in kept})
    random.Random(5).shuffle(groups)
    val_g = set(groups[:len(groups) // 10])
    split = {"train": [k for k in kept if k["group"] not in val_g], "val": [k for k in kept if k["group"] in val_g]}
    # small run-2 classes: seen twice in training (oversampling, not new sentences)
    split["train"] += [k for k in split["train"] if k["cls"] in ("storq", "storfree", "dups", "dupsimg", "refuse2")]
    if STRICT:   # run 5: refusal, chat and ask once more (run 4 had too few: rename -> move, open -> show)
        split["train"] += [k for k in split["train"] if k["cls"] in ("refuse", "refuse2", "chat", "ask")]
    cap = int(os.environ.get("LORA_MAX_TRAIN", "0"))   # run 4: the locked training job must stay under 25 min (~0,26 s/example)
    if cap and len(split["train"]) > cap:
        split["train"] = random.Random(6).sample(split["train"], cap)
    for name, rows in split.items():
        with open(OUT / f"{name}.jsonl", "w", encoding="utf-8") as fh:
            for k in rows:
                fh.write(json.dumps({**k, "prompt": bench.prefix(rules3.SYSTEM_LORA) + bench.suffix(k["text"]),
                                     "completion": rules3.plan_json(k["target"]) + "<|im_end|>"}, ensure_ascii=False) + "\n")
    L = ["# Trainingsdaten (lora_build.py)", "", f"Sätze von den LLMs: {sum(len(v) for g in gens for v in g['words'].values())}",
         f"Behalten: {len(kept)} (Training {len(split['train'])}, Validierung {len(split['val'])}, getrennt nach Plan, nicht nach Satz)", "",
         "Klassen: " + ", ".join(f"{k} {v}" for k, v in Counter(k["cls"] for k in kept).most_common()),
         "Generatoren: " + ", ".join(f"{k} {v}" for k, v in Counter(k["backend"] for k in kept).most_common()),
         "Netz (Regeln a-d) bei behaltenen Planfällen: " + ", ".join(f"{k} {v}" for k, v in Counter(k.get("net_status") for k in kept if "net_status" in k).most_common()),
         "", "Verworfen: " + ", ".join(f"{k} {v}" for k, v in drops.most_common()), "",
         f"## Leck-Prüfung gegen {len(tests)} Testsätze (p0, p0b, Blind 1, Blind 2, 20 Originale, Labor-Sätze)"
         f" und {len(b3)} Blind-3-Sätze (nur gezählt, nie angezeigt)", "",
         "Maß: max(Wort-Jaccard, Zeichen-Ähnlichkeit) nach Kleinschreibung/Umlaut-Faltung. Ab 0,70 verworfen.", ""]
    L += [f"- {sc:.2f} „{t}“ ~ „{n}“" for sc, t, n in sorted(leaks, reverse=True)[:25]]
    near = sorted((leak(k["text"], tests, index) + (k["text"],) for k in kept), reverse=True)[:10]
    L += ["", "Nächste behaltene Sätze (unter 0,70):", ""] + [f"- {sc:.2f} „{t}“ ~ „{n}“" for sc, n, t in near]
    (OUT / "build.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L[:12]))


def selftest():
    """noisy() never empties a sentence and never changes its numbers (the facts are re-validated anyway)."""
    r = random.Random(3)
    for t in ["Lösch die 3 größten Videos über 200 MB", "Zeig mir PDFs von Vodafone außer ZIP-Dateien", "show the five newest photos"]:
        for _ in range(300):
            n = noisy(t, r)
            assert n.strip() and re.findall(r"\d", n) == re.findall(r"\d", t), n
    print("selftest ok")


if __name__ == "__main__":
    import sys
    selftest() if sys.argv[1:] == ["selftest"] else main()
