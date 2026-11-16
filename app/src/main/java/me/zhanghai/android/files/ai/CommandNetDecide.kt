/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import me.zhanghai.android.files.ai.CommandNet.Work
import me.zhanghai.android.files.ai.CommandNet.actionsSaid
import me.zhanghai.android.files.ai.CommandNet.findall
import me.zhanghai.android.files.ai.CommandNet.full
import me.zhanghai.android.files.ai.CommandNet.isVerb
import me.zhanghai.android.files.ai.CommandNet.lower
import me.zhanghai.android.files.ai.CommandNet.match
import me.zhanghai.android.files.ai.CommandNet.norm
import me.zhanghai.android.files.ai.CommandNet.related
import me.zhanghai.android.files.ai.CommandNet.search
import me.zhanghai.android.files.ai.CommandNet.splitCompounds
import me.zhanghai.android.files.ai.CommandNet.stem
import me.zhanghai.android.files.ai.CommandNetLexicon as X
import java.time.LocalDateTime

/**
 * What the net decided for one command: a plan, a question back or a refusal. [reason] and [args] fill the lab's
 * text [say] (German; the app shows its own strings for the same reason and args).
 */
internal class NetDecision(
    val status: String, val reason: String = "", val args: List<String> = emptyList(),
    val plan: Map<String, Any>? = null, val unaccounted: List<String> = emptyList(), val warn: List<String> = emptyList(),
    /** Not in the lab's answer: words the coverage check left out because together they found nothing. */
    val dropped: List<String> = emptyList(),
    /** Not in the lab's answer: how many words the subset search tried (0: none), for the cap below. */
    val subsetTerms: Int = 0
)

/**
 * The two frozen decision functions, line by line: [guarded] = rules2.decide(flags "abcdef", lab commit 430da086) for
 * prompt C, [tuned] = rules3.decide(flags "abcd", lab commit 13bfbed9) for the tuned model's schema L. A plan is a map
 * with the lab's field names; [CommandNetPlan] turns it into the app's plan. Exceptions (a date that does not exist,
 * a number too large) are the caller's: Python raises there too.
 */
internal object CommandNetDecide {
    private val DESTRUCTIVE = listOf("move", "delete", "organize")

    /**
     * Word subsets rules2 tries are 2^n - 1; above this many words it tries none (a change asks back, a show keeps the
     * model's words). The lab's cases never reach it: CommandGuardedGoldenTest checks the largest search it saw.
     */
    const val MAX_SUBSET_TERMS = 10
    private val CHANGES = DESTRUCTIVE + "copy"

    /** p0_cases.DEFAULT */
    fun defaultPlan(): MutableMap<String, Any> = linkedMapOf(
        "action" to "none", "source" to "", "destination" to "", "kinds" to emptyList<String>(),
        "extensions" to emptyList<String>(), "name_contains" to "", "content_contains" to "", "older_than" to 0L,
        "newer_than" to 0L, "age_unit" to "days", "larger_than" to 0L, "size_unit" to "mb", "sort" to "", "limit" to 0L,
        "group_by" to "", "group" to ""
    )

    private fun str(raw: Map<String, Any?>, key: String): String = raw[key]?.let { if (it is String) it else it.toString() } ?: ""

    @Suppress("UNCHECKED_CAST")
    private fun strs(raw: Map<String, Any?>, key: String): List<String> = (raw[key] as? List<Any?>)?.map { it.toString() } ?: emptyList()

    private fun truthy(v: Any?) = when (v) {
        null -> false
        is String -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0
        is Boolean -> v
        is Collection<*> -> v.isNotEmpty()
        else -> true
    }

    private fun narrowest(ps: Collection<String>) = ps.filter { p -> ps.none { q -> q != p && q.startsWith("$p/") } }.toSortedSet().toList()

    private fun stripPy(s: String) = s.trim { it.isWhitespace() }

    private fun keepWord(x: String, neg: Set<String>, guardIgnore: Boolean) =
        x !in X.STOP && !(guardIgnore && x in X.GUARD_IGNORE) && x !in X.GENERIC && !isVerb(x) && x !in X.NEGATOR &&
            x !in X.UNIT && x.length >= 2 && full("\\d{1,2}", x) == null && x !in neg

