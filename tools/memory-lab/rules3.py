#!/usr/bin/env python3
"""Nachtraining (LoRA): the model writes the whole plan, the code is only a safety net.

Schema L (all fields optional except action; the model leaves out what the command does not say):
    action      show | move | copy | delete | organize | none | ask
    source, destination   folder as the user names it
    kinds       image | video | audio | document | archive;  extensions: ["pdf", ...]
    name_contains, content_contains   words in the file name / in the file
    exclude     what the user excludes ("außer ..."): a kind, an extension or a word
    time, size  the time / size condition in a short canonical German form the code reads
                ("letzte Woche", "älter als 2 Monate", "im März 2025", "seit 3.10.", "über 100 MB")
    sort        size | newest | oldest | smallest;  limit: how many
    group_by    month | type (organize)

decide(text, raw, tree, flags) -> same dict as rules2.decide.
flags = "" -> model alone: only what the model wrote, its time/size spans read by the code, no fallbacks, no checks.
flags "abcd" = safety net (rules2 mechanisms, but they fill gaps and veto; they never overwrite what the model said):
    a  coverage guard: a change whose content words are not all used by the plan is asked back
    b  time/size: the model's span is read by the code; if the model wrote none, the code reads the command;
       a change with a vague time/size word ("alte", "große") or a span nobody can read is asked back
    c  exclusion: read from the command when the model wrote none
    d  folders against the real tree: the model's folder if it exists, else the folder the code reads; a change
       without an existing source, without a target, or with several candidates is asked back
always (as in rules2): memory refusal, action safety per clause (code never creates a delete), storage questions.
"""
import re
from datetime import datetime

import bench
import rules2 as R
from p0_cases import DEFAULT

NET = frozenset("abcd")
# words that never name files: fillers, particles, adverbs of place/time-of-speaking (rules2.STOP has the rest)
GUARD_IGNORE = set("""everything anything something nothing now up over down out back away right asap quickly please pls plz
los sofort schnell gleich nochmal endlich bitteschön rüber drüber rein raus hinein heraus dahin dorthin daraus dort
datum date month monat monate monaten typ typen dateityp dateitypen art arten type types kind""".split())
KINDS = ("image", "video", "audio", "document", "archive")
ASK_MODEL = "Was genau meinst du? Sag mir bitte, welche Dateien und welcher Ordner gemeint sind."
FIELDS_L = ["action", "source", "destination", "kinds", "extensions", "name_contains", "content_contains", "exclude", "time",
            "size", "sort", "limit", "group_by"]


def grammar():
    """GBNF: fixed field order, every field but action optional, no empty values (the model leaves a field out)."""
    rule = {"source": "str", "destination": "str", "kinds": "kinds", "extensions": "strs", "name_contains": "str",
            "content_contains": "str", "exclude": "strs", "time": "str", "size": "str", "sort": "sort", "limit": "int",
            "group_by": "groupby"}
    parts = ['"{\\"action\\":" action']
    parts += [f'( ",\\"{f}\\":" {rule[f]} )?' for f in FIELDS_L[1:]]
    return ("root ::= " + " ".join(parts) + ' "}"\n'
            'action ::= "\\"show\\"" | "\\"move\\"" | "\\"copy\\"" | "\\"delete\\"" | "\\"organize\\"" | "\\"none\\"" | "\\"ask\\""\n'
            'kinds ::= "[" kind ( "," kind ){0,4} "]"\n'
            'kind ::= "\\"image\\"" | "\\"video\\"" | "\\"audio\\"" | "\\"document\\"" | "\\"archive\\""\n'
            'strs ::= "[" str ( "," str ){0,7} "]"\n'
            'sort ::= "\\"size\\"" | "\\"newest\\"" | "\\"oldest\\"" | "\\"smallest\\""\n'
            'groupby ::= "\\"type\\"" | "\\"month\\""\n'
            'str ::= "\\"" char{1,60} "\\""\n'
            'char ::= [^"\\\\\\x7F\\x00-\\x1F] | "\\\\" ["\\\\/nt]\n'
            'int ::= [1-9] [0-9]{0,3}')


