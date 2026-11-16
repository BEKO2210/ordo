/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Debug builds and unit tests only: runs a lab fixture (tools/memory-lab/export_guarded_golden.py,
 * export_tuned_golden.py) through the Kotlin net and counts the rows where every part of the decision equals the
 * Python one (status, reason, text, words left over, warnings, every plan field). The JVM test and the device check
 * (DebugAiActivity, Android's ICU regex engine) share it, so both compare the same thing.
 */
internal object CommandNetGolden {
    class Result(val equal: Int, val total: Int, val differs: List<String>) {
        override fun toString() = "$equal/$total equal" + differs.take(10).joinToString("") { "\n  differs: $it" }
    }

    fun read(stream: InputStream): JSONObject = JSONObject(GZIPInputStream(stream).bufferedReader().readText())

    /** rules2.decide with prompt C plans; the file counts come from [LabPhone], a port of the lab's executor. */
    fun guarded(o: JSONObject): Result {
        val now = LocalDateTime.parse(o.getString("now"))
        val zone = ZoneId.of(o.getString("zone"))
        val table = o.getJSONArray("files")
        val phones = o.getJSONArray("phones").let { a ->
            (0 until a.length()).map { i ->
                val ids = a.getJSONArray(i)
                LabPhone((0 until ids.length()).map { table.getJSONArray(ids.getInt(it)) }.map { f ->
                    LabPhone.File(f.getString(0), f.getLong(1), f.getLong(2), f.getString(3))
                }, zone)
            }
        }
        return compare(o, o.getJSONArray("rows")) { row ->
            val phone = phones[row.getInt("phone")]
            val text = row.getString("text")
            CommandNetDecide.guarded(text, map(row.getJSONObject("raw")), phone.folders, now) { phone.count(it, text) }
        }
    }

    /** rules3.decide with schema L plans. */
    fun tuned(o: JSONObject): Result {
        val now = LocalDateTime.parse(o.getString("now"))
        val folders = o.getJSONArray("folders").let { a -> (0 until a.length()).map { strings(a.getJSONArray(it)).toSet() } }
        return compare(o, o.getJSONArray("rows")) { row ->
            CommandNetDecide.tuned(row.getString("text"), map(row.getJSONObject("raw")), folders[row.getInt("folders")], now)
        }
    }

    private fun compare(o: JSONObject, rows: JSONArray, decide: (JSONObject) -> NetDecision): Result {
        val ask = o.getJSONObject("ask")
        val unsupported = o.optJSONObject("unsupported")
        var equal = 0
        val differs = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val d = try {
                decide(row)
            } catch (e: Exception) {
                differs += "${row.getString("src")} ${row.getString("id")} ${row.getString("text")}: $e"
                continue
            }
            val say = when {
                d.status == "refuse" && d.reason == "memory" -> o.getString("memory_say")
                d.status == "refuse" -> unsupported?.optString(d.reason) ?: ""
                d.status == "ask" -> (ask.optString(d.reason, "").ifEmpty { o.optString("ask_model") })
                    .let { t -> d.args.foldIndexed(t) { n, s, a -> s.replace("{$n}", a) } }
                else -> ""
            }
            val got = JSONObject().put("status", d.status).put("reason", d.reason).put("say", say)
                .put("unaccounted", JSONArray(d.unaccounted)).put("plan", d.plan?.let { nonDefault(it) } ?: JSONObject.NULL)
            val want = JSONObject().put("status", row.getString("status")).put("reason", row.getString("reason"))
                .put("say", row.getString("say")).put("unaccounted", row.getJSONArray("unaccounted"))
                .put("plan", row.opt("plan") ?: JSONObject.NULL)
            if (row.has("warn")) {
                got.put("warn", JSONArray(d.warn))
                want.put("warn", row.getJSONArray("warn"))
            }
            if (got.similar(want)) equal++ else differs += "${row.getString("src")} ${row.getString("id")} " +
                "${row.getString("text")}\n    kotlin $got\n    python $want"
        }
        return Result(equal, rows.length(), differs)
    }

    private val PLAN_DEFAULT: Map<String, Any> = CommandNetDecide.defaultPlan() + mapOf(
        "exclude_kinds" to emptyList<String>(), "exclude_extensions" to emptyList<String>(),
        "exclude_terms" to emptyList<String>(), "date_from" to "", "date_to" to "", "min_bytes" to 0L, "max_bytes" to 0L,
        "name_terms" to emptyList<String>(), "text_terms" to emptyList<String>()
    )

    private fun nonDefault(plan: Map<String, Any>): JSONObject {
        val out = JSONObject()
        for ((k, v) in plan) {
            require(k in PLAN_DEFAULT) { "unknown plan field $k" }
            if (v != PLAN_DEFAULT[k]) out.put(k, if (v is List<*>) JSONArray(v) else v)
        }
        return out
    }

    fun map(o: JSONObject): Map<String, Any?> = o.keySet().associateWith { value(o.get(it)) }

    private fun value(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> map(v)
        is JSONArray -> (0 until v.length()).map { value(v.get(it)) }
        is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong() else v.toDouble()
        else -> v
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    /** The lab's phone and executor (phase0.execute/filt/c_filters, bench.Phone.resolve), as far as rules2 counts files. */
    class LabPhone(private val files: List<File>, private val zone: ZoneId) {
        class File(val path: String, val size: Long, val mtime: Long, val text: String)

        val folders: Set<String> = HashSet<String>().also { set ->
            for (f in files) {
                val parts = f.path.substringBeforeLast('/').split('/')
                for (i in 1..parts.size) set += parts.subList(0, i).joinToString("/")
            }
        }
        // ties in Python's set order are not reproducible; sorted makes ours stable
        private val sortedFolders = folders.sorted()

        private fun lower(s: String) = s.lowercase(Locale.ROOT)

        fun resolve(name: String): Pair<String?, Boolean> {
            var n = Regex("^(den |dem |the )?(ordner|folder) ", RegexOption.IGNORE_CASE).replace(name.trim().trim('/'), "").trim()
            n = Regex("[- ](ordner|folder)$", RegexOption.IGNORE_CASE).replace(n, "").trim()
            if (lower(n) in CommandNetLexicon.HERE_WORDS) return null to false
            val first = n.substringBefore('/')
            val rest = if ('/' in n) n.substringAfter('/') else ""
            val full = (ALIASES[lower(first)] ?: first) + if (rest.isNotEmpty()) "/$rest" else ""
            sortedFolders.firstOrNull { lower(it) == lower(full) }?.let { return it to true }
            if ('/' !in n) {
                sortedFolders.filter { lower(it.substringAfterLast('/')) == lower(n) }.sortedBy { f -> f.count { it == '/' } }
                    .firstOrNull()?.let { return it to true }
            }
            return full to false
        }

        @Suppress("UNCHECKED_CAST")
        fun count(plan: Map<String, Any>, text: String): Int {
            val act = plan["action"] as String
            if (act !in setOf("show", "move", "copy", "delete", "organize")) return 0
            val srcName = plan["source"] as String
            val (src, ok) = if (srcName.trim() !in setOf("", ".")) resolve(srcName) else null to false
            val pool = if (act == "show") {
                when {
                    lower(srcName.trim()) in CommandNetLexicon.HERE_WORDS + setOf("handy", "telefon", "phone") -> files
                    ok -> files.filter { it.path.startsWith("$src/") }
                    else -> return 0
                }
            } else {
                if (!ok) return 0
                files.filter { it.path.substringBeforeLast('/') == src }
            }
            val exts = (plan["extensions"] as List<String>).map { lower(it.trim().trimStart('.')) }.filter { it.isNotEmpty() }.toSet()
                .ifEmpty { (plan["kinds"] as List<String>).flatMap { CommandNetLexicon.FILE_KINDS[it] ?: emptySet() }.toSet() }
            if (exts.isEmpty() && (plan["kinds"] as List<String>).isNotEmpty() &&
                (plan["extensions"] as List<String>).none { it.trim().trimStart('.').isNotEmpty() }) return 0
            var out = pool.filter { f -> (exts.isEmpty() || ext(f) in exts) && cFilters(f, plan) }
            if (said(text, DUP_WORDS)) {
                val groups = LinkedHashMap<Pair<Long, String>, MutableList<File>>()
                for (f in out) {
                    groups.getOrPut(f.size to lower(Regex(" \\(\\d+\\)(?=\\.)").replace(f.path.substringAfterLast('/'), ""))) {
                        mutableListOf()
                    } += f
                }
                val extra = groups.values.filter { it.size > 1 }.sumOf { it.size - 1 }
                out = out.take(extra)
            }
            val limit = (plan["limit"] as Number).toInt()
            return if (limit > 0) minOf(limit, out.size) else out.size
        }

        private fun ext(f: File) = lower(f.path.substringAfterLast('.'))

        private fun ts(d: String): Long = (if ('T' in d) LocalDateTime.parse(d) else java.time.LocalDate.parse(d).atStartOfDay())
            .atZone(zone).toEpochSecond()

        @Suppress("UNCHECKED_CAST")
        private fun cFilters(f: File, plan: Map<String, Any>): Boolean {
            val name = CommandNet.fold(f.path.substringAfterLast('/'))
            val text = CommandNet.fold(f.text)
            val from = plan["date_from"] as? String ?: ""
            val to = plan["date_to"] as? String ?: ""
            if (from.isNotEmpty() && f.mtime < ts(from)) return false
            if (to.isNotEmpty() && f.mtime >= ts(to)) return false
            val min = plan["min_bytes"] as? Long ?: 0L
            val max = plan["max_bytes"] as? Long ?: 0L
            if (min != 0L && f.size < min) return false
            if (max != 0L && f.size > max) return false
            if ((plan["name_terms"] as? List<String> ?: emptyList()).any { CommandNet.fold(it) !in name }) return false
            if ((plan["text_terms"] as? List<String> ?: emptyList()).any { CommandNet.fold(it) !in name && CommandNet.fold(it) !in text }) return false
            val ex = (plan["exclude_extensions"] as? List<String> ?: emptyList()).toSet() +
                (plan["exclude_kinds"] as? List<String> ?: emptyList()).flatMap { CommandNetLexicon.FILE_KINDS[it] ?: emptySet() }
            return !(ext(f) in ex || (plan["exclude_terms"] as? List<String> ?: emptyList()).any { CommandNet.fold(it) in name })
        }

        /** phase0.said */
        private fun said(text: String, stems: List<String>): Boolean = CommandNet.rx("[,;.!?]").split(lower(text)).any { clause ->
            val toks = CommandNet.rx("[^\\w'/]+").split(clause).filter { it.isNotEmpty() }
            toks.indices.any { i ->
                stems.any { toks[i].startsWith(it) } && toks.subList(maxOf(0, i - 3), minOf(toks.size, i + 6)).none { it in NEGATIONS }
            }
        }

        private companion object {
            val ALIASES = mapOf("download" to "Download", "downloads" to "Download", "musik" to "Music", "music" to "Music",
                "dokumente" to "Documents", "documents" to "Documents", "bilder" to "Pictures", "pictures" to "Pictures",
                "filme" to "Movies", "movies" to "Movies", "kamera" to "DCIM/Camera", "camera" to "DCIM/Camera", "dcim" to "DCIM")
            val NEGATIONS = setOf("ausser", "außer", "don't", "dont", "except", "kein", "keine", "keinen", "keiner", "never",
                "nicht", "nichts", "no", "not", "ohne", "without")
            val DUP_WORDS = listOf("doppel", "duplikat", "duplicat", "dupe", "mehrfach")
        }
    }
}
