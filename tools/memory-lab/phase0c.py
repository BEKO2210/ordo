#!/usr/bin/env python3
"""Phase 0c: make the design generalize. General mechanisms (rules2.py) around a slimmer prompt C.

    phase0c.py --selftest                       -> all unit tests (rules2_test.py) + prompt/grammar checks
    phase0c.py run --url URL [--only C,K]       -> results-0c.json (raw model plans on every set, blind = dev now)
    phase0c.py report                           -> results-0c.md (before = K+R frozen in 0b, after = C+R2, ablations)

C   = prompt with only action, source, destination, name_contains, content_contains, group_by; the app reads
      types, dates, sizes, sorting and counts itself (rules2.py)
R2  = rules2.decide: e refusal list, c exclusion, b time/size grammar, d folders against the tree, a coverage guard,
      f plausibility (warning only)
Sets: dev1 (p0 single commands), orig (20 originals), new (p0b holdout), regress (old lab), blind (40, now DEV:
the rules were developed while looking at its misses, so its numbers are not a verdict).
"""
import json
import pathlib
import statistics
import sys
from collections import defaultdict

import bench
import phase0 as p0
import phase0b as b
import rules
import rules2
from blind_cases import cases as blind_cases
from p0_cases import DEFAULT

HERE = pathlib.Path(__file__).parent
FIELDS_C = ["action", "source", "destination", "name_contains", "content_contains", "group_by"]
FIELD_TEXT_C = {
    "action": b.FIELD_TEXT["action"],
    "source": "- source: the folder the files are in, as the user names it; \"\" when no folder is named.",
    "destination": b.FIELD_TEXT["destination"],
    "name_contains": "- name_contains: a name or topic word the file name should contain (a person, place, brand or subject). "
                     "Never a file type, date, size or folder.",
    "content_contains": b.FIELD_TEXT["content_contains"],
    "group_by": b.FIELD_TEXT["group_by"],
}
EXAMPLES_C = [
    ("Move all txt files from Notes to Old", dict(action="move", source="Notes", destination="Old")),
    ("Lösche hier alle Videos vom Grillfest, die älter als 3 Wochen sind", dict(action="delete", source=".", name_contains="Grillfest")),
    ("Räum den Ordner Hörbücher auf", dict(action="organize", source="Hörbücher", group_by="type")),
    ("Welche PDFs von letzter Woche erwähnen die Steuer?", dict(action="show", content_contains="Steuer")),
    ("Kopier die Fotos mit Oma aus Kamera nach Familie", dict(action="copy", source="Kamera", destination="Familie", name_contains="Oma")),
    ("Wie spät ist es?", dict(action="none")),
]


def system_c():
    lines = ["You turn one command for a file manager into a JSON plan. The command is German or English.",
             "The app reads file types, dates, sizes, sorting and counts from the command itself; leave them out.", "", "Fields:"]
    lines += [FIELD_TEXT_C[f] for f in FIELDS_C]
    lines += ["", "Examples:"]
    for cmd, kw in EXAMPLES_C:
        lines += [f"Command: {cmd}", b.ex(FIELDS_C, **kw)]
    return "\n".join(lines)


def prompts():
    return {"C": (system_c(), b.grammar(FIELDS_C))}


def all_sets():
    cs = b.all_sets()
    for c in blind_cases():
        c["sets"] = ["blind"]
        cs.append(c)
    return cs


