#!/usr/bin/env python3
"""Phase 0b: get single commands right without memory. Levers measured one by one.

    phase0b.py --selftest
    phase0b.py run --url URL --only S,K,KS,T     -> results-0b.json (raw model plans, all sets)
    phase0b.py report                            -> results-0b.md (scores incl. the +R variants, computed offline)

S  = Phase-0 schema prompt (1,440 tokens), raw
K  = short prompt, same fields minus "group" (no memory)                     (lever 2)
KS = short prompt, slim answer: time and size left to code                  (lever 2, fields moved into code)
T  = two steps: intent word first, then a small prompt for that intent only  (lever 3)
+R = deterministic rules.py around the model: router (no model call) + word fixes (lever 1)
Sets: dev = Phase-0 main + holdout (tuning allowed), orig = the 20 commands (part of dev), new = fresh holdout
(never tuned on), regress = old lab's today-commands. cache_prompt is off, so every answer is reproducible.
"""
import json
import pathlib
import re
import statistics
import sys
import time

import bench
import phase0 as p0
import rules
from p0_cases import DEFAULT, cases as p0_cases
from p0b_cases import cases as p0b_cases

HERE = pathlib.Path(__file__).parent
READ, WRITE, LOAD, RESTORE = 24.0, 9.0, 5.0, 0.2   # Tab S9 Ultra, measured 16.11. (22-26 t/s read, 9 t/s write)
FIELDS_K = ["action", "source", "destination", "kinds", "extensions", "name_contains", "content_contains", "older_than",
            "newer_than", "age_unit", "larger_than", "size_unit", "sort", "limit", "group_by"]
FIELDS_KS = ["action", "source", "destination", "kinds", "extensions", "name_contains", "content_contains", "sort", "limit",
             "group_by"]
RULE = {"action": 'action', "source": "string", "destination": "string", "kinds": "kinds", "extensions": "strings",
        "name_contains": "string", "content_contains": "string", "older_than": "int", "newer_than": "int",
        "age_unit": "ageunit", "larger_than": "int", "size_unit": "sizeunit", "sort": "sort", "limit": "int",
        "group_by": "groupby"}
BASE_RULES = '''kinds ::= "[" ( kind ( "," kind ){0,4} )? "]"
kind ::= "\\"audio\\"" | "\\"video\\"" | "\\"image\\"" | "\\"document\\"" | "\\"archive\\""
strings ::= "[" ( string ( "," string ){0,7} )? "]"
ageunit ::= "\\"days\\"" | "\\"weeks\\"" | "\\"months\\"" | "\\"years\\""
sizeunit ::= "\\"mb\\"" | "\\"gb\\""
groupby ::= "\\"\\"" | "\\"type\\"" | "\\"month\\""
sort ::= "\\"\\"" | "\\"size\\"" | "\\"newest\\""
string ::= "\\"" char{0,80} "\\""
char ::= [^"\\\\\\x7F\\x00-\\x1F] | "\\\\" ["\\\\/nt]
int ::= "0" | [1-9] [0-9]{0,5}'''
ACTIONS = ["show", "move", "copy", "delete", "organize", "none"]


def grammar(fields, actions=ACTIONS):
    parts = []
    for i, f in enumerate(fields):
        parts.append(f'"{"{" if i == 0 else ","}\\"{f}\\":" {RULE[f]}')
    act = " | ".join(f'"\\"{a}\\""' for a in actions)
    return "root ::= " + " ".join(parts) + ' "}"\naction ::= ' + act + "\n" + BASE_RULES


def ex(fields, **kw):
    return json.dumps({f: kw.get(f, DEFAULT[f]) for f in fields}, ensure_ascii=False, separators=(",", ":"))


