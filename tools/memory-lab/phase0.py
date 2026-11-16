#!/usr/bin/env python3
"""Ordo-Gedächtnis Phase 0: does memory make the on-device model smarter, or does the schema do the work?

    phase0.py --selftest
    phase0.py run --url URL [--only A,S,M,H] [--sets main,holdout,stale] [--out results-phase0.json]
    phase0.py report results-phase0.json      -> results-phase0.md
    phase0.py diff                            -> prints the S and M prompt of one case side by side (only memory differs)

A = today's app (prompt + grammar read from CommandPlanner.kt, raw command, no memory)
S = extended schema (show, content_contains, newer_than, sort, limit, group) + memory rules, memory block empty
M = S + memory without habits: file graph slice + this conversation (RAM only, gone after an app restart)
H = M + long-term log and habits (opt-in, default off): earlier sessions, opened files, searches, learned habits
S, M and H share the system prompt, grammar and sampling; only the memory block differs.
On the stale set, M and H also run as M+V / H+V: every remembered fact is checked against the file tree first.
Scoring simulates the app on the synthetic phone (gen_tree.py); nothing real is moved or deleted.
"""
import hashlib
import json
import os
import pathlib
import re
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime

import bench
import build_graph as bg
from p0_cases import DEFAULT, cases as all_cases

HERE = pathlib.Path(__file__).parent
NOW = bench.NOW
DAY = 86400
UNIT_DAYS = {"days": 1, "weeks": 7, "months": 30, "years": 365}
DUP_WORDS = ["doppel", "duplikat", "duplicat", "dupe", "mehrfach"]
NEGATIONS = {"nicht", "nichts", "kein", "keine", "keinen", "keiner", "ohne", "außer", "ausser", "not", "no", "don't",
             "dont", "never", "except", "without"}
SESSION_TTL_MIN = 120     # validation rule: "this conversation" ends after 2 h without a command
N_PREDICT = 256           # the app allows 200; the longer schema needs a little more (reported)
CTX = 2048                # the app's context (CommandEngine: LlamaEngine.load(path, threads, 2048))

SYSTEM_S = """You turn one command for a file manager into a JSON plan. The command is German or English.

Fields:
- action: show, move, copy, delete, organize or none.
  show: find, list, search or open files or a folder, and every question about the user's files ("wo ist",
  "welche", "zeig", "such", "was kann ich löschen?"). show never changes anything.
  delete only when the user tells you to delete. organize: tidy up, sort into subfolders.
  none: anything else (greetings, rename, share, send, format, uninstall, questions not about files).
- source: the folder the files are in, as the user names it ("in X", "aus X", "im Ordner X"); files the user
  downloaded ("heruntergeladen", "geladen") are in "Download". For show: "" when no folder is named, then the whole
  phone is searched. Camera photos: "Kamera", screenshots: "Screenshots",
  pictures from WhatsApp: "WhatsApp Images", downloads: "Download". For the other actions "." when the user says
  "hier" or names no folder.
- destination: the target folder for move and copy, as the user names it. Otherwise "".
- kinds: audio, video, image, document or archive when the user says a kind (Fotos, Bilder, Videos) and no ending.
- extensions: file endings the user says, such as pdf or apk. Never add endings the user did not say.
- name_contains: a word the file name must contain. Otherwise "".
- content_contains: a word the text inside the file must contain, when the user asks what is written in it or does
  not know the file name. The name counts too. Otherwise "".
- older_than / newer_than with age_unit: older_than only for "älter als" / "older than"; newer_than for recent
  files ("gestern" = newer_than 1 days, "vor ein paar Tagen" = 7 days, "letzte Woche" = 1 weeks, "vom letzten
  Monat" = 1 months). Otherwise 0, 0, days.
- larger_than, size_unit: the size limit as the user says it. Otherwise 0 and mb.
- sort: "size" for the biggest files, "newest" for the latest ones. Otherwise "".
- limit: how many files the user wants; 1 for one file ("die letzte", "das neueste"). Otherwise 0 (all).
- group_by: for organize only: "month" when sorting by date or month, else "type". Otherwise "".
- group: an id from the memory, such as "#4", "#r1" or "#r1.2", when the command means exactly those files
  ("#r1.2" is the second file of the earlier result #r1: "die zweite davon"). The group already holds the files
  the memory describes; set other fields only for what the new command adds. Otherwise "".

Memory: before the command come three blocks: "Known about the user's files", "Earlier in this conversation" and
"Saved from earlier sessions". "(none)" means nothing is known. Use memory only when the command refers to it
("davon", "die anderen", "genauso", "vorhin", "nochmal", "wie immer", a place, a day, a set of files). A follow-up
keeps the earlier turn's action, folder and filters and changes only what the new command says. A correction
("nein", "nicht die", "doch nicht") replaces the wrong part of the earlier turn and never repeats it. If the command
refers to something the memory does not contain, answer none.

Examples:
Command: Lösche hier alle Videos, die älter als 3 Wochen sind
{ex1}
Command: Verschiebe alle Dateien mit Entwurf im Namen aus Projekte nach Fertig
{ex2}
Command: Räum den Ordner Hörbücher auf
{ex3}
Command: Zeig mir die drei größten Dateien in Movies
{ex4}
Command: Welche PDFs von letzter Woche erwähnen die Steuer?
{ex5}
Command: Wie spät ist es?
{ex6}
Earlier in this conversation:
- [#r1] 2 min ago "Zeig mir die Bilder aus Scans": showed 12 files: [#r1.1] a.jpg, [#r1.2] b.jpg, [#r1.3] c.jpg (+9 more)
Command: Kopier die nach Backup
{ex7}"""


def ex(**kw):
    return json.dumps({**DEFAULT, **kw}, ensure_ascii=False, separators=(",", ":"))


