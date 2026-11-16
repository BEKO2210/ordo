#!/usr/bin/env python3
"""Golden fixture for the Kotlin port of rules.py (CommandRulesGoldenTest).

    python3 export_golden.py   -> app/src/test/resources/ai/rules-golden.json

For every variant that the lab scored with rules (S, K, KS, T) and every case of every set, plus the 40 blind cases
(blind_cases.jsonl, raw plans of S and K from blind/results-0b.json): the command, the raw
model plan from results-0b.json, the folder list the lab passed to fix(), and what rules.route / rules.fix made of it.
The Kotlin rules must reproduce "final" for every row; a difference is a bug in the port.
Also exported: prompt K and its grammar, which the app must use word for word (CommandPlanner).
"""
import json
import pathlib

import phase0 as p0
import rules
from p0_cases import DEFAULT
from blind_cases import cases as blind_cases
from phase0b import FIELDS_K, SYSTEM_K, all_sets, grammar

HERE = pathlib.Path(__file__).parent
OUT = HERE.parents[1] / "app/src/test/resources/ai/rules-golden.json"
FIELDS = [k for k in DEFAULT if k != "group"]   # "group" is a Phase-0 field the app never had


def main():
    res = json.loads((HERE / "results-0b.json").read_text(encoding="utf-8"))
    blind = json.loads((HERE / "blind/results-0b.json").read_text(encoding="utf-8"))
    blind_set = blind_cases()
    for c in blind_set:
        c["sets"] = ["blind"]
    runs = [(v, res, all_sets()) for v in ["S", "K", "KS", "T"]] + [(v, blind, blind_set) for v in ["S", "K"]]
    base_files, base_history = p0.load()
    folder_sets, rows = [], []
    for variant, results, case_list in runs:
        for case in case_list:
            raw = results["raw"][variant].get(case["id"])
            if raw is None:
                continue
            _, now_files, _, _ = p0.build_state(case, base_files, base_history)
            folders = sorted(p0.Tree(now_files).folders)
            if folders not in folder_sets:
                folder_sets.append(folders)
            plan = {**DEFAULT, **raw["plan"]}
            routed = rules.route(case["text"])
            if routed is not None:
                final = routed
            else:
                final = rules.fix(case["text"], plan, folders)
            row = {"variant": variant, "id": case["id"], "sets": case["sets"], "text": case["text"],
                   "raw": {k: plan[k] for k in FIELDS}, "folders": folder_sets.index(folders),
                   "routed": routed is not None}
            if final.get("refuse"):
                row["refuse"] = True
            else:
                row["final"] = {**{k: final[k] for k in FIELDS}, "today": bool(final.get("_today", False))}
            rows.append(row)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"system_k": SYSTEM_K, "grammar_k": grammar(FIELDS_K), "folders": folder_sets, "rows": rows}, ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    print(f"{len(rows)} rows, {len(folder_sets)} folder sets -> {OUT}")


if __name__ == "__main__":
    main()
