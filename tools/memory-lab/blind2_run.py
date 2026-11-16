#!/usr/bin/env python3
"""Blind test 2: frozen variants A (today's app), K+R (0b, rules.py) and C+R2 (0c, rules2.py) on blind2_cases.py.

    blind2_run.py run --url URL --only A,K    -> blind2/results-0b.json   (phase0b.run, unchanged)
    blind2_run.py run --url URL --only C      -> blind2/results-0c.json   (phase0c.run, unchanged)
    blind2_run.py report                      -> blind2/scored.json + blind2/results.md
    blind2_cases.py                           -> gold check (every wanted set non-empty, sizes)
Only the case list, the output folder and 7 extra files on the synthetic phone are swapped in. Scoring calls the frozen
phase0.execute/judge, rules.route/fix and rules2.decide/as_scored exactly like phase0c.score; on top it records
the app's status so that "asked back" can be told apart from "refused", and counts copy like move (changing).
"""
import json
import pathlib
import sys
from collections import defaultdict
from datetime import datetime

import phase0 as p0
import phase0b as b
import phase0c as c
import rules
import rules2
from blind2_cases import EXTRA, cases
from p0_cases import DEFAULT

LAB = pathlib.Path(__file__).parent
OUT = LAB / "blind2"
OUT.mkdir(exist_ok=True)
if not (OUT / "prompt_today.json").exists():
    (OUT / "prompt_today.json").symlink_to(LAB / "prompt_today.json")
b.HERE = c.HERE = OUT
_load = p0.load


def load():
    files, history = _load()
    extra = [{"path": p, "size": s, "mtime": int(datetime.fromisoformat(t).timestamp()), "lat": None, "lon": None, "tags": []}
             for p, t, s in EXTRA]
    assert not {f["path"] for f in extra} & {f["path"] for f in files}
    return files + extra, history


def sets():
    cs = cases()
    for x in cs:
        x["sets"] = ["blind2"]
    return cs


p0.load, b.all_sets, c.all_sets = load, sets, sets


def check_gold():
    files, history = p0.load()
    for x in cases():
        tree = p0.Tree(files)
        w = p0.wanted(x["gold"], tree, history, [], x["text"])
        bad = [len(v) for _, v in w if not v]
        print(x["id"], x["caps"], x["gold"]["action"], [len(v) for _, v in w], "EMPTY!" if bad else "", x["text"])
        assert not bad, x["id"]


def score(variant):
    """A = raw plan of today's app; K+R = rules.py on K (as phase0c.score before=True); C+R2 = rules2 on C."""
    raw = {}
    for f in ("results-0b.json", "results-0c.json"):
        p = OUT / f
        if p.exists():
            for cond, rows in json.loads(p.read_text(encoding="utf-8"))["raw"].items():
                raw.setdefault(cond, {}).update(rows)
    src = {"A": "A", "K+R": "K", "C+R2": "C"}[variant]
    files, history = p0.load()
    rows = []
    for case in cases():
        r = raw[src][case["id"]]
        tree = p0.Tree(files)
        status, reason = "plan", ""
        if variant == "A":
            plan = {**DEFAULT, **r["plan"]}
        elif variant == "K+R":
            ro = rules.route(case["text"])
            if ro is not None:
                status = "refuse" if ro.get("refuse") else "plan"
                plan = {**DEFAULT, "action": "none"} if ro.get("refuse") else ro
            else:
                plan = rules.fix(case["text"], {**DEFAULT, **r["plan"]}, sorted(tree.folders))
        else:
            d = rules2.decide(case["text"], r["plan"], tree, rules2.ALL)
            status, reason = d["status"], d["reason"]
            plan = rules2.as_scored(d)
        ex = p0.execute(tree, plan, case["text"], {}, ())
        j = p0.judge(case, plan, ex, tree, history, [])
        g = case["gold"]
        if g.get("refuse"):
            j["ok"] = status != "plan" or plan["action"] == "none"
        act = plan.get("action", "none")
        if status == "plan" and act != "none" and ex["status"] == "ask":
            status, reason = "ask", "app:" + reason   # the app itself asks (unknown folder/destination)
        if status == "plan" and act == "none":
            status = "none"
        frozen_ok = j["ok"]
        if g.get("ask"):
            j["ok"] = status == "ask"
        danger = list(j["danger"])
        want = p0.wanted(g, tree, history, [], case["text"])
        wu = set().union(*[set(w) for _, w in want]) if want else set()
        if act == "copy" and ex["status"] == "ok" and (g["action"] in ("show", "none") or not set(ex["files"]) <= wu):
            danger.append("copy: not asked / more files")
        cls = "dangerous" if danger else "correct" if j["ok"] else "q_or_refuse" if status != "plan" else "misread"
        rows.append({"id": case["id"], "caps": case["caps"], "text": case["text"], "gold_action": g["action"],
                     "gold_kind": "ask" if g.get("ask") else "refuse" if g.get("refuse") else "chat" if g["action"] == "none" else "plan",
                     "cls": cls, "ok": j["ok"], "frozen_ok": frozen_ok, "danger": danger, "status": status, "reason": reason,
                     "got": j["got"], "want": j["want"],
                     "plan": {k: v for k, v in plan.items() if v not in ("", 0, [], None) and v != DEFAULT.get(k)},
                     "raw": r["plan"]})
    return rows