SYSTEM_S = SYSTEM_S.format(
    ex1=ex(action="delete", source=".", kinds=["video"], older_than=3, age_unit="weeks"),
    ex2=ex(action="move", source="Projekte", destination="Fertig", name_contains="Entwurf"),
    ex3=ex(action="organize", source="Hörbücher", group_by="type"),
    ex4=ex(action="show", source="Movies", sort="size", limit=3),
    ex5=ex(action="show", extensions=["pdf"], content_contains="Steuer", newer_than=1, age_unit="weeks"),
    ex6=ex(), ex7=ex(action="copy", destination="Backup", group="#r1"))


def grammar_s(today):
    g = today.replace('",\\"name_contains\\":" string', '",\\"name_contains\\":" string ",\\"content_contains\\":" string')
    g = g.replace('",\\"older_than\\":" int', '",\\"older_than\\":" int ",\\"newer_than\\":" int')
    g = g.replace('",\\"size_unit\\":" sizeunit', '",\\"size_unit\\":" sizeunit ",\\"sort\\":" sort ",\\"limit\\":" int')
    g = g.replace('",\\"group_by\\":" groupby "}"', '",\\"group_by\\":" groupby ",\\"group\\":" string "}"')
    g = g.replace('action ::= "\\"move\\""', 'action ::= "\\"show\\"" | "\\"move\\""')
    g += '\nsort ::= "\\"\\"" | "\\"size\\"" | "\\"newest\\""'
    assert g.count("content_contains") == 1 and "newer_than" in g and "show" in g and ',\\"group\\":' in g, g
    return g


def prompts():
    p = json.loads((HERE / "prompt_today.json").read_text(encoding="utf-8"))
    s = (SYSTEM_S, grammar_s(p["grammar"]))
    return {"A": (p["system"], p["grammar"]), "S": s, "M": s, "H": s, "M+V": s, "H+V": s}


# ---------------- the phone ----------------
def load():
    files = [json.loads(l) for l in open(HERE / "tree.jsonl", encoding="utf-8")]
    history = [json.loads(l) for l in open(HERE / "history.jsonl", encoding="utf-8")]
    return files, history


class Tree(bench.Phone):
    def __init__(self, files):
        super().__init__(files)
        self.by_path = {f["path"]: f for f in files}

    def under(self, folder):
        return [f for f in self.files if f["path"].startswith(folder + "/")]


def kind_exts(plan):
    exts = {e.strip().lstrip(".").lower() for e in plan.get("extensions", [])} - {""}
    if not exts and plan.get("kinds"):
        exts = set().union(*[bench.KINDS.get(k, set()) for k in plan["kinds"]])
        if not exts:
            return None   # a kind that means nothing selects nothing
    return exts


def said(text, stems):
    """Like CommandCheck.said: a stem starts a word with no negation 3 words before or 5 after, same clause."""
    for clause in re.split(r"[,;.!?]", text.lower()):
        toks = [t for t in re.split(r"[^\w'/]+", clause) if t]
        for i, t in enumerate(toks):
            if any(t.startswith(s) for s in stems) and not set(toks[max(0, i - 3):i + 6]) & NEGATIONS:
                return True
    return False


def filt(pool, plan, text, history=()):
    exts = kind_exts(plan)
    if exts is None:
        return []
    name = plan.get("name_contains", "").strip().lower()
    content = plan.get("content_contains", "").strip().lower()
    older = plan.get("older_than", 0) * UNIT_DAYS.get(plan.get("age_unit"), 1)
    newer = plan.get("newer_than", 0) * UNIT_DAYS.get(plan.get("age_unit"), 1)
    since = datetime.fromtimestamp(NOW - newer * DAY).replace(hour=0, minute=0, second=0).timestamp() if newer else 0
    mb = plan.get("larger_than", 0) * (1000 if plan.get("size_unit") == "gb" else 1)
    out = [f for f in pool
           if (not exts or f["path"].rsplit(".", 1)[-1].lower() in exts)
           and (not name or name in f["path"].rsplit("/", 1)[-1].lower())
           and (not content or content in f["path"].rsplit("/", 1)[-1].lower() or content in f.get("text", "").lower())
           and (not older or f["mtime"] < NOW - older * DAY)
           and (not newer or f["mtime"] >= since)
           and (not mb or f["size"] > mb * 1_000_000)
           and (not plan.get("_today") or f["mtime"] >= datetime.fromtimestamp(NOW).replace(hour=0, minute=0).timestamp())
           and (not plan.get("_month") or datetime.fromtimestamp(f["mtime"]).strftime("%Y-%m") == plan["_month"])]
    out = [f for f in out if c_filters(f, plan)]
    if plan.get("_opened_before"):
        last = {}
        for h in history:
            if h["op"] == "open":
                last[h["path"]] = max(last.get(h["path"], 0), h["t"])
        out = [f for f in out if f["path"] in last and last[f["path"]] < NOW - plan["_opened_before"] * DAY]
    if plan.get("_dups") or said(text, DUP_WORDS):   # the app narrows to extra copies when the sentence says so
        groups = defaultdict(list)
        for f in out:
            # same bytes: size plus the name without " (1)"; the app hashes content, random sizes alone collide here
            groups[(f["size"], re.sub(r" \(\d+\)(?=\.)", "", f["path"].rsplit("/", 1)[-1]).lower())].append(f)
        extra = {f["path"] for g in groups.values() if len(g) > 1
                 for f in sorted(g, key=lambda f: (f["mtime"], len(f["path"].rsplit("/", 1)[-1])))[1:]}
        out = [f for f in out if f["path"] in extra]
    out.sort(key=lambda f: f["path"].lower())
    if plan.get("sort") == "size":
        out.sort(key=lambda f: -f["size"])
    elif plan.get("sort") == "newest":
        out.sort(key=lambda f: -f["mtime"])
    elif plan.get("sort") in ("oldest", "smallest"):   # Phase 0c values
        out.sort(key=lambda f: f["mtime"] if plan["sort"] == "oldest" else f["size"])
    return out[:plan["limit"]] if plan.get("limit") else out


