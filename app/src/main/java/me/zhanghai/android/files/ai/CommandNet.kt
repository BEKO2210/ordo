/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import me.zhanghai.android.files.ai.CommandNetLexicon as X
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.MatchResult
import java.util.regex.Pattern

/**
 * The code readings of the frozen net, a line-by-line port of the parts of tools/memory-lab/rules2.py that
 * rules3.decide uses: words, time and size grammar, exclusion, folders against the real tree, sort, types, counts,
 * action safety per clause. Pure Kotlin, no Android. Safety-critical: CommandTunedGoldenTest checks it against every
 * recorded lab case. Change the Python first, export again, then port the change.
 */
internal object CommandNet {
    // ---------------------------------------------------------------- Python's re, as far as the net uses it

    // Python's \w and \b know umlauts. Android's regex (ICU) always does; a plain JVM (unit tests) needs the flag,
    // which Android does not support.
    private val FLAGS = if (System.getProperty("java.vm.name") == "Dalvik") 0 else Pattern.UNICODE_CHARACTER_CLASS

    /** A Python pattern: named groups become plain groups, read by number (Matcher.group(String) needs API 26). */
    class Rx(python: String) {
        val names = HashMap<String, Int>()
        val pattern: Pattern

        init {
            val out = StringBuilder()
            var groups = 0
            var inClass = false
            var i = 0
            while (i < python.length) {
                val c = python[i]
                when {
                    c == '\\' -> {
                        out.append(python, i, minOf(i + 2, python.length))
                        i += 2
                        continue
                    }
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> inClass = true
                    c == '(' && python.startsWith("(?P<", i) -> {
                        val close = python.indexOf('>', i)
                        names[python.substring(i + 4, close)] = ++groups
                        out.append('(')
                        i = close + 1
                        continue
                    }
                    c == '(' && !python.startsWith("(?", i) -> groups++
                }
                out.append(c)
                i++
            }
            pattern = Pattern.compile(out.toString(), FLAGS)
        }

        fun search(s: String): Hit? = pattern.matcher(s).let { if (it.find()) Hit(it.toMatchResult(), this) else null }
        fun match(s: String): Hit? = pattern.matcher(s).let { if (it.lookingAt()) Hit(it.toMatchResult(), this) else null }
        fun full(s: String): Hit? = pattern.matcher(s).let { if (it.matches()) Hit(it.toMatchResult(), this) else null }
        fun all(s: String): List<Hit> {
            val m = pattern.matcher(s)
            val out = mutableListOf<Hit>()
            while (m.find()) out += Hit(m.toMatchResult(), this)
            return out
        }

        fun sub(s: String, replacement: String): String = pattern.matcher(s).replaceAll(replacement)
        fun split(s: String): List<String> = pattern.split(s, -1).toList()
    }

    class Hit(private val r: MatchResult, private val rx: Rx) {
        val value: String get() = r.group()
        fun g(name: String): String? = r.group(rx.names.getValue(name))
        fun g(group: Int): String? = r.group(group)
        fun start(): Int = r.start()
        fun end(): Int = r.end()
        fun start(name: String): Int = r.start(rx.names.getValue(name))
    }

    private val cache = ConcurrentHashMap<String, Rx>()
    fun rx(p: String): Rx = cache.getOrPut(p) { Rx(p) }
    fun search(p: String, s: String) = rx(p).search(s)
    fun match(p: String, s: String) = rx(p).match(s)
    fun full(p: String, s: String) = rx(p).full(s)
    fun findall(p: String, s: String) = rx(p).all(s).map { it.value }

    /** re.escape: a literal as a pattern. */
    fun escape(s: String): String = buildString { s.forEach { if (it in "\\^$.|?*+()[]{}") append('\\'); append(it) } }

    /** str.title(): a letter after a cased letter is lower case, any other upper (title) case. */
    fun title(s: String): String = buildString {
        var prevCased = false
        for (c in s) {
            append(if (prevCased) c.lowercaseChar() else c.titlecaseChar())
            prevCased = c.isUpperCase() || c.isLowerCase() || c.isTitleCase()
        }
    }

    fun lower(s: String) = s.lowercase(Locale.ROOT)

    private fun pyStrip(s: String) = s.trim { it.isWhitespace() }

    /** The command as a string where every consumed span is blanked, so the guard sees what is left. */
    class Work(var s: String) {
        fun take(pattern: String, fn: ((Hit) -> Boolean)? = null): List<Hit> {
            val hits = mutableListOf<Hit>()
            for (m in rx(pattern).all(s)) {
                if (fn == null || fn(m)) {
                    hits += m
                    s = s.substring(0, m.start()) + " ".repeat(m.end() - m.start()) + s.substring(m.end())
                }
            }
            return hits
        }

        fun tokens(): List<String> = findall("[a-zäöüß0-9]+", s)
    }

    // ---------------------------------------------------------------- numbers and words

    fun num(s0: String): Double {
        val s = pyStrip(s0)
        return if (s[0].isDigit()) s.replace(",", ".").toDouble() else X.NUM.getValue(s)
    }

    private val TYPES = X.TYPES

