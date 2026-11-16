#!/usr/bin/env python3
"""Run the frozen Phase-0b variants on the blind set, unchanged (rules.py, prompts, scorer untouched).

    blind_run.py run --url URL --only A,S,K   -> blind/results-0b.json
    blind_run.py report                       -> blind/scored.json + blind/results.md
Only the case list and the output folder are swapped; everything else is phase0b.run / phase0b.score_all.
"""
import json
import pathlib
import sys
from collections import defaultdict

import phase0b as b
from blind_cases import cases

LAB = pathlib.Path(__file__).parent
OUT = LAB / "blind"
OUT.mkdir(exist_ok=True)
if not (OUT / "prompt_today.json").exists():
    (OUT / "prompt_today.json").symlink_to(LAB / "prompt_today.json")
b.HERE = OUT


def blind_sets():
    cs = cases()
    for c in cs:
        c["sets"] = ["blind"]
    return cs


b.all_sets = blind_sets


def cls(r):
    if r["danger"]:
        return "dangerous"
    if r["ok"]:
        return "correct"
    return "refused" if r["refused"] or not r["plan"].get("action") or r["plan"].get("action") == "none" else "misread"


def report():
    out, _ = b.score_all()
    (OUT / "scored.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    L = ["| Variante | richtig | falsch verstanden | abgelehnt | gefährlich |", "|---|---|---|---|---|"]
    for v in ("A", "S+R", "K+R", "S", "K"):
        if v in out:
            n = defaultdict(int)
            for r in out[v]:
                n[cls(r)] += 1
            L.append(f"| {v} | {n['correct']}/{len(out[v])} | {n['misread']} | {n['refused']} | {n['dangerous']} |")
    L += ["", "K+R je Fähigkeit:", ""]
    per = defaultdict(lambda: [0, 0])
    for r in out["K+R"]:
        for c in r["caps"]:
            per[c][0] += r["ok"]
            per[c][1] += 1
    L += [f"- {c}: {a}/{t}" for c, (a, t) in sorted(per.items())]
    L += ["", "K+R alle Fehler:", ""]
    L += [f"- {r['id']} [{cls(r)}] {r['text']} -> {r['plan']} {r['danger']}" for r in out["K+R"] if not r["ok"] or r["danger"]]
    (OUT / "results.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    if sys.argv[1] == "run":
        arg = lambda k: sys.argv[sys.argv.index(k) + 1]
        b.run(arg("--url"), arg("--only").split(","))
    else:
        report()
