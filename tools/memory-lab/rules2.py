#!/usr/bin/env python3
"""Phase 0c: general mechanisms around the model, no rule keyed to a test sentence.

    decide(text, raw_plan, tree)  -> {"status": plan|refuse|ask, "plan", "reason", "say", "warn", "unaccounted"}

Order (each step is a general linguistic or structural category, see docs/KI-ZEIGEN-PHASE0C.md):
  e  closed list of things Ordo cannot do            -> honest refusal before any model call
  m  memory/anaphora (habits, earlier turns, usage)  -> refusal (Ordo keeps no log)
  c  exclusion phrases (außer, ohne, except ...)     -> exclude_kinds / exclude_extensions / exclude_terms
  b  time and size grammar                           -> date_from / date_to, min_bytes / max_bytes (model never decides)
  d  folders against the real tree                   -> source / destination (exact, alias, unique fuzzy; ambiguity asks)
     types, sort, limit, counts from the words       -> kinds / extensions / sort / limit
     action safety per clause (question, negation, subordinate clause; code never creates a delete)
  a  coverage guard: every content word must be used by a plan field; a destructive plan with a word left over
     takes it as a filter only when that is the only reading that exists on the phone, else asks
  f  plausibility: a count in the words ("die drei ...") against the matched files -> warning only
The model (prompt C) only fills action, source, destination, name_contains, content_contains, group_by.
"""
import itertools
import re
from datetime import date, datetime, timedelta

import bench
import phase0 as p0
from p0_cases import DEFAULT

ALL = frozenset("abcdef")
DESTRUCTIVE = ("move", "delete", "organize")

# ---------------------------------------------------------------- lexicons (closed, general)
NUM = {"null": 0, "ein": 1, "eine": 1, "einen": 1, "einem": 1, "einer": 1, "eines": 1, "one": 1, "a": 1, "an": 1,
       "zwei": 2, "two": 2, "beide": 2, "beiden": 2, "both": 2, "drei": 3, "three": 3, "vier": 4, "four": 4, "fünf": 5,
       "five": 5, "sechs": 6, "six": 6, "sieben": 7, "seven": 7, "acht": 8, "eight": 8, "neun": 9, "nine": 9, "zehn": 10,
       "ten": 10, "elf": 11, "eleven": 11, "zwölf": 12, "twelve": 12, "fünfzehn": 15, "fifteen": 15, "zwanzig": 20,
       "twenty": 20, "dreißig": 30, "thirty": 30, "fünfzig": 50, "fifty": 50, "hundert": 100, "hundred": 100}
NUMW = r"(\d+(?:[.,]\d+)?|" + "|".join(sorted(NUM, key=len, reverse=True)) + r")"


def num(s):
    s = s.strip()
    return float(s.replace(",", ".")) if s[0].isdigit() else NUM[s]


MONTHS = {"januar": 1, "january": 1, "jänner": 1, "februar": 2, "february": 2, "märz": 3, "maerz": 3, "march": 3,
          "april": 4, "mai": 5, "may": 5, "juni": 6, "june": 6, "juli": 7, "july": 7, "august": 8, "september": 9,
          "oktober": 10, "october": 10, "november": 11, "dezember": 12, "december": 12}
WEEKDAYS = {"montag": 0, "monday": 0, "dienstag": 1, "tuesday": 1, "mittwoch": 2, "wednesday": 2, "donnerstag": 3,
            "thursday": 3, "freitag": 4, "friday": 4, "samstag": 5, "sonnabend": 5, "saturday": 5, "sonntag": 6, "sunday": 6}
SEASONS = {"frühling": 3, "frühjahr": 3, "spring": 3, "sommer": 6, "summer": 6, "herbst": 9, "autumn": 9,
           "winter": 12}   # start month; each season is 3 months
# fixed-date holidays (month, day, days long); Easter is computed
HOLIDAYS = {"heiligabend": (12, 24, 1), "christmas eve": (12, 24, 1), "weihnachten": (12, 25, 2), "christmas": (12, 25, 2),
            "silvester": (12, 31, 1), "new year's eve": (12, 31, 1), "neujahr": (1, 1, 1), "new year": (1, 1, 1),
            "new year's day": (1, 1, 1), "valentinstag": (2, 14, 1), "valentine's day": (2, 14, 1), "halloween": (10, 31, 1),
            "tag der arbeit": (5, 1, 1), "tag der deutschen einheit": (10, 3, 1), "nikolaus": (12, 6, 1)}
UNIT = {"tag": "d", "tage": "d", "tagen": "d", "tages": "d", "day": "d", "days": "d", "woche": "w", "wochen": "w",
        "week": "w", "weeks": "w", "monat": "m", "monate": "m", "monaten": "m", "monats": "m", "month": "m", "months": "m",
        "jahr": "y", "jahre": "y", "jahren": "y", "jahres": "y", "year": "y", "years": "y"}
UNITW = r"(" + "|".join(sorted(UNIT, key=len, reverse=True)) + r")"
DAYS_PER = {"d": 1, "w": 7, "m": 30, "y": 365}   # durations ("älter als 6 Monate") are lengths: fixed days

# file types: word -> (kinds, extensions). Lexicon of type NAMES, not of phrasings.
TYPES = {}
for _words, _kinds, _exts in [
    ("foto fotos photo photos bild bilder picture pictures pic pics image images aufnahme aufnahmen", ["image"], []),
    ("video videos film filme clip clips movie movies", ["video"], []),
    ("musik music song songs lied lieder track tracks audio audios sprachaufnahme sprachaufnahmen sprachmemo "
     "sprachmemos sprachnachricht sprachnachrichten voicemail", ["audio"], []),
    ("dokument dokumente dokumenten document documents", ["document"], []),
    ("archiv archive archives", ["archive"], []),
    ("screenshot screenshots", ["image"], []),
    ("wallpaper hintergrundbild hintergrundbilder", ["image"], []),
    ("excel tabelle tabellen spreadsheet spreadsheets", [], ["xlsx", "xls", "ods", "csv"]),
    ("word", [], ["docx", "doc", "odt"]),
    ("powerpoint präsentation präsentationen folien slides", [], ["pptx", "ppt", "odp"]),
    ("textdatei textdateien", [], ["txt"]),
]:
    for _w in _words.split():
        TYPES[_w] = (_kinds, _exts)
EXT_WORDS = {"pdf", "apk", "zip", "rar", "7z", "mp3", "mp4", "mkv", "jpg", "jpeg", "png", "gif", "heic", "docx", "doc",
             "xlsx", "xls", "pptx", "txt", "csv", "opus", "m4a", "wav", "flac", "ogg", "epub", "odt", "mov", "webp"}
for _e in EXT_WORDS:
    TYPES[_e] = ([], [_e])
    TYPES[_e + "s"] = ([], [_e])
TYPES["apps"] = ([], ["apk"])   # installer files on a phone

# Android standard folders: word -> candidate paths, first that exists wins
ALIAS = {"download": ["Download"], "downloads": ["Download"], "dokumente": ["Documents"], "documents": ["Documents"],
         "kamera": ["DCIM/Camera"], "camera": ["DCIM/Camera"], "dcim": ["DCIM/Camera", "DCIM"],
         "screenshots": ["Pictures/Screenshots", "DCIM/Screenshots"], "screenshot": ["Pictures/Screenshots", "DCIM/Screenshots"],
         "bildschirmfotos": ["Pictures/Screenshots"], "bilder": ["Pictures"], "pictures": ["Pictures"], "filme": ["Movies"],
         "movies": ["Movies"], "musik": ["Music"], "music": ["Music"], "klingeltöne": ["Ringtones"], "ringtones": ["Ringtones"],
         "podcasts": ["Podcasts"], "hörbücher": ["Audiobooks"], "audiobooks": ["Audiobooks"], "aufnahmen": ["Recordings"],
         "recordings": ["Recordings"]}
WA = "Android/media/com.whatsapp/WhatsApp/Media/"
# messenger media folders by kind; voice messages live in "Voice Notes" (newer) or "Audio" (older layout)
MESSENGER = {"image": ["WhatsApp Images"], "video": ["WhatsApp Video"], "voice": ["WhatsApp Voice Notes", "WhatsApp Audio"],
             "audio": ["WhatsApp Audio"], "document": ["WhatsApp Documents"]}
