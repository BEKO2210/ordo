/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import java.util.Locale
import java.util.regex.Pattern

/**
 * Fixed word rules around the model ("R" in docs/KI-ZEIGEN-PHASE0B.md), a line-by-line port of
 * tools/memory-lab/rules.py. [route] answers without the model where Ordo already has a screen or must say no;
 * [fix] overrides the plan fields the words decide. Safety-critical: the lab measured 0 dangerous plans with these
 * rules and 3 to 23 without. CommandRulesGoldenTest checks every lab case against the Python result; change
 * rules.py first, export the fixture again, then port the change here.
 */
object CommandRules {
    /** What Ordo does without the model. */
    sealed interface Route {
        /** Ordo cannot know this without a memory: say so instead of guessing. */
        data class Refuse(val reason: Refusal) : Route

        /** An existing screen fits; [plan] is the plan rules.py gives for it (show, maybe sorted by size). */
        data class Screen(val screen: Kind, val plan: CommandPlan) : Route
    }

    enum class Kind { STORAGE, DUPLICATES, LARGEST }

    /** Why a command needs knowledge Ordo does not keep, so the answer can say what to do instead. */
    enum class Refusal { OPENED, SEARCHED, HABIT, CONVERSATION, WORK }

    // Python's \w and \b know umlauts. Android's regex (ICU) always does; a plain JVM (unit tests) needs the flag,
    // which Android does not support.
    private val UNICODE = if (System.getProperty("java.vm.name") == "Dalvik") 0 else Pattern.UNICODE_CHARACTER_CLASS

    private fun re(pattern: String) = Pattern.compile(pattern, UNICODE).toRegex()

    // Order matters: the alternations of N try the words in this order, as in rules.py.
    private val NUM = linkedMapOf(
        "ein" to 1, "eine" to 1, "einem" to 1, "einen" to 1, "einer" to 1, "one" to 1, "a" to 1, "zwei" to 2,
        "two" to 2, "drei" to 3, "three" to 3, "vier" to 4, "four" to 4, "fünf" to 5, "funf" to 5, "five" to 5,
        "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "ten" to 10, "zwanzig" to 20
    )
    private val N = "(\\d+|" + NUM.keys.joinToString("|") + ")"

    private fun num(s: String): Int =
        if (s.all { it.isDigit() }) s.toBigInteger().min(Int.MAX_VALUE.toBigInteger()).toInt() else NUM.getValue(s)

    internal fun norm(text: String): String {
        var t = " " + text.lowercase(Locale.ROOT).replace("’", "'") + " "
        t = re("\\bscreen ?shots?\\b|\\bbildschirmfotos?\\b").replace(t, " screenshots ")
        t = re("\\bwhats ?app\\b").replace(t, "whatsapp")
        t = re("\\ba ?p ?k\\b|\\bapks\\b").replace(t, "apk")
        t = re("\\bmega ?bytes?\\b|\\bmegabytes?\\b").replace(t, "mb")
        t = re("\\bgiga ?bytes?\\b|\\bgigabytes?\\b|\\bgiga\\b").replace(t, "gb")
        t = re("video'?s\\b").replace(t, "videos")
        return t
    }

    private const val OPENED = "(ge(ö|oe)ffnet|aufgemacht|angeschaut|angesehen)"

