#!/usr/bin/env python3
"""Ordo-Gedächtnis prototype: file graph from metadata + history, keyword retrieval, prompt slice.

    build_graph.py            -> graph.json, prints node counts and a few sample slices
    build_graph.py --selftest

Reads only what the phone has without the network: path, size, mtime, EXIF GPS, own usage history.
Never reads the bench's ground-truth `tags`.
"""
import json
import math
import os
import pathlib
import re
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime

HERE = pathlib.Path(__file__).parent
CAMERA = "DCIM/Camera"
# Offline reverse geocoding: on device a GeoNames cities table (CC-BY) does this; a few rows suffice here.
CITIES = [("Stuttgart", "Stuttgart", "Deutschland", 48.7758, 9.1829), ("Ludwigsburg", "Ludwigsburg", "Deutschland", 48.8975, 9.1922),
          ("Hamburg", "Hamburg", "Deutschland", 53.5511, 9.9937), ("Lissabon", "Lisbon Lisboa", "Portugal", 38.7223, -9.1393),
          ("München", "Munich", "Deutschland", 48.1351, 11.582), ("Berlin", "Berlin", "Deutschland", 52.52, 13.405),
          ("Paris", "Paris", "Frankreich", 48.8566, 2.3522)]
MONTHS = ["Januar Jan January", "Februar Feb February", "März Maerz March", "April Apr", "Mai May", "Juni Jun June",
          "Juli Jul July", "August Aug", "September Sep", "Oktober Okt October", "November Nov", "Dezember Dez December"]
ALIASES = {"Download": "Downloads", "Documents": "Dokumente Unterlagen", "Pictures": "Bilder Fotos", "Music": "Musik",
           "Movies": "Filme", "Camera": "Kamera Fotos Handyfotos", "Screenshots": "Bildschirmfotos",
           "Rechnungen": "Rechnung invoices", "Bank": "Kontoauszüge Konto", "Gesundheit": "Arzt Gesundheit",
           "Wohnung": "Miete Vermieter", "Bewerbungen": "Bewerbung"}
KINDS = {"image": {"jpg", "jpeg", "png", "heic", "webp", "gif"}, "video": {"mp4", "mkv", "mov", "3gp", "webm"},
         "audio": {"mp3", "flac", "m4a", "opus", "ogg", "wav"}, "document": {"pdf", "docx", "doc", "xlsx", "pptx", "txt", "odt"},
         "archive": {"zip", "rar", "7z"}}
BORING = {"img", "vid", "aud", "screenshot", "wallpaper", "track", "file", "image", "video", "document", "scan", "pdf"}
HABIT_WORDS = {"immer", "üblich", "übliche", "üblichen", "sonst", "gewohnt", "hingehören", "gehören", "letztes",
               "wieder", "usual", "always", "anderen"}
STOP = set("""der die das den dem des ein eine einen einem und oder in im ins nach aus von vom zu zum zur mit für auf
an am bei bitte alle alles meine meinen meiner mein mir mich ich du wo wie was welche welcher hab habe
habt sind ist sie es da dahin hin wo sollen soll die neuen neue neu neuer neues the a an to from of my all
verschieb verschiebe kopier kopiere kopieren lösch lösche löschen leg lege pack packe mach mache räum räume sortiere
sortier zusammen einen ordner folder dateien datei files file move copy delete auf noch so als oder""".split())


def fold(s):
    return s.lower().replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")


def words(s):
    return [w for w in re.split(r"[^0-9A-Za-zÄÖÜäöüß]+", s) if w]


def name_tokens(name):
    # "Rechnung_Telekom_2026-10.pdf" -> Rechnung, Telekom ; camelCase stays whole on purpose (keeps compounds).
    return [w for w in words(os.path.splitext(name)[0]) if w.isalpha() and len(w) >= 3]


def size(b):
    return f"{b / 1e9:.1f} GB" if b >= 1e9 else f"{b / 1e6:.0f} MB"


def kind_of(path):
    ext = path.rsplit(".", 1)[-1].lower()
    return next((k for k, v in KINDS.items() if ext in v), "other")


