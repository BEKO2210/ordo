#!/usr/bin/env python3
"""Deterministic helpers around the model (Phase 0b, lever 1). Plain word rules, no learning, German + English.

route(text)            -> a finished plan for intents Ordo already has a screen for, or a refusal, or None (ask the model)
fix(text, plan)        -> the model's plan with time, size, sort/limit, kind, folder and safety fixed from the words
Everything here is code the app can run in < 1 ms; the model only fills what the words do not decide.
"""
import re

from p0_cases import DEFAULT

NUM = {"ein": 1, "eine": 1, "einem": 1, "einen": 1, "einer": 1, "one": 1, "a": 1, "zwei": 2, "two": 2, "drei": 3, "three": 3,
       "vier": 4, "four": 4, "fünf": 5, "funf": 5, "five": 5, "sechs": 6, "sieben": 7, "acht": 8, "neun": 9, "zehn": 10, "ten": 10,
       "zwanzig": 20}
N = r"(\d+|" + "|".join(NUM) + r")"


def num(s):
    return int(s) if s.isdigit() else NUM[s]


def norm(text):
    t = " " + text.lower().replace("’", "'") + " "
    t = re.sub(r"\bscreen ?shots?\b|\bbildschirmfotos?\b", " screenshots ", t)
    t = re.sub(r"\bwhats ?app\b", "whatsapp", t)
    t = re.sub(r"\ba ?p ?k\b|\bapks\b", "apk", t)
    t = re.sub(r"\bmega ?bytes?\b|\bmegabytes?\b", "mb", t)
    t = re.sub(r"\bgiga ?bytes?\b|\bgigabytes?\b|\bgiga\b", "gb", t)
    t = re.sub(r"video'?s\b", "videos", t)
    return t


# Commands that need a memory or log Ordo does not keep: answer "kann ich noch nicht", never guess.
NEEDS_MEMORY = [r"\bnicht mehr (ge(ö|oe)ffnet|aufgemacht|angeschaut|angesehen)", r"lange nicht (mehr )?(ge(ö|oe)ffnet|aufgemacht|angeschaut|angesehen)",
                r"\b(seit|länger als) .*nicht (mehr )?(ge(ö|oe)ffnet|aufgemacht)", r"\bgesucht\b", r"\bsuche gestern\b",
                r"\bwie (immer|sonst|gewohnt|normalerweise|letztes mal)\b", r"\bwie jede[nrs]? (tag|woche|monat|mal)\b", r"\bso,? wie ich (es|das) (immer|normalerweise|sonst)",
                r"\bwie sonst\b", r"\bgenauso\b", r"\bgenau so\b", r"\bdas gleiche\b", r"\bdasselbe\b", r"\bwie gerade\b", r"\bwie vorhin\b",
                r"\bvon vorhin\b", r"\bvon eben\b", r"\bvorhin\b", r"\bdavon\b", r"\bdie (anderen|übrigen|restlichen)\b",
                r"\bden restlichen\b", r"\bzu dieser\b", r"\bzu der rechnung\b", r"\bhingehören\b", r"\bwo die anderen\b",
                r"\bzu den anderen\b", r"\barbeits(dokumente|sachen|dateien)\b", r"\bmeine arbeit\b"]
STORAGE = r"(was kann (ich )?(alles )?(löschen|loeschen|weg)|speicher(platz)? (ist )?voll|kein (platz|speicher)|(mehr|wieder) (speicher)?platz|" \
          r"platz (frei|schaffen)|wie viel speicher|speicher .*frei|what can i delete|free up space)"
LARGEST = r"(größt|groesst|grösst|am meisten platz|biggest|largest)"
NEWEST = r"(neueste|neuesten|neuestes|zuletzt|als letztes|newest|latest|most recent|letzte (apk|datei|pdf|foto|bild|video|download))"
DUPS = r"(doppelt|duplikat|mehrfach|duplicate)"
DELETE = r"(lösch|losch|loesch|entfern|wirf|schmeiß|schmeiss|delete|remove|erase)"
NO_DELETE = r"(nichts|nix|bloß nichts|ja nichts|nicht) (wichtiges )?(lösch|loesch|weg)|nicht lösch|lösch (bloß |ja )?nichts|nichts wichtiges"
QUESTION = r"^\s*(was|wo|welche|welcher|welches|wie|kann ich|darf ich|brauch ich|brauche ich|gibt es|hab ich|habe ich|ist|where|which|what|can i|do i|should i)\b"
KINDS = [("image", r"\b(bilder|bild|fotos|foto|images?|pictures?|photos?|pics?)\b"), ("video", r"\b(videos?|filme|clips?)\b"),
         ("audio", r"\b(musik|songs?|lieder|music|audio)\b")]