    // ================================================================ rules2.decide (prompt C), flags "abcdef"

    /** [count] is p0.execute's file count for a plan on the phone (the coverage guard picks the reading that exists). */
    fun guarded(
        text: String, raw0: Map<String, Any?>?, folders0: Collection<String>, now: LocalDateTime,
        count: (Map<String, Any>) -> Int
    ): NetDecision {
        val today = now.toLocalDate()
        val folders = folders0.sorted()
        var unaccounted = emptyList<String>()
        var subsetTerms = 0
        val warn = mutableListOf<String>()
        fun ask(reason: String, plan: Map<String, Any>?, vararg a: String) =
            NetDecision("ask", reason, a.toList(), plan?.toMap(), unaccounted, subsetTerms = subsetTerms)
        val (t, leaves) = CommandNet.prepare(text, folders)
        for ((cat, pat, _) in X.UNSUPPORTED) if (search(pat, t) != null) return NetDecision("refuse", cat)
        for (pat in X.MEMORY) if (search(pat, t) != null) return NetDecision("refuse", "memory")
        val raw = HashMap<String, Any?>(defaultPlan()).apply { raw0?.let { putAll(it) } }
        var act = raw["action"].let { if (truthy(it)) it.toString() else "none" }
        val w = Work(t)
        val plan = defaultPlan()
        plan["action"] = act
        val storage = w.take(X.STORAGE).isNotEmpty()
        val ex = CommandNet.parseExclusion(w, ::isVerb)
        plan["exclude_kinds"] = ex.kinds
        plan["exclude_extensions"] = ex.exts
        plan["exclude_terms"] = ex.terms
        val group = X.GROUP.entries.firstOrNull { (_, pat) -> w.take("\\b(nach|by|per) ($pat)\\b").isNotEmpty() }?.key ?: ""
        val fo = CommandNet.parseFolders(w, folders, leaves)
        CommandNet.parseTime(w, today, now).let { plan["date_from"] = it.first; plan["date_to"] = it.second }
        CommandNet.parseSize(w).let { plan["min_bytes"] = it.first; plan["max_bytes"] = it.second }
        var (sort, limit) = CommandNet.parseSort(w)
        val said_count = CommandNet.parseCount(w)
        val (kinds, exts) = CommandNet.parseTypes(w)
        w.take("\\b(doppelt\\w*|duplikat\\w*|mehrfach\\w*|duplicates?|dupes?|identische[nr]?)\\b")
        val vagueT = search(X.VAGUE_T, w.s)
        val vagueS = search(X.VAGUE_S, w.s)
        w.take(X.VAGUE_T)
        w.take(X.VAGUE_S)
        val said = actionsSaid(text)
        if (act in CHANGES && act !in said) {
            val alt = said.filter { it in setOf("move", "copy", "organize") }
            act = if (alt.size == 1) alt[0] else "show"
        }
        if (act == "organize" && "move" in said && fo.dst.isNotEmpty()) act = "move"
        if (storage && act in setOf("show", "none", "delete")) {
            act = "show"
            sort = sort.ifEmpty { "size" }
        }
        plan["action"] = act
        if (act == "none") return NetDecision("plan", plan = plan)
        plan["kinds"] = kinds
        plan["extensions"] = exts
        plan["sort"] = sort
        plan["limit"] = if (sort.isNotEmpty()) limit.toLong() else 0L
        plan["group_by"] = group.ifEmpty { str(raw, "group_by") }
        if (act == "organize" && (plan["group_by"] as String).isEmpty()) plan["group_by"] = "type"
        val changes = act in CHANGES
        val noSize = plan["min_bytes"] == 0L && plan["max_bytes"] == 0L
        val noTime = plan["date_from"] == "" && plan["date_to"] == ""
        if (vagueS != null && noSize && sort.isEmpty()) {
            if (changes) return ask("vague_size", plan, vagueS.g(1)!!)
            plan["sort"] = if (match("klein|small|tiny", vagueS.g(1)!!) != null) "smallest" else "size"
        }
        if (vagueT != null && noTime) {
            if (changes) return ask("vague_time", plan, vagueT.g(1)!!)
            if ((plan["sort"] as String).isEmpty()) plan["sort"] = if (match("alt|old|ält", vagueT.g(1)!!) != null) "oldest" else "newest"
        }
        // d: source and destination against the real tree
        if (fo.problems.isNotEmpty() && changes) return ask(fo.problems[0].first, plan, fo.problems[0].second)
        val strong = fo.src.filter { it.second == 2 }.map { it.first }.toSortedSet().toList()
        val weak = fo.src.filter { it.second == 1 }.map { it.first }.toSortedSet().toList()
        val rawSource = str(raw, "source")
        val found = if (stripPy(rawSource) !in setOf("", ".")) CommandNet.resolveFolder(rawSource, folders, leaves) else null
        val how = found?.how ?: "missing"
        val modelSrc = if (how == "ambiguous") null else found?.path
        var cand = when {
            strong.isNotEmpty() -> narrowest(strong)
            weak.isNotEmpty() -> narrowest(weak)
            else -> listOfNotNull(modelSrc)   // the model's folder, when it exists, is never replaced
        }
        if (cand.size > 1) {
            if (changes) return ask("ambiguous_folder", plan, cand.joinToString(", ") { it.substringAfterLast('/') })
            cand = emptyList()
        }
        plan["source"] = cand.firstOrNull() ?: ""
        if (plan["source"] == "" && changes) {
            if (stripPy(rawSource) !in setOf("", ".", "hier") && how == "missing") return ask("unknown_folder", plan, rawSource)
            return ask("no_source", plan)
        }
        if (act == "move" || act == "copy") {
            val have = fo.dst.filter { it.isNotEmpty() && it in folders0 }.toSortedSet().toList()
            val new = fo.dst.filter { it.isNotEmpty() && it !in folders0 }
            if (have.size > 1) return ask("ambiguous_folder", plan, have.joinToString(", ") { it.substringAfterLast('/') })
            val rawDest = str(raw, "destination")
            if (have.isNotEmpty() || new.isNotEmpty()) {
                plan["destination"] = have.ifEmpty { new }.last()
            } else if (stripPy(rawDest).isNotEmpty() && stripPy(rawDest) !in setOf(".", "hier", "")) {
                val rp = CommandNet.resolveFolder(rawDest, folders, leaves)
                plan["destination"] = rp.path ?: stripPy(rawDest)
            } else if ("" !in fo.dst) {
                return ask("no_dest", plan)
            }
            if (plan["destination"] == plan["source"]) return ask("no_dest", plan)
        }
        for ((path, a, txt) in fo.bare) {
            if (path != plan["source"]) w.s = w.s.substring(0, a) + txt + w.s.substring(a + txt.length)
        }
        // a: coverage guard. Content words still unconsumed must be used by a filter.
        val nameSig = search("\\b(im (datei)?namen|in the (file ?)?name|named|called|dateiname|namens)\\b", t)
        val textSig = search("\\b(steht|stehen|vorkomm\\w*|erwähn\\w*|mention\\w*|contain\\w*|drin|inhalt|über die|über den|über das|about)\\b", t)
        val neg = CommandNet.negatedWords(text)
        val left = w.tokens().filter { keepWord(it, neg, false) }
        val saidByModel = listOf("name_contains", "content_contains").flatMap { f ->
            findall("[a-zäöüß0-9]+", splitCompounds(norm(str(raw, f)))).filter { keepWord(it, neg, false) && it !in X.TYPES }
        }
        val marked = left.filter { x -> saidByModel.any { related(x, it) } }
        val unacc = left.filter { it !in marked }
        val terms = (marked + unacc).map { stem(it) }.distinct()
        unaccounted = unacc
        val field = if (act == "delete" || (nameSig != null && textSig == null)) "name_terms" else "text_terms"
        val base = plan.toMap()
        fun nFiles(ts: List<String>) = count(base + (field to ts))
        var chosen: List<String>? = terms
        if (terms.isNotEmpty() && nFiles(terms) == 0) {
            chosen = null
            subsetTerms = terms.size
            for (k in (if (terms.size <= MAX_SUBSET_TERMS) terms.size - 1 else 0) downTo 1) {
                val ok = combinations(terms, k).filter { nFiles(it) != 0 }
                if (ok.isNotEmpty()) {
                    chosen = if (ok.size == 1 || !changes) ok[0] else null
                    break
                }
            }
            if (chosen == null) {
                if (changes) {
                    return ask(if (unacc.isNotEmpty()) "uncovered" else "no_reading", plan, unacc.ifEmpty { terms }.joinToString(" "))
                }
                chosen = marked.map { stem(it) }
            }
        }
        val picked: List<String> = chosen!!
        plan[field] = picked
        if ((act == "move" || act == "copy") && plan["destination"] == "") {
            if (picked.isEmpty()) return ask("no_dest", plan)
            plan["destination"] = capitalize(picked[0])
        }
        // f: plausibility of a count in the words (warning in the preview only)
        if (said_count != 0 && changes) {
            val n = count(plan.toMap())
            if (n != said_count) warn += "count $said_count said, $n matched"
        }
        return NetDecision("plan", plan = plan, unaccounted = unacc, warn = warn, dropped = terms - picked.toSet(),
            subsetTerms = subsetTerms)
    }