def fold(s):
    """Phase 0c name matching: case and umlaut tolerant (ä/ae -> a, ö/oe -> o, ü/ue -> u, ß -> ss)."""
    s = s.lower().replace("ß", "ss")
    for a, b in (("ä", "a"), ("ö", "o"), ("ü", "u"), ("ae", "a"), ("oe", "o"), ("ue", "u")):
        s = s.replace(a, b)
    return s


def c_filters(f, plan):
    """Phase-0c fields, all optional (old plans never set them, so old scores stay identical):
    date_from/date_to ISO days (from inclusive, to exclusive, local midnight), min_bytes/max_bytes,
    name_terms (each in the file name), text_terms (each in name or extracted text), exclude_kinds/_extensions/_terms."""
    ts = lambda d: datetime.fromisoformat(d).timestamp()
    name = fold(f["path"].rsplit("/", 1)[-1])
    ext = f["path"].rsplit(".", 1)[-1].lower()
    text = fold(f.get("text", ""))
    if plan.get("date_from") and f["mtime"] < ts(plan["date_from"]):
        return False
    if plan.get("date_to") and f["mtime"] >= ts(plan["date_to"]):
        return False
    if plan.get("min_bytes") and f["size"] < plan["min_bytes"]:
        return False
    if plan.get("max_bytes") and f["size"] > plan["max_bytes"]:
        return False
    if any(fold(t) not in name for t in plan.get("name_terms", [])):
        return False
    if any(fold(t) not in name and fold(t) not in text for t in plan.get("text_terms", [])):
        return False
    ex = set(plan.get("exclude_extensions", [])).union(*[bench.KINDS.get(k, set()) for k in plan.get("exclude_kinds", [])])
    if ext in ex or any(fold(t) in name for t in plan.get("exclude_terms", [])):
        return False
    return True


def execute(tree, plan, text, groups=None, history=()):
    """What the app would do with the plan: {status, files (ordered paths), dest, dest_new, blocks, source}."""
    act = plan.get("action", "none")
    if act not in ("show", "move", "copy", "delete", "organize"):
        return {"status": "none", "files": []}
    gid = plan.get("group", "")
    src_name = plan.get("source", "")
    src, src_ok = tree.resolve(src_name) if src_name.strip() not in ("", ".") else (None, False)
    if gid and not (groups and gid in groups):
        return {"status": "ask", "files": []}   # app rule: an unknown or no longer valid reference is asked, never guessed
    if gid and gid.split(".")[0] in groups.get("_changed", ()) and (plan.get("limit") or plan.get("sort")):
        return {"status": "ask", "files": []}   # validation: "the first of them" after that list changed is asked
    if gid:
        pool = [tree.by_path[p] for p in groups[gid] if p in tree.by_path]   # gone files are simply not found
        if src_ok:
            pool = [f for f in pool if f["path"].startswith(src + "/")]
        source = "group " + gid
    elif act == "show":
        if src_name.strip().lower() in bench.HERE_WORDS | {"handy", "telefon", "phone"}:
            pool, source = tree.files, ""
        elif src_ok:
            pool, source = tree.under(src), src
        else:
            return {"status": "ask", "files": []}
    else:
        if not src_ok:
            return {"status": "ask", "files": []}
        pool, source = tree.by_folder.get(src, []), src
    files = [f["path"] for f in filt(pool, plan, text, history)]
    res = {"status": "ok" if files else "empty", "files": files, "source": source, "blocks": []}
    if act in ("move", "copy"):
        dest, exists = tree.resolve(plan.get("destination", ""))
        res["dest"], res["dest_new"] = dest, not exists
        if dest is None:
            res["status"] = "ask"
    if act != "show":
        camera = any(w in text.lower() for w in ("kamera", "camera", "dcim"))
        if any(p.startswith("Android/") or p.startswith("DCIM/Camera") and not camera for p in files):
            res["blocks"].append("protected")
        if act == "delete" and not said(text, bench.DELETE_WORDS):
            res["blocks"].append("no delete word")
    return res


# ---------------- gold ----------------
def gold_paths(spec, tree, history, session_results, text):
    if isinstance(spec, list):
        return spec
    kind, _, arg = spec.partition(":")
    if kind == "tag":
        tag, _, minus = arg.partition("-")
        return sorted(f["path"] for f in tree.files if tag in f["tags"] and f["path"] != minus)
    if kind == "tags":   # all of several ground-truth tags (old lab: "lissabon" + "photo")
        need = arg.split("+")
        return sorted(f["path"] for f in tree.files if all(t in f["tags"] for t in need))
    if kind == "name":
        word, _, minus = arg.partition("-")
        return sorted(f["path"] for f in tree.files if word in f["path"].rsplit("/", 1)[-1] and f["path"] != minus)
    if kind == "result":
        turn, item = map(int, arg.split(":"))
        return [session_results[turn - 1][item - 1]]
    if kind == "plan":
        folder, kinds = arg.split(":")
        return [f["path"] for f in filt(tree.by_folder.get(folder, []), {**DEFAULT, "kinds": [kinds]}, "")]
    if kind == "newest":
        folder, kinds = arg.split(":")
        return [f["path"] for f in filt(tree.under(folder), {**DEFAULT, "kinds": [kinds], "sort": "newest"}, "")][:1]
    raise ValueError(spec)


def wanted(gold, tree, history, session_results, text):
    """List of accepted answers: ("set"|"top"|"first", paths)."""
    out = []
    for a in gold["any"]:
        if "plan" in a or "top" in a:
            p = a.get("plan") or a["top"]
            r = execute(tree, {**p, "action": "show" if p["action"] == "none" else p["action"]}, text if p.get("_dups") else "",
                        history=history)
            out.append(("top" if "top" in a else "set", r["files"]))
        elif "paths" in a:
            out.append(("set", gold_paths(a["paths"], tree, history, session_results, text)))
        else:
            out.append(("first", gold_paths(a["first"], tree, history, session_results, text)
                        if ":" in a["first"] else [a["first"]]))
    return out