    // Commands that need a memory or log Ordo does not keep: answer "kann ich noch nicht", never guess.
    private val NEEDS_MEMORY = listOf(
        "\\bnicht mehr $OPENED" to Refusal.OPENED,
        "lange nicht (mehr )?$OPENED" to Refusal.OPENED,
        "\\b(seit|länger als) .*nicht (mehr )?(ge(ö|oe)ffnet|aufgemacht)" to Refusal.OPENED,
        "\\bgesucht\\b" to Refusal.SEARCHED, "\\bsuche gestern\\b" to Refusal.SEARCHED,
        "\\bwie (immer|sonst|gewohnt|normalerweise|letztes mal)\\b" to Refusal.HABIT,
        "\\bwie jede[nrs]? (tag|woche|monat|mal)\\b" to Refusal.HABIT,
        "\\bso,? wie ich (es|das) (immer|normalerweise|sonst)" to Refusal.HABIT,
        "\\bwie sonst\\b" to Refusal.HABIT,
        "\\bgenauso\\b" to Refusal.CONVERSATION, "\\bgenau so\\b" to Refusal.CONVERSATION,
        "\\bdas gleiche\\b" to Refusal.CONVERSATION, "\\bdasselbe\\b" to Refusal.CONVERSATION,
        "\\bwie gerade\\b" to Refusal.CONVERSATION, "\\bwie vorhin\\b" to Refusal.CONVERSATION,
        "\\bvon vorhin\\b" to Refusal.CONVERSATION, "\\bvon eben\\b" to Refusal.CONVERSATION,
        "\\bvorhin\\b" to Refusal.CONVERSATION, "\\bdavon\\b" to Refusal.CONVERSATION,
        "\\bdie (anderen|übrigen|restlichen)\\b" to Refusal.CONVERSATION,
        "\\bden restlichen\\b" to Refusal.CONVERSATION, "\\bzu dieser\\b" to Refusal.CONVERSATION,
        "\\bzu der rechnung\\b" to Refusal.CONVERSATION, "\\bhingehören\\b" to Refusal.CONVERSATION,
        "\\bwo die anderen\\b" to Refusal.CONVERSATION, "\\bzu den anderen\\b" to Refusal.CONVERSATION,
        "\\barbeits(dokumente|sachen|dateien)\\b" to Refusal.WORK, "\\bmeine arbeit\\b" to Refusal.WORK
    ).map { (pattern, reason) -> re(pattern) to reason }
    private val STORAGE = re(
        "(was kann (ich )?(alles )?(löschen|loeschen|weg)|speicher(platz)? (ist )?voll|kein (platz|speicher)|" +
            "(mehr|wieder) (speicher)?platz|platz (frei|schaffen)|wie viel speicher|speicher .*frei|what can i delete|" +
            "free up space)"
    )
    private val LARGEST = re("(größt|groesst|grösst|am meisten platz|biggest|largest)")
    private val NEWEST = re(
        "(neueste|neuesten|neuestes|zuletzt|als letztes|newest|latest|most recent|" +
            "letzte (apk|datei|pdf|foto|bild|video|download))"
    )
    private val DUPS = re("(doppelt|duplikat|mehrfach|duplicate)")
    private val DELETE = re("(lösch|losch|loesch|entfern|wirf|schmeiß|schmeiss|delete|remove|erase)")
    private val NO_DELETE = re(
        "(nichts|nix|bloß nichts|ja nichts|nicht) (wichtiges )?(lösch|loesch|weg)|nicht lösch|" +
            "lösch (bloß |ja )?nichts|nichts wichtiges"
    )
    private val QUESTION = re(
        "^\\s*(was|wo|welche|welcher|welches|wie|kann ich|darf ich|brauch ich|brauche ich|gibt es|hab ich|" +
            "habe ich|ist|where|which|what|can i|do i|should i)\\b"
    )
    private val KINDS = listOf(
        "image" to re("\\b(bilder|bild|fotos|foto|images?|pictures?|photos?|pics?)\\b"),
        "video" to re("\\b(videos?|filme|clips?)\\b"),
        "audio" to re("\\b(musik|songs?|lieder|music|audio)\\b")
    )
    private val EXTS = listOf("pdf", "apk", "zip", "mp3", "mp4", "docx", "xlsx", "jpg", "png")
    private val EXT_PATTERNS = EXTS.map { it to re("\\b${it}s?\\b") }
    private val FOLDERS = listOf(
        "Screenshots" to re("\\bscreenshots\\b"),
        "WhatsApp Images" to re("whatsapp\\b.*\\b(bilder|fotos|images|pictures|photos)|(bilder|fotos|images) .*whatsapp"),
        "WhatsApp Video" to re("whatsapp\\b.*\\bvideos?|videos? .*whatsapp"),
        "Download" to re("(herunter|runter)geladen|\\bgeladen\\b|\\bdownloads?\\b|download-ordner"),
        "Kamera" to re("\\bkamera\\b|\\bcamera\\b|(foto|bild|fotos|bilder)\\b.*\\b(gemacht|aufgenommen)")
    )
    private val EVERYWHERE = re("\\b(auf|am) (dem |meinem |mein )?(handy|telefon|phone|gerät)\\b|\\bon my phone\\b")
    private val NAMED_FOLDER_WORD = re("\\b(ordner|folder|aus|im|in)\\b \\w")