VOICE = r"\b(sprachnachricht(en)?|sprachmemos?|voice ?(messages?|notes?|memos?)|voicemails?)\b"
DOWNLOAD_VERB = r"\b(heruntergeladen|runtergeladen|geladen|gedownloadet|downloaded|herunterladen)\b"
TAKEN = r"\b(gemacht|aufgenommen|geknipst|fotografiert|gefilmt|taken|shot|recorded|filmed)\b"

STOP = set("""
der die das den dem des ein eine einen einem einer eines kein keine keinen keinem keiner
ich du er sie es wir ihr mir mich dir dich ihm ihn uns euch ihnen sich mein meine meinen meinem meiner meines dein deine
deinen unser unsere euer ihre ihren sein seine man
und oder aber sondern denn doch ja nein nur noch schon mal bitte danke gern gerne eben halt auch so dann jetzt gleich
erstmal erst mal nochmal wieder einfach eigentlich ganz sehr echt wirklich irgendwie irgendwas irgendwelche irgendein
etwas was wer wo wohin woher wann wie warum wieso welche welcher welches welchen welchem wieviel wieviele viele viel
alle alles allen aller sämtliche sämtlichen jede jeder jedes jeden diese dieser dieses diesen diesem jene
hier da dort drin drinnen drauf darin darauf dabei dazu damit davor danach dahin daher rein raus hin her weg mit von vom zu zum zur
in im ins an am auf aus bei nach über unter vor hinter neben zwischen durch für gegen um ab bis seit während per als wie ob
dass daß weil wenn falls bevor nachdem sobald also sonst nicht nichts nix bloß blos nie niemals
mehr weniger am meisten paar einige einigen wenige wenigen andere anderen übrige übrigen restliche restlichen
hey hallo hi ok okay okey bitteschön super frisst fressen belegt belegen nimmt nehmen takes take uses
the a an of to in on at for from with by into onto about as and or but not no nor so then just only also please
thanks thank me my mine you your yours i we our us they them their it its this that these those there here
what where which who when how why all any some every each other others more most few many much
is are was were be been being am do does did done have has had having can could will would shall should may might must
s t d ll ve re m
""".split())
GENERIC = set("""datei dateien file files sache sachen zeug kram stuff thing things item items ordner folder folders
handy handys telefon phone smartphone gerät device speicher storage platz space unterlagen papiere papers kopie kopien
version versionen original originale inhalt inhalte content text wort word name namen named called
liste list ergebnis ergebnisse result results art arten sorte typ dateityp dateitypen type types""".split())
VERB_EXACT = set("""tu tue tun mach mache machen macht gemacht hab habe hat haben hast hatte hatten gehabt bin bist ist sind
sei war waren wäre wären gewesen gibt gibts gab geben gegeben kann kannst können konnte könnte könntest darf darfst dürfen
soll sollen sollte will willst wollen möchte möchtest muss musst müssen brauch brauche brauchst braucht brauchen
weiß weiss wissen kenn kenne liegt liegen lag lagen steht stehen stand vorkommt vorkommen kommt kommen gekommen
dazugekommen hinzugekommen gelandet landen bekommen gekriegt erhalten empfangen gespeichert speichern abgelegt
heißt heisst heißen nennt passiert passieren würde wird werden geworden geht gehen ging get got gets give go goes
show find list look search see seen open want need know put keep let tell help please""".split())
# verb stems: prefix of a word form (all inflections) + exact short forms
ACTION_STEMS = {
    "show": ["zeig", "such", "find", "anzeig", "list", "gezeigt", "durchsuch", "search"],
    "move": ["verschieb", "schieb", "beweg", "move", "verleg", "einsortier", "packe", "pack", "ablegen", "ableg", "wegsortier",
             "transfer"],
    "copy": ["kopier", "copy", "dupliz", "sichere", "sichern"],
    "delete": ["lösch", "losch", "loesch", "entfern", "wegwerf", "wirf", "schmeiß", "schmeiss", "wegschmeiß", "wegmach",
               "delete", "remove", "erase", "wipe", "purge"],
    "organize": ["räum", "raeum", "aufräum", "ordne", "ordnen", "ordnung", "sortier", "organis", "organiz", "tidy", "sort", "clean"],
}
ACTION_EXACT = {"leg": "move", "lege": "move", "legen": "move", "tu": "move", "tue": "move", "put": "move", "ordn": "organize"}
NEGATOR = {"nicht", "nichts", "nix", "kein", "keine", "keinen", "keinesfalls", "niemals", "nie", "not", "never", "no", "don't",
           "dont", "nothing"}
SUBORD = r"^\s*(bevor|wenn|falls|ob|nachdem|sobald|bis|before|if|when|whether|once|after)\b"
QUESTION_START = r"^\s*(was|wo|wohin|welche[nrsm]?|wie|wieviel(e)?|warum|wieso|weshalb|wann|kann ich|könnte ich|darf ich|soll ich|" \
                 r"sollte ich|brauch ich|brauche ich|muss ich|gibt es|gibts|hab ich|habe ich|hast du|ist|sind|lohnt|" \
                 r"where|which|what|how|why|when|can i|could i|should i|do i|does|did|is|are|have i|has)\b"

# e: closed list of things Ordo cannot do -> (category, refusal text)
UNSUPPORTED = [
    ("archive", r"\b(komprimier\w*|zippen|zippe|zip it|entpack\w*|unzip\w*|extrahier\w*|extract\w*|archivier\w*|compress\w*|"
                r"dekomprimier\w*)\b|\b(in|zu|into|to) (ein|eine|einer|einem|einen|an|a|one|1) (einzige[nrs]?|single|gemeinsame[nrs]?)? ?"
                r"(zip|rar|7z|archiv|archive|tar)\b|\b(als|as) (zip|rar|7z|archiv|archive|tar)\b",
     "Packen und Entpacken (ZIP) kann Ordo noch nicht."),
    ("convert", r"\b(umwandel\w*|wandel\w*|konvertier\w*|convert\w*|zusammenfüg\w*|zusammenführ\w*|merge\w*|combine\w*|"
                r"verbind\w* zu)\b|\b(füg\w*|führ\w*)\b.{0,60}\bzusammen\b|\b(aus|from) .{1,60}\b(eine|einer|ein|an|a|one|1) (einzige[nrs]?|single|gemeinsame[nrs]?|neue[nrs]?|new)? ?"
                r"(pdf|datei|dokument|file|document)\b|\b(in|zu|als|into|to|as) (eine|ein|einer|an|a|one) (einzige[nrs]?|single)? ?(pdf|jpg|png|mp3|mp4|docx|word)\b",
     "Dateien umwandeln oder zusammenfügen kann Ordo nicht."),
    ("rename", r"\b(umbenenn\w*|benenn\w*|rename\w*|umtaufen)\b|\bnenn\w* .{1,40} (um|in)\b",
     "Umbenennen per Befehl kann Ordo noch nicht. Halte die Datei gedrückt und wähle „Umbenennen“."),
    ("share", r"\b(schick\w*|send\w*|verschick\w*|versend\w*|teilen|teile|share\w*|weiterleit\w*|forward\w*|mail\w*|"
              r"e mail\w*|email\w*|poste\w*|post it|hochlad\w*|upload\w*|cloud|google drive|dropbox|onedrive|synchronisier\w*|sync\w*)\b",
     "Teilen, Senden und Hochladen geht per Befehl nicht. Halte die Datei gedrückt und wähle „Teilen“."),
    ("open", r"^\s*(öffne|öffnen|open|starte|start|launch|spiel\w* .{0,40}ab|play|druck\w*|print\w*)\b|\b(abspielen|ausdrucken|drucken)\b",
     "Öffnen, Abspielen und Drucken geht per Befehl nicht. Ich kann dir die Datei zeigen; tippe sie dann an."),
    ("restore", r"\b(wiederherstell\w*|restore\w*|recover\w*|undelete|papierkorb|recycle bin|trash bin)\b|"
                r"\bgelöscht\w*\b.{0,60}\bwieder(finden|holen|bekommen|haben)?\b|\bdeleted\b.{0,60}\b(back|again|find)\b",
     "Gelöschte Dateien kann Ordo nicht zurückholen."),
    ("edit", r"\b(bearbeit\w*|edit\w*|zuschneid\w*|beschneid\w*|crop\w*|drehen|dreh|rotier\w*|rotate\w*|verkleiner\w*|"
             r"vergrößer\w*|resize\w*|scale)\b",
     "Dateien bearbeiten kann Ordo nicht."),
]
# m: Ordo keeps no log of habits, earlier commands or what was opened/searched
MEMORY = [r"\b(wie|so wie|as) (immer|sonst|gewohnt|üblich|normalerweise|usual|always|before|last time)\b",
          r"\bwie (letztes|beim letzten) mal\b|\blike last time\b|\bwie jede[nrs]? (tag|woche|monat|jahr|mal)\b",
          r"\bnormalerweise\b|\bdas übliche\b|\bthe usual\b|\bwie sonst\b",
          r"\b(das gleiche|dasselbe|dieselben?|genauso|genau so|the same)\b",
          r"\b(vorhin|von eben|wie eben|wie gerade|earlier today|a moment ago|just now)\b",
          r"\b(davon|die anderen|den anderen|die übrigen|den übrigen|die restlichen|den restlichen|the others|the rest)\b",
          r"\b(hingehör\w*|where (they|it) belongs?)\b|\bdahin,? wo\b",
          r"\b(ge(ö|oe)ffnet|aufgemacht|angeschaut|angesehen|angeguckt|benutzt|gesucht|opened|viewed|looked at|searched|used)\b",
          r"\bmeine[rn]? suche\b|\bmy search\b",
          # follow-ups that only make sense after an earlier turn: a continuation word first, or a pronoun as the object
          r"^\s*(und|nein|doch|no|and|but|aber)\b",
          r"^\s*(jetzt |nun |dann )?(nur )?(die|das|der|den|those|the ones) (von|aus|über|mit|unter|im|from|with|over|in)\b",
          r"\b(verschieb\w*|schieb\w*|lösch\w*|kopier\w*|pack\w*|leg|zeig\w*|move|delete|copy|show)( mir)? (sie|die|das|them|those|it|these)"
          r"( (nach|in|zu|to|into|bitte|mal|nochmal|again)\b|\s*$)",
          r"\b(die|der|das|the) (erste|zweite|dritte|andere|ältere|neuere|first|second|third|other one)\b"]