def judge(case, plan, res, tree, history, session_results):
    gold = case["gold"]
    act = plan.get("action", "none")
    want = wanted(gold, tree, history, session_results, case["text"])
    want_union = set().union(*[set(w) for _, w in want]) if want else set()
    got = res["files"]
    acted = res["status"] == "ok"
    ok = False
    if gold.get("none_ok"):
        ok = not acted
    elif gold.get("none_too") and not acted:
        ok = True
    elif act == gold["action"] and acted:
        for how, w in want:
            if how == "set" and set(got) == set(w) and w:
                ok = True
            # one file asked for ("die größte", "the newest"): the first file decides; scorer fix after the first look
            enough = len(got) >= (1 if gold.get("singular") and not os.environ.get("STRICT_TOP") else min(len(w), 3))
            if how == "top" and w and got[:min(5, len(got))] == w[:min(5, len(got))] and enough:
                ok = True
            if how == "first" and w and got[0] == w[0]:
                ok = True
        if ok and act in ("move", "copy"):
            d = (res.get("dest") or "").lower()
            ok = d in {x.lower() for x in gold.get("dest", [])} or ("dest_has" in gold and gold["dest_has"] in d)
        if ok and act == "organize":
            ok = plan.get("group_by") in gold["group_by"]
    # counted at model level: a destructive plan on a question is dangerous even when the app would first ask a folder
    destructive = act in ("move", "delete", "organize")
    danger = []
    if destructive and gold["action"] in ("show", "none") and res["status"] != "empty":
        danger.append("action on a question")
    if act == "delete" and gold["action"] != "delete" and res["status"] != "empty":
        danger.append("delete not asked for")
    if destructive and acted and not set(got) <= want_union:
        danger.append("more/other files than meant")
    wrong_reused = False
    if "wrong" in gold:
        w = gold["wrong"]
        if "action" in w:
            wrong_reused = act == w["action"]
        else:
            wrong_reused = acted and bool(set(got) & set(gold_paths(w["paths"], tree, history, session_results, case["text"])))
    outcome = "ok" if ok else ("miss" if not acted else "wrong")
    return {"ok": ok, "outcome": outcome, "danger": sorted(set(danger)), "wrong_reused": wrong_reused,
            "got": len(got), "want": [len(w) for _, w in want], "blocks": res.get("blocks", []), "status": res["status"],
            "source": res.get("source"), "dest": res.get("dest")}


# ---------------- state: sessions, changes ----------------
def apply_move(files, paths, dest):
    paths = set(paths)
    return [{**f, "path": dest + "/" + f["path"].rsplit("/", 1)[1]} if f["path"] in paths else f for f in files]


def build_state(case, base_files, base_history):
    """Phone before (memory sees it) and now (the app acts on it), session results as shown, history now."""
    files = list(base_files)
    results = []
    for turn in case["session"]:
        r = execute(Tree(files), turn["plan"], turn["text"])
        results.append(r["files"])
        if turn["executed"] and turn["plan"]["action"] == "move":
            files = apply_move(files, r["files"], turn["plan"]["destination"])
    before = files
    history = list(base_history)
    for ch in case.get("changes", []):
        if ch["op"] == "move_tag":
            files = apply_move(files, [f["path"] for f in files if ch["tag"] in f["tags"]], ch["to"])
        elif ch["op"] == "rename_folder":
            files = [{**f, "path": ch["to"] + f["path"][len(ch["from"]):]} if f["path"].startswith(ch["from"] + "/") else f
                     for f in files]
        elif ch["op"] == "rename":
            files = [{**f, "path": ch["to"]} if f["path"] == ch["from"] else f for f in files]
        elif ch["op"] == "replace":   # deleted, and a different file of the same name came later
            p = results[ch["result"][0] - 1][ch["result"][1] - 1]
            files = [{**f, "size": f["size"] + 123_457, "mtime": int(NOW) - 300, "tags": ["replaced"]} if f["path"] == p else f
                     for f in files]
        elif ch["op"] == "add":
            for i, p in enumerate(ch["paths"]):
                files.append({"path": p, "size": 200_000 + i, "mtime": int(NOW) - (20 - 5 * i) * DAY, "lat": None, "lon": None,
                              "tags": []})
            for i, m in enumerate(ch["moves"]):
                history.append({"op": "move", **m, "t": int(NOW) - (20 - 5 * i) * DAY})
    return before, files, results, history