    /** The time and size words of [t] (already [norm]ed), in the order rules.py sets them. */
    internal fun timeAndSize(t: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (re("\\bheute\\b|\\btoday\\b").containsMatchIn(t)) {
            out["_today"] = true
        } else if (re("\\bvorgestern\\b").containsMatchIn(t)) {
            out["newer_than"] = 2; out["age_unit"] = "days"
        } else if (re("\\bgestern\\b|\\byesterday\\b").containsMatchIn(t)) {
            out["newer_than"] = 1; out["age_unit"] = "days"
        }
        val last = re("\\b(letzten|vergangenen|last|past) $N (tage|tagen|days|wochen|weeks|monate|months)\\b").find(t)
        if (last != null) {
            out["newer_than"] = num(last.groupValues[2])
            out["age_unit"] = when (last.groupValues[3][0]) {
                'w' -> "weeks"
                'm' -> "months"
                else -> "days"
            }
        } else if (re("\\b(letzte|letzten|vergangene|vergangenen|diese|dieser|last|this) (woche|week)\\b").containsMatchIn(t)) {
            out["newer_than"] = 1; out["age_unit"] = "weeks"
        } else if (re("\\b(letzten|vergangenen|last) (monat|month)\\b|\\bvom letzten monat\\b").containsMatchIn(t)) {
            out["newer_than"] = 1; out["age_unit"] = "months"
        } else if (re("\\b(vor )?(ein paar|paar|einigen|wenigen) tagen\\b|\\bneulich\\b|\\bkürzlich\\b|\\brecently\\b|\\bfew days\\b")
                .containsMatchIn(t)) {
            out["newer_than"] = 7; out["age_unit"] = "days"
        }
        val older = re(
            "\\b(älter|aelter|older) (als|than) $N (tag|tage|tagen|woche|wochen|monat|monate|monaten|jahr|jahre|jahren|" +
                "day|days|week|weeks|month|months|year|years)\\b"
        ).find(t)
        if (older != null) {
            val u = older.groupValues[4]
            out["older_than"] = num(older.groupValues[3])
            out["age_unit"] = when {
                u.startsWith("jahr") || u.startsWith("year") -> "years"
                u.startsWith("monat") || u.startsWith("month") -> "months"
                u.startsWith("woche") || u.startsWith("week") -> "weeks"
                else -> "days"
            }
        }
        if (re("\\b(ein )?halbes gb\\b").containsMatchIn(t)) {
            out["larger_than"] = 500; out["size_unit"] = "mb"
        } else {
            val size = re("\\b(größer|grösser|groesser|über|ueber|mehr als|bigger|larger|over|ab)( als| than)? $N ?(mb|gb)\\b")
                .find(t)
            if (size != null) {
                out["larger_than"] = num(size.groupValues[3]); out["size_unit"] = size.groupValues[4]
            }
        }
        return out
    }

    /** A finished answer without the model, or null when the model has to read the command. */
    fun route(text: String): Route? {
        val t = norm(text)
        NEEDS_MEMORY.firstOrNull { (pattern, _) -> pattern.containsMatchIn(t) }?.let { return Route.Refuse(it.second) }
        val hasDelete = DELETE.containsMatchIn(t) && !NO_DELETE.containsMatchIn(t)
        val namedFolder = FOLDERS.any { it.second.containsMatchIn(t) } || NAMED_FOLDER_WORD.containsMatchIn(t)
        val show = CommandPlan("show", "")
        // The app's storage analysis: biggest files first (and duplicates), never a delete plan.
        if (STORAGE.containsMatchIn(t)) return Route.Screen(Kind.STORAGE, show.copy(sort = "size"))
        if (DUPS.containsMatchIn(t) && !hasDelete && !namedFolder) return Route.Screen(Kind.DUPLICATES, show)
        if (LARGEST.containsMatchIn(t) && !namedFolder && KINDS.none { it.second.containsMatchIn(t) } &&
            EXT_PATTERNS.none { it.second.containsMatchIn(t) }) {
            return Route.Screen(Kind.LARGEST, show.copy(sort = "size"))
        }
        return null
    }