MEMORY_SAY = "Ich merke mir keine früheren Befehle, Suchen oder Gewohnheiten. Sag bitte genau, welche Dateien du meinst."
STORAGE = r"\b(speicher|speicherplatz|platz|space|storage)\b.{0,30}\b(voll|frei|schaffen|freimachen|frisst|fressen|belegt|" \
          r"full|free|freeing|takes? up|uses?)\b|\b(voll|frei|full|free up|mehr|wieder|kein|keinen|more|no)\b.{0,15}\b(speicher|speicherplatz|platz|space|storage)\b|" \
          r"\bam meisten (platz|speicher)\b|\bwas kann (ich )?(alles )?(weg|gelöscht werden|löschen|loeschen)\b|\bwhat can i (delete|remove)\b"
ASK = {"no_source": "In welchem Ordner soll ich das machen?",
       "no_dest": "Wohin soll ich die Dateien legen? Nenn mir bitte einen Ordner.",
       "ambiguous_folder": "Ich habe mehrere passende Ordner gefunden: {0}. Welchen meinst du?",
       "unknown_folder": "Den Ordner „{0}“ finde ich nicht. Wie heißt er genau?",
       "uncovered": "Ich weiß nicht, wie ich „{0}“ auf deine Dateien anwenden soll. Kommt das Wort im Dateinamen vor?",
       "vague_time": "Ab wann ist es für dich „{0}“? Sag es mir bitte genauer, zum Beispiel „älter als ein Jahr“.",
       "vague_size": "Ab welcher Größe meinst du „{0}“? Zum Beispiel „größer als 100 MB“.",
       "no_reading": "Ich finde keine Datei zu „{0}“. Kommt das Wort im Dateinamen vor?"}


# ---------------------------------------------------------------- text helpers
def norm(text):
    t = " " + text.lower().replace("’", "'").replace("‘", "'").replace("´", "'") + " "
    t = re.sub(r"(?<=[a-zäöüß])-(?=[a-zäöüß0-9])", " ", t)       # Instagram-Screenshots, Download-Ordner
    t = re.sub(r"(?<=\w)'s\b", "s", t)                             # video's
    t = re.sub(r"\b([a-z]) ([a-z]) ([a-z])\b", r"\1\2\3", t)       # dictated letters: a p k
    t = re.sub(r"\b(last|this|the|in the|next) fall\b", r"\1 autumn", t)   # English season, not "auf keinen Fall"
    t = re.sub(r"\s+", " ", t)
    return t


def stem(w):
    """Search stem of a noun: folded, plural and case endings off, Fugen-s after typical suffixes."""
    w = p0.fold(w)
    for suf in ("innen", "en", "er", "e", "n", "s"):
        if w.endswith(suf) and len(w) - len(suf) >= 4:
            return w[:-len(suf)]
    return w


def related(a, b):
    a, b = stem(a), stem(b)
    return len(a) >= 3 and len(b) >= 3 and (a in b or b in a)


def joinable_words(tree_leaves):
    words = set(TYPES) | set(ALIAS) | set(UNIT) | {"whatsapp", "download", "megabyte", "gigabyte", "kilobyte", "terabyte",
                                                   "dazugekommen", "hinzugekommen", "heruntergeladen", "runtergeladen"}
    return words | {p0.fold(l).replace(" ", "") for l in tree_leaves}


def join_dictation(t, words):
    """'down load' -> 'download', 'whats app' -> 'whatsapp': join two words when only the joined form is known."""
    toks = t.split(" ")
    out, i = [], 0
    while i < len(toks):
        if i + 1 < len(toks) and toks[i] and toks[i + 1] and (toks[i] + toks[i + 1]) in words and \
                not (toks[i] in words and toks[i + 1] in words):
            out.append(toks[i] + toks[i + 1])
            i += 2
        else:
            out.append(toks[i])
            i += 1
    return " ".join(out)


HEADS = sorted(set(TYPES) | {"dateien", "datei", "sachen", "unterlagen", "ordner", "papiere"} - {"a", "an"}, key=len, reverse=True)


def split_compounds(t):
    """'bewerbungsfotos' -> 'bewerbung fotos', 'steuerordner' -> 'steuer ordner': a known head at the end of a word."""
    def one(m):
        w = m.group(0)
        if w in TYPES or w in ALIAS or w in GENERIC or len(w) < 7:
            return w
        for h in HEADS:
            if w.endswith(h) and len(w) - len(h) >= 3 and len(h) >= 4:
                mod = w[:-len(h)]
                if mod.endswith("s") and re.search(r"(ung|heit|keit|schaft|ion|tät|ing|ts|ls|ns|gs)s$", mod):
                    mod = mod[:-1]
                return mod + " " + h
        return w
    return re.sub(r"[a-zäöüß]+", one, t)


class Work:
    """The command as a string where every consumed span is blanked, so the guard sees what is left."""

    def __init__(self, t):
        self.s = t

    def take(self, pattern, fn=None):
        hits = []
        for m in list(re.finditer(pattern, self.s)):
            if fn is None or fn(m) is not False:
                hits.append(m)
                self.s = self.s[:m.start()] + " " * (m.end() - m.start()) + self.s[m.end():]
        return hits

    def tokens(self):
        return re.findall(r"[a-zäöüß0-9]+", self.s)


# ---------------------------------------------------------------- b: time grammar
def add_months(d, n):
    y, m = divmod(d.year * 12 + d.month - 1 + n, 12)
    return date(y, m + 1, 1)


