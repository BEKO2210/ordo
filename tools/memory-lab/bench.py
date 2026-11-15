#!/usr/bin/env python3
"""Does a graph slice make Ordo's on-device model better? Three conditions, scored by running the plan.

    bench.py extract <CommandPlanner.kt>   -> prompt_today.json (system prompt + GBNF exactly as the app has them)
    bench.py run --url http://127.0.0.1:8096 [--only A,B,C] [--cases holdout.jsonl]   -> results-<cases>.json
    bench.py report results-<cases>.json   -> results-<cases>.md
    bench.py --selftest

A = today's app prompt (raw Qwen chat text + grammar, greedy, like ordo_llm.cpp)
B = A + fixed rule block in the system prompt + retrieved graph slice before the command (schema unchanged)
C = B + plan field "group": the id of a known file group, which the app expands to its member files
Scoring simulates the app: resolve folders, list the source's top level, apply the filters, compare file sets.
"""
import json
import pathlib
import re
import sys
import time
import urllib.request
from datetime import datetime

import build_graph as bg

HERE = pathlib.Path(__file__).parent
NOW = datetime(2026, 11, 15, 12, 0).timestamp()
TAB_S9_READ, TAB_S9_WRITE = 32.2, 10.5   # t/s, Qwen3.5-4B Q4_0, docs/KI-BEFEHLE.md
KINDS = {"audio": {"mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mid"},
         "video": {"mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "wmv"},
         "image": {"jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "svg"},
         "document": {"pdf", "doc", "docx", "odt", "txt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "csv", "md", "epub"},
         "archive": {"zip", "rar", "7z", "tar", "gz", "bz2", "xz"}}
ALIASES = {"download": "Download", "downloads": "Download", "musik": "Music", "music": "Music", "dokumente": "Documents",
           "documents": "Documents", "bilder": "Pictures", "pictures": "Pictures", "filme": "Movies", "movies": "Movies",
           "kamera": "DCIM/Camera", "camera": "DCIM/Camera", "dcim": "DCIM"}
HERE_WORDS = {"", ".", "hier", "here", "this folder", "diesen ordner", "diesem ordner", "current folder"}
DELETE_WORDS = ["lösch", "losch", "loesch", "entfern", "wirf", "schmeiß", "schmeiss", "delete", "remove", "erase"]

RULES_B = """

Known facts:
The command may come after a block "Known about the user's files:". Use a fact only when the command refers to it
(a habit such as "wie immer", "as usual", "where they belong"; a trip, place, day, sender or series of files).
Then copy source and destination exactly as the fact gives them, as full paths such as Documents/Bank, never the
user's own paraphrase of the folder. The user's words come first for the filter: when the command names a sender,
a name or a month (Telekom, Oktober), use that word as name_contains; otherwise take name_contains from the fact.
Ignore facts the command does not refer to. A question stays none.

Example:
Known about the user's files:
- habit: files with "Lohn" in the name are moved from Download to Documents/Lohn (5x)
Command: Leg die Lohnzettel ab wie immer
{"action":"move","source":"Download","destination":"Documents/Lohn","kinds":[],"extensions":[],"name_contains":"Lohn","older_than":0,"age_unit":"days","larger_than":0,"size_unit":"mb","group_by":""}"""

RULES_C = RULES_B.replace('"group_by":""}', '"group_by":"","group":""}') + """
- group: when the command means exactly one group from the facts marked with [#n], its id such as "#4";
  the app then takes all files of that group (source does not matter). When the command means only part of the
  group (only the scans, only the videos), also set kinds or name_contains for that part. Otherwise "".
Known about the user's files:
- [#8] Download, 03.02.2026: 6 files saved together (Mietvertrag_Garage.pdf, Scan_20260203)
Command: Lösche die Garagen-Unterlagen
{"action":"delete","source":"Download","destination":"","kinds":[],"extensions":[],"name_contains":"","older_than":0,"age_unit":"days","larger_than":0,"size_unit":"mb","group_by":"","group":"#8"}"""


def extract(kt_path):
    kt = pathlib.Path(kt_path).read_text(encoding="utf-8")
    grammar = re.search(r'const val GRAMMAR = """(.*?)"""', kt, re.S).group(1)
    system = re.search(r'private val SYSTEM = """(.*?)"""', kt, re.S).group(1)

    def example(m):
        a = [x.strip() for x in re.findall(r'"((?:[^"\\]|\\.)*)"|(\d+)', m.group(1)) for x in [x[0] or x[1]]]
        a = [x.replace('\\"', '"') for x in a]
        action, source, dest, kinds, exts, name, older, au, larger, su, gb = a
        return (f'{{"action":"{action}","source":"{source}","destination":"{dest}","kinds":[{kinds}],'
                f'"extensions":[{exts}],"name_contains":"{name}","older_than":{older},"age_unit":"{au}",'
                f'"larger_than":{larger},"size_unit":"{su}","group_by":"{gb}"}}')
    system = re.sub(r"\$\{example\((.*?)\)\}", example, system)
    json.loads(system.splitlines()[-1])
    (HERE / "prompt_today.json").write_text(json.dumps({"system": system, "grammar": grammar}, ensure_ascii=False, indent=1),
                                            encoding="utf-8")
    print("prompt_today.json written,", len(system), "chars")


def prompts():
    p = json.loads((HERE / "prompt_today.json").read_text(encoding="utf-8"))
    g_c = p["grammar"].replace('",\\"group_by\\":" groupby "}"', '",\\"group_by\\":" groupby ",\\"group\\":" string "}"')
    assert g_c != p["grammar"]
    return {"A": (p["system"], p["grammar"]), "B": (p["system"] + RULES_B, p["grammar"]),
            "C": (p["system"] + RULES_C, g_c)}


def prefix(system):
    return f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n"


def suffix(text):
    return text.replace("<|", "< |") + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"


def post(url, path, body):
    req = urllib.request.Request(url + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def tokens(url, text):
    return len(post(url, "/tokenize", {"content": text, "with_pieces": False})["tokens"])


# ---------- the app, simulated ----------
class Phone:
    def __init__(self, files):
        self.files = files
        self.by_folder = {}
        self.folders = set()
        for f in files:
            d = f["path"].rsplit("/", 1)[0]
            self.by_folder.setdefault(d, []).append(f)
            parts = d.split("/")
            self.folders.update("/".join(parts[:i]) for i in range(1, len(parts) + 1))

    def resolve(self, name):
        """(path, exists). Like CommandCheck.resolveAll but over the whole tree (also Android/, any depth)."""
        n = re.sub(r"^(den |dem |the )?(ordner|folder) ", "", name.strip().strip("/"), flags=re.I).strip()
        n = re.sub(r"[- ](ordner|folder)$", "", n, flags=re.I).strip()
        if n.lower() in HERE_WORDS:
            return None, False
        first, _, rest = n.partition("/")
        full = ALIASES.get(first.lower(), first) + ("/" + rest if rest else "")
        for f in self.folders:
            if f.lower() == full.lower():
                return f, True
        if "/" not in n:
            hits = sorted((f for f in self.folders if f.rsplit("/", 1)[-1].lower() == n.lower()), key=lambda f: f.count("/"))
            if hits:
                return hits[0], True
        return full, False

    def collect(self, plan, source=None, base=None):
        exts = {e.strip().lstrip(".").lower() for e in plan.get("extensions", [])} - {""}
        if not exts:
            exts = set().union(*[KINDS.get(k, set()) for k in plan.get("kinds", [])]) if plan.get("kinds") else set()
            if plan.get("kinds") and not exts:
                return set()
        days = plan.get("older_than", 0) * {"weeks": 7, "months": 30, "years": 365}.get(plan.get("age_unit"), 1)
        mb = plan.get("larger_than", 0) * (1000 if plan.get("size_unit") == "gb" else 1)
        pool = base if base is not None else self.by_folder.get(source, [])
        name = plan.get("name_contains", "").strip().lower()
        return {f["path"] for f in pool
                if (not exts or f["path"].rsplit(".", 1)[-1].lower() in exts)
                and (not name or name in f["path"].rsplit("/", 1)[-1].lower())
                and (not days or f["mtime"] < NOW - days * 86400)
                and (not mb or f["size"] > mb * 1_000_000)}


def expected(phone, gold):
    if "tag" in gold:
        return {f["path"] for f in phone.files if gold["tag"] in f["tags"] and gold.get("tag2", gold["tag"]) in f["tags"]}
    g = {"extensions": gold.get("extensions", []), "kinds": gold.get("kinds", []), "name_contains": gold.get("name_contains", ""),
         "older_than": gold.get("older_days", 0), "larger_than": gold.get("larger_mb", 0)}
    return phone.collect(g, gold["source"])


def score(phone, graph, text, plan, gold):
    """(correct, detail). Also notes what today's CommandCheck would block although the plan is right."""
    act = plan.get("action", "none")
    if gold["action"] == "none" or act == "none":
        return act == gold["action"], {}
    if act != gold["action"]:
        return False, {"why": "action"}
    group = plan.get("group", "")
    node = next((n for n in graph["nodes"] if n["id"] == group and n["members"]), None) if group else None
    if node:
        members = set(node["members"])
        got = phone.collect(plan, base=[f for f in phone.files if f["path"] in members])
        src = "group " + group
    else:
        src, _ = phone.resolve(plan.get("source", ""))
        got = phone.collect(plan, src) if src else set()
    want = expected(phone, gold)
    detail = {"source": src, "got": len(got), "want": len(want), "overlap": len(got & want)}
    ok = got == want and bool(want)
    if act in ("move", "copy"):
        dest, exists = phone.resolve(plan.get("destination", ""))
        detail["dest"] = dest
        ok = ok and dest is not None and dest.lower() in {d.lower() for d in gold["dest"]}
    if act == "organize":
        ok = ok and plan.get("group_by") == gold["group_by"]
    if act == "delete" and not any(w in text.lower() for w in DELETE_WORDS):
        detail["refused"] = "no delete word"
    blocked = [p for p in [src or ""] if p.startswith("Android/") or p.startswith("DCIM/Camera") or p.count("/") >= 3]
    if node:
        blocked = [p for p in members if p.startswith(("Android/", "DCIM/Camera"))][:1]
    if ok and blocked:
        detail["app_today_blocks"] = blocked[0].rsplit("/", 1)[0]
    return ok, detail


# ---------- R: the graph used by the app after the model, no extra prompt tokens ----------
NEW_FOLDER = re.compile(r"\b(einen|neuen|new|a) (neuen )?(ordner|folder)\b", re.I)


def selected(phone, graph, plan):
    node = next((n for n in graph["nodes"] if n["id"] == plan.get("group")), None)
    if node:
        return phone.collect(plan, base=[f for f in phone.files if f["path"] in set(node["members"])])
    src, ok = phone.resolve(plan.get("source", ""))
    return phone.collect(plan, src) if ok else set()


def folder_for(phone, words_text, text):
    """Existing folder a paraphrase means: "Gesundheitssachen" -> Documents/Gesundheit; a number in the sentence decides ties."""
    toks = [bg.fold(w) for w in bg.words(words_text) if len(w) >= 4 and w.lower() not in bg.STOP]
    def stems(folder):
        base = bg.fold(folder.rsplit("/", 1)[-1]).split(" ")[0]   # "Steuer 2025" -> steuer
        return {base, base.rstrip("n").rstrip("e")}               # "Bewerbungen" -> bewerbung
    hits = [f for f in phone.folders if any(t.startswith(s) for t in toks for s in stems(f) if len(s) >= 4)]
    numbers = set(re.findall(r"\d{4}", text))
    if len(hits) > 1 and numbers:
        hits = [f for f in hits if numbers & set(re.findall(r"\d{4}", f))]
    return hits[0] if len(hits) == 1 else None


def graph_resolve(plan, text, graph, phone):
    """Deterministic: the model keeps the user's words, the app maps them onto the graph. Only fills gaps."""
    p = dict(plan)
    if p.get("action") not in ("move", "copy", "delete", "organize"):
        return p
    nodes = bg.retrieve(graph, text)
    habit = next((n for n in nodes if n["kind"] == "habit" and n["selector"]), None)
    _, src_ok = phone.resolve(p.get("source", ""))
    if not src_ok and not p.get("group"):
        group = next((n for n in nodes if n["kind"] in ("event", "series", "duplicates")), None)
        if group:
            p["group"] = group["id"]
        elif habit:
            p["source"] = habit["selector"]["source"]
            p["name_contains"] = p.get("name_contains") or habit["selector"]["name_contains"]
    if src_ok and not p.get("group") and not any(p.get(k) for k in ("extensions", "kinds", "name_contains", "older_than", "larger_than")):
        dup = nodes[0] if nodes and nodes[0]["kind"] == "duplicates" else None   # "die doppelten" means the copies, never the folder
        if dup:
            p["group"] = dup["id"]
    # narrow by a word of the sentence that some, not all, of the chosen files carry (Telekom, Scans, Riester)
    files = selected(phone, graph, p)
    for w in bg.words(text):
        stem = bg.fold(w)[:-1] if bg.fold(w).endswith("s") else bg.fold(w)
        if len(stem) < 4 or w.lower() in bg.STOP:
            continue
        some = {f for f in files if stem in bg.fold(f.rsplit("/", 1)[-1])}
        if some and some != files:
            p["name_contains"] = stem   # shortcut: replaces a broader name filter; two name filters would need a schema field
            files = some
    if p["action"] in ("move", "copy"):
        dest, ok = phone.resolve(p.get("destination", ""))
        if not ok and not (NEW_FOLDER.search(text) and p.get("destination")):
            mapped = folder_for(phone, p.get("destination", ""), text) if p.get("destination") else None
            if mapped:
                p["destination"] = mapped
            elif habit and habit["selector"].get("destination"):
                p["destination"] = habit["selector"]["destination"]
    return p


def resolve_offline(name):
    files, history = bg.load()
    graph, phone = bg.build(files, history), Phone(files)
    cases = {json.loads(l)["text"]: json.loads(l) for f in ("commands.jsonl", "holdout.jsonl", "holdout2.jsonl")
             for l in open(HERE / f, encoding="utf-8")}
    res = json.loads((HERE / name).read_text(encoding="utf-8"))
    for cond in [c for c in ("A", "C") if c in res["conditions"]]:
        rows = []
        for r in res["conditions"][cond]["rows"]:
            plan = graph_resolve(r["plan"], r["text"], graph, phone)
            ok, detail = score(phone, graph, r["text"], plan, cases[r["text"]]["gold"])
            rows.append({**r, "plan": plan, "ok": ok, "detail": detail})
        res["conditions"][cond + "+R"] = {**res["conditions"][cond], "rows": rows}
    (HERE / name).write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


# ---------- run ----------
def run(url, only, cases_name):
    files, history = bg.load()
    graph = bg.build(files, history)
    cases = [json.loads(l) for l in open(HERE / cases_name, encoding="utf-8")]
    out_file = HERE / f"results-{cases_name.split('.')[0]}.json"
    phone = Phone(files)
    out = {"conditions": {}, "server": url}
    for cond, (system, grammar) in prompts().items():
        if cond not in only:
            continue
        pre = prefix(system)
        post(url, "/completion", {"prompt": pre + suffix("Hallo"), "grammar": grammar, "n_predict": 1, "temperature": 0,
                                  "cache_prompt": True})   # warm the cache like CommandEngine.prepare
        rows = []
        for case in cases:
            nodes = bg.retrieve(graph, case["text"]) if cond != "A" else []
            slice_text = bg.render(nodes, with_ids=cond == "C")
            user = f"{slice_text}\nCommand: {case['text']}" if slice_text else (case["text"] if cond == "A" else f"Command: {case['text']}")
            started = time.time()
            r = post(url, "/completion", {"prompt": pre + suffix(user), "grammar": grammar, "n_predict": 200,
                                          "temperature": 0, "cache_prompt": True})
            took = time.time() - started
            try:
                plan = json.loads(r["content"])
            except ValueError:
                plan = {"action": "none", "_raw": r["content"]}
            ok, detail = score(phone, graph, case["text"], plan, case["gold"])
            t = r.get("timings", {})
            rows.append({"text": case["text"], "type": case["type"], "ok": ok, "plan": plan, "detail": detail,
                         "slice_tokens": tokens(url, slice_text) if slice_text else 0,
                         "cmd_tokens": tokens(url, suffix(user)), "nodes": [n["id"] for n in nodes],
                         "prompt_n": t.get("prompt_n"), "predicted_n": t.get("predicted_n"), "seconds": round(took, 2),
                         "phone_s": round(tokens(url, suffix(user)) / TAB_S9_READ + t.get("predicted_n", 0) / TAB_S9_WRITE, 1),
                         "gold_action": case["gold"]["action"]})
            print(cond, "OK " if ok else "-- ", case["text"], "->", json.dumps(plan, ensure_ascii=False), detail, flush=True)
        out["conditions"][cond] = {"system_tokens": tokens(url, pre), "rows": rows}
    old = json.loads(out_file.read_text()) if out_file.exists() else {"conditions": {}}
    old["conditions"].update(out["conditions"])
    out_file.write_text(json.dumps(old, ensure_ascii=False, indent=1), encoding="utf-8")


def report(name):
    res = json.loads((HERE / name).read_text(encoding="utf-8"))
    med = lambda xs: sorted(xs)[len(xs) // 2] if xs else 0
    lines = ["| Bedingung | Gesamt | Persönlich | Allgemein | gefährlich* | Slice-Token Median/Max | "
             "Token je Befehl ohne System (Median) | System-Token | GPU s (Median) | Tab S9 s geschätzt (Median) |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for cond, c in sorted(res["conditions"].items()):
        rows = c["rows"]
        p = [r for r in rows if r["type"] == "personal"]
        g = [r for r in rows if r["type"] == "generic"]
        bad = sum((r["plan"].get("action") == "delete" and r["gold_action"] != "delete") or
                  (r["gold_action"] == "none" and r["plan"].get("action") != "none") or
                  (r["plan"].get("action") == "delete" and r["detail"].get("got", 0) > r["detail"].get("overlap", 0))
                  for r in rows)
        sl = [r["slice_tokens"] for r in rows]
        lines.append(f"| {cond} | {sum(r['ok'] for r in rows)}/{len(rows)} | {sum(r['ok'] for r in p)}/{len(p)} | "
                     f"{sum(r['ok'] for r in g)}/{len(g)} | {bad} | {med(sl)}/{max(sl)} | {med([r['cmd_tokens'] for r in rows])} | "
                     f"{c['system_tokens']} | {med([r['seconds'] for r in rows])} | {med([r['phone_s'] for r in rows])} |")
    lines += ["", "*gefährlich = Löschen ohne Lösch-Auftrag, Löschen von mehr Dateien als gemeint, oder Aktion bei einer Frage.", "",
              "| Befehl | " + " | ".join(sorted(res["conditions"])) + " |", "|---|" + "---|" * len(res["conditions"])]
    conds = sorted(res["conditions"])
    for i, r in enumerate(res["conditions"][conds[0]]["rows"]):
        cells = []
        for cond in conds:
            row = res["conditions"][cond]["rows"][i]
            mark = "ok" if row["ok"] else "–"
            if row["ok"] and "app_today_blocks" in row["detail"]:
                mark = "ok (App sperrt)"
            cells.append(mark)
        lines.append(f"| {'P' if r['type'] == 'personal' else 'A'}: {r['text']} | " + " | ".join(cells) + " |")
    (HERE / name.replace(".json", ".md")).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def selftest():
    files, history = bg.load()
    phone = Phone(files)
    graph = bg.build(files, history)
    assert phone.resolve("Musik") == ("Music", True)
    assert phone.resolve("Bewerbungen") == ("Documents/Bewerbungen", True)
    assert phone.resolve("WhatsApp Video")[0].endswith("Media/WhatsApp Video")
    assert phone.resolve("den Ordner Auto") == ("Auto", False)
    assert phone.resolve("Download-Ordner") == ("Download", True)
    gold = {"action": "move", "tag": "rechnung", "dest": ["Documents/Rechnungen"]}
    plan = {"action": "move", "source": "Download", "destination": "Documents/Rechnungen", "name_contains": "Rechnung"}
    assert score(phone, graph, "x", plan, gold)[0]
    assert not score(phone, graph, "x", {**plan, "destination": "Rechnungsordner"}, gold)[0]
    assert not score(phone, graph, "x", {**plan, "extensions": ["jpg"]}, gold)[0]
    car = next(n for n in graph["nodes"] if n["kind"] == "series")
    assert score(phone, graph, "x", {"action": "move", "source": "", "destination": "Auto", "group": car["id"]},
                 {"action": "move", "tag": "autokauf", "dest": ["Auto"]})[0]
    for case in [json.loads(l) for n in ("commands.jsonl", "holdout.jsonl", "holdout2.jsonl") for l in open(HERE / n, encoding="utf-8")]:
        assert case["gold"]["action"] == "none" or expected(phone, case["gold"]), case["text"]
    assert folder_for(phone, "meine Gesundheitssachen", "x") == "Documents/Gesundheit"
    assert folder_for(phone, "Steuerordner", "für 2025") == "Documents/Steuer 2025"
    assert folder_for(phone, "Auto", "x") is None
    print("selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    elif sys.argv[1] == "extract":
        extract(sys.argv[2])
    elif sys.argv[1] == "run":
        url = sys.argv[sys.argv.index("--url") + 1]
        only = sys.argv[sys.argv.index("--only") + 1].split(",") if "--only" in sys.argv else ["A", "B", "C"]
        run(url, only, sys.argv[sys.argv.index("--cases") + 1] if "--cases" in sys.argv else "commands.jsonl")
    elif sys.argv[1] == "resolve":
        resolve_offline(sys.argv[2])
    elif sys.argv[1] == "report":
        report(sys.argv[2])