    private val QUALIFIER_STOP = setOf(
        "whatsapp", "kamera", "camera", "handy", "alle", "die", "meine", "neue", "neuen", "alte", "alten", "der", "das",
        "den", "von", "word", "excel", "bild", "musik", "video", "pdf", "zip", "apk", "mp3", "screen", "eigene", "andere"
    )
    private val MOVE_VERB = re("\\b(verschieb|verschiebe|schieb|pack|leg|move|tu)\\b")

    /** The longest existing folder name right after one of the words in [after] ("aus", "nach"), or null. */
    private fun namedFolder(t: String, folders: List<String>, after: String): String? {
        var best: String? = null
        for (f in folders) {
            val leaf = f.substringAfterLast('/').lowercase(Locale.ROOT)
            // The contains() check only skips compiling a pattern that cannot match.
            if (leaf.codePointCount(0, leaf.length) >= 4 && t.contains(leaf) &&
                re("\\b($after) (dem |den |die |das |ordner |folder )*${Regex.escape(leaf)}\\b").containsMatchIn(t)) {
                val bestLeaf = best?.substringAfterLast('/')
                if (bestLeaf == null || leaf.codePointCount(0, leaf.length) > bestLeaf.codePointCount(0, bestLeaf.length)) {
                    best = f
                }
            }
        }
        return best
    }

    // Like Python's str.capitalize(): title case first ("ß" -> "Ss"), the rest lower case.
    private fun capitalize(word: String): String =
        word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