def easter(y):
    a, b, c = y % 19, y // 100, y % 100
    d, e = b // 4, b % 4
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = c // 4, c % 4
    l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451
    return date(y, (h + l - 7 * m + 114) // 31, (h + l - 7 * m + 114) % 31 + 1)


def latest(make, today, year=None, ended=False):
    """The most recent occurrence of a yearly period that has started (or ended, for 'letzten Sommer')."""
    if year:
        return make(year)
    for y in (today.year, today.year - 1, today.year - 2):
        a, b = make(y)
        if (b <= today if ended else a <= today):
            return a, b
    return make(today.year - 1)


HOL = "|".join(sorted(list(HOLIDAYS) + ["ostern", "easter", "ostersonntag", "ostermontag", "pfingsten", "pentecost"],
                      key=len, reverse=True))
MON = "|".join(sorted(MONTHS, key=len, reverse=True))
WD = "|".join(sorted(WEEKDAYS, key=len, reverse=True))
SEA = "|".join(sorted(SEASONS, key=len, reverse=True))
YEAR = r"(19[7-9]\d|20\d\d)(?! ?(mb|gb|kb|tb|byte|megabyte|gigabyte|stück|dateien|files|fotos|bilder|photos))"
LAST = r"(letzte[mnrs]?|vergangene[mnrs]?|vorige[mnrs]?|last|previous|past)"
THIS = r"(diese[mnrs]?|this|current|aktuelle[mnrs]?)"
POINT = (rf"(?P<iso>\d{{4}}-\d{{2}}-\d{{2}})|(?P<dmy>(?P<d1>\d{{1}}|[0-2]\d|3[01])\.(?P<m1>\d{{1,2}})\.(?P<y1>\d{{2,4}})?)|"
         rf"(?P<dm>(?P<d2>\d{{1,2}})(\.|st|nd|rd|th)? (of )?(?P<m2>{MON})( (?P<y2>\d{{4}}))?)|"
         rf"(?P<md>(?P<m3>{MON}) (?P<d3>\d{{1,2}})(st|nd|rd|th)?(,? (?P<y3>\d{{4}}))?)|"
         rf"(?P<mon>((?P<monmod>{LAST}|{THIS}) )?(?P<m4>{MON})( (?P<y4>\d{{4}}))?)|"
         rf"(?P<sea>((?P<seamod>{LAST}|{THIS}) )?(?P<s>{SEA})( (?P<y5>\d{{4}}))?)|"
         rf"(?P<hol>((?P<holmod>{LAST}|{THIS}) )?(?P<h>{HOL})( (?P<y6>\d{{4}}))?)|"
         rf"(?P<wd>((?P<wdmod>{LAST}|{THIS}) )?(?P<w>{WD}))|"
         rf"(?P<rel>vorgestern|day before yesterday|gestern|yesterday|heute|today|tonight|heute morgen|this morning)|"
         rf"(?P<cal>(?P<calmod>{LAST}|{THIS}) (?P<calu>woche|week|monat|month|jahr|year|wochenende|weekend))|"
         rf"(?P<ago>vor (?P<an>{NUMW}) (?P<au>{UNITW})|(?P<an2>{NUMW}) (?P<au2>{UNITW}) ago)|"
         rf"(?P<yr>{YEAR})")


def resolve_point(m, today):
    """[start, end) of one time point as dates."""
    g = m.groupdict()
    y2k = lambda y: (2000 + int(y)) if y and len(y) == 2 else (int(y) if y else None)
    def day_recent(mo, d, y):
        if y:
            return date(y, mo, d)
        x = date(today.year, mo, d)
        return x if x <= today else date(today.year - 1, mo, d)
    if g["iso"]:
        d = date.fromisoformat(g["iso"])
        return d, d + timedelta(1)
    if g["dmy"]:
        d = day_recent(int(g["m1"]), int(g["d1"]), y2k(g["y1"]))
        return d, d + timedelta(1)
    if g["dm"] or g["md"]:
        mo = MONTHS[g["m2"] or g["m3"]]
        d = day_recent(mo, int(g["d2"] or g["d3"]), y2k(g["y2"] or g["y3"]))
        return d, d + timedelta(1)
    if g["mon"]:
        mo, mod = MONTHS[g["m4"]], g["monmod"] or ""
        make = lambda y: (date(y, mo, 1), add_months(date(y, mo, 1), 1))
        if re.match(THIS, mod):
            return make(today.year)
        return latest(make, today, y2k(g["y4"]), ended=bool(re.match(LAST, mod)))
    if g["sea"]:
        sm, mod = SEASONS[g["s"]], g["seamod"] or ""
        make = lambda y: (date(y, sm, 1) if sm != 12 else date(y - 1, 12, 1),
                          add_months(date(y, sm, 1) if sm != 12 else date(y - 1, 12, 1), 3))
        if re.match(THIS, mod):
            return make(today.year + (1 if sm == 12 and today.month == 12 else 0))
        return latest(make, today, int(g["y5"]) + (1 if sm == 12 else 0) if g["y5"] else None, ended=bool(re.match(LAST, mod)))
    if g["hol"]:
        h, mod = g["h"], g["holmod"] or ""
        if h in ("ostern", "easter", "ostersonntag", "ostermontag"):
            make = lambda y: (easter(y), easter(y) + timedelta(2))
        elif h in ("pfingsten", "pentecost"):
            make = lambda y: (easter(y) + timedelta(49), easter(y) + timedelta(51))
        else:
            mo, d, n = HOLIDAYS[h]
            make = lambda y: (date(y, mo, d), date(y, mo, d) + timedelta(n))
        if re.match(THIS, mod):
            return make(today.year)
        return latest(make, today, int(g["y6"]) if g["y6"] else None, ended=bool(re.match(LAST, mod)))
    if g["wd"]:
        back = (today.weekday() - WEEKDAYS[g["w"]]) % 7 or 7   # the last such day before today
        d = today - timedelta(back)
        return d, d + timedelta(1)
    if g["rel"]:
        r = g["rel"]
        off = 2 if r in ("vorgestern", "day before yesterday") else 1 if r in ("gestern", "yesterday") else 0
        d = today - timedelta(off)
        return d, d + timedelta(1)
    if g["cal"]:
        last = bool(re.match(LAST, g["calmod"]))
        u = g["calu"]
        if u in ("woche", "week"):
            mon = today - timedelta(today.weekday())
            return (mon - timedelta(7), mon) if last else (mon, today + timedelta(1))
        if u in ("wochenende", "weekend"):
            sat = today - timedelta((today.weekday() - 5) % 7)
            if last and sat + timedelta(2) > today:
                sat -= timedelta(7)
            return sat, sat + timedelta(2)
        if u in ("monat", "month"):
            first = date(today.year, today.month, 1)
            return (add_months(first, -1), first) if last else (first, today + timedelta(1))
        jan = date(today.year, 1, 1)
        return (date(today.year - 1, 1, 1), jan) if last else (jan, today + timedelta(1))
    if g["ago"]:
        n, u = num(g["an"] or g["an2"]), UNIT[g["au"] or g["au2"]]
        d = today - timedelta(int(n * DAYS_PER[u]))
        if u == "d":
            return d, d + timedelta(1)
        if u == "w":
            mon = d - timedelta(d.weekday())
            return mon, mon + timedelta(7)
        if u == "m":
            first = add_months(date(today.year, today.month, 1), -int(n))
            return first, add_months(first, 1)
        return date(today.year - int(n), 1, 1), date(today.year - int(n) + 1, 1, 1)
    if g["yr"]:
        y = int(m.group("yr")[:4])
        return date(y, 1, 1), date(y + 1, 1, 1)
    raise ValueError(m.group(0))


def parse_time(w, today, now):
    """Consumes every time expression in w; returns (date_from, date_to): ISO day or minute, from inclusive, to exclusive."""
    lo, hi = [], []
    iso = lambda d: d.isoformat() if isinstance(d, date) else None
    P = "(?:" + re.sub(r"\(\?P<\w+>", "(?:", POINT) + ")"   # same grammar without group names, to nest it
    rng = []

    def span(m, name):
        return resolve_point(re.fullmatch(POINT, m.group(name)), today)

    def between(m):
        a, b = span(m, "p1"), span(m, "p2")
        rng.append((a[0], b[1]))
    w.take(rf"\b(zwischen|between) (dem |der |den )?(?P<p1>{P}) (und|and) (dem |der |den )?(?P<p2>{P})(?!\w)", between)
    w.take(rf"\b(von|vom|from) (dem |der )?(?P<p1>{P}) (bis|to|until|till|through) (zum |zur |zu |zum )?(?P<p2>{P})(?!\w)", between)
    # durations: lengths from now
    def dur(sign):
        def f(m):
            n, u = num(m.group("n")), UNIT[m.group("u")]
            cut = now - timedelta(days=n * DAYS_PER[u])
            rng.append((None, cut) if sign == "older" else (cut, None))
        return f
    D = rf"(?P<n>{NUMW}) (?P<u>{UNITW})"
    for pat in (rf"\b(älter|aelter|older) (als|than) {D}\b", rf"\b(mehr als|more than|über|over) {D} (alt|old)\b",
                rf"\bseit (über|mehr als) {D}\b"):
        w.take(pat, dur("older"))
    for pat in (rf"\b(jünger|juenger|neuer|newer|younger) (als|than) {D}\b",
                rf"\b(weniger als|less than|höchstens|at most|nicht älter als|not older than) {D}( (alt|old))?\b",
                rf"\b(innerhalb|within) (der |the )?(letzten |last |past )?{D}\b", rf"\bseit {D}\b", rf"\bfor the (last|past) {D}\b"):
        w.take(pat, dur("newer"))
    def last_n(m):
        n, u = num(m.group("n")) if m.group("n") else (7 if UNIT[m.group("u")] == "d" else 1), UNIT[m.group("u")]
        start = today - timedelta(days=int(n * DAYS_PER[u])) if u in "dw" else (
            add_months(date(today.year, today.month, 1), -int(n)) if u == "m" else date(today.year - int(n), today.month, 1))
        if u in "dw":
            rng.append((start, None))
        else:   # "letzten 3 Monate": from the same day N months back
            back = add_months(date(today.year, today.month, 1), -int(n) * (12 if u == "y" else 1))
            rng.append((date(back.year, back.month, min(today.day, 28)), None))
    w.take(rf"\b(in |während |during |over |of )?(den |der |the )?(letzten|vergangenen|past|last) (?P<n>{NUMW} )?(?P<u>tagen|tage|wochen|monaten|monate|jahren|jahre|days|weeks|months|years)\b",
           last_n)
    w.take(r"\b(vor (ein paar|paar|einigen|wenigen|kurzem) (tagen)?|neulich|kürzlich|vor kurzem|letztens|recently|lately|"
           r"(a )?few days ago|the other day|neu hinzugekommen\w*)\b", lambda m: rng.append((today - timedelta(7), None)))
    def bound(kind):
        def f(m):
            a, b = span(m, "p")
            rng.append({"since": (a, None), "until": (None, b), "before": (None, a), "after": (b, None)}[kind])
        return f
    w.take(rf"\b(seit|since|ab|starting) (dem |der |den |letzte[nrm]? )?(?P<p>{P})(?!\w)", bound("since"))
    w.take(rf"\b(bis|until|till|up to) (zum |zur |zu |zum |dem |der )?(?P<p>{P})(?!\w)", bound("until"))
    w.take(rf"\b(vor|before) (dem |der |den )?(?P<p>(?!{NUMW} ){P})(?!\w)", bound("before"))
    w.take(rf"\b(nach|after) (dem |der |den )?(?P<p>(?!{NUMW} ){P})(?!\w)", bound("after"))
    w.take(rf"\b(von|vom|am|im|in|aus|from|on|in|during|at|of|des|der|dem)? ?(?P<p>{P})(?!\w)",
           lambda m: rng.append(span(m, "p")))
    for a, b in rng:
        if a is not None:
            lo.append(a if isinstance(a, datetime) else datetime.combine(a, datetime.min.time()))
        if b is not None:
            hi.append(b if isinstance(b, datetime) else datetime.combine(b, datetime.min.time()))
    if rng:
        w.take(r"\b(alte[nrs]?|alt|old|neue[nrs]?|new)\b")
    fmt = lambda x: (x.date().isoformat() if x.time() == datetime.min.time() else x.isoformat(timespec="minutes"))
    return fmt(max(lo)) if lo else "", fmt(min(hi)) if hi else ""


# ---------------------------------------------------------------- b: size grammar
SIZEU = {"b": 1, "byte": 1, "bytes": 1, "kb": 1e3, "kilobyte": 1e3, "mb": 1e6, "megabyte": 1e6, "mega": 1e6, "meg": 1e6,
         "gb": 1e9, "gigabyte": 1e9, "giga": 1e9, "gig": 1e9, "gigs": 1e9, "tb": 1e12, "terabyte": 1e12}
SU = r"(megabytes?|gigabytes?|kilobytes?|terabytes?|bytes?|mega|giga|meg|gigs?|kb|mb|gb|tb)"
SN = rf"(?:ein |a )?(?P<n>halbes|halben|half an?|{NUMW})"


def size_val(n, u):
    v = 0.5 if n.startswith(("halb", "half")) else num(n)
    u = u.rstrip("s") if u.rstrip("s") in SIZEU else u
    return int(v * SIZEU[u])


def parse_size(w):
    out = {}
    def between(m):
        out["min"], out["max"] = size_val(m.group("a"), m.group("u")), size_val(m.group("b"), m.group("u"))
    w.take(rf"\b(zwischen|between) (?P<a>{NUMW}) ({SU} )?(und|and) (?P<b>{NUMW}) ?(?P<u>{SU})\b", between)
    w.take(rf"\b(größer|grösser|groesser|über|ueber|mehr als|ab|mindestens|bigger|larger|greater|more|over|above|at least)"
           rf"( als| than)? {SN} ?(?P<u>{SU})( groß| gross| big| large)?\b",
           lambda m: out.__setitem__("min", size_val(m.group("n"), m.group("u"))))
    w.take(rf"\b(kleiner|unter|weniger als|höchstens|bis|smaller|less|under|below|at most)( als| than)? {SN} ?(?P<u>{SU})"
           rf"( groß| gross| klein| big| small)?\b", lambda m: out.__setitem__("max", size_val(m.group("n"), m.group("u"))))
    w.take(rf"\b{SN} ?(?P<u>{SU})\b", lambda m: out.__setitem__("min", size_val(m.group("n"), m.group("u"))))
    return out.get("min", 0), out.get("max", 0)


# ---------------------------------------------------------------- c: exclusion
EXCL = r"\b(außer|ausser|abgesehen von|bis auf|ohne|except( for)?|excluding|other than|apart from|but not|without|" \
       r"aber nicht|aber keine|nur nicht|not the|nicht die|nicht den|nicht das|nicht der)\b"
STOP_AT = set("im in ins aus nach von vom auf am zu zum zur from to into on at bei mit with".split())


def parse_exclusion(w, verb_word):
    kinds, exts, terms = [], [], []
    def f(m):
        rest = w.s[m.end():]
        toks = list(re.finditer(r"[a-zäöüß0-9]+|[,.;!?]", rest))
        used = 0
        got = False
        for t in toks:
            x = t.group(0)
            if x in ("von", "vom", "aus", "from", "of") and not got:
                used = t.end()
                continue   # "außer denen aus Lissabon", "ohne die vom Urlaub"
            if x in ",.;!?" or x in STOP_AT or verb_word(x):
                break
            used = t.end()
            if x in ("und", "and", "oder", "or") or x in STOP:
                continue
            if x in TYPES:
                kinds.extend(TYPES[x][0])
                exts.extend(TYPES[x][1])
            elif x not in GENERIC:
                terms.append(stem(x))
            got = True
        if not got:
            return False
        w.s = w.s[:m.end()] + " " * used + w.s[m.end() + used:]
        return True
    w.take(EXCL, f)
    return sorted(set(kinds)), sorted(set(exts)), terms


# ---------------------------------------------------------------- d: folders against the real tree
def folder_index(folders):
    leaves = {}
    for f in folders:
        leaves.setdefault(p0.fold(f.rsplit("/", 1)[-1]), []).append(f)
    return leaves


def resolve_folder(name, folders, leaves=None):
    """-> (path, how) with how in exact|alias|fuzzy, or (None, 'missing') or (list, 'ambiguous')."""
    leaves = leaves or folder_index(folders)
    n = re.sub(r"^(den |dem |der |die |the |meine[nm]? |my )?(ordner |folder )?", "", name.strip().strip("/").lower())
    n = re.sub(r"[ -]?(ordner|folder)$", "", n).strip()
    if not n or n in bench.HERE_WORDS:
        return None, "missing"
    fs = {f.lower(): f for f in folders}
    if n in fs:
        return fs[n], "exact"
    first, _, rest = n.partition("/")
    for cand in ALIAS.get(first, []):
        full = cand + ("/" + rest if rest else "")
        if full.lower() in fs:
            return fs[full.lower()], "alias"
    if n.startswith("whatsapp "):
        for leaf in ("WhatsApp " + n.split(" ", 1)[1].title(),):
            if (WA + leaf).lower() in fs:
                return fs[(WA + leaf).lower()], "alias"
    hits = leaves.get(p0.fold(n), [])
    if len(hits) == 1:
        return hits[0], "exact"
    if len(hits) > 1:
        top = sorted(hits, key=lambda f: f.count("/"))
        return (top[0], "exact") if top[0].count("/") < top[1].count("/") else (hits, "ambiguous")
    # unique fuzzy: same stem (Screenshot ~ Screenshots) or one edit for long names
    fz = [f for l, fl in leaves.items() for f in fl
          if stem(l) == stem(n) or (len(n) >= 5 and edit1(p0.fold(n).replace(" ", ""), l.replace(" ", "")))]
    if len(fz) == 1:
        return fz[0], "fuzzy"
    if len(fz) > 1:
        return fz, "ambiguous"
    return None, "missing"


def edit1(a, b):
    if a == b:
        return True
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) == 1
    if len(a) > len(b):
        a, b = b, a
    return any(b[:i] + b[i + 1:] == a for i in range(len(b)))