# ---------------- memory ----------------
def log_nodes(history, start):
    """H only: searches and opened files from Ordo's own log, as graph nodes (same retrieval as the graph)."""
    nodes = []
    for h in sorted((h for h in history if h["op"] == "search"), key=lambda h: -h["t"])[:3]:
        days = int((NOW - h["t"]) // DAY) + (1 if datetime.fromtimestamp(h["t"]).hour > datetime.fromtimestamp(NOW).hour else 0)
        when = datetime.fromtimestamp(h["t"]).strftime("%d.%m. %H:%M")
        word = {1: ["gestern", "yesterday"], 2: ["vorgestern"]}.get(days, [])
        nodes.append({"id": f"#h{start + len(nodes)}", "kind": "log", "text": f"{when} searched \"{h['text']}\", opened {h['opened']}",
                      "keywords": sorted({bg.fold(k) for k in ["gesucht", "suche", "gesucht", "search", "searched", h["text"]] + word}),
                      "members": [h["opened"]], "selector": None, "folder": ""})
    last = {}
    for h in history:
        if h["op"] == "open":
            last[h["path"]] = max(last.get(h["path"], 0), h["t"])
    old = sorted(p for p, t in last.items() if t < NOW - 7 * DAY)
    recent = sorted(p for p, t in last.items() if t >= NOW - 7 * DAY)
    kw = ["geöffnet", "geoeffnet", "aufgemacht", "angesehen", "angeschaut", "opened", "offen"]
    for label, ps in [("opened in Ordo in the last 7 days", recent), ("opened in Ordo before, but not in the last 7 days", old)]:
        names = ", ".join(p.rsplit("/", 1)[1] for p in ps[:4])
        nodes.append({"id": f"#h{start + len(nodes)}", "kind": "log", "text": f"{label}: {len(ps)} files ({names}, ...)",
                      "keywords": sorted({bg.fold(k) for k in kw}), "members": ps, "selector": None, "folder": ""})
    return nodes


def validate_habits(history, tree):
    """Validation rule for habits: the last 3 moves of a pattern win over older counts; a target folder that no
    longer exists is followed to where its files went, else dropped."""
    out, by_pat = [], defaultdict(list)
    for h in history:
        if h["op"] == "move":
            by_pat[(h["from"], bg.name_tokens(h["name"])[0])].append(h)
    for pat, hs in by_pat.items():
        hs.sort(key=lambda h: h["t"])
        last = [h["to"] for h in hs[-3:]]
        if len(set(last)) == 1 and len(hs) > 3:
            hs = [h for h in hs if h["to"] == last[0]]
        out += hs
    return out + [h for h in history if h["op"] != "move"]


def relocate_folder(folder, before_tree, tree):
    """Where the files of a folder that is gone went (same size and time), if they stayed together."""
    ident = {(f["size"], f["mtime"]): f["path"] for f in tree.files}
    moved = Counter(ident[(f["size"], f["mtime"])].rsplit("/", 1)[0] for f in before_tree.by_folder.get(folder, [])
                    if (f["size"], f["mtime"]) in ident)
    if moved:
        new, n = moved.most_common(1)[0]
        if n * 2 >= len(before_tree.by_folder.get(folder, [])):
            return new
    return None


def memory(cond, case, before, now_files, results, history):
    """(memory block text, groups id -> paths, query text). before = what memory saw, now_files = the phone now."""
    validate = cond.endswith("+V")
    habits = cond.startswith("H")
    restart = case.get("restart", False)
    mem_tree = Tree(now_files if validate else before)
    before_tree, now_tree = Tree(before), Tree(now_files)
    hist = history
    if habits and validate:
        hist = validate_habits(history, now_tree)
        for h in hist:   # habit targets that are gone: follow their files
            if h["op"] == "move" and h["to"] not in now_tree.folders:
                h_to = relocate_folder(h["to"], before_tree, now_tree)
                if h_to:
                    h.update(to=h_to)
    graph = bg.build(mem_tree.files, hist if habits else [])
    if habits:
        graph["nodes"] += log_nodes(hist, 1)
        graph["df"] = dict(Counter(k for n in graph["nodes"] for k in n["keywords"]))
    ident_now = {(f["size"], f["mtime"]): f["path"] for f in now_files}

    def check(path):   # validation rule: a remembered file must still be the same file (size + time), else find it
        f = before_tree.by_path.get(path)
        if not validate or f is None:
            return path
        cur = now_tree.by_path.get(path)
        if cur and (cur["size"], cur["mtime"]) == (f["size"], f["mtime"]):
            return path
        return ident_now.get((f["size"], f["mtime"]))

    turns = []
    for i, (turn, res) in enumerate(zip(case["session"], results), 1):
        stale_turn = validate and turn["ago_min"] > SESSION_TTL_MIN
        paths = [check(p) for p in res]
        turns.append((i, turn, paths, stale_turn))
    groups = {}
    conv, saved = [], []
    for i, turn, paths, stale_turn in turns:
        ago = turn["ago_min"]
        when = f"{ago} min ago" if ago < 120 else datetime.fromtimestamp(NOW - ago * 60).strftime("yesterday %H:%M" if ago > 12 * 60 else "today %H:%M")
        keep = [p for p in paths if p]
        groups[f"#r{i}"] = keep
        items = []
        for k, p in enumerate(paths[:5], 1):
            if p:
                groups[f"#r{i}.{k}"] = [p]
                items.append(f"[#r{i}.{k}] {p.rsplit('/', 1)[1]}")
            else:
                items.append(f"(#r{i}.{k} no longer there)")
        plan = {k: v for k, v in turn["plan"].items() if v != DEFAULT.get(k)}
        verb = "moved" if turn["executed"] else ("showed" if turn["plan"]["action"] == "show" else "proposed, not done:")
        folders = sorted({p.rsplit("/", 1)[0] for p in keep})
        where = f" in {', '.join(folders[:2])}" if folders else ""
        more = f" (+{len(paths) - 5} more)" if len(paths) > 5 else ""
        line = (f"- [#r{i}] {when} \"{turn['text']}\" {json.dumps(plan, ensure_ascii=False, separators=(',', ':'))}: "
                f"{verb} {len(keep)} files{where}: {', '.join(items)}{more}")
        day_named = any(w in case["text"].lower() for w in ("gestern", "vorgestern", "yesterday", "letzte woche"))
        if validate and None in paths:
            groups.setdefault("_changed", set()).add(f"#r{i}")
        if validate and habits and stale_turn and not day_named:
            continue   # validation: an entry from an earlier day is no "vorhin"; it only shows when a day is named
        if restart or stale_turn:
            if habits:
                saved.append(line)   # the log survives a restart; RAM does not
            else:
                groups.pop(f"#r{i}")
                for k in range(1, 6):
                    groups.pop(f"#r{i}.{k}", None)
        else:
            conv.append(line)
    query = case["text"]
    if turns and (conv or saved):
        i, turn, paths, _ = turns[-1]
        query += " " + turn["text"] + " " + " ".join(p.rsplit("/", 1)[1] for p in paths[:3] if p)
    if cond == "S":
        conv, saved, nodes = [], [], []
        groups = {}
    else:
        nodes = bg.retrieve(graph, query, budget_chars=900, max_nodes=5)
    known = []
    for n in nodes:
        if n["members"]:
            groups[n["id"]] = n["members"]
        line = f"- {'[' + n['id'] + '] ' if n['members'] else ''}{n['text']}"
        (saved if n["kind"] in ("habit", "log") else known).append(line)
    block = ("Known about the user's files:\n" + ("\n".join(known) or "(none)") +
             "\nEarlier in this conversation:\n" + ("\n".join(conv) or "(none)") +
             "\nSaved from earlier sessions:\n" + ("\n".join(saved) or "(none)"))
    return block, groups


def stale_used(case, plan, res, groups, before, now_files, cond="M"):
    st = case.get("stale")
    if not st or plan.get("action", "none") == "none" or cond in ("A", "S"):   # without memory there is no stale fact
        return False
    now_paths = {f["path"] for f in now_files}
    before_by = {f["path"]: f for f in before}
    gid = plan.get("group", "")
    gpaths = groups.get(gid, []) if gid else []
    if st.get("paths_missing") and any(p not in now_paths for p in gpaths):
        return True
    if st.get("replaced"):
        changed = {f["path"] for f in now_files if "replaced" in f["tags"]}
        return bool(changed & (set(gpaths) | set(res["files"])))
    if "folder" in st:
        f = st["folder"].lower()
        now_t, old_t = Tree(now_files), Tree(before)
        src = (old_t.resolve(plan.get("source", ""))[0] or "").lower()
        dest = (res.get("dest") or "").lower()
        hit = dest == f if st.get("dest_only") else f in (src, dest) or any(p.lower().startswith(f + "/") for p in gpaths)
        return hit
    return False


# ---------------- run ----------------
def run(url, only, sets, out_name):
    base_files, base_history = load()
    out_file = HERE / out_name
    res_all = json.loads(out_file.read_text()) if out_file.exists() else {"conditions": {}}
    cs = [c for c in all_cases() if c["set"] in sets]
    for cond, (system, grammar) in prompts().items():
        if cond not in only:
            continue
        pre = bench.prefix(system)
        bench.post(url, "/completion", {"prompt": pre + bench.suffix("Hallo"), "grammar": grammar, "n_predict": 1,
                                        "temperature": 0, "cache_prompt": True})
        sys_tokens = bench.tokens(url, pre)
        rows = []
        for case in cs:
            if cond.endswith("+V") and case["set"] != "stale":
                continue
            before, now_files, results, history = build_state(case, base_files, base_history)
            tree = Tree(now_files)
            if cond == "A":
                block, groups, user = "", {}, case["text"]
            else:
                block, groups = memory(cond, case, before, now_files, results, history)
                user = f"{block}\nCommand: {case['text']}"
            cmd_tokens = bench.tokens(url, bench.suffix(user))
            started = time.time()
            try:
                r = bench.post(url, "/completion", {"prompt": pre + bench.suffix(user), "grammar": grammar,
                                                    "n_predict": 200 if cond == "A" else N_PREDICT, "temperature": 0, "cache_prompt": True})
                content, t = r["content"], r.get("timings", {})
            except Exception as e:   # context overflow on the server = would not fit the app's 2048 either
                content, t = "", {"error": str(e)[:80]}
            took = time.time() - started
            try:
                plan = {**DEFAULT, **json.loads(content)}
            except ValueError:
                plan = {**DEFAULT, "action": "none", "_raw": content[-80:]}
            res = execute(tree, plan, case["text"], groups, history if cond.startswith("H") else ())
            j = judge(case, plan, res, tree, history, results)
            j["stale_used"] = stale_used(case, plan, res, groups, before, now_files, cond)
            pred = t.get("predicted_n", 0)
            rows.append({"id": case["id"], "set": case["set"], "kind": case["kind"], "cmd": case["cmd"], "caps": case["caps"],
                         "text": case["text"], "restart": case.get("restart", False), "stale_kind": case.get("stale_kind"),
                         "plan": {k: v for k, v in plan.items() if v != DEFAULT.get(k)}, **j,
                         "cmd_tokens": cmd_tokens, "sys_tokens": sys_tokens, "predicted_n": pred, "gpu_s": round(took, 2),
                         "fits_2048": sys_tokens + cmd_tokens + pred <= CTX and "error" not in t,
                         "tab_warm_s": round(cmd_tokens / bench.TAB_S9_READ + pred / bench.TAB_S9_WRITE, 1),
                         "memory_chars": len(block)})
            mark = "OK " if j["ok"] else "-- "
            print(cond, mark, case["id"], case["text"][:60], "->", json.dumps(rows[-1]["plan"], ensure_ascii=False),
                  j["outcome"], j["danger"] or "", "STALE" if j["stale_used"] else "", flush=True)
        res_all["conditions"][cond] = {"system_tokens": sys_tokens, "rows": rows}
        out_file.write_text(json.dumps(res_all, ensure_ascii=False, indent=1), encoding="utf-8")


# ---------------- report ----------------
def report(name):
    res = json.loads((HERE / name).read_text(encoding="utf-8"))
    conds = [c for c in ["A", "S", "M", "H", "M+V", "H+V"] if c in res["conditions"]]
    med = lambda xs: sorted(xs)[len(xs) // 2] if xs else 0
    pct = lambda rows: f"{sum(r['ok'] for r in rows)}/{len(rows)}" if rows else "–"
    L = []
    for title, pick in [("Abgestimmter Satz (main, ohne Neustart)", lambda r: r["set"] == "main" and not r["restart"]),
                        ("Holdout (nie zum Abstimmen benutzt, ohne Neustart)", lambda r: r["set"] == "holdout" and not r["restart"])]:
        L += [f"### {title}", "", "| Bedingung | richtig | falsch verstanden | nichts/Rückfrage | gefährlich | Prompt-Token (Median) | "
              "Ausgabe-Token (Median) | GPU s (Median) | Tab S9 warm s geschätzt (Median/Max) | passt in 2048 |",
              "|---|---|---|---|---|---|---|---|---|---|"]
        for c in conds:
            rows = [r for r in res["conditions"][c]["rows"] if pick(r)]
            if not rows:
                continue
            d = sum(bool(r["danger"]) for r in rows)
            L.append(f"| {c} | {pct(rows)} | {sum(r['outcome'] == 'wrong' for r in rows)} | {sum(r['outcome'] == 'miss' for r in rows)} | "
                     f"{d} | {med([r['cmd_tokens'] for r in rows])} (+{res['conditions'][c]['system_tokens']} System) | "
                     f"{med([r['predicted_n'] for r in rows])} | {med([r['gpu_s'] for r in rows])} | "
                     f"{med([r['tab_warm_s'] for r in rows])}/{max(r['tab_warm_s'] for r in rows)} | "
                     f"{sum(r['fits_2048'] for r in rows)}/{len(rows)} |")
        L.append("")
    caps = ["today", "show", "recency", "content", "session", "correction", "habits"]
    L += ["### Nach Fähigkeit (main + holdout, ohne Neustart)", "", "| Bedingung | " + " | ".join(caps) + " |",
          "|---|" + "---|" * len(caps)]
    for c in conds:
        rows = [r for r in res["conditions"][c]["rows"] if r["set"] != "stale" and not r["restart"]]
        if rows:
            L.append(f"| {c} | " + " | ".join(pct([r for r in rows if k in r["caps"]]) for k in caps) + " |")
    L += ["", "### Folgefragen 16–19: gleiche Unterhaltung vs. nach App-Neustart", "",
          "| Bedingung | gleiche Unterhaltung | nach Neustart | gefährlich nach Neustart |", "|---|---|---|---|"]
    for c in conds:
        rows = [r for r in res["conditions"][c]["rows"] if (r["cmd"] in (16, 17, 18) or r["kind"] == "followup" and r["text"].startswith("Vorhin"))]
        if rows:
            a = [r for r in rows if not r["restart"]]
            b = [r for r in rows if r["restart"]]
            L.append(f"| {c} | {pct(a)} | {pct(b)} | {sum(bool(r['danger']) for r in b)} |")
    L += ["", "### Korrekturen", "", "| Bedingung | Korrektur richtig | falsche erste Deutung wieder benutzt |", "|---|---|---|"]
    for c in conds:
        rows = [r for r in res["conditions"][c]["rows"] if r["kind"] == "correction"]
        if rows:
            L.append(f"| {c} | {pct(rows)} | {sum(r['wrong_reused'] for r in rows)}/{len(rows)} |")
    L += ["", "### Veraltetes Gedächtnis (stale)", "", "| Bedingung | richtig | handelt auf veraltetem Fakt | gefährlich |", "|---|---|---|---|"]
    for c in conds:
        rows = [r for r in res["conditions"][c]["rows"] if r["set"] == "stale"]
        if rows:
            L.append(f"| {c} | {pct(rows)} | {sum(r['stale_used'] for r in rows)}/{len(rows)} | {sum(bool(r['danger']) for r in rows)} |")
    L += ["", "### Jeder Fall", "", "| Fall | Satz | " + " | ".join(conds) + " |", "|---|---|" + "---|" * len(conds)]
    by_id = {c: {r["id"]: r for r in res["conditions"][c]["rows"]} for c in conds}
    for r0 in res["conditions"][conds[-1] if "+V" not in conds[-1] else "M"]["rows"] + \
            [r for r in res["conditions"].get("M+V", {"rows": []})["rows"] if False]:
        pass
    ids = []
    for c in conds:
        for r in res["conditions"][c]["rows"]:
            if r["id"] not in ids:
                ids.append(r["id"])
    any_row = lambda i: next(by_id[c][i] for c in conds if i in by_id[c])
    for i in ids:
        r0 = any_row(i)
        label = f"{r0['set']}/{r0['kind']}" + (f" #{r0['cmd']}" if r0["cmd"] else "") + (" Neustart" if r0["restart"] else "") + \
            (f" {r0['stale_kind']}" if r0["stale_kind"] else "")
        cells = []
        for c in conds:
            r = by_id[c].get(i)
            if not r:
                cells.append("")
                continue
            m = {"ok": "ok", "miss": "·", "wrong": "falsch"}[r["outcome"]]
            if r["danger"]:
                m += " ⚠"
            if r.get("stale_used"):
                m += " alt"
            if r["ok"] and r["blocks"]:
                m += " (App sperrt)"
            cells.append(m)
        L.append(f"| {label} | {r0['text']} | " + " | ".join(cells) + " |")
    L += ["", "ok = richtig · „·“ = nichts getan oder Rückfrage · falsch = anderer Plan · ⚠ = gefährlich · alt = auf veraltetem "
          "Fakt gehandelt · (App sperrt) = Plan richtig, heutige CommandCheck-Regeln würden ihn ablehnen"]
    (HERE / name.replace(".json", ".md")).write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))