    fun norm(text: String): String {
        var t = " " + lower(text).replace("’", "'").replace("‘", "'").replace("´", "'") + " "
        t = rx("(?<=[a-zäöüß])-(?=[a-zäöüß0-9])").sub(t, " ")
        t = rx("(?<=\\w)'s\\b").sub(t, "s")
        t = rx("\\b([a-z]) ([a-z]) ([a-z])\\b").sub(t, "\$1\$2\$3")
        t = rx("\\b(last|this|the|in the|next) fall\\b").sub(t, "\$1 autumn")
        t = rx("\\s+").sub(t, " ")
        return t
    }

    /** phase0.fold: case and umlaut tolerant. */
    fun fold(s0: String): String {
        var s = lower(s0).replace("ß", "ss")
        for ((a, b) in listOf("ä" to "a", "ö" to "o", "ü" to "u", "ae" to "a", "oe" to "o", "ue" to "u")) s = s.replace(a, b)
        return s
    }

    fun stem(w0: String): String {
        val w = fold(w0)
        for (suf in listOf("innen", "en", "er", "e", "n", "s")) {
            if (w.endsWith(suf) && w.length - suf.length >= 4) return w.substring(0, w.length - suf.length)
        }
        return w
    }

    fun related(a0: String, b0: String): Boolean {
        val a = stem(a0)
        val b = stem(b0)
        return a.length >= 3 && b.length >= 3 && (a in b || b in a)
    }

    private fun joinableWords(leaves: Collection<String>): Set<String> =
        TYPES.keys + X.ALIAS.keys + X.UNIT.keys + setOf("whatsapp", "download", "megabyte", "gigabyte", "kilobyte",
            "terabyte", "dazugekommen", "hinzugekommen", "heruntergeladen", "runtergeladen") +
            leaves.map { fold(it).replace(" ", "") }

    private fun joinDictation(t: String, words: Set<String>): String {
        val toks = t.split(" ")
        val out = mutableListOf<String>()
        var i = 0
        while (i < toks.size) {
            if (i + 1 < toks.size && toks[i].isNotEmpty() && toks[i + 1].isNotEmpty() && (toks[i] + toks[i + 1]) in words &&
                !(toks[i] in words && toks[i + 1] in words)) {
                out += toks[i] + toks[i + 1]
                i += 2
            } else {
                out += toks[i]
                i += 1
            }
        }
        return out.joinToString(" ")
    }

    fun splitCompounds(t: String): String {
        val m = rx("[a-zäöüß]+").pattern.matcher(t)
        val sb = StringBuilder()
        var last = 0
        while (m.find()) {
            sb.append(t, last, m.start()).append(compound(m.group()))
            last = m.end()
        }
        return sb.append(t, last, t.length).toString()
    }

    private fun compound(w: String): String {
        if (w in TYPES || w in X.ALIAS || w in X.GENERIC || w.length < 7) return w
        for (h in X.HEADS) {
            if (w.endsWith(h) && w.length - h.length >= 3 && h.length >= 4) {
                var mod = w.substring(0, w.length - h.length)
                if (mod.endsWith("s") && search("(ung|heit|keit|schaft|ion|tät|ing|ts|ls|ns|gs)s$", mod) != null) {
                    mod = mod.dropLast(1)
                }
                return "$mod $h"
            }
        }
        return w
    }

    // ---------------------------------------------------------------- b: time grammar

    private fun addMonths(d: LocalDate, n: Long): LocalDate {
        val total = d.year * 12L + d.monthValue - 1 + n
        return LocalDate.of(Math.floorDiv(total, 12L).toInt(), Math.floorMod(total, 12L).toInt() + 1, 1)
    }

    private fun easter(y: Int): LocalDate {
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = Math.floorMod(19 * a + b - d - g + 15, 30)
        val i = c / 4
        val k = c % 4
        val l = Math.floorMod(32 + 2 * e + 2 * i - h - k, 7)
        val m = (a + 11 * h + 22 * l) / 451
        return LocalDate.of(y, (h + l - 7 * m + 114) / 31, (h + l - 7 * m + 114) % 31 + 1)
    }

    private fun weekday(d: LocalDate) = d.dayOfWeek.value - 1   // Python: Monday 0

    private fun latest(make: (Int) -> Pair<LocalDate, LocalDate>, today: LocalDate, year: Int?, ended: Boolean)
        : Pair<LocalDate, LocalDate> {
        if (year != null && year != 0) return make(year)
        for (y in listOf(today.year, today.year - 1, today.year - 2)) {
            val (a, b) = make(y)
            if (if (ended) !b.isAfter(today) else !a.isAfter(today)) return a to b
        }
        return make(today.year - 1)
    }

    private fun pyInt(s: String) = s.toInt()

