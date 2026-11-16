#!/usr/bin/env python3
"""Frozen LoRA state (13bfbed9) on blind 3: raw plans from run_frozen.sh (blind3/raw_blind3.json) through the frozen
rules3.decide/as_scored exactly like eval3.score_case ("" = model alone, rules3.NET = + net a-d) -> plans for blind3_score.py.

    blind3_lora.py   -> blind3/plans_L.jsonl, blind3/plans_L+net.jsonl
"""
import json
import pathlib

import phase0 as p0
import rules3

OUT = pathlib.Path(__file__).parent / "blind3"
raw = json.loads((OUT / "raw_blind3.json").read_text(encoding="utf-8"))["raw"]
tree = p0.Tree(p0.load()[0])
TEXT = {json.loads(l)["id"]: json.loads(l)["text"] for l in open(OUT.parent / "blind3_cases.jsonl", encoding="utf-8")}
for name, flags in (("L", ""), ("L+net", rules3.NET)):
    with open(OUT / f"plans_{name}.jsonl", "w", encoding="utf-8") as f:
        for cid in sorted(raw):
            text = TEXT[cid]
            d = rules3.decide(text, raw[cid]["plan"], tree, flags)
            plan = rules3.as_scored(d)
            f.write(json.dumps({"id": cid, "plan": plan, "status": d["status"], "reason": d["reason"]}, ensure_ascii=False) + "\n")