    /** str.capitalize(): first character title case, the rest lower case. */
    private fun capitalize(s: String) = if (s.isEmpty()) s else s[0].titlecaseChar() + lower(s.substring(1))

    /** itertools.combinations, in its order. */
    private fun combinations(xs: List<String>, k: Int): List<List<String>> {
        val out = mutableListOf<List<String>>()
        fun rec(start: Int, acc: List<String>) {
            if (acc.size == k) {
                out += acc
                return
            }
            for (i in start until xs.size) rec(i + 1, acc + xs[i])
        }
        rec(0, emptyList())
        return out
    }

    // ================================================================ rules3.decide (schema L), flags "abcd"

    private fun exclusion(items: List<String>): CommandNet.Exclusion {
        val ek = mutableListOf<String>()
        val ee = mutableListOf<String>()
        val et = mutableListOf<String>()
        for (x0 in items) {
            val x = lower(stripPy(x0)).trimStart('.')
            if (x.isEmpty()) continue
            val type = X.TYPES[x]
            if (x in X.NET_KINDS) {
                ek += x
            } else if (type != null && (type.first.isNotEmpty() || type.second.isNotEmpty())) {
                ek += type.first
                ee += type.second
            } else if (full("[a-z0-9]{2,5}", x) != null && X.FILE_KINDS.values.any { x in it } || x in X.EXT_WORDS || x == "apk") {
                ee += x
            } else {
                et += x
            }
        }
        return CommandNet.Exclusion(ek, ee, et)
    }

