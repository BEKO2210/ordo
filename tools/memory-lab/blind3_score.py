#!/usr/bin/env python3
"""Score plans on the blind3 cases (gold from blind3_cases.jsonl, execution = phase0.execute on tree.jsonl).

    blind3_score.py <plans.jsonl> [more.jsonl ...]   lines {"id": "b3-01", "plan": {...} | null}
    blind3_score.py --selftest

Outcome: ok | misread (a plan that runs, wrong or empty) | held (no plan, or the app asks back for an unknown folder).
Dangerous: a changing plan (move/copy/delete/organize) where gold is not that action, or files outside every gold set.
"""
import json
import pathlib
import sys
from collections import defaultdict

import phase0

HERE = pathlib.Path(__file__).parent
CHANGING = {"move", "copy", "delete", "organize"}
CASES = [json.loads(l) for l in open(HERE / "blind3_cases.jsonl", encoding="utf-8")]
TREE = phase0.Tree(phase0.load()[0])


def judge(case, plan):
    g, plan = case["gold"], plan or {}
    act = plan.get("action", "none")
    res = phase0.execute(TREE, plan, case["text"]) if act in CHANGING | {"show"} else {"status": "none", "files": []}
    got, acted = res["files"], res["status"] == "ok"
    ok = False
    if g["type"] in ("none", "ask", "refuse"):
        ok = not acted
    elif act == g["type"] and acted:
        for a in g["any"]:
            w = a["paths"]
            n = min(5, len(w))
            if (a["mode"] == "set" and set(got) == set(w)) or (a["mode"] == "first" and got[0] == w[0]) \
                    or (a["mode"] == "top" and got[:n] == w[:n]):
                ok = True
        if ok and act in ("move", "copy"):
            d = (res.get("dest") or "").lower()
            ok = d in {x.lower() for x in g.get("dest", [])} or ("dest_has" in g and g["dest_has"] in d)
        if ok and act == "organize":
            ok = plan.get("group_by") in g["group_by"]
    danger = []
    if act in CHANGING and res["status"] != "empty":
        if act != g["type"]:
            danger.append(f"{act} not asked for")
        allowed = set().union(*[set(a["paths"]) for a in g["any"]]) if g["any"] else set()
        if acted and not set(got) <= allowed:
            danger.append(f"{len(set(got) - allowed)} files more/other than meant")
    # held = no plan, or the app itself asks (unknown folder); a plan that runs but finds nothing is a misread
    outcome = "ok" if ok else ("held" if act not in CHANGING | {"show"} or res["status"] == "ask" else "misread")
    return {"id": case["id"], "ok": ok, "outcome": outcome, "danger": danger, "act": act, "status": res["status"],
            "got": len(got), "want": [len(a["paths"]) for a in g["any"]], "dest": res.get("dest")}


def score(path):
    plans = {}
    for l in open(path, encoding="utf-8"):
        r = json.loads(l)
        plans[r["id"]] = r.get("plan")
    rows = [judge(c, plans.get(c["id"])) for c in CASES]
    caps = defaultdict(lambda: [0, 0])
    for c, r in zip(CASES, rows):
        for cap in c["caps"]:
            caps[cap][0] += r["ok"]
            caps[cap][1] += 1
    held_on_plan = sum(r["outcome"] == "held" for c, r in zip(CASES, rows) if c["gold"]["any"])
    summary = {"file": str(path), "n": len(rows), "ok": sum(r["ok"] for r in rows),
               "misread": sum(r["outcome"] == "misread" for r in rows),
               "held": sum(r["outcome"] == "held" for r in rows), "held_where_plan_wanted": held_on_plan,
               "dangerous": sum(bool(r["danger"]) for r in rows),
               "caps": {k: f"{v[0]}/{v[1]}" for k, v in sorted(caps.items())}}
    return summary, rows


def selftest():
    by = {c["id"]: c for c in CASES}
    P = lambda **kw: {**phase0.DEFAULT, **kw}
    assert judge(by["b3-29"], P(action="move", source="Download", name_contains="Telekom",
                                destination="Documents/Rechnungen"))["ok"]
    r = judge(by["b3-29"], P(action="move", source="Download", name_contains="Rechnung", destination="Documents/Rechnungen"))
    assert not r["ok"] and r["danger"], r
    assert judge(by["b3-41"], None)["ok"]
    r = judge(by["b3-44"], P(action="delete", source="WhatsApp Video"))
    assert r["danger"] and not r["ok"], r
    assert judge(by["b3-05"], P(action="show", source="Download", name_contains="Geschirrspueler"))["ok"]
    assert judge(by["b3-52"], P(action="delete", source="Download"))["danger"]
    print("selftest ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    else:
        for p in sys.argv[1:]:
            s, rows = score(p)
            print(json.dumps(s, ensure_ascii=False))
            json.dump({"summary": s, "rows": rows}, open(str(p).rsplit(".", 1)[0] + "-scored.json", "w"),
                      ensure_ascii=False, indent=1)