FIELD_TEXT = {
    "action": """- action: show (find, list or open files; every question about the user's files; never changes anything),
  move, copy, delete (only when the user says to delete), organize (tidy up into subfolders) or none (anything
  else: greetings, rename, share, send).""",
    "source": """- source: the folder as the user names it. Downloads: "Download", camera photos: "Kamera", screenshots:
  "Screenshots", WhatsApp pictures: "WhatsApp Images". For show "" when no folder is named (whole phone).""",
    "destination": "- destination: the target folder for move and copy, else \"\".",
    "kinds": "- kinds: image, video, audio, document or archive when the user says a kind (Fotos, Videos, Musik).",
    "extensions": "- extensions: file endings the user says (pdf, apk, mp3). Never invent one.",
    "name_contains": "- name_contains: a word the file name must contain.",
    "content_contains": "- content_contains: a word inside the file, when the user asks what is written in it or does not know the name.",
    "older_than": "- older_than / newer_than with age_unit: \"älter als\" / recent files (\"gestern\" = newer_than 1 days).",
    "larger_than": "- larger_than, size_unit: the size limit the user says.",
    "sort": "- sort: \"size\" for the biggest files, \"newest\" for the latest ones; limit: how many files, else 0.",
    "group_by": "- group_by: organize only: \"month\" or \"type\".",
}
EXAMPLES = [   # (command, plan kw)
    ("Move all txt files from Notes to Old", dict(action="move", source="Notes", destination="Old", extensions=["txt"])),
    ("Lösche hier alle Videos, die älter als 3 Wochen sind", dict(action="delete", source=".", kinds=["video"], older_than=3, age_unit="weeks")),
    ("Räum den Ordner Hörbücher auf", dict(action="organize", source="Hörbücher", group_by="type")),
    ("Zeig mir die drei größten Dateien in Movies", dict(action="show", source="Movies", sort="size", limit=3)),
    ("Welche PDFs von letzter Woche erwähnen die Steuer?", dict(action="show", extensions=["pdf"], content_contains="Steuer", newer_than=1, age_unit="weeks")),
    ("Wie spät ist es?", dict(action="none")),
]


def system_for(fields, examples=EXAMPLES, intro="You turn one command for a file manager into a JSON plan. The command is German or English."):
    lines = [intro, "", "Fields:"] + [FIELD_TEXT[f] for f in fields if f in FIELD_TEXT]
    lines += ["", "Examples:"]
    for cmd, kw in examples:
        lines += [f"Command: {cmd}", ex(fields, **{k: v for k, v in kw.items() if k in fields})]
    return "\n".join(lines)


SYSTEM_K = system_for(FIELDS_K)
SYSTEM_KS = system_for(FIELDS_KS).replace(FIELD_TEXT["sort"], FIELD_TEXT["sort"] + "\n- Times and sizes are read by the app; leave them out.")
SYSTEM_INTENT = """Classify one command for a file manager. Answer one word:
show = find, list or open files, or any question about the user's files (changes nothing)
move, copy = put files into another folder
delete = only when the user says to delete
organize = tidy up or sort a folder into subfolders
none = anything else (greetings, rename, share, send)
Wo sind meine Fotos von gestern? -> show
Kann ich die alten Videos löschen? -> show
Verschieb die PDFs nach Dokumente -> move
Räum den Download auf, aber lösch nichts -> organize
Lösche alle APKs im Download -> delete
Schick das Bild an Mama -> none"""
SLOTS = {"show": ["action", "source", "kinds", "extensions", "name_contains", "content_contains", "sort", "limit"],
         "move": ["action", "source", "destination", "kinds", "extensions", "name_contains", "content_contains"],
         "copy": ["action", "source", "destination", "kinds", "extensions", "name_contains", "content_contains"],
         "delete": ["action", "source", "kinds", "extensions", "name_contains", "content_contains"],
         "organize": ["action", "source", "group_by"]}