    /** [start, end) of one time point as dates. */
    private fun resolvePoint(m: Hit, today: LocalDate): Pair<LocalDate, LocalDate> {
        fun y2k(y: String?): Int? = if (!y.isNullOrEmpty() && y.length == 2) 2000 + pyInt(y) else if (!y.isNullOrEmpty()) pyInt(y) else null
        fun dayRecent(mo: Int, d: Int, y: Int?): LocalDate {
            if (y != null && y != 0) return LocalDate.of(y, mo, d)
            val x = LocalDate.of(today.year, mo, d)
            return if (!x.isAfter(today)) x else LocalDate.of(today.year - 1, mo, d)
        }
        fun day(d: LocalDate) = d to d.plusDays(1)
        val g = { n: String -> m.g(n) }
        if (g("iso") != null) return day(LocalDate.parse(g("iso")))
        if (g("dmy") != null) return day(dayRecent(pyInt(g("m1")!!), pyInt(g("d1")!!), y2k(g("y1"))))
        if (g("dm") != null || g("md") != null) {
            val mo = X.MONTHS.getValue(g("m2") ?: g("m3")!!)
            return day(dayRecent(mo, pyInt(g("d2") ?: g("d3")!!), y2k(g("y2") ?: g("y3"))))
        }
        if (g("mon") != null) {
            val mo = X.MONTHS.getValue(g("m4")!!)
            val mod = g("monmod") ?: ""
            val make = { y: Int -> LocalDate.of(y, mo, 1).let { it to addMonths(it, 1) } }
            if (match(X.THIS, mod) != null) return make(today.year)
            return latest(make, today, y2k(g("y4")), match(X.LAST, mod) != null)
        }
        if (g("sea") != null) {
            val sm = X.SEASONS.getValue(g("s")!!)
            val mod = g("seamod") ?: ""
            val make = { y: Int ->
                val start = if (sm != 12) LocalDate.of(y, sm, 1) else LocalDate.of(y - 1, 12, 1)
                start to addMonths(start, 3)
            }
            if (match(X.THIS, mod) != null) return make(today.year + if (sm == 12 && today.monthValue == 12) 1 else 0)
            return latest(make, today, g("y5")?.let { pyInt(it) + if (sm == 12) 1 else 0 }, match(X.LAST, mod) != null)
        }
        if (g("hol") != null) {
            val h = g("h")!!
            val mod = g("holmod") ?: ""
            val make: (Int) -> Pair<LocalDate, LocalDate> = when (h) {
                "ostern", "easter", "ostersonntag", "ostermontag" -> { y -> easter(y) to easter(y).plusDays(2) }
                "pentecost", "pfingsten" -> { y -> easter(y).plusDays(49) to easter(y).plusDays(51) }
                else -> X.HOLIDAYS.getValue(h).let { (mo, d, n) ->
                    { y: Int -> LocalDate.of(y, mo, d).let { it to it.plusDays(n.toLong()) } }
                }
            }
            if (match(X.THIS, mod) != null) return make(today.year)
            return latest(make, today, g("y6")?.let { pyInt(it) }, match(X.LAST, mod) != null)
        }
        if (g("wd") != null) {
            val back = Math.floorMod(weekday(today) - X.WEEKDAYS.getValue(g("w")!!), 7).let { if (it == 0) 7 else it }
            return day(today.minusDays(back.toLong()))
        }
        if (g("rel") != null) {
            val r = g("rel")!!
            val off = if (r in setOf("vorgestern", "day before yesterday")) 2L else if (r in setOf("gestern", "yesterday")) 1L else 0L
            return day(today.minusDays(off))
        }
        if (g("cal") != null) {
            val last = match(X.LAST, g("calmod")!!) != null
            val u = g("calu")!!
            if (u == "woche" || u == "week") {
                val mon = today.minusDays(weekday(today).toLong())
                return if (last) mon.minusDays(7) to mon else mon to today.plusDays(1)
            }
            if (u == "wochenende" || u == "weekend") {
                var sat = today.minusDays(Math.floorMod(weekday(today) - 5, 7).toLong())
                if (last && sat.plusDays(2).isAfter(today)) sat = sat.minusDays(7)
                return sat to sat.plusDays(2)
            }
            if (u == "monat" || u == "month") {
                val first = LocalDate.of(today.year, today.monthValue, 1)
                return if (last) addMonths(first, -1) to first else first to today.plusDays(1)
            }
            val jan = LocalDate.of(today.year, 1, 1)
            return if (last) LocalDate.of(today.year - 1, 1, 1) to jan else jan to today.plusDays(1)
        }
        if (g("ago") != null) {
            val n = num(g("an") ?: g("an2")!!)
            val u = X.UNIT.getValue(g("au") ?: g("au2")!!)
            val d = today.minusDays((n * X.DAYS_PER.getValue(u)).toLong())
            if (u == "d") return day(d)
            if (u == "w") {
                val mon = d.minusDays(weekday(d).toLong())
                return mon to mon.plusDays(7)
            }
            if (u == "m") {
                val first = addMonths(LocalDate.of(today.year, today.monthValue, 1), -n.toLong())
                return first to addMonths(first, 1)
            }
            return LocalDate.of(today.year - n.toInt(), 1, 1) to LocalDate.of(today.year - n.toInt() + 1, 1, 1)
        }
        if (g("yr") != null) {
            val y = pyInt(g("yr")!!.substring(0, 4))
            return LocalDate.of(y, 1, 1) to LocalDate.of(y + 1, 1, 1)
        }
        throw IllegalArgumentException(m.value)
    }

    private val P = "(?:" + Regex("\\(\\?P<\\w+>").replace(X.POINT, "(?:") + ")"

    private fun midnight(d: LocalDate): LocalDateTime = d.atStartOfDay()

    /** Python timedelta(days=x): whole microseconds. */
    private fun minusDays(t: LocalDateTime, days: Double): LocalDateTime =
        t.minus(Math.rint(days * 86_400_000_000.0).toLong(), ChronoUnit.MICROS)

    private fun fmt(x: LocalDateTime): String =
        if (x.toLocalTime() == LocalTime.MIDNIGHT) x.toLocalDate().toString()
        else x.toLocalDate().toString() + "T%02d:%02d".format(Locale.ROOT, x.hour, x.minute)