def messenger_folder(kind, folders):
    fs = set(folders)
    for leaf in MESSENGER.get(kind, []):
        if WA + leaf in fs:
            return WA + leaf
    return None


DST_PREP = {"nach", "to", "into", "zu", "zum", "zur", "ins"}
SRC_PREP = {"aus", "im", "from", "von", "vom", "inside", "within"}
ACC_ART = {"den", "die", "das", "einen", "eine", "ein", "nen", "meinen", "meine", "mein", "deinen", "unseren"}   # "in" + Akkusativ = wohin
DAT_ART = {"dem", "der", "einem", "einer", "meinem", "meiner", "deinem", "unserem"}                              # "in" + Dativ = wo
NEWQ = {"neuen", "neue", "neues", "eigenen", "eigene", "extra", "separaten", "new", "separate", "own", "the", "my", "a", "an"}
FOLDERWORD = {"ordner", "folder", "verzeichnis", "directory"}
FOLDERISH = {"screenshots", "screenshot", "bildschirmfotos", "wallpaper", "wallpapers", "hintergrundbilder"}


def parse_folders(w, folders, leaves):
    """Consumes folder mentions. -> (src [(path, strength)], dst [path | new name | ""], problems)
    strength 2 = said as a place (preposition or 'Ordner'), 1 = a folder name or standard folder word somewhere."""
    src, dst, problems, bare = [], [], [], []
    # messenger + kind ("WhatsApp Bilder", "whatsapp voice messages"); voice messages alone
    named = any(p0.fold("whatsapp " + x) in leaves or stem("whatsapp " + x) in {stem(l) for l in leaves}
                for x in re.findall(r"\bwhatsapp ([a-zäöüß]+)", w.s))   # "WhatsApp Documents" is a real folder name
    if re.search(r"\bwhatsapp\b", w.s) and not named:
        kind = "voice" if re.search(VOICE, w.s) else next((TYPES[x][0][0] for x in re.findall(r"[a-zäöüß0-9]+", w.s)
                                                           if x in TYPES and TYPES[x][0] and TYPES[x][0][0] in MESSENGER), None)
        if not kind and re.search(r"\b(pdfs?|docx?|xlsx)\b", w.s):
            kind = "document"
        path = messenger_folder(kind, folders) if kind else next((f for f in (WA[:-1],) if f in folders), None)
        if path:
            src.append((path, 1))
        w.take(r"\bwhatsapp\b")
    elif re.search(VOICE, w.s) and not named:
        path = messenger_folder("voice", folders)
        if path:
            src.append((path, 1))
    toks = [(m.group(0), m.start(), m.end()) for m in re.finditer(r"[a-zäöüß0-9]+(?:[/._][a-zäöüß0-9]+)*", w.s)]
    used = set()

    def blank(i, j):
        for k in range(i, j):
            used.add(k)

    def resolve_run(i, maxlen=3):
        """Longest run of 1..3 tokens from i that names a folder -> (k, path|list, how)."""
        for k in range(min(maxlen, len(toks) - i), 0, -1):
            if any(x in used for x in range(i, i + k)):
                continue
            name = w.s[toks[i][1]:toks[i + k - 1][2]]
            if k == 1 and (name in STOP or name in GENERIC):
                continue
            path, how = resolve_folder(name, folders, leaves)
            if path:
                return k, path, how
        return 0, None, "missing"

    i = 0
    while i < len(toks):
        word = toks[i][0]
        role = None
        j = i + 1
        if word in DST_PREP:
            role = "dst"
        elif word in SRC_PREP:
            role = "src"
        elif word == "in":
            nxt = toks[j][0] if j < len(toks) else ""
            role = "dst" if nxt in ACC_ART else "src"
        if role is None or i in used:
            i += 1
            continue
        while j < len(toks) and (toks[j][0] in ACC_ART | DAT_ART | NEWQ):
            j += 1
        fw = j < len(toks) and toks[j][0] in FOLDERWORD
        if fw:
            j += 1
        k, path, how = resolve_run(j) if j < len(toks) else (0, None, "missing")
        if not k and not fw and j + 1 < len(toks) and toks[j + 1][0] in FOLDERWORD:
            k, path, how = resolve_run(j, 1)   # "im download ordner"
        if how == "ambiguous":
            problems.append(("ambiguous_folder", ", ".join(sorted(x.rsplit("/", 1)[-1] for x in path))))
            blank(i, j + 1)
        elif path:
            (src.append((path, 2)) if role == "src" else dst.append(path))
            end = j + k
            if end < len(toks) and toks[end][0] in FOLDERWORD:
                end += 1
            blank(i, end)
            i = end
            continue
        elif role == "dst" and j < len(toks) and word != "zu" and (fw or word in ("nach", "to", "into")):
            nm = toks[j][0]
            if nm not in STOP and nm not in GENERIC and nm not in TYPES and not verb_of(nm) and nm not in UNIT and \
                    nm.isalpha() and not re.fullmatch(f"{MON}|{WD}|{SEA}|{HOL}|heute|gestern|vorgestern|today|yesterday", nm):
                dst.append(nm.title())   # a new folder of that name
                blank(i, j + 1)
        elif role == "dst" and fw:
            dst.append("")   # "in einen neuen Ordner" without a name
            blank(i, j)
        elif fw and role == "src" and j < len(toks) and toks[j][0] not in STOP | GENERIC and not verb_of(toks[j][0]):
            problems.append(("unknown_folder", toks[j][0]))
        i += 1
    # "X Ordner" / "Ordner X" without a preposition: the 1-2 words before, else the 1-3 words after
    for i, (word, _, _) in enumerate(toks):
        if word in FOLDERWORD and i not in used:
            for a_, b_ in [(i - 2, i), (i - 1, i), (i + 1, i + 4), (i + 1, i + 3), (i + 1, i + 2)]:
                if a_ < 0 or b_ > len(toks) or any(x in used for x in range(a_, b_)):
                    continue
                path, how = resolve_folder(w.s[toks[a_][1]:toks[b_ - 1][2]], folders, leaves)
                if path and how != "ambiguous":
                    src.append((path, 2))
                    blank(min(a_, i), max(b_, i + 1))
                    break
            else:
                used.add(i)
    for k in sorted(used):
        a, b = toks[k][1], toks[k][2]
        w.s = w.s[:a] + " " * (b - a) + w.s[b:]
    # bare folder names on the phone (1-3 words), then standard folder words; plain type words are no folder
    toks = [(m.group(0), m.start(), m.end()) for m in re.finditer(r"[a-zäöüß0-9]+(?:[._][a-zäöüß0-9]+)*", w.s)]
    for i in range(len(toks)):
        for k in (3, 2, 1):
            if i + k > len(toks):
                continue
            words = [t[0] for t in toks[i:i + k]]
            if k == 1 and (words[0] in STOP or words[0] in GENERIC or (words[0] in TYPES and words[0] not in FOLDERISH)
                           or len(words[0]) < 4):
                continue
            name = " ".join(words)
            hits = leaves.get(p0.fold(name), [])
            if not hits and k == 1 and words[0] in ALIAS and (words[0] not in TYPES or words[0] in FOLDERISH):
                hits = [next((c for c in ALIAS[words[0]] if c in folders), None)] if any(c in folders for c in ALIAS[words[0]]) else []
            if len(hits) == 1 and w.s[toks[i][1]:toks[i + k - 1][2]].strip():
                src.append((hits[0], 1))
                a, b = toks[i][1], toks[i + k - 1][2]
                bare.append((hits[0], a, w.s[a:b]))   # given back when another folder is the source ("die Bewerbungen aus Dokumente")
                w.s = w.s[:a] + " " * (b - a) + w.s[b:]
                break
    if re.search(r"\bhandy ?(videos?|fotos?|bilder|clips?|photos?|aufnahmen)\b", w.s) or \
            re.search(rf"\b(fotos?|bilder|bild|videos?|photos?|pictures?|clips?)\b.{{0,40}}{TAKEN}", w.s):
        if "DCIM/Camera" in folders:
            src.append(("DCIM/Camera", 1))
        w.take(r"\bhandy(?= ?(videos?|fotos?|bilder|clips?|photos?|aufnahmen)\b)")
        w.take(TAKEN)
    if re.search(DOWNLOAD_VERB, w.s) and "Download" in folders:
        src.append(("Download", 1))
        w.take(DOWNLOAD_VERB)
    return src, dst, problems, bare