SLOT_EX = {"show": [EXAMPLES[3], EXAMPLES[4], ("Wo ist die neueste APK im Download?", dict(action="show", source="Download", extensions=["apk"], sort="newest", limit=1))],
           "move": [EXAMPLES[0], ("Verschiebe alle Dateien mit Entwurf im Namen aus Projekte nach Fertig", dict(action="move", source="Projekte", destination="Fertig", name_contains="Entwurf"))],
           "copy": [("Kopiere die Fotos aus Kamera nach Backup", dict(action="copy", source="Kamera", destination="Backup", kinds=["image"]))],
           "delete": [EXAMPLES[1], ("Lösche alle Bilder im Ordner Scans", dict(action="delete", source="Scans", kinds=["image"]))],
           "organize": [EXAMPLES[2], ("Sortiere die Screenshots nach Monat", dict(action="organize", source="Screenshots", group_by="month"))]}


def prompts():
    p = json.loads((HERE / "prompt_today.json").read_text(encoding="utf-8"))
    out = {"A": (p["system"], p["grammar"]), "S": p0.prompts()["S"], "K": (SYSTEM_K, grammar(FIELDS_K)),
           "KS": (SYSTEM_KS, grammar(FIELDS_KS)), "T1": (SYSTEM_INTENT, 'root ::= "show" | "move" | "copy" | "delete" | "organize" | "none"')}
    for intent, fields in SLOTS.items():
        out["T2" + intent] = (system_for(fields, SLOT_EX[intent], f"You fill the plan for a \"{intent}\" command of a file manager. "
                                                                     "The command is German or English."), grammar(fields, [intent]))
    return out


def all_sets():
    dev = [c for c in p0_cases() if c["set"] in ("main", "holdout") and not c.get("restart")]
    for c in dev:
        c["sets"] = ["dev"] + (["orig"] if c["kind"] == "original" else []) + (["dev1"] if not c["session"] else [])
    rest = p0b_cases()
    for c in rest:
        c["sets"] = ["new"] if c["set"] == "holdout2b" else ["regress"]
    return dev + rest


def ask(url, system, grammar_, user, n=256):
    r = bench.post(url, "/completion", {"prompt": bench.prefix(system) + bench.suffix(user), "grammar": grammar_, "n_predict": n,
                                        "temperature": 0, "cache_prompt": False})
    return r["content"], r.get("timings", {})