SYSTEM = """You turn one command for an Android file manager into a JSON plan. The command is German or English, typed or dictated.
Write only the fields the command says, in this order:
action: show (find, list, any question about the user's files), move, copy, delete (only if the user says so), organize (sort a folder into subfolders), none (chat, or something a file manager cannot do: zip, rename, send, convert, print, edit, restore), ask (unclear what or where).
source, destination: folder names as the user says them.
kinds: image, video, audio, document, archive. extensions: file endings.
name_contains: a name or topic in the file name. content_contains: a word inside the file.
exclude: kinds, endings or words the user excludes.
time, size: the condition in short German ("gestern", "letzte Woche", "älter als 2 Monate", "im März 2025", "über 100 MB").
sort: size, newest, oldest, smallest; limit: how many.
group_by: month or type."""


# With the adapter the task lives in the weights: a short prompt is enough (and ~4x faster to read on the phone and to train).
SYSTEM_LORA = ("You are Ordo's command reader. Turn one file manager command (German or English, typed or dictated) into a JSON plan "
               "with the fields action, source, destination, kinds, extensions, name_contains, content_contains, exclude, time, size, "
               "sort, limit, group_by. Leave out what the command does not say.")


def plan_json(p):
    """Target text: compact JSON, field order of the grammar, empty fields left out."""
    import json
    out = {}
    for f in FIELDS_L:
        v = p.get(f)
        if f == "action" or v not in (None, "", [], 0):
            out[f] = v
    return json.dumps(out, ensure_ascii=False, separators=(",", ":"))


def read_span(s, fn, today, now):
    """The code reads a span the model wrote. -> (value, fully_read)."""
    w = R.Work(R.norm(s))
    v = fn(w, today, now) if fn is R.parse_time else fn(w)
    rest = [x for x in w.tokens() if x not in R.STOP and x not in R.GENERIC]
    return v, bool(any(v)) and not rest


def exclusion(items):
    ek, ee, et = [], [], []
    for x in items or []:
        x = x.strip().lower().lstrip(".")
        if not x:
            continue
        if x in KINDS:
            ek.append(x)
        elif x in R.TYPES and (R.TYPES[x][0] or R.TYPES[x][1]):
            ek += R.TYPES[x][0]
            ee += R.TYPES[x][1]
        elif re.fullmatch(r"[a-z0-9]{2,5}", x) and any(x in k for k in bench.KINDS.values()) or x in R.EXT_WORDS or x == "apk":
            ee.append(x)
        else:
            et.append(x)
    return ek, ee, et