EXTS = ["pdf", "apk", "zip", "mp3", "mp4", "docx", "xlsx", "jpg", "png"]
FOLDERS = [("Screenshots", r"\bscreenshots\b"), ("WhatsApp Images", r"whatsapp\b.*\b(bilder|fotos|images|pictures|photos)|(bilder|fotos|images) .*whatsapp"),
           ("WhatsApp Video", r"whatsapp\b.*\bvideos?|videos? .*whatsapp"),
           ("Download", r"(herunter|runter)geladen|\bgeladen\b|\bdownloads?\b|download-ordner"),
           ("Kamera", r"\bkamera\b|\bcamera\b|(foto|bild|fotos|bilder)\b.*\b(gemacht|aufgenommen)")]
EVERYWHERE = r"\b(auf|am) (dem |meinem |mein )?(handy|telefon|phone|gerät)\b|\bon my phone\b"


def time_and_size(t):
    out = {}
    if re.search(r"\bheute\b|\btoday\b", t):
        out["_today"] = True
    elif re.search(r"\bvorgestern\b", t):
        out.update(newer_than=2, age_unit="days")
    elif re.search(r"\bgestern\b|\byesterday\b", t):
        out.update(newer_than=1, age_unit="days")
    m = re.search(r"\b(letzten|vergangenen|last|past) " + N + r" (tage|tagen|days|wochen|weeks|monate|months)\b", t)
    if m:
        unit = {"t": "days", "d": "days", "w": "weeks", "m": "months"}[m.group(3)[0]]
        out.update(newer_than=num(m.group(2)), age_unit=unit)
    elif re.search(r"\b(letzte|letzten|vergangene|vergangenen|diese|dieser|last|this) (woche|week)\b", t):
        out.update(newer_than=1, age_unit="weeks")
    elif re.search(r"\b(letzten|vergangenen|last) (monat|month)\b|\bvom letzten monat\b", t):
        out.update(newer_than=1, age_unit="months")
    elif re.search(r"\b(vor )?(ein paar|paar|einigen|wenigen) tagen\b|\bneulich\b|\bkürzlich\b|\brecently\b|\bfew days\b", t):
        out.update(newer_than=7, age_unit="days")
    m = re.search(r"\b(älter|aelter|older) (als|than) " + N + r" (tag|tage|tagen|woche|wochen|monat|monate|monaten|jahr|jahre|jahren|day|days|week|weeks|month|months|year|years)\b", t)
    if m:
        u = m.group(4)
        unit = "years" if u.startswith(("jahr", "year")) else "months" if u.startswith(("monat", "month")) else \
            "weeks" if u.startswith(("woche", "week")) else "days"
        out.update(older_than=num(m.group(3)), age_unit=unit)
    if re.search(r"\b(ein )?halbes gb\b", t):
        out.update(larger_than=500, size_unit="mb")
    else:
        m = re.search(r"\b(größer|grösser|groesser|über|ueber|mehr als|bigger|larger|over|ab)( als| than)? " + N + r" ?(mb|gb)\b", t)
        if m:
            out.update(larger_than=num(m.group(3)), size_unit=m.group(4))
    return out


def route(text):
    """A finished plan without the model, {"refuse": True}, or None."""
    t = norm(text)
    if any(re.search(p, t) for p in NEEDS_MEMORY):
        return {"refuse": True}
    has_delete = re.search(DELETE, t) and not re.search(NO_DELETE, t)
    named_folder = any(re.search(p, t) for _, p in FOLDERS) or re.search(r"\b(ordner|folder|aus|im|in)\b \w", t)
    if re.search(STORAGE, t):   # the app's storage analysis: biggest files first (and duplicates), never a delete plan
        return {**DEFAULT, "action": "show", "sort": "size"}
    if re.search(DUPS, t) and not has_delete and not named_folder:
        return {**DEFAULT, "action": "show"}   # the app narrows to extra copies by the word "doppelt"
    if re.search(LARGEST, t) and not named_folder and not any(re.search(p, t) for _, p in KINDS) and \
            not any(re.search(rf"\b{e}s?\b", t) for e in EXTS):
        return {**DEFAULT, "action": "show", "sort": "size"}
    return None


