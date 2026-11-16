#!/usr/bin/env python3
"""Fixture for CommandTunedGoldenTest: every raw plan the lab has, through the frozen net rules3.decide(flags "abcd").

    python3 export_tuned_golden.py <frozen lab dir> <out .json.gz>

Rows: the command, the phone's folders at that moment (index into "folders"), the raw model plan (schema L) and what
rules3.decide answered. Sources: the recorded raw outputs of every model run (lora/raw_*.json: the frozen adapter run2,
the other runs and the base model) on every dev set, plus the target plans of the training and validation data
(lora/train.jsonl, val.jsonl) on the lab phone. Clock: bench.NOW, as in the lab.
"""
import gzip
import json
import os
import subprocess
import sys

LAB = os.path.abspath(sys.argv[1])
OUT = os.path.abspath(sys.argv[2])
sys.path.insert(0, LAB)
os.chdir(LAB)
import bench  # noqa: E402
import eval3  # noqa: E402
import phase0 as p0  # noqa: E402
import rules2 as R  # noqa: E402
import rules3  # noqa: E402
from p0_cases import DEFAULT  # noqa: E402

PLAN_DEFAULT = {**DEFAULT, "exclude_kinds": [], "exclude_extensions": [], "exclude_terms": [], "date_from": "", "date_to": "",
                "min_bytes": 0, "max_bytes": 0, "name_terms": [], "text_terms": []}

folder_sets, folder_index = [], {}
rows = []


def tree_id(tree):
    key = tuple(sorted(tree.folders))
    if key not in folder_index:
        folder_index[key] = len(folder_sets)
        folder_sets.append(list(key))
    return folder_index[key]


def add(src, cid, text, raw, tree):
    d = rules3.decide(text, raw, tree, rules3.NET)
    plan = d["plan"]
    if plan is not None:
        unknown = set(plan) - set(PLAN_DEFAULT)
        assert not unknown, unknown
        plan = {k: v for k, v in plan.items() if v != PLAN_DEFAULT[k]}
    rows.append({"src": src, "id": cid, "text": text, "folders": tree_id(tree), "raw": raw,
                 "status": d["status"], "reason": d["reason"], "say": d["say"], "unaccounted": d["unaccounted"],
                 "plan": plan})


base, history = p0.load()
files = {"base": base, "b2": eval3.b2_files(base)}
trees = {}
for case in eval3.cases():
    if "blind2" in case["sets"]:
        trees[case["id"]] = p0.Tree(files["b2"])
    else:
        _, now_files, _, _ = p0.build_state(case, files["base"], history)
        trees[case["id"]] = p0.Tree(now_files)
texts = {c["id"]: c["text"] for c in eval3.cases()}
base_tree = p0.Tree(base)
vals = [json.loads(l) for l in open("lora/val.jsonl", encoding="utf-8")]
for i, v in enumerate(vals):
    texts[f"val{i}"] = v["text"]
    trees[f"val{i}"] = base_tree

for name in sorted(os.listdir("lora")):
    if not (name.startswith("raw_") and name.endswith(".json")):
        continue
    tag = name[4:-5]
    res = json.load(open(f"lora/{name}", encoding="utf-8"))
    for cid, r in res["raw"].items():
        if cid in texts:
            add(tag, cid, texts[cid], r["plan"], trees[cid])
        else:
            print("skipped", tag, cid, file=sys.stderr)

for fname in ("train", "val"):
    for line in open(f"lora/{fname}.jsonl", encoding="utf-8"):
        x = json.loads(line)
        add(fname, x["id"], x["text"], json.loads(rules3.plan_json(x["target"])), base_tree)

commit = subprocess.run(["git", "log", "-1", "--format=%h", "--abbrev=8", "--", "rules2.py", "rules3.py", "bench.py", "p0_cases.py"], capture_output=True, text=True).stdout.strip()
from datetime import datetime  # noqa: E402
out = {"lab_commit": commit, "now": datetime.fromtimestamp(bench.NOW).isoformat(timespec="minutes"), "system": rules3.SYSTEM_LORA, "grammar": rules3.grammar(),
       "ask": R.ASK, "ask_model": rules3.ASK_MODEL, "memory_say": R.MEMORY_SAY, "folders": folder_sets, "rows": rows}
with gzip.open(OUT, "wt", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
from collections import Counter  # noqa: E402
print(len(rows), "rows,", len(folder_sets), "folder sets,", dict(Counter(r["src"] for r in rows)), dict(Counter(r["status"] for r in rows)))
