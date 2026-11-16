#!/usr/bin/env python3
"""Fixture for CommandGuardedGoldenTest: raw model plans through the frozen net rules2.decide(flags ALL), as the blind
tests call it (blind2_run.score, phase0c.score).

    python3 export_guarded_golden.py <frozen lab dir> <out .json.gz>

Sources: every recorded prompt-C plan (results-0c.json dev sets, blind2/, blind3/), and, to widen coverage, every other
recorded raw plan the lab has (prompts A, S, K, KS, T of results-0b.json, blind 2 and 3, the schema-L runs in lora/)
plus the target plans of the training data, all through the same rules2.decide. rules2 counts files on the phone, so
the fixture carries the phones: a table of files (path, size, mtime, text) and per row the files on the phone then.
"""
import gzip
import json
import os
import subprocess
import sys
from collections import Counter
from datetime import datetime

LAB = os.path.abspath(sys.argv[1])
OUT = os.path.abspath(sys.argv[2])
sys.path.insert(0, LAB)
os.chdir(LAB)
import bench  # noqa: E402
import eval3  # noqa: E402
import phase0 as p0  # noqa: E402
import phase0c  # noqa: E402
import phase0b  # noqa: E402
import rules2  # noqa: E402
import rules3  # noqa: E402
import blind2_cases  # noqa: E402
import blind3_adapt  # noqa: E402
from p0_cases import DEFAULT  # noqa: E402

PLAN_DEFAULT = {**DEFAULT, "exclude_kinds": [], "exclude_extensions": [], "exclude_terms": [], "date_from": "", "date_to": "",
                "min_bytes": 0, "max_bytes": 0, "name_terms": [], "text_terms": []}
file_table, file_index, phones, phone_index, rows = [], {}, [], {}, []


def phone_id(files):
    ids = []
    for f in files:
        key = (f["path"], f["size"], f["mtime"], f.get("text", ""))
        if key not in file_index:
            file_index[key] = len(file_table)
            file_table.append(list(key))
        ids.append(file_index[key])
    key = tuple(ids)
    if key not in phone_index:
        phone_index[key] = len(phones)
        phones.append(ids)
    return phone_index[key]


def add(src, cid, text, raw, files):
    tree = p0.Tree(files)
    d = rules2.decide(text, raw, tree, rules2.ALL)
    plan = d["plan"]
    if plan is not None:
        assert not set(plan) - set(PLAN_DEFAULT), set(plan) - set(PLAN_DEFAULT)
        plan = {k: v for k, v in plan.items() if v != PLAN_DEFAULT[k]}
    rows.append({"src": src, "id": cid, "text": text, "phone": phone_id(files), "raw": raw, "status": d["status"],
                 "reason": d["reason"], "say": d["say"], "unaccounted": d["unaccounted"], "warn": d["warn"], "plan": plan})


def extra_files(base, extra):
    return base + [{"path": q, "size": s, "mtime": int(datetime.fromisoformat(t).timestamp()), "lat": None, "lon": None, "tags": []}
                   for q, t, s in extra]


base, history = p0.load()
dev = {}
for case in phase0c.all_sets() + eval3.cases():
    if case["id"] in dev:
        continue
    if "blind2" in case.get("sets", []):
        dev[case["id"]] = (case["text"], extra_files(base, blind2_cases.EXTRA))
    else:
        dev[case["id"]] = (case["text"], p0.build_state(case, base, history)[1])
b2 = {c["id"]: (c["text"], extra_files(base, blind2_cases.EXTRA)) for c in blind2_cases.cases()}
b3 = {c["id"]: (c["text"], extra_files(base, blind3_adapt.EXTRA)) for c in blind3_adapt.cases()}


def raws(path, cases, tag_prefix=""):
    if not os.path.exists(path):
        return
    for cond, rs in json.load(open(path, encoding="utf-8"))["raw"].items():
        for cid, r in rs.items():
            if cid in cases:
                add(tag_prefix + cond, cid, cases[cid][0], r["plan"], cases[cid][1])
            else:
                print("skipped", path, cond, cid, file=sys.stderr)


raws("results-0c.json", dev)
raws("blind2/results-0c.json", b2, "b2:")
raws("blind3/results-0c.json", b3, "b3:")
raws("results-0b.json", dev)
raws("blind2/results-0b.json", b2, "b2:")
raws("blind3/results-0b.json", b3, "b3:")
vals = [json.loads(l) for l in open("lora/val.jsonl", encoding="utf-8")]
for i, v in enumerate(vals):
    dev[f"val{i}"] = (v["text"], base)
for name in sorted(os.listdir("lora")):
    if name.startswith("raw_") and name.endswith(".json"):
        for cid, r in json.load(open(f"lora/{name}", encoding="utf-8"))["raw"].items():
            if cid in dev:
                add("L:" + name[4:-5], cid, dev[cid][0], r["plan"], dev[cid][1])
for fname in ("train", "val"):
    for line in open(f"lora/{fname}.jsonl", encoding="utf-8"):
        x = json.loads(line)
        add(fname, x["id"], x["text"], json.loads(rules3.plan_json(x["target"])), base)

commit = subprocess.run(["git", "log", "-1", "--format=%h", "--abbrev=8", "--", "rules2.py", "phase0c.py", "phase0b.py", "phase0.py",
                         "bench.py", "p0_cases.py"], capture_output=True, text=True).stdout.strip()
out = {"lab_commit": commit, "now": datetime.fromtimestamp(bench.NOW).isoformat(timespec="minutes"), "zone": "Europe/Berlin",
       "system": phase0c.system_c(), "grammar": phase0b.grammar(phase0c.FIELDS_C), "ask": rules2.ASK,
       "unsupported": {c: say for c, _, say in rules2.UNSUPPORTED}, "memory_say": rules2.MEMORY_SAY,
       "files": file_table, "phones": phones, "rows": rows}
with gzip.open(OUT, "wt", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
print(len(rows), "rows,", len(phones), "phones,", len(file_table), "files,", dict(Counter(r["src"] for r in rows)),
      dict(Counter(r["status"] for r in rows)))