    /** Consumes every time expression in w; returns (date_from, date_to): ISO day or minute, from inclusive, to exclusive. */
    fun parseTime(w: Work, today: LocalDate, now: LocalDateTime): Pair<String, String> {
        val rng = mutableListOf<Pair<LocalDateTime?, LocalDateTime?>>()
        fun span(m: Hit, name: String) = resolvePoint(full(X.POINT, m.g(name)!!)!!, today)
        val between = { m: Hit ->
            val a = span(m, "p1")
            val b = span(m, "p2")
            rng += midnight(a.first) to midnight(b.second)
            true
        }
        w.take("\\b(zwischen|between) (dem |der |den )?(?P<p1>$P) (und|and) (dem |der |den )?(?P<p2>$P)(?!\\w)", between)
        w.take("\\b(von|vom|from) (dem |der )?(?P<p1>$P) (bis|to|until|till|through) (zum |zur |zu |zum )?(?P<p2>$P)(?!\\w)", between)
        fun dur(older: Boolean) = { m: Hit ->
            val n = num(m.g("n")!!)
            val u = X.UNIT.getValue(m.g("u")!!)
            val cut = minusDays(now, n * X.DAYS_PER.getValue(u))
            rng += if (older) null to cut else cut to null
            true
        }
        val d = "(?P<n>${X.NUMW}) (?P<u>${X.UNITW})"
        for (pat in listOf("\\b(älter|aelter|older) (als|than) $d\\b", "\\b(mehr als|more than|über|over) $d (alt|old)\\b",
                "\\bseit (über|mehr als) $d\\b")) {
            w.take(pat, dur(true))
        }
        for (pat in listOf("\\b(jünger|juenger|neuer|newer|younger) (als|than) $d\\b",
                "\\b(weniger als|less than|höchstens|at most|nicht älter als|not older than) $d( (alt|old))?\\b",
                "\\b(innerhalb|within) (der |the )?(letzten |last |past )?$d\\b", "\\bseit $d\\b", "\\bfor the (last|past) $d\\b")) {
            w.take(pat, dur(false))
        }
        val lastN = { m: Hit ->
            val u = X.UNIT.getValue(m.g("u")!!)
            val n = m.g("n")?.let { num(it) } ?: if (u == "d") 7.0 else 1.0
            // computed as in rules3 even where unused: a year out of range fails there too
            val start = if (u == "d" || u == "w") today.minusDays((n * X.DAYS_PER.getValue(u)).toLong())
            else if (u == "m") addMonths(LocalDate.of(today.year, today.monthValue, 1), -n.toLong())
            else LocalDate.of(today.year - n.toInt(), today.monthValue, 1)
            if (u == "d" || u == "w") {
                rng += midnight(start) to null
            } else {
                val back = addMonths(LocalDate.of(today.year, today.monthValue, 1), -n.toLong() * if (u == "y") 12 else 1)
                rng += midnight(LocalDate.of(back.year, back.monthValue, minOf(today.dayOfMonth, 28))) to null
            }
            true
        }
        w.take("\\b(in |während |during |over |of )?(den |der |the )?(letzten|vergangenen|past|last) (?P<n>${X.NUMW} )?(?P<u>tagen|tage|wochen|monaten|monate|jahren|jahre|days|weeks|months|years)\\b",
            lastN)
        w.take("\\b(vor (ein paar|paar|einigen|wenigen|kurzem) (tagen)?|neulich|kürzlich|vor kurzem|letztens|recently|lately|" +
            "(a )?few days ago|the other day|neu hinzugekommen\\w*)\\b") { rng += midnight(today.minusDays(7)) to null; true }
        fun bound(kind: String) = { m: Hit ->
            val (a, b) = span(m, "p")
            rng += when (kind) {
                "since" -> midnight(a) to null
                "until" -> null to midnight(b)
                "before" -> null to midnight(a)
                else -> midnight(b) to null
            }
            true
        }
        w.take("\\b(seit|since|ab|starting) (dem |der |den |letzte[nrm]? )?(?P<p>$P)(?!\\w)", bound("since"))
        w.take("\\b(bis|until|till|up to) (zum |zur |zu |zum |dem |der )?(?P<p>$P)(?!\\w)", bound("until"))
        w.take("\\b(vor|before) (dem |der |den )?(?P<p>(?!${X.NUMW} )$P)(?!\\w)", bound("before"))
        w.take("\\b(nach|after) (dem |der |den )?(?P<p>(?!${X.NUMW} )$P)(?!\\w)", bound("after"))
        w.take("\\b(von|vom|am|im|in|aus|from|on|in|during|at|of|des|der|dem)? ?(?P<p>$P)(?!\\w)") {
            rng += span(it, "p").let { (a, b) -> midnight(a) to midnight(b) }
            true
        }
        val lo = rng.mapNotNull { it.first }
        val hi = rng.mapNotNull { it.second }
        if (rng.isNotEmpty()) w.take("\\b(alte[nrs]?|alt|old|neue[nrs]?|new)\\b")
        return (lo.maxOrNull()?.let(::fmt) ?: "") to (hi.minOrNull()?.let(::fmt) ?: "")
    }

    // ---------------------------------------------------------------- b: size grammar

