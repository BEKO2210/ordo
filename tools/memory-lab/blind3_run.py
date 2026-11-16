#!/usr/bin/env python3
"""Baselines A (today's app) and C+R2 (frozen 430da086) on blind 3, through the unchanged blind2_run/phase0b/phase0c.
Only the case list (blind3_adapt) and the output folder (blind3/) are swapped in.

    blind3_run.py run --url URL --only A,C   -> blind3/results-0b.json, blind3/results-0c.json
    blind3_run.py dump                       -> blind3/plans_A.jsonl, blind3/plans_C+R2.jsonl (for blind3_score.py)
"""
import json
import pathlib
import sys

# blind2_run links blind2/prompt_today.json on import; a copied lab can hold a dangling link there -> skip, not crash
_link = pathlib.Path.symlink_to
pathlib.Path.symlink_to = lambda self, *a, **k: None if self.is_symlink() else _link(self, *a, **k)
import blind2_run as br  # noqa: E402
pathlib.Path.symlink_to = _link
import blind3_adapt

br.OUT = br.LAB / "blind3"
br.OUT.mkdir(exist_ok=True)
if not (br.OUT / "prompt_today.json").exists():
    (br.OUT / "prompt_today.json").symlink_to(br.LAB / "prompt_today.json")
br.b.HERE = br.c.HERE = br.OUT
br.cases, br.EXTRA = blind3_adapt.cases, blind3_adapt.EXTRA

if __name__ == "__main__":
    if sys.argv[1] == "run":
        arg = lambda k: sys.argv[sys.argv.index(k) + 1]
        only = arg("--only").split(",")
        if "C" in only:
            br.c.run(arg("--url"), ["C"])
        if "A" in only:
            br.b.run(arg("--url"), ["A"])
    else:
        for v in ("A", "C+R2"):
            rows = br.score(v)
            with open(br.OUT / f"plans_{v}.jsonl", "w", encoding="utf-8") as f:
                for r in rows:   # the plan after the variant's net; "none" when it asked/refused
                    plan = {**br.DEFAULT, **r["plan"]}   # the plan after the net; app-level asks keep it (model-level danger)
                    f.write(json.dumps({"id": r["id"], "plan": plan, "author_cls": r["cls"], "status": r["status"]},
                                       ensure_ascii=False) + "\n")
            n = {k: sum(r["cls"] == k for r in rows) for k in ("correct", "misread", "q_or_refuse", "dangerous")}
            print(v, "author count:", n)