    /**
     * Overrides the fields the words decide. Never turns a plan into a delete, only away from one.
     * [folders] = the folder paths on the phone, relative to the storage root, to read "aus X" / "nach Y" exactly.
     */
    fun fix(text: String, plan: CommandPlan, folders: List<String> = emptyList()): CommandPlan {
        val t = norm(text)
        var p = plan
        var act = p.action
        // "verschieb/pack ... nach Y" is a move even when the model said organize
        val destWord = re("\\b(nach|to|in (den|einen|nen|ein) (neuen |eigenen |extra )?ordner) ([\\wäöüß/-]+)").find(t)
        if (act == "organize" && MOVE_VERB.containsMatchIn(t) && destWord != null) {
            act = "move"
            p = p.copy(action = act)
            if (p.destination.isEmpty() || p.destination.lowercase(Locale.ROOT).startsWith("extra")) {
                p = p.copy(destination = destWord.groupValues[4])
            }
        }
        // an explicit "aus/in X" wins over "von X", which wins over a folder named as the object ("die Wallpapers")
        val src = namedFolder(t, folders, "aus|from|in|im") ?: namedFolder(t, folders, "von")
            ?: namedFolder(t, folders, "die|alle")
        if (src != null && act in setOf("move", "copy", "delete", "organize", "show")) p = p.copy(source = src)
        val dst = namedFolder(t, folders, "nach|to")
        if (dst != null && act in setOf("move", "copy")) p = p.copy(destination = dst)
        val question = QUESTION.containsMatchIn(t) || text.trim().endsWith("?")
        val hasDelete = DELETE.containsMatchIn(t) && !NO_DELETE.containsMatchIn(t)
        // safety: a question or a "don't delete" never becomes a destructive plan
        if (act in setOf("delete", "move", "organize") && question) {
            act = "show"
            p = p.copy(action = act)
        }
        if (act == "delete" && !hasDelete) {
            act = if (re("räum|raeum|ordnung|sortier|tidy").containsMatchIn(t)) "organize" else "show"
            p = p.copy(action = act)
        }
        val ts = timeAndSize(t)
        if (ts.keys.any { it == "newer_than" || it == "older_than" || it == "_today" }) {
            // the words decide the time completely
            p = p.copy(newerThan = 0, olderThan = 0, today = false)
        }
        for ((key, value) in ts) {
            p = when (key) {
                "_today" -> p.copy(today = value as Boolean)
                "newer_than" -> p.copy(newerThan = value as Int)
                "older_than" -> p.copy(olderThan = value as Int)
                "age_unit" -> p.copy(ageUnit = value as String)
                "larger_than" -> p.copy(largerThan = value as Int)
                else -> p.copy(sizeUnit = value as String)
            }
        }
        if (LARGEST.containsMatchIn(t)) {
            p = p.copy(sort = "size")
        } else if (NEWEST.containsMatchIn(t) && !re("letzte[n]? (woche|monat|tage|jahr)").containsMatchIn(t)) {
            p = p.copy(sort = "newest")
        }
        val limit = re("\\b(die|the|top)? ?$N (größten|groessten|grössten|neuesten|letzten|biggest|largest|newest|latest)\\b")
            .find(t)
        if (limit != null) {
            p = p.copy(limit = num(limit.groupValues[2]))
        } else if (p.sort.isNotEmpty() && re(
                "\\bzuletzt\\b|\\bals letztes\\b|\\b(die|das|der|the) (größte|groesste|grösste|neueste|biggest|largest|newest|latest)\\b"
            ).containsMatchIn(t)) {
            p = p.copy(limit = 1)   // one file asked for
        }
        val exts = EXT_PATTERNS.filter { it.second.containsMatchIn(t) }.map { it.first }
        val kinds = KINDS.filter { it.second.containsMatchIn(t) }.map { it.first }
        if (exts.isNotEmpty()) {
            p = p.copy(extensions = exts, kinds = emptyList())
        } else if (kinds.isNotEmpty() && !(p.source.isNotEmpty() && "whatsapp" in t && act != "show")) {
            p = p.copy(kinds = if (p.extensions.isEmpty()) kinds.take(1) else p.kinds)
        }
        if (act == "show" && src == null) {
            if (EVERYWHERE.containsMatchIn(t)) p = p.copy(source = "")
            FOLDERS.firstOrNull { it.second.containsMatchIn(t) }?.let { p = p.copy(source = it.first) }
            if (p.source == "." || p.source == "hier") p = p.copy(source = "")
        }
        // "weiß nicht, wie sie heißt" / "in denen X steht": the word is a content word, not a name
        if (re("wei(ß|ss) (aber )?(nicht|den namen nicht)|keine ahnung,? wie|nicht mehr,? wie|in denen|vorkommt|steht\\b|" +
                "mention|erwähn|\\bdrin\\b|über (die|den|das)").containsMatchIn(t) &&
            p.nameContains.isNotEmpty() && !re("im namen|in the name").containsMatchIn(t)) {
            p = p.copy(contentContains = p.nameContains, nameContains = "")
        }
        // "Telekom-Rechnungen", "Instagram-Screenshots", "Hochzeitsfotos": the qualifier must be in the plan, or the
        // plan takes every file of that kind (too many files for a move or delete)
        val qualifier = re(
            "\\b([a-zäöüß]{3,}?)s?(?:-\\s*|)(fotos|bilder|videos|screenshots|dateien|sachen|unterlagen|rechnungen|briefe|papiere|pdfs)\\b"
        ).find(t)
        if (qualifier != null && qualifier.groupValues[1] !in QUALIFIER_STOP && p.nameContains.isEmpty() &&
            p.contentContains.isEmpty()) {
            p = p.copy(nameContains = capitalize(qualifier.groupValues[1]))
        }
        // a word that is not said to be "im Namen" is looked for in name and text (content covers both); not for delete
        if (p.nameContains.isNotEmpty() && act in setOf("show", "move", "copy") &&
            !re("im namen|in the name|hei(ß|ss)t|named").containsMatchIn(t)) {
            p = p.copy(contentContains = p.nameContains, nameContains = "")
        }
        if (p.contentContains.isNotEmpty()) {
            // rules.py takes split()[0], which fails on a blank word; a blank word here simply stays empty
            val first = p.contentContains.trim().split(Regex("\\s+"))[0]
            val w = Regex("s?(zeug|sachen|kram)$", RegexOption.IGNORE_CASE).replace(first, "")
            p = p.copy(contentContains = if (w.lowercase(Locale.ROOT).endsWith("ungs")) w.dropLast(1) else w)
        }
        return p
    }
}