def run(url, only, sets=None):
    ps = prompts()
    out_file = HERE / "results-0b.json"
    res = json.loads(out_file.read_text()) if out_file.exists() else {"raw": {}, "system_tokens": {}}
    base_files, base_history = p0.load()
    for k, (s, _) in ps.items():
        res["system_tokens"][k] = bench.tokens(url, bench.prefix(s))
    for cond in only:
        rows = res["raw"].get(cond, {}) if sets else {}
        for case in all_sets():
            if sets and not set(sets) & set(case["sets"]):
                continue
            before, now_files, results, history = p0.build_state(case, base_files, base_history)
            text = case["text"]
            calls = []
            if cond == "T":
                word, t1 = ask(url, *ps["T1"], text, 4)
                calls.append(("T1", bench.tokens(url, bench.suffix(text)), t1.get("predicted_n", 0)))
                plan = {**DEFAULT, "action": word.strip() or "none"}
                if plan["action"] in SLOTS:
                    content, t2 = ask(url, *ps["T2" + plan["action"]], text)
                    calls.append(("T2" + plan["action"], bench.tokens(url, bench.suffix(text)), t2.get("predicted_n", 0)))
                    try:
                        plan = {**DEFAULT, **json.loads(content)}
                    except ValueError:
                        plan = {**DEFAULT, "action": "none"}
            else:
                user = text
                if cond == "S":
                    user = p0.memory("S", case, before, now_files, results, history)[0] + f"\nCommand: {text}"
                content, t = ask(url, *ps[cond], user, 200 if cond == "A" else 256)
                calls.append((cond, bench.tokens(url, bench.suffix(user)), t.get("predicted_n", 0)))
                try:
                    plan = {**DEFAULT, **json.loads(content)}
                except ValueError:
                    plan = {**DEFAULT, "action": "none"}
            rows[case["id"]] = {"plan": {k: v for k, v in plan.items() if v != DEFAULT.get(k)}, "calls": calls}
            print(cond, case["id"], text[:50], "->", rows[case["id"]]["plan"], flush=True)
        res["raw"][cond] = rows
        out_file.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def score_all():
    """Every variant on every case: raw model, and +R (router first, then fixes)."""
    res = json.loads((HERE / "results-0b.json").read_text(encoding="utf-8"))
    base_files, base_history = p0.load()
    cases = all_sets()
    variants = [(c, False) for c in res["raw"]] + [(c, True) for c in res["raw"] if c != "A"]
    out = {}
    for cond, with_rules in variants:
        name = cond + ("+R" if with_rules else "")
        rows = []
        for case in cases:
            raw = res["raw"][cond].get(case["id"])
            if raw is None:
                continue
            before, now_files, results, history = p0.build_state(case, base_files, base_history)
            plan, calls, refused, routed = {**DEFAULT, **raw["plan"]}, raw["calls"], False, False
            if with_rules:
                r = rules.route(case["text"])
                if r is not None:
                    routed, calls = True, []
                    refused = r.get("refuse", False)
                    plan = {**DEFAULT, "action": "none"} if refused else r
                else:
                    plan = rules.fix(case["text"], plan, sorted(p0.Tree(now_files).folders))
            tree = p0.Tree(now_files)
            r_exec = p0.execute(tree, plan, case["text"], {}, ())
            j = p0.judge(case, plan, r_exec, tree, history, results)
            if case["gold"].get("refuse"):
                j["ok"] = refused or (plan["action"] == "none")
            warm = sum(t / READ + o / WRITE for _, t, o in calls)
            sys_t = sum(res["system_tokens"][k] for k, _, _ in calls)
            rows.append({"id": case["id"], "sets": case["sets"], "caps": case["caps"], "text": case["text"], "ok": j["ok"],
                         "outcome": j["outcome"], "danger": j["danger"], "blocks": j["blocks"], "routed": routed, "refused": refused,
                         "plan": {k: v for k, v in plan.items() if v != DEFAULT.get(k)},
                         "cmd_tokens": sum(t for _, t, _ in calls), "out_tokens": sum(o for _, _, o in calls),
                         "tab_warm": round(warm, 1), "tab_cold_nocache": round(LOAD + sys_t / READ + warm, 1) if calls else 0.0,
                         "tab_cold_cache": round(LOAD + RESTORE * len(calls) + warm, 1) if calls else 0.0, "type": case.get("type")})
        out[name] = rows
    return out, res["system_tokens"]