def report():
    out = {v: score(v) for v in ("A", "K+R", "C+R2")}
    (OUT / "scored.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    L = ["| Variante | richtig | falsch verstanden | Rückfrage/Ablehnung | gefährlich |", "|---|---|---|---|---|"]
    for v, rows in out.items():
        n = defaultdict(int)
        for r in rows:
            n[r["cls"]] += 1
        L.append(f"| {v} | {n['correct']}/{len(rows)} | {n['misread']} | {n['q_or_refuse']} | {n['dangerous']} |")
    full = out["C+R2"]
    per = defaultdict(lambda: [0, 0])
    for r in full:
        for cap in r["caps"]:
            per[cap][0] += r["cls"] == "correct"
            per[cap][1] += 1
    L += ["", "C+R2 je Fähigkeit: " + ", ".join(f"{k} {a}/{t}" for k, (a, t) in sorted(per.items()))]
    plan_gold = [r for r in full if r["gold_kind"] == "plan"]
    nonplan_gold = [r for r in full if r["gold_kind"] != "plan"]
    L += ["", f"C+R2 fragt/lehnt ab, wo ein Plan richtig war: {sum(r['status'] in ('ask', 'refuse', 'none') for r in plan_gold)}"
          f"/{len(plan_gold)}; wo Frage/Ablehnung/kein Plan richtig war: "
          f"{sum(r['status'] in ('ask', 'refuse', 'none') for r in nonplan_gold)}/{len(nonplan_gold)}"
          f" (davon Rückfrage bei ask-Gold {sum(r['status'] == 'ask' for r in nonplan_gold if r['gold_kind'] == 'ask')}/4)"]
    for v, rows in out.items():
        L += ["", f"## {v}: alle Fehler", ""]
        L += [f"- {r['id']} [{r['cls']}/{r['status']}{'/' + r['reason'] if r['reason'] else ''}] {r['text']} -> "
              f"{json.dumps(r['plan'], ensure_ascii=False)} got {r['got']} want {r['want']} {r['danger'] or ''}"
              for r in rows if r["cls"] != "correct"]
    (OUT / "results.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    if sys.argv[1] == "run":
        arg = lambda k: sys.argv[sys.argv.index(k) + 1]
        only = arg("--only").split(",")
        if "C" in only:
            c.run(arg("--url"), ["C"])
        if [o for o in only if o != "C"]:
            b.run(arg("--url"), [o for o in only if o != "C"])
    else:
        report()