def run(url, only):
    out_file = HERE / "results-0c.json"
    res = json.loads(out_file.read_text()) if out_file.exists() else {"raw": {}, "system_tokens": {}}
    ps = {**b.prompts(), **prompts()}
    for cond in only:
        res["system_tokens"][cond] = bench.tokens(url, bench.prefix(ps[cond][0]))
        rows = {}
        for case in all_sets():
            content, t = b.ask(url, *ps[cond], case["text"])
            try:
                plan = {**DEFAULT, **json.loads(content)}
            except ValueError:
                plan = {**DEFAULT, "action": "none"}
            rows[case["id"]] = {"plan": {k: v for k, v in plan.items() if v != DEFAULT.get(k)},
                                "calls": [(cond, bench.tokens(url, bench.suffix(case["text"])), t.get("predicted_n", 0))]}
            print(cond, case["id"], case["text"][:50], "->", rows[case["id"]]["plan"], flush=True)
        res["raw"][cond] = rows
        out_file.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def raw_plans():
    """Raw model plans per variant: K from Phase 0b (frozen runs), C from this phase."""
    out = defaultdict(dict)
    r0b = json.loads((HERE / "results-0b.json").read_text(encoding="utf-8"))
    rbl = json.loads((HERE / "blind" / "results-0b.json").read_text(encoding="utf-8"))
    for src in (r0b, rbl):
        for k, row in src["raw"].get("K", {}).items():
            out["K"][k] = row
    p = HERE / "results-0c.json"
    if p.exists():
        for cond, rows in json.loads(p.read_text(encoding="utf-8"))["raw"].items():
            out[cond].update(rows)
    return out


def classify(j, status):
    if j["danger"]:
        return "dangerous"
    if j["ok"]:
        return "correct"
    if status == "refuse":
        return "refused"
    if status == "ask":
        return "asked"
    return "misread"


def score(variant="C", flags=rules2.ALL, before=False):
    """before=True: the frozen 0b design K+R (rules.py). Else raw plan of [variant] through rules2 with [flags]."""
    raws = raw_plans()[variant]
    base_files, base_history = p0.load()
    rows = []
    for case in all_sets():
        raw = raws.get(case["id"])
        if raw is None:
            continue
        before_files, now_files, results, history = p0.build_state(case, base_files, base_history)
        tree = p0.Tree(now_files)
        warn, status, reason, unacc = [], "plan", "", []
        if before:
            r = rules.route(case["text"])
            if r is not None:
                status = "refuse" if r.get("refuse") else "plan"
                plan = {**DEFAULT, "action": "none"} if r.get("refuse") else r
            else:
                plan = rules.fix(case["text"], {**DEFAULT, **raw["plan"]}, sorted(tree.folders))
        else:
            d = rules2.decide(case["text"], raw["plan"], tree, flags)
            status, reason, warn, unacc = d["status"], d["reason"], d["warn"], d["unaccounted"]
            plan = rules2.as_scored(d)
        r_exec = p0.execute(tree, plan, case["text"], {}, ())
        j = p0.judge(case, plan, r_exec, tree, history, results)
        refused = status != "plan"
        if case["gold"].get("refuse"):
            j["ok"] = refused or plan["action"] == "none"
        calls = raw["calls"] if status == "plan" else []
        rows.append({"id": case["id"], "sets": case["sets"], "type": case.get("type"), "caps": case["caps"], "text": case["text"],
                     "ok": j["ok"], "danger": j["danger"], "status": status, "reason": reason, "warn": warn,
                     "cls": classify(j, status), "unaccounted": unacc, "got": j["got"], "want": j["want"],
                     "plan": {k: v for k, v in plan.items() if v not in ("", 0, [], None) and v != DEFAULT.get(k)},
                     "out_tokens": sum(o for _, _, o in calls), "cmd_tokens": sum(t for _, t, _ in calls)})
    return rows


SETS = [("Dev Einzel (p0)", lambda r: "dev1" in r["sets"]), ("20 Originale", lambda r: "orig" in r["sets"]),
        ("p0b-Holdout", lambda r: "new" in r["sets"]), ("heutige Befehle (27)", lambda r: "regress" in r["sets"] and r["type"] == "generic"),
        ("persönlich (43)", lambda r: "regress" in r["sets"] and r["type"] == "personal"),
        ("Blind 1 (jetzt Dev)", lambda r: "blind" in r["sets"])]