    private fun sizeVal(n: String, u0: String): Long {
        val v = if (n.startsWith("halb") || n.startsWith("half")) 0.5 else num(n)
        val u = if (u0.trimEnd('s') in X.SIZEU) u0.trimEnd('s') else u0
        return (v * X.SIZEU.getValue(u)).toLong()
    }

    fun parseSize(w: Work): Pair<Long, Long> {
        val out = HashMap<String, Long>()
        w.take("\\b(zwischen|between) (?P<a>${X.NUMW}) (${X.SU} )?(und|and) (?P<b>${X.NUMW}) ?(?P<u>${X.SU})\\b") { m ->
            out["min"] = sizeVal(m.g("a")!!, m.g("u")!!)
            out["max"] = sizeVal(m.g("b")!!, m.g("u")!!)
            true
        }
        w.take("\\b(größer|grösser|groesser|über|ueber|mehr als|ab|mindestens|bigger|larger|greater|more|over|above|at least)" +
            "( als| than)? ${X.SN} ?(?P<u>${X.SU})( groß| gross| big| large)?\\b") { m ->
            out["min"] = sizeVal(m.g("n")!!, m.g("u")!!)
            true
        }
        w.take("\\b(kleiner|unter|weniger als|höchstens|bis|smaller|less|under|below|at most)( als| than)? ${X.SN} ?(?P<u>${X.SU})" +
            "( groß| gross| klein| big| small)?\\b") { m ->
            out["max"] = sizeVal(m.g("n")!!, m.g("u")!!)
            true
        }
        w.take("\\b${X.SN} ?(?P<u>${X.SU})\\b") { m ->
            out["min"] = sizeVal(m.g("n")!!, m.g("u")!!)
            true
        }
        return (out["min"] ?: 0L) to (out["max"] ?: 0L)
    }

    // ---------------------------------------------------------------- c: exclusion

    class Exclusion(val kinds: List<String>, val exts: List<String>, val terms: List<String>)

    fun parseExclusion(w: Work, verbWord: (String) -> Boolean): Exclusion {
        val kinds = mutableListOf<String>()
        val exts = mutableListOf<String>()
        val terms = mutableListOf<String>()
        w.take(X.EXCL) { m ->
            val rest = w.s.substring(m.end())
            var used = 0
            var got = false
            for (t in rx("[a-zäöüß0-9]+|[,.;!?]").all(rest)) {
                val x = t.value
                if (x in setOf("von", "vom", "aus", "from", "of") && !got) {
                    used = t.end()
                    continue
                }
                if (x in ",.;!?" && x.length == 1 || x in X.STOP_AT || verbWord(x)) break
                used = t.end()
                if (x in setOf("und", "and", "oder", "or") || x in X.STOP) continue
                val type = TYPES[x]
                if (type != null) {
                    kinds += type.first
                    exts += type.second
                } else if (x !in X.GENERIC) {
                    terms += stem(x)
                }
                got = true
            }
            if (got) w.s = w.s.substring(0, m.end()) + " ".repeat(used) + w.s.substring(m.end() + used)
            got
        }
        return Exclusion(kinds.toSortedSet().toList(), exts.toSortedSet().toList(), terms)
    }

    // ---------------------------------------------------------------- d: folders against the real tree

    fun folderIndex(folders: List<String>): LinkedHashMap<String, MutableList<String>> {
        val leaves = LinkedHashMap<String, MutableList<String>>()
        for (f in folders) leaves.getOrPut(fold(f.substringAfterLast('/'))) { mutableListOf() } += f
        return leaves
    }

    /** A folder: a path (how exact|alias|fuzzy), several (ambiguous) or none (missing). */
    class Found(val path: String?, val paths: List<String>, val how: String) {
        val any: Boolean get() = path != null || paths.isNotEmpty()
    }

    private fun countSlash(s: String) = s.count { it == '/' }

    fun resolveFolder(name: String, folders: List<String>, leaves: Map<String, List<String>>): Found {
        var n = rx("^(den |dem |der |die |the |meine[nm]? |my )?(ordner |folder )?").sub(lower(pyStrip(name).trim('/')), "")
        n = pyStrip(rx("[ -]?(ordner|folder)$").sub(n, ""))
        if (n.isEmpty() || n in X.HERE_WORDS) return Found(null, emptyList(), "missing")
        val fs = HashMap<String, String>()
        for (f in folders) fs[lower(f)] = f
        fs[n]?.let { return Found(it, emptyList(), "exact") }
        val first = n.substringBefore('/')
        val rest = if ('/' in n) n.substringAfter('/') else ""
        for (cand in X.ALIAS[first] ?: emptyList()) {
            val full = cand + if (rest.isNotEmpty()) "/$rest" else ""
            fs[lower(full)]?.let { return Found(it, emptyList(), "alias") }
        }
        if (n.startsWith("whatsapp ")) {
            val leaf = "WhatsApp " + title(n.split(" ", limit = 2)[1])
            fs[lower(X.WA + leaf)]?.let { return Found(it, emptyList(), "alias") }
        }
        val hits = leaves[fold(n)] ?: emptyList()
        if (hits.size == 1) return Found(hits[0], emptyList(), "exact")
        if (hits.size > 1) {
            val top = hits.sortedBy { countSlash(it) }
            return if (countSlash(top[0]) < countSlash(top[1])) Found(top[0], emptyList(), "exact")
            else Found(null, hits, "ambiguous")
        }
        val fz = leaves.flatMap { (l, fl) ->
            fl.filter { stem(l) == stem(n) || (n.length >= 5 && edit1(fold(n).replace(" ", ""), l.replace(" ", ""))) }
        }
        if (fz.size == 1) return Found(fz[0], emptyList(), "fuzzy")
        if (fz.size > 1) return Found(null, fz, "ambiguous")
        return Found(null, emptyList(), "missing")
    }

