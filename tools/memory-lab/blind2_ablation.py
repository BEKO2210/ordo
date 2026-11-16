#!/usr/bin/env python3
"""Ablation of rules2 on RECORDED raw prompt-C plans (no model run): blind 2 (blind2/results-0c.json) and blind 1
(results-0c.json, ids b000..b039).   blind2_ablation.py -> prints tables + per-case raw/final, writes blind2/ablation.json

Not switchable in rules2.decide (always on as soon as any rule runs): memory refusal (m), action safety per clause,
types/sort/limit from the words, vague size/time words. With "b" off, decide falls back to rules.time_and_size
(rules.py); here that fallback is stubbed to {} so "b off" really means no time/size parsing. Frozen files unchanged.
"""
import itertools
import json
import sys

import blind2_run as b2
import blind_cases
import phase0 as p0
import rules
import rules2
from p0_cases import DEFAULT

rules.time_and_size = lambda t: {}   # shortcut: only affects decide() with "b" off, see docstring

SUBSETS = [("roh (keine Regeln)", None), ("nur Wächter a", "a"), ("nur Zeit/Größe b", "b"), ("nur Ordner d", "d"),
           ("nur Ausschluss c", "c"), ("nur Kann-nicht e", "e"), ("Wächter+Ordner a+d", "ad"), ("komplett", "abcdef")]


def evaluate(cases, raw, files, history, flags):
    rows = []
    for case in cases:
        r = raw[case["id"]]["plan"]
        tree = p0.Tree(files)
        status = "plan"
        if flags is None:
            plan = {**DEFAULT, **r}
        else:
            d = rules2.decide(case["text"], r, tree, frozenset(flags))
            status, plan = d["status"], rules2.as_scored(d)
        ex = p0.execute(tree, plan, case["text"], {}, ())
        j = p0.judge(case, plan, ex, tree, history, [])
        g = case["gold"]
        act = plan.get("action", "none")
        if status == "plan" and act != "none" and ex["status"] == "ask":
            status = "ask"
        if status == "plan" and act == "none":
            status = "none"
        if g.get("refuse"):
            j["ok"] = status != "plan"
        if g.get("ask"):
            j["ok"] = status == "ask"
        danger = list(j["danger"])
        want = p0.wanted(g, tree, history, [], case["text"])
        wu = set().union(*[set(w) for _, w in want]) if want else set()
        if act == "copy" and ex["status"] == "ok" and (g["action"] in ("show", "none") or not set(ex["files"]) <= wu):
            danger.append("copy")
        cls = "dangerous" if danger else "correct" if j["ok"] else "q_or_refuse" if status != "plan" else "misread"
        rows.append({"id": case["id"], "text": case["text"], "cls": cls, "status": status,
                     "plan": {k: v for k, v in plan.items() if v not in ("", 0, [], None) and v != DEFAULT.get(k)}})
    return rows


def count(rows):
    return {k: sum(r["cls"] == k for r in rows) for k in ("correct", "misread", "q_or_refuse", "dangerous")}


def main():
    sets = {}
    raw2 = json.loads((b2.OUT / "results-0c.json").read_text(encoding="utf-8"))["raw"]["C"]
    sets["blind2"] = (b2.cases(), raw2, *b2.load())
    c1 = blind_cases.cases()
    raw1 = json.loads((b2.LAB / "results-0c.json").read_text(encoding="utf-8"))["raw"]["C"]
    sets["blind1"] = (c1, raw1, *b2._load())
    out = {}
    for name, (cs, raw, files, hist) in sets.items():
        print(f"\n## {name} ({len(cs)})\n\n| Regeln | richtig | falsch | Rückfrage/Abl. | gefährlich |\n|---|---|---|---|---|")
        out[name] = {}
        for label, fl in SUBSETS:
            rows = evaluate(cs, raw, files, hist, fl)
            out[name][label] = rows
            n = count(rows)
            print(f"| {label} | {n['correct']} | {n['misread']} | {n['q_or_refuse']} | {n['dangerous']} |")
    # every flag subset: which have 0 dangerous on both sets, most correct
    best = []
    for k in range(7):
        for fl in itertools.combinations("abcdef", k):
            n = [count(evaluate(*sets[s], "".join(fl))) for s in ("blind2", "blind1")]
            best.append(("".join(fl) or "-", n[0]["correct"], n[0]["dangerous"], n[1]["correct"], n[1]["dangerous"]))
    print("\n## alle 64 Teilmengen (blind2 richtig/gefährlich, blind1 richtig/gefährlich), 0 gefährlich zuerst")
    for x in sorted(best, key=lambda x: (x[2] + x[4] > 0, -(x[1] + x[3]), len(x[0])))[:12]:
        print(x)
    out["subsets"] = best
    (b2.OUT / "ablation.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    if "-v" in sys.argv:
        raw_rows, full = out["blind2"]["roh (keine Regeln)"], out["blind2"]["komplett"]
        for r, f in zip(raw_rows, full):
            print(r["id"], r["cls"][:4], "->", f["cls"][:4], r["text"][:60], json.dumps(r["plan"], ensure_ascii=False), "=>",
                  json.dumps(f["plan"], ensure_ascii=False), f["status"])


if __name__ == "__main__":
    main()
