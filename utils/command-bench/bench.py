#!/usr/bin/env python3
"""Measures how well a small local model turns a typed command into a file plan.

    bench.py <label> [--url http://127.0.0.1:8085] [--cases holdout.jsonl] [--out result.json]

Talks to an OpenAI-compatible server (llama-server). The answer is forced into SCHEMA, the same
contract the app will validate before it shows a preview. Self-check: bench.py --selftest
"""
import json
import pathlib
import sys
import time
import urllib.request

HERE = pathlib.Path(__file__).parent

SCHEMA = {
    "type": "object",
    "properties": {
        "action": {"enum": ["move", "copy", "delete", "organize", "none"]},
        "source": {"type": "string"},
        "destination": {"type": "string"},
        "kinds": {"type": "array", "items": {"enum": ["audio", "video", "image", "document", "archive"]}},
        "extensions": {"type": "array", "items": {"type": "string"}},
        "name_contains": {"type": "string"},
        "older_than": {"type": "integer"},
        "age_unit": {"enum": ["days", "weeks", "months", "years"]},
        "larger_than": {"type": "integer"},
        "size_unit": {"enum": ["mb", "gb"]},
        "group_by": {"enum": ["", "type", "month"]},
    },
    "required": ["action", "source", "destination", "kinds", "extensions", "name_contains",
                 "older_than", "age_unit", "larger_than", "size_unit", "group_by"],
    "additionalProperties": False,
}

# The model only copies numbers and units. The app does the arithmetic (see days() and megabytes()).
REST = '"older_than":%d,"age_unit":"%s","larger_than":%d,"size_unit":"%s","group_by":"%s"}'

SYSTEM = """You turn one command for a file manager into a JSON plan. The command is German or English.

Fields:
- action: move, copy, delete, organize (tidy up, sort into subfolders) or none.
  Use none for anything else: questions, greetings, rename, share, send, open, format, uninstall.
- source: the folder the files are in, as the user names it ("in X", "from X", "aus X", "im Ordner X").
  Use "." only when the user says "here", "this folder", "hier" or names no folder.
- destination: the target folder for move and copy, as the user names it. Otherwise "".
- extensions: file endings the user says, such as mp3 or pdf. Never add endings the user did not say. Otherwise [].
- kinds: audio, video, image, document or archive when the user says a kind (photos, Bilder, songs, Videos)
  and no ending. Otherwise [].
- name_contains: a word the file name must contain ("with X in the name", "mit X im Namen"). Otherwise "".
- older_than, age_unit: the age limit, number and unit as the user says them. Otherwise 0 and days.
- larger_than, size_unit: the size limit, number and unit as the user says them. Otherwise 0 and mb.
- group_by: for organize only: "month" when sorting by date or month, else "type". Otherwise "".

Examples:
Move all txt files from Notes to Old
{"action":"move","source":"Notes","destination":"Old","kinds":[],"extensions":["txt"],"name_contains":"",""" \
    + REST % (0, "days", 0, "mb", "") + """
Lösche hier alle Videos, die älter als 3 Wochen sind
{"action":"delete","source":".","destination":"","kinds":["video"],"extensions":[],"name_contains":"",""" \
    + REST % (3, "weeks", 0, "mb", "") + """
Lösche alle Bilder im Ordner Scans
{"action":"delete","source":"Scans","destination":"","kinds":["image"],"extensions":[],"name_contains":"",""" \
    + REST % (0, "days", 0, "mb", "") + """
Copy everything bigger than 4 GB from Podcasts to Stick
{"action":"copy","source":"Podcasts","destination":"Stick","kinds":[],"extensions":[],"name_contains":"",""" \
    + REST % (0, "days", 4, "gb", "") + """
Verschiebe alle Dateien mit Entwurf im Namen aus Projekte nach Fertig
{"action":"move","source":"Projekte","destination":"Fertig","kinds":[],"extensions":[],"name_contains":"Entwurf",""" \
    + REST % (0, "days", 0, "mb", "") + """
Räum den Ordner Hörbücher auf
{"action":"organize","source":"Hörbücher","destination":"","kinds":[],"extensions":[],"name_contains":"",""" \
    + REST % (0, "days", 0, "mb", "type") + """
Wie spät ist es?
{"action":"none","source":"","destination":"","kinds":[],"extensions":[],"name_contains":"",""" \
    + REST % (0, "days", 0, "mb", "")

# The app resolves spoken folder names the same way before it looks for the real folder.
ALIASES = {"downloads": "download", "musik": "music", "dokumente": "documents", "bilder": "pictures",
           "filme": "movies", "": ".", "hier": ".", "here": ".", "this folder": ".", "diesen ordner": ".",
           "diesem ordner": ".", "current folder": "."}


def folder(name):
    name = name.strip().strip("/").lower()
    for suffix in ("-ordner", " ordner", " folder"):
        if name.endswith(suffix):
            name = name[:-len(suffix)].strip()
    return ALIASES.get(name, name)