    private fun edit1(a0: String, b0: String): Boolean {
        var a = a0
        var b = b0
        if (a == b) return true
        if (Math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) return a.indices.count { a[it] != b[it] } == 1
        if (a.length > b.length) a = b.also { b = a }
        return b.indices.any { b.removeRange(it, it + 1) == a }
    }

    fun messengerFolder(kind: String?, folders: List<String>): String? {
        for (leaf in X.MESSENGER[kind] ?: emptyList()) if (X.WA + leaf in folders) return X.WA + leaf
        return null
    }

    class Folders(
        val src: List<Pair<String, Int>>, val dst: List<String>, val problems: List<Pair<String, String>>,
        val bare: List<Triple<String, Int, String>>
    )

    private class Tok(val word: String, val start: Int, val end: Int)

    private val DATE_WORD = "${X.MON}|${X.WD}|${X.SEA}|${X.HOL}|heute|gestern|vorgestern|today|yesterday"

    fun parseFolders(w: Work, folders: List<String>, leaves: Map<String, List<String>>): Folders {
        val src = mutableListOf<Pair<String, Int>>()
        val dst = mutableListOf<String>()
        val problems = mutableListOf<Pair<String, String>>()
        val bare = mutableListOf<Triple<String, Int, String>>()
        val leafStems by lazy { leaves.keys.map { stem(it) }.toSet() }
        val named = rx("\\bwhatsapp ([a-zäöüß]+)").all(w.s).any { m ->
            val x = m.g(1)!!
            fold("whatsapp $x") in leaves || stem("whatsapp $x") in leafStems
        }
        if (search("\\bwhatsapp\\b", w.s) != null && !named) {
            var kind: String? = if (search(X.VOICE, w.s) != null) "voice" else findall("[a-zäöüß0-9]+", w.s).firstNotNullOfOrNull { x ->
                TYPES[x]?.first?.firstOrNull()?.takeIf { it in X.MESSENGER }
            }
            if (kind == null && search("\\b(pdfs?|docx?|xlsx)\\b", w.s) != null) kind = "document"
            val path = if (kind != null) messengerFolder(kind, folders) else X.WA.dropLast(1).takeIf { it in folders }
            if (path != null) src += path to 1
            w.take("\\bwhatsapp\\b")
        } else if (search(X.VOICE, w.s) != null && !named) {
            messengerFolder("voice", folders)?.let { src += it to 1 }
        }
        var toks = rx("[a-zäöüß0-9]+(?:[/._][a-zäöüß0-9]+)*").all(w.s).map { Tok(it.value, it.start(), it.end()) }
        val used = HashSet<Int>()
        fun blank(i: Int, j: Int) {
            for (k in i until j) used += k
        }
        fun resolveRun(i: Int, maxlen: Int = 3): Triple<Int, Found?, String> {
            for (k in minOf(maxlen, toks.size - i) downTo 1) {
                if ((i until i + k).any { it in used }) continue
                val name = w.s.substring(toks[i].start, toks[i + k - 1].end)
                if (k == 1 && (name in X.STOP || name in X.GENERIC)) continue
                val f = resolveFolder(name, folders, leaves)
                if (f.any) return Triple(k, f, f.how)
            }
            return Triple(0, null, "missing")
        }
        var i = 0
        while (i < toks.size) {
            val word = toks[i].word
            var role: String? = null
            var j = i + 1
            if (word in X.DST_PREP) role = "dst"
            else if (word in X.SRC_PREP) role = "src"
            else if (word == "in") {
                val nxt = if (j < toks.size) toks[j].word else ""
                role = if (nxt in X.ACC_ART) "dst" else "src"
            }
            if (role == null || i in used) {
                i += 1
                continue
            }
            while (j < toks.size && (toks[j].word in X.ACC_ART || toks[j].word in X.DAT_ART || toks[j].word in X.NEWQ)) j += 1
            val fw = j < toks.size && toks[j].word in X.FOLDERWORD
            if (fw) j += 1
            var (k, found, how) = if (j < toks.size) resolveRun(j) else Triple(0, null, "missing")
            if (k == 0 && !fw && j + 1 < toks.size && toks[j + 1].word in X.FOLDERWORD) {
                val r = resolveRun(j, 1)
                k = r.first
                found = r.second
                how = r.third
            }
            if (how == "ambiguous") {
                problems += "ambiguous_folder" to found!!.paths.map { it.substringAfterLast('/') }.sorted().joinToString(", ")
                blank(i, j + 1)
            } else if (found?.path != null) {
                if (role == "src") src += found.path to 2 else dst += found.path
                var end = j + k
                if (end < toks.size && toks[end].word in X.FOLDERWORD) end += 1
                blank(i, end)
                i = end
                continue
            } else if (role == "dst" && j < toks.size && word != "zu" && (fw || word in setOf("nach", "to", "into"))) {
                val nm = toks[j].word
                if (nm !in X.STOP && nm !in X.GENERIC && nm !in TYPES && verbOf(nm) == null && nm !in X.UNIT &&
                    nm.isNotEmpty() && nm.all { it.isLetter() } && full(DATE_WORD, nm) == null) {
                    dst += title(nm)
                    blank(i, j + 1)
                }
            } else if (role == "dst" && fw) {
                dst += ""
                blank(i, j)
            } else if (fw && role == "src" && j < toks.size && toks[j].word !in X.STOP && toks[j].word !in X.GENERIC &&
                verbOf(toks[j].word) == null) {
                problems += "unknown_folder" to toks[j].word
            }
            i += 1
        }
        // "X Ordner" / "Ordner X" without a preposition: the 1-2 words before, else the 1-3 words after
        for ((idx, tok) in toks.withIndex()) {
            if (tok.word in X.FOLDERWORD && idx !in used) {
                var hit = false
                for ((a, b) in listOf(idx - 2 to idx, idx - 1 to idx, idx + 1 to idx + 4, idx + 1 to idx + 3, idx + 1 to idx + 2)) {
                    if (a < 0 || b > toks.size || (a until b).any { it in used }) continue
                    val f = resolveFolder(w.s.substring(toks[a].start, toks[b - 1].end), folders, leaves)
                    if (f.path != null && f.how != "ambiguous") {
                        src += f.path to 2
                        blank(minOf(a, idx), maxOf(b, idx + 1))
                        hit = true
                        break
                    }
                }
                if (!hit) used += idx
            }
        }
        for (k in used.sorted()) {
            if (k >= toks.size) continue
            val a = toks[k].start
            val b = toks[k].end
            w.s = w.s.substring(0, a) + " ".repeat(b - a) + w.s.substring(b)
        }
        // bare folder names on the phone (1-3 words), then standard folder words; plain type words are no folder
        toks = rx("[a-zäöüß0-9]+(?:[._][a-zäöüß0-9]+)*").all(w.s).map { Tok(it.value, it.start(), it.end()) }
        for (ii in toks.indices) {
            for (k in listOf(3, 2, 1)) {
                if (ii + k > toks.size) continue
                val words = toks.subList(ii, ii + k).map { it.word }
                if (k == 1 && (words[0] in X.STOP || words[0] in X.GENERIC || (words[0] in TYPES && words[0] !in X.FOLDERISH) ||
                        words[0].length < 4)) continue
                val name = words.joinToString(" ")
                var hits: List<String?> = leaves[fold(name)] ?: emptyList()
                if (hits.isEmpty() && k == 1 && words[0] in X.ALIAS && (words[0] !in TYPES || words[0] in X.FOLDERISH)) {
                    val cands = X.ALIAS.getValue(words[0])
                    hits = if (cands.any { it in folders }) listOf(cands.firstOrNull { it in folders }) else emptyList()
                }
                if (hits.size == 1 && pyStrip(w.s.substring(toks[ii].start, toks[ii + k - 1].end)).isNotEmpty()) {
                    src += hits[0]!! to 1
                    val a = toks[ii].start
                    val b = toks[ii + k - 1].end
                    bare += Triple(hits[0]!!, a, w.s.substring(a, b))
                    w.s = w.s.substring(0, a) + " ".repeat(b - a) + w.s.substring(b)
                    break
                }
            }
        }
        if (search("\\bhandy ?(videos?|fotos?|bilder|clips?|photos?|aufnahmen)\\b", w.s) != null ||
            search("\\b(fotos?|bilder|bild|videos?|photos?|pictures?|clips?)\\b.{0,40}${X.TAKEN}", w.s) != null) {
            if ("DCIM/Camera" in folders) src += "DCIM/Camera" to 1
            w.take("\\bhandy(?= ?(videos?|fotos?|bilder|clips?|photos?|aufnahmen)\\b)")
            w.take(X.TAKEN)
        }
        if (search(X.DOWNLOAD_VERB, w.s) != null && "Download" in folders) {
            src += "Download" to 1
            w.take(X.DOWNLOAD_VERB)
        }
        return Folders(src, dst, problems, bare)
    }

