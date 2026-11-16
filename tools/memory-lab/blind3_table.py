#!/usr/bin/env python3
"""Blind 3 table from the *-scored.json files of blind3_score.py: exclusive classes (dangerous > correct > misread > held),
"ask strict" (an ask gold needs a question back, the author's rule), and the totals without b3-05 (not fully independent)."""
import json
import pathlib

OUT = pathlib.Path(__file__).parent / "blind3"
GOLD = {json.loads(l)["id"]: json.loads(l)["gold"]["type"] for l in open(OUT.parent / "blind3_cases.jsonl", encoding="utf-8")}
L = ["| Variante | richtig | falsch verst. | Rückfr./Abl. | gefährlich | richtig (ask streng) | ohne b3-05 |", "|---|---|---|---|---|---|---|"]
for v in ("A", "C+R2", "L", "L+net"):
    rows = json.loads((OUT / f"plans_{v}-scored.json").read_text(encoding="utf-8"))["rows"]
    st = {json.loads(l)["id"]: json.loads(l).get("status") for l in open(OUT / f"plans_{v}.jsonl", encoding="utf-8")}
    cls = {r["id"]: "dangerous" if r["danger"] else "ok" if r["ok"] else r["outcome"] for r in rows}
    strict = sum(c == "ok" and not (GOLD[i] == "ask" and st[i] not in ("ask", None)) for i, c in cls.items())
    n = lambda k: sum(c == k for c in cls.values())
    no5 = sum(c == "ok" for i, c in cls.items() if i != "b3-05")
    L.append(f"| {v} | {n('ok')}/60 | {n('misread')} | {n('held')} | {n('dangerous')} | {strict}/60 | {no5}/59 |")
print("\n".join(L))
(OUT / "table.md").write_text("\n".join(L) + "\n", encoding="utf-8")