# ---------------------------------------------------------------- words: types, sort, counts, verbs
def verb_of(tok):
    if tok in GENERIC or tok in TYPES:
        return None
    if tok in ACTION_EXACT:
        return ACTION_EXACT[tok]
    for act, stems in ACTION_STEMS.items():
        if any(tok.startswith(s) for s in stems):
            return act
    return None


def is_verb(tok):
    return tok in VERB_EXACT or verb_of(tok) is not None


SORTW = {"size": r"größte[nrs]?|groesste[nrs]?|grösste[nrs]?|biggest|largest|am größten|am meisten platz",
         "smallest": r"kleinste[nrs]?|smallest",
         "newest": r"neueste[nrs]?|jüngste[nrs]?|newest|latest|most recent|zuletzt|als letztes|als letzte[rs]?",
         "oldest": r"älteste[nrs]?|aelteste[nrs]?|oldest|earliest"}


def parse_sort(w):
    sort, limit = "", 0
    for key, pat in SORTW.items():
        m = re.search(rf"\b((?P<art>die|der|das|den|dem|mein|meine|meinen|meinem|meiner|the|my|top) )?((?P<n>{NUMW}) )?(?P<adj>{pat})"
                      rf"(?P<noun> [a-zäöüß0-9]+)?", w.s)
        if m:
            sort = key
            if m.group("n"):
                limit = int(num(m.group("n")))
            elif m.group("adj") in ("zuletzt", "als letztes", "als letzte", "als letzter"):
                limit = 1
            elif m.group("art") or m.group("adj").startswith(("biggest", "largest", "newest", "latest", "smallest", "oldest")):
                adj, art, noun = m.group("adj"), m.group("art") or "", (m.group("noun") or "").strip()
                if adj.startswith("am "):
                    pass
                elif adj.endswith(("e", "er", "es")) or (adj.endswith("en") and art in ("den", "dem", "meinen", "meinem")):
                    limit = 1   # singular: "die größte Datei", "den größten Film"
                elif not adj.endswith("en") and noun and not noun.endswith("s") and noun not in STOP:
                    limit = 1   # "the largest movie"
            w.take(re.escape(m.group(0).rstrip()) if not m.group("noun") else re.escape(m.group(0)[:m.start("noun") - m.start()]))
            break
    # "die letzten fünf X", "mein letzter Screenshot", "the last 3 photos" (not a time unit: that is the time grammar)
    m = re.search(rf"\b(?P<art>die|der|das|den|mein|meine|meinen|meiner|the|my)? ?(?P<adj>letzte[nrs]?|last)\b (?P<n>{NUMW} )?(?P<noun>[a-zäöüß]+)?", w.s)
    if m and not sort and (m.group("noun") or "") not in UNIT and m.group("noun") not in ("mal", "time", "times"):
        sort = "newest"
        if m.group("n"):
            limit = int(num(m.group("n").strip()))
        elif m.group("adj") in ("letzte", "letzter", "letztes") or (m.group("adj") == "letzten" and m.group("art") in ("den", "meinen")) \
                or (m.group("adj") == "last" and not (m.group("noun") or "s").endswith("s")):
            limit = 1
        w.take(re.escape(m.group(0)[:(m.start("noun") if m.group("noun") else m.end()) - m.start()]))
    m = re.search(rf"\b(top|erste[nn]?|first) (?P<n>{NUMW})\b", w.s)
    if m and sort:
        limit = int(num(m.group("n")))
        w.take(re.escape(m.group(0)))
    return sort, limit