    // ---------------------------------------------------------------- words: types, sort, counts, verbs

    fun verbOf(tok: String): String? {
        if (tok in X.GENERIC || tok in TYPES) return null
        X.ACTION_EXACT[tok]?.let { return it }
        for ((act, stems) in X.ACTION_STEMS) if (stems.any { tok.startsWith(it) }) return act
        return null
    }

    fun isVerb(tok: String) = tok in X.VERB_EXACT || verbOf(tok) != null

    fun parseSort(w: Work): Pair<String, Int> {
        var sort = ""
        var limit = 0
        for ((key, pat) in X.SORTW) {
            val m = search("\\b((?P<art>die|der|das|den|dem|mein|meine|meinen|meinem|meiner|the|my|top) )?((?P<n>${X.NUMW}) )?(?P<adj>$pat)" +
                "(?P<noun> [a-zäöüß0-9]+)?", w.s) ?: continue
            sort = key
            val adj = m.g("adj")!!
            if (!m.g("n").isNullOrEmpty()) {
                limit = num(m.g("n")!!).toInt()
            } else if (adj in setOf("zuletzt", "als letztes", "als letzte", "als letzter")) {
                limit = 1
            } else if (!m.g("art").isNullOrEmpty() ||
                listOf("biggest", "largest", "newest", "latest", "smallest", "oldest").any { adj.startsWith(it) }) {
                val art = m.g("art") ?: ""
                val noun = pyStrip(m.g("noun") ?: "")
                if (adj.startsWith("am ")) {
                    // "am größten": no count
                } else if (adj.endsWith("e") || adj.endsWith("er") || adj.endsWith("es") ||
                    (adj.endsWith("en") && art in setOf("den", "dem", "meinen", "meinem"))) {
                    limit = 1
                } else if (!adj.endsWith("en") && noun.isNotEmpty() && !noun.endsWith("s") && noun !in X.STOP) {
                    limit = 1
                }
            }
            w.take(escape(if (m.g("noun") == null) m.value.trimEnd { it.isWhitespace() } else m.value.substring(0, m.start("noun") - m.start())))
            break
        }
        val m = search("\\b(?P<art>die|der|das|den|mein|meine|meinen|meiner|the|my)? ?(?P<adj>letzte[nrs]?|last)\\b (?P<n>${X.NUMW} )?(?P<noun>[a-zäöüß]+)?", w.s)
        if (m != null && sort.isEmpty() && (m.g("noun") ?: "") !in X.UNIT && m.g("noun") !in setOf("mal", "time", "times")) {
            sort = "newest"
            val adj = m.g("adj")!!
            if (!m.g("n").isNullOrEmpty()) {
                limit = num(pyStrip(m.g("n")!!)).toInt()
            } else if (adj in setOf("letzte", "letzter", "letztes") || (adj == "letzten" && m.g("art") in setOf("den", "meinen")) ||
                (adj == "last" && !(m.g("noun") ?: "s").let { it.ifEmpty { "s" } }.endsWith("s"))) {
                limit = 1
            }
            w.take(escape(m.value.substring(0, (if (m.g("noun") != null) m.start("noun") else m.end()) - m.start())))
        }
        val t = search("\\b(top|erste[nn]?|first) (?P<n>${X.NUMW})\\b", w.s)
        if (t != null && sort.isNotEmpty()) {
            limit = num(t.g("n")!!).toInt()
            w.take(escape(t.value))
        }
        return sort to limit
    }

