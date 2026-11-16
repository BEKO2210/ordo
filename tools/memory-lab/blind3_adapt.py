#!/usr/bin/env python3
"""blind3_cases.jsonl in the blind2_cases format (cases(), EXTRA) for the existing runners. Text and gold unchanged:
set -> {"paths"}, first -> {"first"}, top -> {"top": plan} with a plan whose result is the frozen order (asserted below).
Only loss: b3-26's second accepted answer (direct children of Documents only) has no plan form; blind3_score.py keeps it.
"""
import json
import pathlib

from p0_cases import G, P

HERE = pathlib.Path(__file__).parent
EXTRA = []
TOP_PLAN = {"b3-06": P("show", kinds=["video"], sort="size"), "b3-07": P("show", sort="size"),
            "b3-26": P("show", "Documents", sort="oldest"), "b3-56": P("show", "Download", sort="size")}


def gold(r):
    g = r["gold"]
    if g["type"] in ("none", "ask", "refuse"):
        return G("none", none_ok=True, **({g["type"]: True} if g["type"] != "none" else {}))
    any_, tops = [], 0
    for a in g["any"]:
        if a["mode"] == "set":
            any_.append({"paths": a["paths"]})
        elif a["mode"] == "first":
            any_.append({"first": a["paths"][0]})
        elif tops == 0:
            any_.append({"top": TOP_PLAN[r["id"]]})
            tops += 1
    return G(g["type"], *any_, **{k: g[k] for k in ("dest", "dest_has", "group_by") if k in g})


def cases():
    rows = [json.loads(l) for l in open(HERE / "blind3_cases.jsonl", encoding="utf-8")]
    return [{"id": r["id"], "set": "blind3", "type": "blind3", "caps": r["caps"], "text": r["text"], "session": [],
             "gold": gold(r)} for r in rows]


if __name__ == "__main__":
    import phase0 as p0
    files, history = p0.load()
    tree = p0.Tree(files)
    mine = {json.loads(l)["id"]: json.loads(l) for l in open(HERE / "blind3_cases.jsonl", encoding="utf-8")}
    for x in cases():
        w = p0.wanted(x["gold"], tree, history, [], x["text"])
        for (how, got), a in zip(w, mine[x["id"]]["gold"]["any"]):
            assert got, x["id"]
            if how == "top":
                n = min(5, len(a["paths"]))
                assert got[:n] == a["paths"][:n], x["id"]
            elif how == "set":
                assert set(got) == set(a["paths"]), x["id"]
    print(len(cases()), "cases, gold reproduced")