def parse_types(w):
    kinds, exts = [], []
    for tok in re.findall(r"[a-zäöüß0-9]+", w.s):
        if tok in TYPES:
            kinds += TYPES[tok][0]
            exts += TYPES[tok][1]
    for word in TYPES:
        w.take(rf"\b{re.escape(word)}\b")
    w.take(VOICE)
    exts = sorted(set(exts))
    kinds = [] if exts else sorted(set(kinds))
    return kinds, exts


def parse_count(w):
    m = re.search(rf"\b(die|diese|meine|the|my|alle|all)? ?(?P<n>\d+|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|two|three|four|"
                  rf"five|six|seven|eight|nine|ten|beide|beiden|both)\b(?! ?(mb|gb|kb|uhr|%))", w.s)
    if m:
        w.take(rf"\b{m.group('n')}\b")
        return int(num(m.group("n")))
    return 0


def clauses(text):
    t = norm(text)
    parts = re.split(r"(?<=[.;!?])\s+|[,:]\s+", t)
    return [(c, c.strip().endswith("?") or bool(re.match(QUESTION_START, c)), bool(re.match(SUBORD, c))) for c in parts if c.strip()]


def actions_said(text):
    """Action verbs in imperative (not question, not subordinate) clauses, without a negation nearby."""
    out = set()
    for c, q, sub in clauses(text):
        toks = re.findall(r"[a-zäöüß']+", c)
        for i, t in enumerate(toks):
            act = verb_of(t)
            if t in ("weg",) and i > 0 and toks[i - 1] in ("kann", "muss", "soll", "darf", "können", "müssen"):
                act = "delete"
            if not act:
                continue
            near = set(toks[max(0, i - 4):i]) | set(toks[i + 1:i + 3])
            if near & NEGATOR or re.search(r"auf keinen fall|bloß nicht|keinesfalls", c):
                continue
            if q or sub:
                continue
            out.add(act)
    return out


# ---------------------------------------------------------------- the pipeline
def prepare(text, folders):
    """Everything the code reads from the words, before the model. Pure, for unit tests."""
    leaves = folder_index(folders)
    t = join_dictation(norm(text), joinable_words([l for l in leaves]))
    t = split_compounds(t)
    return t, leaves


def files_of(tree, plan, text):
    return p0.execute(tree, plan, text)["files"]


VAGUE_T = r"\b(alte[nrs]?|alt|old|older|ältere[nrs]?|neue[nrs]?|newer|new|aktuelle[nrs]?)\b"
VAGUE_S = r"\b(große[nrs]?|groß|gross|riesige[nrs]?|big|bigger|large|huge|kleine[nrs]?|small|tiny)\b"
GROUP = {"month": r"monat|monaten|monate|month|months|datum|date|jahr|jahren|year|years",
         "type": r"typ|typen|dateityp|dateitypen|art|arten|endung|endungen|type|types|kind|file type|dateiart"}


def negated_words(text):
    """Words in a clause whose action verb is negated ("aber lösch nichts Wichtiges"): they say what NOT to do."""
    out = set()
    for c, _, _ in clauses(text):
        toks = re.findall(r"[a-zäöüß']+", c)
        for i, t in enumerate(toks):
            if verb_of(t) and (set(toks[max(0, i - 4):i]) | set(toks[i + 1:i + 3])) & NEGATOR:
                out |= set(toks)
    return out