def decide(text, raw, tree, flags=NET, today=None, now=None):
    now = now or datetime.fromtimestamp(bench.NOW)
    today = today or now.date()
    folders = sorted(tree.folders)
    net = bool(flags)
    out = {"status": "plan", "plan": None, "reason": "", "say": "", "warn": [], "unaccounted": []}
    ask = lambda reason, *a: {**out, "status": "ask", "reason": reason, "say": (R.ASK.get(reason) or ASK_MODEL).format(*a)}
    raw = {**(raw or {})}
    act = raw.get("action") or "none"
    t, leaves = R.prepare(text, folders)
    if net:
        for pat in R.MEMORY:
            if re.search(pat, t):
                return {**out, "status": "refuse", "reason": "memory", "say": R.MEMORY_SAY}
    if act == "ask":
        return ask("model")
    plan = {**DEFAULT, "action": act}
    # code readings of the command: they consume words for the guard and fill what the model left out
    w = R.Work(t)
    w.take(R.STORAGE)   # storage questions: the model decides (none/show); the code no longer turns them into a list
    ek, ee, et = R.parse_exclusion(w, R.is_verb) if "c" in flags else ([], [], [])
    group = next((g for g, pat in R.GROUP.items() if w.take(rf"\b(nach|by|per) ({pat})\b")), "") if net else ""
    src, dst, problems, bare = R.parse_folders(w, folders, leaves) if "d" in flags else ([], [], [], [])
    c_time = R.parse_time(w, today, now) if "b" in flags else ("", "")
    c_size = R.parse_size(w) if "b" in flags else (0, 0)
    c_sort, c_limit = R.parse_sort(w) if net else ("", 0)
    c_kinds, c_exts = R.parse_types(w) if net else ([], [])
    if net:
        R.parse_count(w)   # "die drei Bewerbungen": a count is no filter word
    if net:
        w.take(r"\b(doppelt\w*|duplikat\w*|mehrfach\w*|duplicates?|dupes?|identische[nr]?)\b")
    w.take(r"\b(neue[nrs]?|new|eigene[nrs]?) (ziel)?(ordner|folder)\b")   # "in einen neuen Ordner" is a target, not a time
    sub = bool(w.take(r"\b(unterordner\w*|subfolders?|sub folders?)\b"))
    vague_t = re.search(R.VAGUE_T, w.s) if net else None
    vague_s = re.search(R.VAGUE_S, w.s) if net else None
    w.take(R.VAGUE_T)
    w.take(R.VAGUE_S)
    if net:   # action safety per clause (question, subordinate clause, negation); code never creates a delete
        said = R.actions_said(text)
        if act in R.DESTRUCTIVE + ("copy",) and act not in said:
            alt = [a for a in said if a in ("move", "copy", "organize")]
            act = alt[0] if len(alt) == 1 else "show"
        if act == "organize" and "move" in said and (raw.get("destination") or any(dst)) and not sub:
            act = "move"
    plan["action"] = act
    if act == "none":
        return {**out, "plan": plan}
    changes = act in R.DESTRUCTIVE + ("copy",)
    # types, sort, exclusion, grouping: the model's, else the code's
    kinds = [k for k in raw.get("kinds") or [] if k in KINDS]
    exts = sorted({e.strip().lower().lstrip(".") for e in raw.get("extensions") or [] if re.fullmatch(r"\.?[A-Za-z0-9]{1,6}", e.strip())})
    if not kinds and not exts:
        kinds, exts = c_kinds, c_exts
    plan.update(kinds=[] if exts else kinds, extensions=exts)
    sort = raw.get("sort") or ""
    limit = int(raw.get("limit") or 0)
    if not sort and net:
        sort, limit = c_sort, c_limit
    if limit and not sort:
        sort = "newest"
    plan.update(sort=sort, limit=limit if sort else 0)
    mk, me, mt = exclusion(raw.get("exclude"))
    if not (mk or me or mt):
        mk, me, mt = ek, ee, et
    plan.update(exclude_kinds=mk, exclude_extensions=me, exclude_terms=mt)
    plan["group_by"] = raw.get("group_by") or group or ("type" if act == "organize" else "")
    # b: time and size from the model's span; the code's reading of the command when the model wrote none
    span_bad = []
    for key, fn, fields, c_val in (("time", R.parse_time, ("date_from", "date_to"), c_time),
                                   ("size", R.parse_size, ("min_bytes", "max_bytes"), c_size)):
        s = (raw.get(key) or "").strip()
        v, full = read_span(s, fn, today, now) if s else (("", "") if key == "time" else (0, 0), False)
        if s and not any(v):
            span_bad.append(s)
        if not any(v) and "b" in flags:
            v = c_val
        plan[fields[0]], plan[fields[1]] = v
    if "b" in flags and changes:
        if span_bad:
            return {**ask("vague_time", span_bad[0]), "plan": plan}
        if vague_s and not (plan["min_bytes"] or plan["max_bytes"]) and not raw.get("size"):
            return {**ask("vague_size", vague_s.group(1)), "plan": plan}
        if vague_t and re.fullmatch(r"neue?[nrs]?|new", vague_t.group(1)) and act in ("move", "copy") and raw.get("destination"):
            vague_t = None   # "into a new folder called X": the target is new, not the files
        if vague_t and not (plan["date_from"] or plan["date_to"]) and not raw.get("time"):
            return {**ask("vague_time", vague_t.group(1)), "plan": plan}
    if net and not changes:
        if vague_s and not (plan["min_bytes"] or plan["max_bytes"]) and not plan["sort"]:
            plan["sort"] = "smallest" if re.match(r"klein|small|tiny", vague_s.group(1)) else "size"
        if vague_t and not (plan["date_from"] or plan["date_to"]) and not plan["sort"]:
            plan["sort"] = "oldest" if re.match(r"alt|old|ält", vague_t.group(1)) else "newest"
    # d: folders
    m_src = (raw.get("source") or "").strip()
    m_dst = (raw.get("destination") or "").strip()
    if "d" in flags:
        if problems and changes:
            return {**ask(problems[0][0], problems[0][1]), "plan": plan}
        model_src, how = R.resolve_folder(m_src, folders, leaves) if m_src not in ("", ".") else (None, "missing")
        if not model_src and not changes and m_src.lower().startswith("whatsapp"):   # shows only: a change never gets a guessed folder
            kind = "voice" if "opus" in exts or re.search(R.VOICE + r"|voice|sprach", m_src.lower()) else (kinds or ["image"])[0]
            model_src = R.messenger_folder(kind, folders)
        if how == "ambiguous":
            model_src = None
        narrowest = lambda ps: sorted({p for p in ps if not any(q != p and q.startswith(p + "/") for q in ps)})
        strong = sorted({p for p, s_ in src if s_ == 2})
        weak = sorted({p for p, s_ in src if s_ == 1})
        if model_src:
            cand = [model_src]
        else:
            # a gap is filled from the folder the words name
            cand = narrowest(strong) if strong else narrowest(weak) if weak and not m_src else []
        if len(cand) > 1:
            if changes:
                return {**ask("ambiguous_folder", ", ".join(c.rsplit("/", 1)[-1] for c in cand)), "plan": plan}
            cand = []
        plan["source"] = cand[0] if cand else (m_src if m_src not in (".", "hier") and not changes else "")
        if not plan["source"] and changes:
            if m_src and m_src not in (".", "hier"):
                return {**ask("unknown_folder", m_src), "plan": plan}
            return {**ask("no_source"), "plan": plan}
        if act in ("move", "copy"):
            if m_dst:
                rp, how = R.resolve_folder(m_dst, folders, leaves)
                plan["destination"] = rp if isinstance(rp, str) else m_dst
            else:
                have = sorted({x for x in dst if x and x in tree.folders})
                new_ = [x for x in dst if x and x not in tree.folders]
                if len(have) > 1:
                    return {**ask("ambiguous_folder", ", ".join(h.rsplit("/", 1)[-1] for h in have)), "plan": plan}
                plan["destination"] = (have or new_ or [""])[-1]
            if not plan["destination"] or plan["destination"] == plan["source"]:
                return {**ask("no_dest"), "plan": plan}
        for path, a, txt in bare:   # a folder name that is only the object ("die Rechnungen aus Download") is a word again
            if path != plan["source"]:
                w.s = w.s[:a] + txt + w.s[a + len(txt):]
    else:   # the app still reads the model's folder names against the tree (exact, standard folder, unique fuzzy)
        rs, _ = R.resolve_folder(m_src, folders, leaves) if m_src not in ("", ".") else (None, "")
        rd, _ = R.resolve_folder(m_dst, folders, leaves) if m_dst else (None, "")
        plan["source"] = rs if isinstance(rs, str) else m_src
        plan["destination"] = rd if isinstance(rd, str) else m_dst
    # name / content filters: the model's words
    name = [x for x in re.split(r"[\s_,;/]+", raw.get("name_contains") or "") if len(x) >= 2]
    content = [x for x in re.split(r"[\s_,;/]+", raw.get("content_contains") or "") if len(x) >= 2]
    plan["name_terms" if changes else "text_terms"] = name
    if content:
        plan["text_terms"] = plan.get("text_terms", []) + content
    # a: coverage guard, changes only: every content word left must be used by a model field
    if "a" in flags and changes:
        neg = R.negated_words(text)
        keep = lambda x: (x not in R.STOP and x not in GUARD_IGNORE and x not in R.GENERIC and not R.is_verb(x) and x not in R.NEGATOR and
                          x not in R.UNIT and len(x) >= 2 and not re.fullmatch(r"\d{1,2}", x) and x not in neg)
        # target folder names are not filters (the source names where the files are): a word the code did not read as a folder must be used by a filter field
        used = " ".join(str(raw.get(f) or "") for f in ("name_contains", "content_contains", "time", "size", "source"))
        used += " " + " ".join(raw.get("exclude") or [])
        used_w = [x for x in re.findall(r"[a-zäöüß0-9]+", R.split_compounds(R.norm(used))) if len(x) >= 2]
        left = [x for x in w.tokens() if keep(x)]
        unacc = [x for x in left if not any(R.related(x, u) or x == u for u in used_w)]
        out["unaccounted"] = unacc
        if unacc:
            return {**ask("uncovered", " ".join(unacc)), "plan": plan}
    out["plan"] = plan
    return out