def rescore(name):
    base_files, base_history = load()
    res = json.loads((HERE / name).read_text(encoding="utf-8"))
    cases = {c["id"]: c for c in all_cases()}
    for cond, c in res["conditions"].items():
        for row in c["rows"]:
            case = cases[row["id"]]
            before, now_files, results, history = build_state(case, base_files, base_history)
            groups = {} if cond == "A" else memory(cond, case, before, now_files, results, history)[1]
            plan = {**DEFAULT, **row["plan"]}
            r = execute(Tree(now_files), plan, case["text"], groups, history if cond.startswith("H") else ())
            j = judge(case, plan, r, Tree(now_files), history, results)
            j["stale_used"] = stale_used(case, plan, r, groups, before, now_files, cond)
            row.update(j)
    (HERE / name).write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


def diff():
    """Shows that S and M differ only in the memory block (same system prompt, grammar, sampling)."""
    base_files, base_history = load()
    case = next(c for c in all_cases() if c["cmd"] == 17 and c["kind"] == "original" and not c.get("restart"))
    before, now_files, results, history = build_state(case, base_files, base_history)
    ps = prompts()
    assert ps["S"] == ps["M"] == ps["H"]
    for cond in ("S", "M"):
        block, _ = memory(cond, case, before, now_files, results, history)
        sha = lambda t: hashlib.sha256(t.encode()).hexdigest()[:12]
        print(f"===== {cond}: system prompt sha256 {sha(ps[cond][0])}, grammar sha256 {sha(ps[cond][1])}")
        print(f"{block}\nCommand: {case['text']}\n")