def decide(text, raw, tree, flags=ALL, today=None, now=None):
    now = now or datetime.fromtimestamp(bench.NOW)
    today = today or now.date()
    folders = sorted(tree.folders)
    out = {"status": "plan", "plan": None, "reason": "", "say": "", "warn": [], "unaccounted": []}
    ask = lambda reason, *a: {**out, "status": "ask", "reason": reason, "say": ASK[reason].format(*a)}
    t, leaves = prepare(text, folders)
    # e: closed list of things Ordo cannot do; m: no memory
    if "e" in flags:
        for cat, pat, say in UNSUPPORTED:
            if re.search(pat, t):
                return {**out, "status": "refuse", "reason": cat, "say": say}
    for pat in MEMORY:
        if re.search(pat, t):
            return {**out, "status": "refuse", "reason": "memory", "say": MEMORY_SAY}
    raw = {**DEFAULT, **(raw or {})}
    act = raw.get("action") or "none"
    w = Work(t)
    plan = {**DEFAULT, "action": act}
    storage = bool(w.take(STORAGE))
    if "c" in flags:   # c: exclusion
        ek, ee, et = parse_exclusion(w, is_verb)
        plan.update(exclude_kinds=ek, exclude_extensions=ee, exclude_terms=et)
    group = next((g for g, pat in GROUP.items() if w.take(rf"\b(nach|by|per) ({pat})\b")), "")
    src, dst, problems, bare = parse_folders(w, folders, leaves) if "d" in flags else ([], [], [], [])
    if "b" in flags:   # b: time and size, entirely in code
        plan["date_from"], plan["date_to"] = parse_time(w, today, now)
        plan["min_bytes"], plan["max_bytes"] = parse_size(w)
    else:
        import rules
        plan.update(rules.time_and_size(rules.norm(text)))
    sort, limit = parse_sort(w)
    count = parse_count(w)
    kinds, exts = parse_types(w)
    w.take(r"\b(doppelt\w*|duplikat\w*|mehrfach\w*|duplicates?|dupes?|identische[nr]?)\b")
    vague_t = re.search(VAGUE_T, w.s)
    vague_s = re.search(VAGUE_S, w.s)
    w.take(VAGUE_T)
    w.take(VAGUE_S)
    # action safety per clause (question, subordinate clause, negation); code never creates a delete
    said = actions_said(text)
    if act in DESTRUCTIVE + ("copy",) and act not in said:
        alt = [a for a in said if a in ("move", "copy", "organize")]
        act = alt[0] if len(alt) == 1 else "show"
    if act == "organize" and "move" in said and dst:
        act = "move"
    if storage and act in ("show", "none", "delete"):
        act, sort = "show", sort or "size"
    plan["action"] = act
    if act == "none":
        return {**out, "plan": plan}
    plan.update(kinds=kinds, extensions=exts, sort=sort, limit=limit if sort else 0, group_by=group or raw["group_by"])
    if act == "organize" and not plan["group_by"]:
        plan["group_by"] = "type"
    changes = act in DESTRUCTIVE + ("copy",)
    # vague size/time words: a show sorts by them, a change asks
    if vague_s and not (plan.get("min_bytes") or plan.get("max_bytes")) and not sort:
        if changes and "b" in flags:
            return {**ask("vague_size", vague_s.group(1)), "plan": plan}
        plan["sort"] = "smallest" if re.match(r"klein|small|tiny", vague_s.group(1)) else "size"
    if vague_t and not (plan.get("date_from") or plan.get("date_to")):
        if changes and "b" in flags:
            return {**ask("vague_time", vague_t.group(1)), "plan": plan}
        plan["sort"] = plan["sort"] or ("oldest" if re.match(r"alt|old|ält", vague_t.group(1)) else "newest")
    # d: source and destination against the real tree
    if "d" in flags:
        if problems and changes:
            return {**ask(problems[0][0], problems[0][1]), "plan": plan}
        strong = sorted({p for p, s_ in src if s_ == 2})
        weak = sorted({p for p, s_ in src if s_ == 1})
        model_src, how = resolve_folder(raw["source"], folders, leaves) if raw["source"].strip() not in ("", ".") else (None, "missing")
        if how == "ambiguous":
            model_src = None
        grounded = model_src and any(model_src == p or p.startswith(model_src + "/") for p in strong + weak)
        narrowest = lambda ps: sorted({p for p in ps if not any(q != p and q.startswith(p + "/") for q in ps)})
        if strong:
            cand = narrowest(strong)
        elif weak:
            cand = narrowest(weak)
        else:
            cand = [model_src] if model_src else []   # the model's folder, when it exists, is never replaced
        if len(cand) > 1:
            if changes:
                return {**ask("ambiguous_folder", ", ".join(c.rsplit("/", 1)[-1] for c in cand)), "plan": plan}
            cand = []
        plan["source"] = cand[0] if cand else ""
        if not plan["source"] and changes:
            if raw["source"].strip() not in ("", ".", "hier") and how == "missing":
                return {**ask("unknown_folder", raw["source"]), "plan": plan}
            return {**ask("no_source"), "plan": plan}
        if act in ("move", "copy"):
            have = sorted({x for x in dst if x and x in tree.folders})
            new_ = [x for x in dst if x and x not in tree.folders]
            if len(have) > 1:
                return {**ask("ambiguous_folder", ", ".join(h.rsplit("/", 1)[-1] for h in have)), "plan": plan}
            if have or new_:
                plan["destination"] = (have or new_)[-1]
            elif raw["destination"].strip() and raw["destination"].strip() not in (".", "hier", ""):
                rp, _ = resolve_folder(raw["destination"], folders, leaves)
                plan["destination"] = rp if isinstance(rp, str) else raw["destination"].strip()
            elif "" not in dst:
                return {**ask("no_dest"), "plan": plan}
            if plan["destination"] == plan["source"]:
                return {**ask("no_dest"), "plan": plan}   # source and target the same: something was misread
        for path, a, txt in bare:
            if path != plan["source"]:
                w.s = w.s[:a] + txt + w.s[a + len(txt):]
    else:
        plan["source"], plan["destination"] = raw["source"], raw["destination"]
    # a: coverage guard. Content words still unconsumed must be used by a filter.
    name_sig = re.search(r"\b(im (datei)?namen|in the (file ?)?name|named|called|dateiname|namens)\b", t)
    text_sig = re.search(r"\b(steht|stehen|vorkomm\w*|erwähn\w*|mention\w*|contain\w*|drin|inhalt|über die|über den|über das|about)\b", t)
    neg = negated_words(text)
    keep = lambda x: (x not in STOP and x not in GENERIC and not is_verb(x) and x not in NEGATOR and x not in UNIT and
                      len(x) >= 2 and not re.fullmatch(r"\d{1,2}", x) and x not in neg)
    left = [x for x in w.tokens() if keep(x)]
    said_by_model = [x for f in ("name_contains", "content_contains")
                     for x in re.findall(r"[a-zäöüß0-9]+", split_compounds(norm(raw[f]))) if keep(x) and x not in TYPES]
    marked = [x for x in left if any(related(x, m) for m in said_by_model)]   # the user's words the model used as filter
    unacc = [x for x in left if x not in marked] if "a" in flags else []
    model_terms = marked
    terms = list(dict.fromkeys(stem(x) for x in marked + unacc))
    out["unaccounted"] = unacc
    field = "name_terms" if act == "delete" or (name_sig and not text_sig) else "text_terms"
    base = {**plan}
    n_files = lambda ts: len(files_of(tree, {**base, field: list(ts)}, text))
    chosen = terms
    if terms and "a" in flags and n_files(terms) == 0:
        # the reading that exists on the phone: the largest subset of the words that matches files
        chosen = None
        for k in range(len(terms) - 1, 0, -1):
            ok = [list(c) for c in itertools.combinations(terms, k) if n_files(c)]
            if ok:
                chosen = ok[0] if len(ok) == 1 or not changes else None
                break
        if chosen is None:
            if changes:
                return {**ask("uncovered" if unacc else "no_reading", " ".join(unacc or terms)), "plan": plan}
            chosen = [stem(x) for x in model_terms]   # a search shows what the model's words find, or nothing
    plan[field] = chosen
    if act in ("move", "copy") and not plan["destination"]:
        if not chosen:
            return {**ask("no_dest"), "plan": plan}
        plan["destination"] = chosen[0].capitalize()   # "in einen eigenen Ordner": named after what is moved
    # f: plausibility of a count in the words (warning in the preview only)
    if count and changes:
        n = len(files_of(tree, plan, text))
        if n != count:
            out["warn"].append(f"count {count} said, {n} matched")
    out["plan"] = plan
    return out


def as_scored(d):
    """For the lab judge: a refusal or a question is no plan."""
    if d["status"] != "plan":
        return {**DEFAULT, "action": "none"}
    return d["plan"]