def near(a, b):
    return abs(a - b) <= 0.05 * max(a, b)


def days(plan):
    return plan.get("older_than", 0) * {"weeks": 7, "months": 30, "years": 365}.get(plan.get("age_unit"), 1)


def megabytes(plan):
    return plan.get("larger_than", 0) * (1000 if plan.get("size_unit") == "gb" else 1)


def matches(plan, gold):
    if plan.get("action") != gold["action"]:
        return False
    if gold["action"] == "none":
        return True
    if folder(plan.get("source", "")) != gold["source"]:
        return False
    if gold["action"] in ("move", "copy") and folder(plan.get("destination", "")) != gold["destination"]:
        return False
    extensions = {e.lower().lstrip(".") for e in plan.get("extensions", [])} - {""}
    if extensions != set(gold.get("extensions", [])):
        return False
    # Named endings are the stricter filter, so the app ignores kinds next to them.
    if not extensions and set(plan.get("kinds", [])) != set(gold.get("kinds", [])):
        return False
    if plan.get("name_contains", "").lower() != gold.get("name_contains", ""):
        return False
    if not near(days(plan), gold.get("older_than_days", 0)):
        return False
    if not near(megabytes(plan), gold.get("larger_than_mb", 0)):
        return False
    return gold["action"] != "organize" or plan.get("group_by") == gold["group_by"]


def ask(url, text):
    body = {
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": text}],
        "temperature": 0,
        "max_tokens": 200,
        "chat_template_kwargs": {"enable_thinking": False},
        "response_format": {"type": "json_schema", "json_schema": {"name": "plan", "schema": SCHEMA}},
    }
    request = urllib.request.Request(url + "/v1/chat/completions", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
    started = time.time()
    with urllib.request.urlopen(request, timeout=300) as response:
        answer = json.load(response)
    return answer["choices"][0]["message"]["content"], time.time() - started


def selftest():
    gold = {"action": "move", "source": "download", "destination": "music", "extensions": ["mp3"]}
    plan = {"action": "move", "source": "Download-Ordner", "destination": "Musik", "kinds": ["audio"],
            "extensions": [".MP3", ""], "name_contains": "", "older_than": 0, "larger_than": 0}
    assert matches(plan, gold)
    assert not matches({**plan, "action": "delete"}, gold)
    assert not matches({**plan, "destination": "Movies"}, gold)
    assert not matches({**plan, "older_than": 30}, gold)
    assert matches({"action": "delete", "source": "hier", "larger_than": 1, "size_unit": "gb"},
                   {"action": "delete", "source": ".", "larger_than_mb": 1000})
    assert not matches({"action": "delete", "source": "hier", "larger_than": 1, "size_unit": "mb"},
                       {"action": "delete", "source": ".", "larger_than_mb": 1000})
    assert matches({"action": "delete", "source": "x", "older_than": 6, "age_unit": "months"},
                   {"action": "delete", "source": "x", "older_than_days": 183})
    json.loads(SYSTEM.splitlines()[-1])
    assert matches({"action": "none", "source": "x"}, {"action": "none"})
    print("selftest ok")


def main():
    if "--selftest" in sys.argv:
        return selftest()
    label = sys.argv[1]
    url = sys.argv[sys.argv.index("--url") + 1] if "--url" in sys.argv else "http://127.0.0.1:8085"
    name = sys.argv[sys.argv.index("--cases") + 1] if "--cases" in sys.argv else "commands.jsonl"
    cases = [json.loads(line) for line in open(HERE / name, encoding="utf-8")]
    hits = wrong_delete = wrong_action = 0
    seconds = []
    misses = []
    for case in cases:
        golds = case["gold"] if isinstance(case["gold"], list) else [case["gold"]]
        raw, took = ask(url, case["text"])
        seconds.append(took)
        try:
            plan = json.loads(raw)
        except ValueError:
            plan = {}
        if any(matches(plan, gold) for gold in golds):
            hits += 1
            continue
        action = plan.get("action")
        # The two errors a preview has to catch: deleting unasked, acting on a non-command.
        wrong_delete += action == "delete" and golds[0]["action"] != "delete"
        wrong_action += golds[0]["action"] == "none" and action != "none"
        misses.append({"text": case["text"], "got": plan})
    seconds.sort()
    result = {"model": label, "cases": len(cases), "hits": hits, "wrong_delete": int(wrong_delete),
              "wrong_action": int(wrong_action), "median_s": round(seconds[len(seconds) // 2], 2),
              "max_s": round(seconds[-1], 2), "misses": misses}
    if "--out" in sys.argv:
        pathlib.Path(sys.argv[sys.argv.index("--out") + 1]).write_text(
            json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "misses"}))
    for miss in misses:
        print("  MISS", miss["text"], "->", json.dumps(miss["got"], ensure_ascii=False))


if __name__ == "__main__":
    main()