def km(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 12742 * math.asin(math.sqrt(h))


def city(lat, lon):
    return min(CITIES, key=lambda c: km((lat, lon), (c[3], c[4])))


def prefix_selector(members, folder_names):
    """Longest common name prefix of [members] that no other file in the folder starts with; None if there is none."""
    names = sorted(os.path.basename(m) for m in members)
    p = os.path.commonprefix([names[0], names[-1]])
    if len(p) < 5:
        return None
    member_set = set(names)
    return p if not any(n.startswith(p) and n not in member_set for n in folder_names) else None


def build(files, history):
    nodes = []
    by_folder = defaultdict(list)
    for f in files:
        by_folder[os.path.dirname(f["path"])].append(f)

    def node(kind, text, keywords, members=(), selector=None, folder="", **extra):
        nodes.append({"id": f"#{len(nodes) + 1}", "kind": kind, "text": text, "folder": folder,
                      "keywords": sorted({fold(k) for k in keywords if len(k) >= 3}), "members": list(members),
                      "selector": selector, **extra})

    # folders
    for folder, fs in by_folder.items():
        kinds = Counter(kind_of(f["path"]) for f in fs)
        segs = folder.split("/")
        kw = segs + [w for s in segs for w in words(s)] + [w for s in segs for w in ALIASES.get(s, "").split()]
        mix = ", ".join(f"{n} {k}" for k, n in kinds.most_common(3))
        node("folder", f"folder {folder}: {len(fs)} files ({mix}), {size(sum(f['size'] for f in fs))}", kw,
             folder=folder)
    # name patterns per folder (sender, app, document type)
    for folder, fs in by_folder.items():
        names = [os.path.basename(f["path"]) for f in fs]
        tok = Counter(t for n in names for t in set(name_tokens(n)))
        for t, n in tok.items():
            if n >= 2 and fold(t) not in BORING and n < len(fs):
                members = [f["path"] for f in fs if t in name_tokens(os.path.basename(f["path"]))]
                node("pattern", f"{folder}: {n} files with \"{t}\" in the name", [t], members,
                     {"source": folder, "name_contains": t}, folder)
    # duplicates: "name (1).ext" next to "name.ext"
    for folder, fs in by_folder.items():
        names = {os.path.basename(f["path"]) for f in fs}
        dups = [f["path"] for f in fs if re.search(r" \(\d+\)\.", f["path"]) and
                re.sub(r" \(\d+\)\.", ".", os.path.basename(f["path"])) in names]
        if dups:
            node("duplicates", f"{folder}: {len(dups)} duplicate downloads, the copies have \" (1)\" in the name",
                 ["doppelt", "doppelte", "doppelten", "duplikat", "duplikate", "duplicate", "kopien", "kopie"] + words(folder),
                 dups, {"source": folder, "name_contains": " (1)"}, folder)
    # events: camera days with many shots; trips when far from home
    cam = by_folder.get(CAMERA, [])
    located = [(f["lat"], f["lon"]) for f in cam if f["lat"] is not None]
    home = Counter(city(*p)[0] for p in located).most_common(1)[0][0] if located else None
    home_c = next(c for c in CITIES if c[0] == home)
    days = defaultdict(list)
    for f in cam:
        days[datetime.fromtimestamp(f["mtime"]).date()].append(f)
    busy = sorted(d for d, fs in days.items() if len(fs) >= 25)
    runs = []
    for d in busy:
        if runs and (d - runs[-1][-1]).days == 1:
            runs[-1].append(d)
        else:
            runs.append([d])
    cam_names = [os.path.basename(f["path"]) for f in cam]
    for run in runs:
        fs = [f for d in run for f in days[d]]
        pts = [(f["lat"], f["lon"]) for f in fs if f["lat"] is not None]
        c = Counter(city(*p) for p in pts).most_common(1)[0][0] if pts else None
        far = c is not None and km((c[3], c[4]), (home_c[3], home_c[4])) > 50
        photos = [f["path"] for f in fs if kind_of(f["path"]) == "image"]
        videos = [f["path"] for f in fs if kind_of(f["path"]) == "video"]
        a, b = run[0], run[-1]
        when = a.strftime("%d.%m.%Y") if a == b else f"{a.strftime('%d.%m.')}–{b.strftime('%d.%m.%Y')}"
        place = f"{c[0]} ({c[2]})" if c else "ohne Ort"
        weekend = len(run) <= 3 and all(d.weekday() >= 5 for d in run)
        kw = [MONTHS[a.month - 1], str(a.year)] + MONTHS[a.month - 1].split()
        if c:
            kw += [c[0], c[2]] + c[1].split()
        if far:
            kw += ["Urlaub", "Reise", "Trip", "holiday", "vacation"]
        if weekend:
            kw += ["Wochenende", "weekend"]
        sel_p = prefix_selector(photos, cam_names) if photos else None
        sel_v = prefix_selector(videos, cam_names) if videos else None
        hint = "; ".join(s for s in [f"photos: name_contains \"{sel_p}\"" if sel_p else "",
                                     f"videos: name_contains \"{sel_v}\"" if sel_v else ""] if s)
        label = ("trip" if far else "event") + (", weekend" if weekend else "")
        node("event", f"{label} {when} in {place}: {len(photos)} photos, {len(videos)} videos in {CAMERA}"
             + (f"; {hint}" if hint else ""), kw + ["Fotos", "Videos", "photos"], photos + videos,
             {"source": CAMERA, "photos": sel_p, "videos": sel_v}, CAMERA, photos=photos, videos=videos)
    # series: several non-media files saved in one folder on one day (scans, a purchase)
    for folder, fs in by_folder.items():
        if folder.startswith((CAMERA, "Android/", "Pictures", "Music")):
            continue
        per_day = defaultdict(list)
        for f in fs:
            per_day[datetime.fromtimestamp(f["mtime"]).date()].append(f)
        for d, group in per_day.items():
            if len(group) >= 5:
                names = [os.path.basename(f["path"]) for f in group]
                kw = [t for n in names for t in name_tokens(n)]
                shown = sorted({n.split("_0")[0] if n.startswith("Scan_") else n for n in names})
                node("series", f"{folder}, {d.strftime('%d.%m.%Y')}: {len(group)} files saved together ({', '.join(shown)})",
                     kw + MONTHS[d.month - 1].split() + ["Unterlagen", "Papiere"], [f["path"] for f in group],
                     {"source": folder, "name_contains": prefix_selector([f["path"] for f in group],
                                                                         [os.path.basename(f["path"]) for f in fs])}, folder)
    # habits from the own history (opt-in on device)
    moves = Counter()
    for h in history:
        if h["op"] == "move":
            moves[(h["from"], name_tokens(h["name"])[0], h["to"])] += 1
    for (src, tok, dst), n in moves.items():
        if n >= 2:
            node("habit", f"habit: files with \"{tok}\" in the name are moved from {src} to {dst} ({n}x)",
                 [tok] + words(dst) + ALIASES.get(dst.split("/")[-1], "").split(), selector={"source": src, "name_contains": tok,
                                                                                              "destination": dst}, folder=src)
    deletes = defaultdict(list)
    for h in history:
        if h["op"] == "delete":
            deletes[(h["from"], h["name"].rsplit(".", 1)[-1])].append(h["age_days"])
    for (src, ext), ages in deletes.items():
        if len(ages) >= 3:
            months = int(statistics.median(ages) // 30)
            age = f"older than {months} month{'s' if months > 1 else ''}" if months else "of any age"
            what = f".{ext} files" if kind_of("x." + ext) in ("other", "archive") or ext == "apk" else f"{kind_of('x.' + ext)} files"
            node("habit", f"habit: in {src} you delete {what} {age} ({len(ages)}x)",
                 words(src) + [ext, "löschen", "aufräumen", "putz"], folder=src)
    orgs = Counter((h["from"], h["group_by"]) for h in history if h["op"] == "organize")
    for (src, g), n in orgs.items():
        node("habit", f"habit: you tidy {src} into subfolders by {g} ({n}x)", words(src) + ["aufräumen", "räum"], folder=src)
    # storage per month (for "what eats space")
    per_month = defaultdict(lambda: defaultdict(int))
    for f in files:
        per_month[datetime.fromtimestamp(f["mtime"]).strftime("%Y-%m")][os.path.dirname(f["path"])] += f["size"]
    for m in sorted(per_month)[-3:]:
        top = sorted(per_month[m].items(), key=lambda x: -x[1])[:2]
        mm = int(m[5:])
        node("month", f"{MONTHS[mm - 1].split()[0]} {m[:4]}: {sum(per_month[m].values()) / 1e9:.1f} GB new, most in "
             + ", ".join(f"{k} {v / 1e9:.1f} GB" for k, v in top),
             MONTHS[mm - 1].split() + ["Monat", "Platz", "Speicher", "frisst", "groß", "space"])
    df = Counter(k for n in nodes for k in n["keywords"])
    return {"nodes": nodes, "df": dict(df)}


def query_tokens(text):
    return [fold(w) for w in words(text) if w.lower() not in STOP and len(w) >= 3]


def hit(tok, kw):
    if tok == kw:
        return True
    if len(kw) >= 4 and tok.startswith(kw):          # compounds: autokauf -> auto, gesundheitssachen -> gesundheit
        return True
    stem = tok[:-2] if len(tok) > 6 else tok         # plurals: rechnungen -> rechnung, kontoauszuge -> kontoauszug
    return len(stem) >= 5 and kw.startswith(stem)


def retrieve(graph, text, budget_chars=1800, max_nodes=6):
    """Keyword match with idf weights; habits win when the user says "wie immer". Returns the chosen nodes."""
    toks = query_tokens(text)
    habitual = any(w in HABIT_WORDS for w in re.split(r"\W+", text.lower()))
    n_all = len(graph["nodes"])
    scored = []
    for node in graph["nodes"]:
        s = sum(math.log(n_all / graph["df"][kw]) for t in toks for kw in node["keywords"] if hit(t, kw))
        if s > 0:
            if node["kind"] == "habit":
                s *= 2 if habitual else 1.2
            scored.append((s, node))
    scored.sort(key=lambda x: -x[0])
    out, used = [], 0
    for s, node in scored[:max_nodes]:
        if s < 0.35 * scored[0][0]:   # weak side hits (one shared generic word) only add noise
            break
        if used + len(node["text"]) > budget_chars:
            break
        out.append(node)
        used += len(node["text"])
    return out


def render(nodes, with_ids):
    if not nodes:
        return ""
    lines = [f"- {'[' + n['id'] + '] ' if with_ids and n['members'] else ''}{n['text']}" for n in nodes]
    return "Known about the user's files:\n" + "\n".join(lines)


def load():
    files = [json.loads(l) for l in open(HERE / "tree.jsonl", encoding="utf-8")]
    history = [json.loads(l) for l in open(HERE / "history.jsonl", encoding="utf-8")]
    return files, history


def selftest():
    assert hit("rechnungen", "rechnung") and hit("autokauf", "auto") and hit("kontoauszuge", "kontoauszug")
    assert not hit("bild", "bildschirmfotos") and not hit("auto", "autobank")
    assert prefix_selector(["a/IMG_2026071_1", "a/IMG_2026072_2"], ["IMG_2026071_1", "IMG_2026072_2"]) == "IMG_202607"
    assert prefix_selector(["a/IMG_20260714"], ["IMG_20260714", "IMG_20260714b"]) is None
    files, history = load()
    g = build(files, history)
    assert all(not any("tags" in str(k) for k in n["keywords"]) for n in g["nodes"])
    texts = [n["text"] for n in retrieve(g, "Fotos vom Urlaub in Lissabon")]
    assert texts and "Lissabon" in texts[0], texts
    print("selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
        sys.exit()
    files, history = load()
    g = build(files, history)
    (HERE / "graph.json").write_text(json.dumps(g, ensure_ascii=False), encoding="utf-8")
    print(len(g["nodes"]), "nodes:", dict(Counter(n["kind"] for n in g["nodes"])),
          "edges(member links):", sum(len(n["members"]) for n in g["nodes"]))
    for q in ["Verschieb die neuen Rechnungen wie immer", "Pack die Unterlagen vom Autokauf in einen Ordner Auto",
              "Kopiere die Fotos vom Hamburg-Wochenende nach Backup", "Verschiebe alle PDFs aus Download nach Dokumente"]:
        print("\n>>", q)
        print(render(retrieve(g, q), True))