    fun parseTypes(w: Work): Pair<List<String>, List<String>> {
        val kinds = mutableListOf<String>()
        val exts = mutableListOf<String>()
        for (tok in findall("[a-zäöüß0-9]+", w.s)) {
            TYPES[tok]?.let {
                kinds += it.first
                exts += it.second
            }
        }
        for (word in TYPES.keys) w.take("\\b${escape(word)}\\b")
        w.take(X.VOICE)
        val e = exts.toSortedSet().toList()
        return (if (e.isNotEmpty()) emptyList() else kinds.toSortedSet().toList()) to e
    }

    fun parseCount(w: Work): Int {
        val m = search("\\b(die|diese|meine|the|my|alle|all)? ?(?P<n>\\d+|zwei|drei|vier|fünf|sechs|sieben|acht|neun|zehn|two|three|four|" +
            "five|six|seven|eight|nine|ten|beide|beiden|both)\\b(?! ?(mb|gb|kb|uhr|%))", w.s) ?: return 0
        w.take("\\b${m.g("n")}\\b")
        return num(m.g("n")!!).toInt()
    }

    class Clause(val text: String, val question: Boolean, val subordinate: Boolean)

    fun clauses(text: String): List<Clause> = rx("(?<=[.;!?])\\s+|[,:]\\s+").split(norm(text))
        .filter { pyStrip(it).isNotEmpty() }
        .map { c -> Clause(c, pyStrip(c).endsWith("?") || match(X.QUESTION_START, c) != null, match(X.SUBORD, c) != null) }

    private fun near(toks: List<String>, i: Int) = toks.subList(maxOf(0, i - 4), i).toSet() + toks.subList(minOf(i + 1, toks.size), minOf(i + 3, toks.size))

    /** Action verbs in imperative (not question, not subordinate) clauses, without a negation nearby. */
    fun actionsSaid(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (c in clauses(text)) {
            val toks = findall("[a-zäöüß']+", c.text)
            for ((i, t) in toks.withIndex()) {
                var act = verbOf(t)
                if (t == "weg" && i > 0 && toks[i - 1] in setOf("kann", "muss", "soll", "darf", "können", "müssen")) act = "delete"
                if (act == null) continue
                if (near(toks, i).any { it in X.NEGATOR } || search("auf keinen fall|bloß nicht|keinesfalls", c.text) != null) continue
                if (c.question || c.subordinate) continue
                out += act
            }
        }
        return out
    }

    /** Words in a clause whose action verb is negated ("aber lösch nichts Wichtiges"): they say what NOT to do. */
    fun negatedWords(text: String): Set<String> {
        val out = HashSet<String>()
        for (c in clauses(text)) {
            val toks = findall("[a-zäöüß']+", c.text)
            for ((i, t) in toks.withIndex()) {
                if (verbOf(t) != null && near(toks, i).any { it in X.NEGATOR }) out += toks
            }
        }
        return out
    }

    /** Everything the code reads from the words, before the model. */
    fun prepare(text: String, folders: List<String>): Pair<String, LinkedHashMap<String, MutableList<String>>> {
        val leaves = folderIndex(folders)
        return splitCompounds(joinDictation(norm(text), joinableWords(leaves.keys))) to leaves
    }

    // the weekday helper above counts like Python's date.weekday()
    @Suppress("unused")
    private val MONDAY = DayOfWeek.MONDAY
}
