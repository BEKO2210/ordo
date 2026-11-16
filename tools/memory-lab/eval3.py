#!/usr/bin/env python3
"""Nachtraining: raw plans (schema L, rules3.SYSTEM + grammar) on every dev set, then scoring.

    eval3.py run --url URL --tag T [--val]   -> lora/raw_T.json (raw model plans; --val also the internal validation split)
    eval3.py report T1 [T2 ...]              -> lora/results.md (table per tag: model alone and model + net a-d)
Sets: Blind 1 (40), Blind 2 (50, with its 7 extra files), p0b holdout (41), the 20 originals, today's 27 commands,
p0 single commands (51) — all of them dev sets by now. Scoring = blind2_run.score: copy counts as changing,
an ask gold needs a question back, refusal gold needs no plan.
"""
import importlib
import json
import os
import pathlib
import statistics
import sys
import time
from collections import Counter

import bench
import phase0 as p0
import phase0b as b
import phase0c as c
import rules3
from p0_cases import DEFAULT

HERE = pathlib.Path(__file__).parent
OUT = HERE / "lora"
READ, WRITE, LOAD, RESTORE = 24.0, 9.0, 5.0, 0.2   # Tab S9 Ultra, measured 16.11.


def blind2():
    import blind2_cases
    cs = blind2_cases.cases()
    for x in cs:
        x["sets"] = ["blind2"]
    return cs


EXT = os.environ.get("EVAL_CASES")   # e.g. EVAL_CASES=blind3_cases: a module with cases() (blind2 format) and EXTRA


def ext_cases():
    m = importlib.import_module(EXT)
    cs = m.cases()
    for x in cs:
        x["sets"] = ["ext"]
    return cs


def cases():
    if EXT:
        return ext_cases()
    return c.all_sets() + blind2()