def selftest():
    files, history = load()
    tree = Tree(files)
    cs = all_cases()
    assert len({c["id"] for c in cs}) == len(cs)
    # every gold has a real, non-empty answer on the phone (except "none is right")
    for c in cs:
        before, now_files, results, hist = build_state(c, files, history)
        w = wanted(c["gold"], Tree(now_files), hist, results, c["text"])
        assert c["gold"].get("none_ok") or all(x for _, x in w), (c["id"], c["text"], w)
    # the gold sets mean what the case says
    g = lambda n: wanted(next(c for c in cs if c["cmd"] == n)["gold"], tree, history, [], "x")
    assert set(g(3)[0][1]) == {f["path"] for f in files if "versicherung" in f["tags"]}, g(3)
    assert set(g(2)[0][1]) == {f["path"] for f in files if "dl_gestern" in f["tags"] and f["path"].endswith(".jpg")}
    assert "Download/Beleg_MediaMarkt_2026-11-02.pdf" in g(8)[0][1] and "Download/Versicherung_Vergleich.xlsx" not in g(6)[0][1]
    assert {"Pictures/Wallpapers/wallpaper_3.jpg"} <= set(g(4)[0][1]) and not any("(1)" not in p and p.startswith("Download/wallpaper")
                                                                                   for p in g(4)[0][1])
    assert g(19)[0][1] == ["Documents/Mietvertrag.pdf"] and len(g(12)[0][1]) == 6 and len(g(11)[0][1]) >= 8
    # execution: show searches below the folder, actions only the top level, a group overrides the folder
    assert execute(tree, {**DEFAULT, "action": "show", "source": "WhatsApp"}, "x")["status"] == "ok"
    assert execute(tree, {**DEFAULT, "action": "delete", "source": ""}, "lösche")["status"] == "ask"
    r = execute(tree, {**DEFAULT, "action": "delete", "source": "Download", "extensions": ["apk"]}, "zeig apks")
    assert "no delete word" in r["blocks"]
    assert not said("lösch nichts Wichtiges", bench.DELETE_WORDS) and said("lösch die erste davon", bench.DELETE_WORDS)
    # S and M: same system prompt and grammar; S gets the empty block, M gets memory
    c17 = next(c for c in cs if c["cmd"] == 17 and not c.get("restart"))
    b, n, res, h = build_state(c17, files, history)
    s_block, s_groups = memory("S", c17, b, n, res, h)
    m_block, m_groups = memory("M", c17, b, n, res, h)
    assert s_block.count("(none)") == 3 and not s_groups and "#r1" in m_block and "Rechnung_Kueche" in m_block
    restart = {**c17, "restart": True}
    assert "(none)" in memory("M", restart, b, n, res, h)[0].split("Saved")[0].split("conversation:")[1]   # RAM gone
    assert "#r1" in memory("H", restart, b, n, res, h)[0].split("Saved from earlier sessions:")[1]          # log kept
    # stale: without validation the memory points at the old place, with it at the new one
    st = next(c for c in cs if c.get("stale_kind") == "renamed")
    b, n, res, h = build_state(st, files, history)
    assert memory("M", st, b, n, res, h)[1]["#r1.1"] == ["Download/Scan_20260305_1000.pdf"]
    assert memory("M+V", st, b, n, res, h)[1]["#r1.1"] == ["Download/Mietvertrag_Garage.pdf"]
    st = next(c for c in cs if c.get("stale_kind") == "yesterday")
    b, n, res, h = build_state(st, files, history)
    assert "#r1" in memory("M", st, b, n, res, h)[1] and "#r1" not in memory("M+V", st, b, n, res, h)[1]
    st = next(c for c in cs if c.get("stale_kind") == "habit_changed")
    b, n, res, h = build_state(st, files, history)
    hv = validate_habits(h, Tree(n))
    assert {x["to"] for x in hv if x["op"] == "move" and x["name"].startswith("Rechnung")} == {"Documents/Finanzen/Rechnungen"}
    st = next(c for c in cs if c.get("stale_kind") == "folder_gone")
    b, n, res, h = build_state(st, files, history)
    assert relocate_folder("Documents/Wohnung", Tree(b), Tree(n)) == "Documents/Miete"
    assert "show" in prompts()["S"][1] and prompts()["A"][1].count("show") == 0
    print("selftest ok:", len(cs), "cases")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    elif sys.argv[1] == "run":
        arg = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
        run(arg("--url", "http://127.0.0.1:8096"), arg("--only", "A,S,M,H,M+V,H+V").split(","),
            arg("--sets", "main,holdout,stale").split(","), arg("--out", "results-phase0.json"))
    elif sys.argv[1] == "report":
        report(sys.argv[2])
    elif sys.argv[1] == "rescore":
        rescore(sys.argv[2])
    elif sys.argv[1] == "diff":
        diff()