    private fun readSpan(s: String, time: Boolean, now: LocalDateTime): Pair<Any, Any> {
        val w = Work(norm(s))
        return if (time) CommandNet.parseTime(w, now.toLocalDate(), now) else CommandNet.parseSize(w)
    }

    fun tuned(text: String, raw0: Map<String, Any?>?, folders0: Collection<String>, now: LocalDateTime): NetDecision {
        val today = now.toLocalDate()
        val folders = folders0.sorted()
        val raw = raw0 ?: emptyMap()
        var act = raw["action"].let { if (truthy(it)) it.toString() else "none" }
        val (t, leaves) = CommandNet.prepare(text, folders)
        for (pat in X.MEMORY) if (search(pat, t) != null) return NetDecision("refuse", "memory")
        if (act == "ask") return NetDecision("ask", "model")
        val plan = defaultPlan()
        plan["action"] = act
        fun ask(reason: String, vararg a: String) = NetDecision("ask", reason, a.toList(), plan.toMap())
        val w = Work(t)
        w.take(X.STORAGE)
        val cx = CommandNet.parseExclusion(w, ::isVerb)
        val group = X.GROUP.entries.firstOrNull { (_, pat) -> w.take("\\b(nach|by|per) ($pat)\\b").isNotEmpty() }?.key ?: ""
        val fo = CommandNet.parseFolders(w, folders, leaves)
        val cTime = CommandNet.parseTime(w, today, now)
        val cSize = CommandNet.parseSize(w)
        val (cSort, cLimit) = CommandNet.parseSort(w)
        val (cKinds, cExts) = CommandNet.parseTypes(w)
        CommandNet.parseCount(w)
        w.take("\\b(doppelt\\w*|duplikat\\w*|mehrfach\\w*|duplicates?|dupes?|identische[nr]?)\\b")
        w.take("\\b(neue[nrs]?|new|eigene[nrs]?) (ziel)?(ordner|folder)\\b")
        val sub = w.take("\\b(unterordner\\w*|subfolders?|sub folders?)\\b").isNotEmpty()
        var vagueT = search(X.VAGUE_T, w.s)
        val vagueS = search(X.VAGUE_S, w.s)
        w.take(X.VAGUE_T)
        w.take(X.VAGUE_S)
        val said = actionsSaid(text)
        if (act in CHANGES && act !in said) {
            val alt = said.filter { it in setOf("move", "copy", "organize") }
            act = if (alt.size == 1) alt[0] else "show"
        }
        if (act == "organize" && "move" in said && (truthy(raw["destination"]) || fo.dst.any { it.isNotEmpty() }) && !sub) act = "move"
        plan["action"] = act
        if (act == "none") return NetDecision("plan", plan = plan)
        val changes = act in CHANGES
        var kinds = strs(raw, "kinds").filter { it in X.NET_KINDS }
        var exts = strs(raw, "extensions").filter { full("\\.?[A-Za-z0-9]{1,6}", stripPy(it)) != null }
            .map { lower(stripPy(it)).trimStart('.') }.toSortedSet().toList()
        if (kinds.isEmpty() && exts.isEmpty()) {
            kinds = cKinds
            exts = cExts
        }
        plan["kinds"] = if (exts.isNotEmpty()) emptyList() else kinds
        plan["extensions"] = exts
        var sort = str(raw, "sort")
        var limit = (raw["limit"] as? Number)?.toLong() ?: 0L
        if (sort.isEmpty()) {
            sort = cSort
            limit = cLimit.toLong()
        }
        if (limit != 0L && sort.isEmpty()) sort = "newest"
        plan["sort"] = sort
        plan["limit"] = if (sort.isNotEmpty()) limit else 0L
        var mx = exclusion(strs(raw, "exclude"))
        if (mx.kinds.isEmpty() && mx.exts.isEmpty() && mx.terms.isEmpty()) mx = cx
        plan["exclude_kinds"] = mx.kinds
        plan["exclude_extensions"] = mx.exts
        plan["exclude_terms"] = mx.terms
        plan["group_by"] = str(raw, "group_by").ifEmpty { group }.ifEmpty { if (act == "organize") "type" else "" }
        // b: time and size from the model's span; the code's reading of the command when the model wrote none
        val spanBad = mutableListOf<String>()
        for (time in listOf(true, false)) {
            val s = stripPy(str(raw, if (time) "time" else "size"))
            var v: Pair<Any, Any> = if (s.isNotEmpty()) readSpan(s, time, now) else if (time) "" to "" else 0L to 0L
            val any = { p: Pair<Any, Any> -> truthy(p.first) || truthy(p.second) }
            if (s.isNotEmpty() && !any(v)) spanBad += s
            if (!any(v)) v = if (time) cTime else cSize
            if (time) {
                plan["date_from"] = v.first
                plan["date_to"] = v.second
            } else {
                plan["min_bytes"] = v.first
                plan["max_bytes"] = v.second
            }
        }
        val noSize = plan["min_bytes"] == 0L && plan["max_bytes"] == 0L
        val noTime = plan["date_from"] == "" && plan["date_to"] == ""
        if (changes) {
            if (spanBad.isNotEmpty()) return ask("vague_time", spanBad[0])
            if (vagueS != null && noSize && !truthy(raw["size"])) return ask("vague_size", vagueS.g(1)!!)
            if (vagueT != null && full("neue?[nrs]?|new", vagueT.g(1)!!) != null && (act == "move" || act == "copy") &&
                truthy(raw["destination"])) {
                vagueT = null   // "into a new folder called X": the target is new, not the files
            }
            if (vagueT != null && noTime && !truthy(raw["time"])) return ask("vague_time", vagueT.g(1)!!)
        }
        if (!changes) {
            if (vagueS != null && noSize && (plan["sort"] as String).isEmpty()) {
                plan["sort"] = if (match("klein|small|tiny", vagueS.g(1)!!) != null) "smallest" else "size"
            }
            if (vagueT != null && noTime && (plan["sort"] as String).isEmpty()) {
                plan["sort"] = if (match("alt|old|ält", vagueT.g(1)!!) != null) "oldest" else "newest"
            }
        }
        // d: folders
        val mSrc = stripPy(str(raw, "source"))
        val mDst = stripPy(str(raw, "destination"))
        if (fo.problems.isNotEmpty() && changes) return ask(fo.problems[0].first, fo.problems[0].second)
        val found = if (mSrc !in setOf("", ".")) CommandNet.resolveFolder(mSrc, folders, leaves) else null
        val how = found?.how ?: "missing"
        var modelSrc: String? = found?.path
        if (modelSrc == null && found?.paths.isNullOrEmpty() && !changes && lower(mSrc).startsWith("whatsapp")) {
            // shows only: a change never gets a guessed folder
            val kind = if ("opus" in exts || search(X.VOICE + "|voice|sprach", lower(mSrc)) != null) "voice" else kinds.ifEmpty { listOf("image") }[0]
            modelSrc = CommandNet.messengerFolder(kind, folders)
        }
        if (how == "ambiguous") modelSrc = null
        val strong = fo.src.filter { it.second == 2 }.map { it.first }.toSortedSet().toList()
        val weak = fo.src.filter { it.second == 1 }.map { it.first }.toSortedSet().toList()
        var cand = if (modelSrc != null) listOf(modelSrc)
        else if (strong.isNotEmpty()) narrowest(strong) else if (weak.isNotEmpty() && mSrc.isEmpty()) narrowest(weak) else emptyList()
        if (cand.size > 1) {
            if (changes) return ask("ambiguous_folder", cand.joinToString(", ") { it.substringAfterLast('/') })
            cand = emptyList()
        }
        plan["source"] = cand.firstOrNull() ?: if (mSrc !in setOf(".", "hier") && !changes) mSrc else ""
        if (plan["source"] == "" && changes) {
            if (mSrc.isNotEmpty() && mSrc !in setOf(".", "hier")) return ask("unknown_folder", mSrc)
            return ask("no_source")
        }
        if (act == "move" || act == "copy") {
            if (mDst.isNotEmpty()) {
                plan["destination"] = CommandNet.resolveFolder(mDst, folders, leaves).path ?: mDst
            } else {
                val have = fo.dst.filter { it.isNotEmpty() && it in folders0 }.toSortedSet().toList()
                val new = fo.dst.filter { it.isNotEmpty() && it !in folders0 }
                if (have.size > 1) return ask("ambiguous_folder", have.joinToString(", ") { it.substringAfterLast('/') })
                plan["destination"] = have.ifEmpty { new }.ifEmpty { listOf("") }.last()
            }
            if (plan["destination"] == "" || plan["destination"] == plan["source"]) return ask("no_dest")
        }
        for ((path, a, txt) in fo.bare) {
            if (path != plan["source"]) w.s = w.s.substring(0, a) + txt + w.s.substring(a + txt.length)
        }
        // name / content filters: the model's words
        val split = { s: String -> CommandNet.rx("[\\s_,;/]+").split(s).filter { it.codePointCount(0, it.length) >= 2 } }
        val name = split(str(raw, "name_contains"))
        val content = split(str(raw, "content_contains"))
        plan[if (changes) "name_terms" else "text_terms"] = name
        if (content.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            plan["text_terms"] = ((plan["text_terms"] as? List<String>) ?: emptyList()) + content
        }
        // a: coverage guard, changes only: every content word left must be used by a model field
        if (changes) {
            val neg = CommandNet.negatedWords(text)
            var used = listOf("name_contains", "content_contains", "time", "size", "source").joinToString(" ") { str(raw, it) }
            used += " " + strs(raw, "exclude").joinToString(" ")
            val usedW = findall("[a-zäöüß0-9]+", splitCompounds(norm(used))).filter { it.length >= 2 }
            val left = w.tokens().filter { keepWord(it, neg, true) }
            val unacc = left.filter { x -> usedW.none { related(x, it) || x == it } }
            if (unacc.isNotEmpty()) return NetDecision("ask", "uncovered", listOf(unacc.joinToString(" ")), plan.toMap(), unacc)
        }
        return NetDecision("plan", plan = plan)
    }
}