def as_scored(d):
    return R.as_scored(d)


def selftest():
    import json
    import phase0 as p0
    files, _ = p0.load()
    tree = p0.Tree(files)
    g = grammar()
    assert g.startswith("root ::=") and '"\\"ask\\""' in g
    j = plan_json({"action": "show", "kinds": ["image"], "time": "gestern", "limit": 0})
    assert j == '{"action":"show","kinds":["image"],"time":"gestern"}', j
    # model alone: the span is read, nothing else is added
    d = decide("Zeig die Fotos von gestern", {"action": "show", "kinds": ["image"], "time": "gestern"}, tree, "")
    assert d["plan"]["date_from"] == "2026-11-14" and d["plan"]["date_to"] == "2026-11-15", d["plan"]
    # net fills a time the model forgot, and asks back on a change with a word nobody used
    d = decide("Zeig die Fotos von gestern", {"action": "show", "kinds": ["image"]}, tree)
    assert d["plan"]["date_from"] == "2026-11-14", d
    d = decide("Lösch die Telekom PDFs im Download", {"action": "delete", "source": "Download", "extensions": ["pdf"]}, tree)
    assert d["status"] == "ask" and d["reason"] == "uncovered", d
    d = decide("Lösch die Telekom PDFs im Download", {"action": "delete", "source": "Download", "extensions": ["pdf"],
                                                       "name_contains": "Telekom"}, tree)
    assert d["status"] == "plan" and d["plan"]["name_terms"] == ["Telekom"], d
    # code never creates a delete; a question stays a show
    d = decide("Kann ich die alten Videos löschen?", {"action": "delete", "kinds": ["video"]}, tree)
    assert d["plan"]["action"] == "show", d
    d = decide("Kopier alle Fotos aus der Kamera in einen neuen Ordner Ausflug", {"action": "copy", "source": "Kamera",
                                                                                 "destination": "Ausflug", "kinds": ["image"]}, tree)
    assert d["status"] == "plan" and d["plan"]["destination"] == "Ausflug", d   # "neuen Ordner" is no vague time word
    d = decide("Schieb alle Rechnungen aus dem Download in einen neuen Ordner Rechnungen",
               {"action": "move", "source": "Download", "destination": "Rechnungen"}, tree)
    assert d["status"] == "ask" and d["reason"] == "uncovered", d   # the object word is no folder: the plan must filter it
    d = decide("mach das schöner", {"action": "ask"}, tree)
    assert d["status"] == "ask"
    print("rules3 selftest ok;", len(SYSTEM), "chars prompt;", json.dumps(g)[:0])


if __name__ == "__main__":
    selftest()