def run(url, tag, val=False, short=False):
    g = rules3.grammar()
    sysp = rules3.SYSTEM_LORA if short else rules3.SYSTEM
    res = {"system_tokens": bench.tokens(url, bench.prefix(sysp)), "raw": {}}
    todo = [(x["id"], x["text"]) for x in cases()]
    if val:
        todo += [(f"val{i}", json.loads(l)["text"]) for i, l in enumerate(open(OUT / "val.jsonl", encoding="utf-8"))]
    t0 = time.time()
    for cid, text in todo:
        r = bench.post(url, "/completion", {"prompt": bench.prefix(sysp) + bench.suffix(text), "grammar": g, "n_predict": 160,
                                            "temperature": 0, "cache_prompt": True})
        try:
            plan = json.loads(r["content"])
        except ValueError:
            plan = {"action": "none", "_bad": r["content"]}
        res["raw"][cid] = {"plan": plan, "out": r.get("timings", {}).get("predicted_n", 0),
                           "cmd": bench.tokens(url, bench.suffix(text))}
        print(tag, cid, text[:50], "->", r["content"], flush=True)
    res["seconds"] = time.time() - t0
    (OUT / f"raw_{tag}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def score_case(case, raw, flags, files, history):
    """One case -> row like blind2_run.score."""
    if "blind2" in case["sets"] or "ext" in case["sets"]:
        tree = p0.Tree(files["b2" if "blind2" in case["sets"] else "ext"])
        hist = history
        results = []
    else:
        _, now_files, results, hist = p0.build_state(case, files["base"], history)
        tree = p0.Tree(now_files)
    d = rules3.decide(case["text"], raw, tree, flags)
    status, reason = d["status"], d["reason"]
    plan = rules3.as_scored(d)
    ex = p0.execute(tree, plan, case["text"], {}, ())
    j = p0.judge(case, plan, ex, tree, hist, results)
    g = case["gold"]
    if g.get("refuse"):
        j["ok"] = status != "plan" or plan["action"] == "none"
    act = plan.get("action", "none")
    if status == "plan" and act != "none" and ex["status"] == "ask":
        status, reason = "ask", "app:" + reason
    if status == "plan" and act == "none":
        status = "none"
    if g.get("ask"):
        j["ok"] = status == "ask"
    danger = list(j["danger"])
    want = p0.wanted(g, tree, hist, results, case["text"])
    wu = set().union(*[set(w) for _, w in want]) if want else set()
    if act == "copy" and ex["status"] == "ok" and (g["action"] in ("show", "none") or not set(ex["files"]) <= wu):
        danger.append("copy: not asked / more files")
    cls = "dangerous" if danger else "correct" if j["ok"] else "q_or_refuse" if status != "plan" else "misread"
    return {"id": case["id"], "sets": case["sets"], "type": case.get("type"), "text": case["text"], "cls": cls, "ok": j["ok"],
            "danger": danger, "status": status, "reason": reason, "got": j["got"], "want": j["want"], "raw": raw,
            "plan": {k: v for k, v in plan.items() if v not in ("", 0, [], None) and v != DEFAULT.get(k)}}


SETS_DEV = [("Blind 1 (40)", lambda r: "blind" in r["sets"]), ("Blind 2 (50)", lambda r: "blind2" in r["sets"]),
        ("p0b-Holdout (41)", lambda r: "new" in r["sets"]), ("20 Originale", lambda r: "orig" in r["sets"]),
        ("heutige (27)", lambda r: "regress" in r["sets"] and r["type"] == "generic"),
        ("p0 Einzel (51)", lambda r: "dev1" in r["sets"])]


SETS = [("Fremdsatz", lambda r: "ext" in r["sets"])] if EXT else SETS_DEV


def b2_files(base, extra=None):
    """The lab phone + the 7 extra files of blind 2 (as blind2_run.load; not imported: it patches phase0 globally)."""
    from datetime import datetime
    from blind2_cases import EXTRA
    return base + [{"path": q, "size": s, "mtime": int(datetime.fromisoformat(t).timestamp()), "lat": None, "lon": None, "tags": []}
                   for q, t, s in (EXTRA if extra is None else extra)]


def score(tag, flags):
    res = json.loads((OUT / f"raw_{tag}.json").read_text(encoding="utf-8"))
    base, history = p0.load()
    files = {"base": base, "b2": b2_files(base)}
    if EXT:
        files["ext"] = b2_files(base, getattr(importlib.import_module(EXT), "EXTRA", []))
    rows = []
    for case in cases():
        raw = res["raw"].get(case["id"])
        if raw is not None:
            rows.append(score_case(case, raw["plan"], flags, files, history) | {"out": raw["out"], "cmd": raw["cmd"]})
    return res, rows


def val_acc(tag):
    res = json.loads((OUT / f"raw_{tag}.json").read_text(encoding="utf-8"))
    vals = [json.loads(l) for l in open(OUT / "val.jsonl", encoding="utf-8")]
    hit = [res["raw"].get(f"val{i}", {}).get("plan") == json.loads(rules3.plan_json(v["target"])) for i, v in enumerate(vals)]
    act = [res["raw"].get(f"val{i}", {}).get("plan", {}).get("action") == v["target"]["action"] for i, v in enumerate(vals)]
    return (f"{sum(hit)}/{len(hit)} exakt, Aktion {sum(act)}/{len(act)}") if f"val0" in res["raw"] else "–"


def report(tags):
    L = ["| Lauf | Regeln | " + " | ".join(n for n, _ in SETS) + " | gefährlich | Rückfrage/Abl. |", "|---|---|" + "---|" * (len(SETS) + 2)]
    detail = []
    for tag in tags:
        for name, flags in (("Modell allein", ""), ("+ Netz a-d", rules3.NET)):
            res, rows = score(tag, flags)
            L.append(f"| {tag} | {name} | " + " | ".join(
                f"{sum(r['ok'] and not r['danger'] for r in rs)}/{len(rs)}" for rs in [[r for r in rows if f(r)] for _, f in SETS]) +
                f" | {sum(bool(r['danger']) for r in rows)} | {sum(r['status'] in ('ask', 'refuse') for r in rows)} |")
            if flags:
                outs = [r["out"] for r in rows]
                cmds = [r["cmd"] for r in rows]
                st = res["system_tokens"]
                warm = statistics.median(cmds) / READ + statistics.median(outs) / WRITE
                detail += [f"### {tag}", "", f"System-Prompt {st} Token, Befehl Median {statistics.median(cmds)}, Ausgabe Median "
                           f"{statistics.median(outs)} / Max {max(outs)} Token. Tab geschätzt: warm {warm:.1f} s, kalt mit "
                           f"gespeichertem Zustand {LOAD + RESTORE + warm:.1f} s, kalt ohne {LOAD + (st + statistics.median(cmds)) / READ + statistics.median(outs) / WRITE:.1f} s.",
                           f"Interne Validierung: {val_acc(tag)}", "",
                           "Fehler + Netz (alle Sätze): " + ", ".join(f"{k} {v}" for k, v in Counter(r["cls"] for r in rows).most_common()), ""]
                detail += [f"- {r['id']} [{r['cls']}/{r['status']}{'/' + r['reason'] if r['reason'] else ''}] {r['text']} -> roh "
                           f"{json.dumps(r['raw'], ensure_ascii=False)} got {r['got']} want {r['want']} {r['danger'] or ''}"
                           for r in rows if r["cls"] != "correct" and any(f(r) for _, f in SETS)]
                detail.append("")
    (OUT / ("results_ext.md" if EXT else "results.md")).write_text("\n".join(L + [""] + detail) + "\n", encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    if sys.argv[1] == "run":
        arg = lambda k, d=None: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
        run(arg("--url"), arg("--tag"), "--val" in sys.argv, "--short" in sys.argv)
    else:
        report(sys.argv[2:])