QUALIFIER_STOP = {"whatsapp", "kamera", "camera", "handy", "alle", "die", "meine", "neue", "neuen", "alte", "alten", "der", "das",
                  "den", "von", "word", "excel", "bild", "musik", "video", "pdf", "zip", "apk", "mp3", "screen", "eigene", "andere"}
MOVE_VERB = r"\b(verschieb|verschiebe|schieb|pack|leg|move|tu)\b"


def named_folder(t, folders, after):
    """The longest existing folder name right after one of the words in [after] ("aus", "nach"), or None."""
    best = None
    for f in folders:
        leaf = f.rsplit("/", 1)[-1].lower()
        if len(leaf) >= 4 and re.search(rf"\b({after}) (dem |den |die |das |ordner |folder )*{re.escape(leaf)}\b", t):
            if best is None or len(leaf) > len(best.rsplit("/", 1)[-1]):
                best = f
    return best


def fix(text, plan, folders=()):
    """Overrides the fields the words decide. Never turns a plan into a delete, only away from one.
    [folders] = the folder paths on the phone (the app has them), to read "aus X" / "nach Y" exactly."""
    t = norm(text)
    p = {**DEFAULT, **plan}
    act = p["action"]
    # "verschieb/pack ... nach Y" is a move even when the model said organize
    dest_word = re.search(r"\b(nach|to|in (den|einen|nen|ein) (neuen |eigenen |extra )?ordner) ([\wäöüß/-]+)", t)
    if act == "organize" and re.search(MOVE_VERB, t) and dest_word:
        p["action"] = act = "move"
        if not p["destination"] or p["destination"].lower().startswith("extra"):
            p["destination"] = dest_word.group(4)
    # an explicit "aus/in X" wins over "von X", which wins over a folder named as the object ("die Wallpapers")
    src = named_folder(t, folders, "aus|from|in|im") or named_folder(t, folders, "von") or named_folder(t, folders, "die|alle")
    if src and act in ("move", "copy", "delete", "organize", "show"):
        p["source"] = src
    dst = named_folder(t, folders, "nach|to")
    if dst and act in ("move", "copy"):
        p["destination"] = dst
    question = re.search(QUESTION, t) or text.strip().endswith("?")
    has_delete = re.search(DELETE, t) and not re.search(NO_DELETE, t)
    # safety: a question or a "don't delete" never becomes a destructive plan
    if act in ("delete", "move", "organize") and question:
        p["action"] = act = "show"
    if act == "delete" and not has_delete:
        p["action"] = act = "organize" if re.search(r"räum|raeum|ordnung|sortier|tidy", t) else "show"
    ts = time_and_size(t)
    if {"newer_than", "older_than", "_today"} & set(ts):   # the words decide the time completely
        p.update(newer_than=0, older_than=0, _today=False)
    p.update(ts)
    if re.search(LARGEST, t):
        p["sort"] = "size"
    elif re.search(NEWEST, t) and not re.search(r"letzte[n]? (woche|monat|tage|jahr)", t):
        p["sort"] = "newest"
    m = re.search(r"\b(die|the|top)? ?" + N + r" (größten|groessten|grössten|neuesten|letzten|biggest|largest|newest|latest)\b", t)
    if m:
        p["limit"] = num(m.group(2))
    elif p["sort"] and re.search(r"\bzuletzt\b|\bals letztes\b|\b(die|das|der|the) (größte|groesste|grösste|neueste|biggest|largest|newest|latest)\b", t):
        p["limit"] = 1   # one file asked for
    exts = [e for e in EXTS if re.search(rf"\b{e}s?\b", t)]
    kinds = [k for k, pat in KINDS if re.search(pat, t)]
    if exts:
        p["extensions"], p["kinds"] = exts, []
    elif kinds and not (p["source"] and "whatsapp" in t and act != "show"):
        p["kinds"] = kinds[:1] if not p["extensions"] else p["kinds"]
    if act == "show" and not src:
        if re.search(EVERYWHERE, t):
            p["source"] = ""
        for folder, pat in FOLDERS:
            if re.search(pat, t):
                p["source"] = folder
                break
        if p["source"] in (".", "hier"):
            p["source"] = ""
    # "weiß nicht, wie sie heißt" / "in denen X steht": the word is a content word, not a name
    if re.search(r"wei(ß|ss) (aber )?(nicht|den namen nicht)|keine ahnung,? wie|nicht mehr,? wie|in denen|vorkommt|steht\b|mention|erwähn|\bdrin\b|über (die|den|das)", t) \
            and p["name_contains"] and not re.search(r"im namen|in the name", t):
        p["content_contains"], p["name_contains"] = p["name_contains"], ""
    # "Telekom-Rechnungen", "Instagram-Screenshots", "Hochzeitsfotos": the qualifier must be in the plan, or the plan
    # takes every file of that kind (too many files for a move or delete)
    m = re.search(r"\b([a-zäöüß]{3,}?)s?(?:-\s*|)(fotos|bilder|videos|screenshots|dateien|sachen|unterlagen|rechnungen|briefe|papiere|pdfs)\b", t)
    if m and m.group(1) not in QUALIFIER_STOP and not p["name_contains"] and not p["content_contains"]:
        p["name_contains"] = m.group(1).capitalize()
    # a word that is not said to be "im Namen" is looked for in name and text (content covers both); not for delete
    if p["name_contains"] and act in ("show", "move", "copy") and not re.search(r"im namen|in the name|hei(ß|ss)t|named", t):
        p["content_contains"], p["name_contains"] = p["name_contains"], ""
    if p["content_contains"]:
        w = re.sub(r"s?(zeug|sachen|kram)$", "", p["content_contains"].split()[0], flags=re.I)
        p["content_contains"] = w[:-1] if w.lower().endswith("ungs") else w
    return p