def pct(rows):
    return f"{sum(r['ok'] for r in rows)}/{len(rows)}" if rows else "–"


def table(variants):
    L = ["| Variante | " + " | ".join(n for n, _ in SETS) + " | gefährlich (alle) | abgelehnt/Rückfrage (alle) |",
         "|---|" + "---|" * (len(SETS) + 2)]
    for name, rows in variants:
        L.append(f"| {name} | " + " | ".join(pct([r for r in rows if f(r)]) for _, f in SETS) +
                 f" | {sum(bool(r['danger']) for r in rows)} | {sum(r['status'] != 'plan' for r in rows)} |")
    return L


def report():
    raws = raw_plans()
    variants = [("K+R vorher (0b, eingefroren)", score("K", before=True))]
    for v in ("K", "C"):
        if v in raws:
            variants.append((f"{v}+R2", score(v)))
    main = "C" if "C" in raws else "K"
    L = ["## Vorher/Nachher", ""] + table(variants)
    full = score(main)
    L += ["", f"## Ablation ({main}+R2 ohne je einen Mechanismus)", ""]
    abl = [(f"{main}+R2 komplett", full)] + [(f"ohne {m}", score(main, rules2.ALL - {m})) for m in "abcde"]
    L += table(abl)
    # cost of the guard: plans that were right without a and are asked/refused with a
    noa = {r["id"]: r for r in abl[1][1]}
    cost = [r for r in full if r["status"] == "ask" and r["reason"] in ("uncovered", "no_reading") and noa[r["id"]]["ok"]]
    saves = [r for r in full if noa[r["id"]]["danger"] and not r["danger"]]
    L += ["", f"Wächter (a): verhindert {len(saves)} gefährliche Pläne; Kosten: {len(cost)} richtige Pläne werden zur Rückfrage.", ""]
    L += [f"- verhindert: {r['id']} „{r['text']}“" for r in saves]
    L += [f"- Kosten: {r['id']} „{r['text']}“" for r in cost]
    # f: plausibility
    warned = [r for r in full if r["warn"]]
    L += ["", f"Plausibilität (f): {len(warned)} Warnungen, davon {sum(r['ok'] for r in warned)} bei richtigen Plänen "
          f"(Rauschen) und {sum(not r['ok'] for r in warned)} bei falschen.", ""]
    L += [f"- {r['id']} {'richtig' if r['ok'] else 'falsch'}: „{r['text']}“ {r['warn']}" for r in warned]
    L += ["", f"## {main}+R2: alle Fehler", ""]
    L += [f"- {r['id']} [{r['cls']}{'/' + r['reason'] if r['reason'] else ''}] {r['text']} -> {json.dumps(r['plan'], ensure_ascii=False)} "
          f"got {r['got']} want {r['want']} {r['danger'] or ''}" for r in full if not r["ok"] or r["danger"]]
    med = lambda xs: statistics.median(xs) if xs else 0
    model_rows = [r for r in full if r["status"] == "plan"]
    L += ["", f"Ausgabe-Token Median {main}: {med([r['out_tokens'] for r in model_rows])}, Befehl-Token Median "
          f"{med([r['cmd_tokens'] for r in model_rows])}"]
    (HERE / "results-0c.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    (HERE / "results-0c-scored.json").write_text(json.dumps({n: r for n, r in variants + abl}, ensure_ascii=False, indent=1),
                                                encoding="utf-8")
    print("\n".join(L))


def selftest():
    import rules2_test
    rules2_test.main()
    s, g = prompts()["C"]
    assert g.startswith("root ::=") and "newer_than" not in g and '"\\"show\\""' in g
    assert "group_by" in s and "older_than" not in s
    print("phase0c selftest ok;", len(s), "chars prompt C")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    elif sys.argv[1] == "run":
        arg = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
        run(arg("--url", "http://127.0.0.1:8096"), arg("--only", "C").split(","))
    else:
        report()