def report():
    out, sys_tokens = score_all()
    (HERE / "results-0b-scored.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    med = lambda xs: round(statistics.median(xs), 1) if xs else 0
    pct = lambda rows: f"{sum(r['ok'] for r in rows)}/{len(rows)} ({round(100 * sum(r['ok'] for r in rows) / len(rows))} %)" if rows else "–"
    order = [v for v in ["A", "S", "S+R", "K", "K+R", "KS", "KS+R", "T", "T+R"] if v in out]
    sysk = {"A": "A", "S": "S", "K": "K", "KS": "KS", "T": "T1+T2"}
    L = ["| Variante | Dev (Einzelbefehle) | 20 Originale | NEUER Holdout | Regression heute (generisch) | gefährlich (alle Sätze) | "
         "ohne Modell gelöst | System-Token | Token je Befehl (Median) | Ausgabe-Token (Median) | Tab warm s (Median/Max, geschätzt) | "
         "Tab kalt s ohne/mit Zustands-Cache (geschätzt) |", "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for v in order:
        rows = out[v]
        base = v.split("+")[0]
        st = (sys_tokens.get(base) if base != "T" else max(sys_tokens[k] for k in sys_tokens if k.startswith("T2")) + sys_tokens["T1"])
        model_rows = [r for r in rows if not r["routed"]]
        new = [r for r in rows if "new" in r["sets"]]
        L.append(f"| {v} | {pct([r for r in rows if 'dev1' in r['sets']])} | {pct([r for r in rows if 'orig' in r['sets']])} | "
                 f"**{pct(new)}** | {pct([r for r in rows if 'regress' in r['sets'] and r['type'] == 'generic'])} | "
                 f"{sum(bool(r['danger']) for r in rows)} | {sum(r['routed'] for r in rows)}/{len(rows)} | {st} | "
                 f"{med([r['cmd_tokens'] for r in model_rows])} | {med([r['out_tokens'] for r in model_rows])} | "
                 f"{med([r['tab_warm'] for r in rows])}/{max(r['tab_warm'] for r in rows)} | "
                 f"{med([r['tab_cold_nocache'] for r in model_rows])}/{med([r['tab_cold_cache'] for r in model_rows])} |")
    caps = ["show", "sort", "recency", "content", "today", "question", "memory", "english"]
    L += ["", "### Neuer Holdout nach Art", "", "| Variante | " + " | ".join(caps) + " |", "|---|" + "---|" * len(caps)]
    for v in order:
        new = [r for r in out[v] if "new" in r["sets"]]
        L.append(f"| {v} | " + " | ".join(pct([r for r in new if c in r["caps"]]) for c in caps) + " |")
    L += ["", "### Regression (alte Labor-Befehle)", "", "| Variante | generisch | persönlich (braucht Gedächtnis) | gefährlich |", "|---|---|---|---|"]
    for v in order:
        rg = [r for r in out[v] if "regress" in r["sets"]]
        L.append(f"| {v} | {pct([r for r in rg if r['type'] == 'generic'])} | {pct([r for r in rg if r['type'] == 'personal'])} | "
                 f"{sum(bool(r['danger']) for r in rg)} |")
    L += ["", "### Gefährliche Fälle", ""]
    for v in order:
        for r in out[v]:
            if r["danger"]:
                L.append(f"- {v} {r['id']} „{r['text']}“ → {json.dumps(r['plan'], ensure_ascii=False)} ({', '.join(r['danger'])})")
    best = order[-1]
    L += ["", f"### Neuer Holdout, jeder Fall ({', '.join(order)})", "", "| Satz | " + " | ".join(order) + " |", "|---|" + "---|" * len(order)]
    by = {v: {r["id"]: r for r in out[v]} for v in order}
    for r0 in [r for r in out[best] if "new" in r["sets"]]:
        cells = []
        for v in order:
            r = by[v][r0["id"]]
            cells.append(("ok" if r["ok"] else {"miss": "·", "wrong": "falsch", "ok": "falsch"}[r["outcome"]]) + (" ⚠" if r["danger"] else "") +
                         (" R" if r["routed"] else ""))
        L.append(f"| {r0['text']} | " + " | ".join(cells) + " |")
    L += ["", "ok = richtig · „·“ = nichts/Rückfrage · ⚠ = gefährlich · R = ohne Modell (Regel)"]
    (HERE / "results-0b.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L[:12]))


def selftest():
    rules.selftest()
    ps = prompts()
    for k, (s, g) in ps.items():
        assert g.startswith("root ::="), k
    assert '"\\"group\\"' not in ps["K"][1] and "newer_than" not in ps["KS"][1] and '"show"' in ps["T1"][1]
    assert ps["T2organize"][1].count('\\"group_by\\"') == 1 and '"\\"move\\""' not in ps["T2show"][1]
    sets = all_sets()
    assert sum("new" in c["sets"] for c in sets) == 41 and sum("orig" in c["sets"] for c in sets) == 20
    print("phase0b selftest ok;", {k: len(v[0]) for k, v in ps.items() if not k.startswith("T2")}, "chars")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    elif sys.argv[1] == "run":
        arg = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
        run(arg("--url", "http://127.0.0.1:8096"), arg("--only", "A,S,K,KS,T").split(","),
            arg("--sets", "").split(",") if "--sets" in sys.argv else None)
    elif sys.argv[1] == "report":
        report()