def selftest():
    assert route("Welche Dateien habe ich doppelt auf dem Handy?") == {**DEFAULT, "action": "show"}
    assert route("Was kann ich löschen, damit ich wieder mehr Speicherplatz habe?")["sort"] == "size"
    assert route("Zeig mir mal die größten Dateien auf meinem Handy.")["sort"] == "size"
    assert route("zeig mir die 5 größten videos") is None
    assert route("Ich hab gestern eine Datei gesucht, kannst du mir die nochmal zeigen?") == {"refuse": True}
    assert route("Lösche die doppelten Dateien im Download") is None
    assert time_and_size(norm("bilder von gestern")) == {"newer_than": 1, "age_unit": "days"}
    assert time_and_size(norm("die bilder der letzten drei tage")) == {"newer_than": 3, "age_unit": "days"}
    assert time_and_size(norm("video's größer 500 mega byte")) == {"larger_than": 500, "size_unit": "mb"}
    assert time_and_size(norm("apks die älter als ein jahr sind")) == {"older_than": 1, "age_unit": "years"}
    assert time_and_size(norm("zip größer als ein giga")) == {"larger_than": 1, "size_unit": "gb"}
    f = fix("Wo ist die APK, die ich zuletzt heruntergeladen habe?", {"action": "show", "source": "Download", "extensions": ["apk"]})
    assert f["sort"] == "newest" and f["limit"] == 1, f
    f = fix("zeig mir die 5 größten videos", {"action": "show"})
    assert f["sort"] == "size" and f["limit"] == 5 and f["kinds"] == ["video"], f
    assert fix("Was kann ich löschen?", {"action": "delete", "source": "."})["action"] == "show"
    assert fix("räum download auf aber lösch bloß nichts", {"action": "delete", "source": "Download"})["action"] == "organize"
    assert fix("Lösche alle APK-Dateien im Download-Ordner", {"action": "delete", "source": "Download", "extensions": ["apk"]})["action"] == "delete"
    f = fix("pdfs mit versicherungs zeug drin such mal", {"action": "show", "extensions": ["pdf"], "content_contains": "versicherungs zeug"})
    assert f["content_contains"] == "versicherung", f
    assert fix("Bildschirmfotos vom letzten Monat anzeigen", {"action": "show", "newer_than": 30})["source"] == "Screenshots"
    fl = ["Download", "Movies", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video", "Pictures/Wallpapers", "Backup"]
    f = fix("Verschiebe alle Videos aus Download nach Filme", {"action": "organize", "source": "Movies", "group_by": "month"}, fl)
    assert f["action"] == "move" and f["source"] == "Download" and f["destination"] == "filme", f
    f = fix("Lösche in WhatsApp Video alle Videos", {"action": "delete", "source": "WhatsApp Images", "kinds": ["video"]}, fl)
    assert f["source"].endswith("WhatsApp Video"), f
    f = fix("Verschieb die Instagram-Screenshots in einen Ordner Insta", {"action": "move", "source": "Screenshots",
            "destination": "Insta"}, fl)
    assert "Instagram" in (f["name_contains"], f["content_contains"]), f
    assert route("Lösch die alten WhatsApp-Videos wie jeden Monat") == {"refuse": True}
    print("rules selftest ok")


if __name__ == "__main__":
    selftest()
