/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.json.JSONArray
import org.json.JSONObject

/** What the model may answer. Same contract as plan K in tools/memory-lab/phase0b.py (FIELDS_K). */
data class CommandPlan(
    val action: String,
    val source: String,
    val destination: String = "",
    val kinds: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val nameContains: String = "",
    /** A word in the name or, for plain text files, in the text (show, move, copy). */
    val contentContains: String = "",
    val olderThan: Int = 0,
    val newerThan: Int = 0,
    val ageUnit: String = "days",
    val largerThan: Int = 0,
    val sizeUnit: String = "mb",
    /** "", "size" (largest first) or "newest". */
    val sort: String = "",
    /** At most this many files; 0 means all. */
    val limit: Int = 0,
    val groupBy: String = "",
    /** "heute"/"today": changed since midnight. Set by [CommandRules], never by the model. */
    val today: Boolean = false,
    // Room for the next rule set (tools/memory-lab/rules2.py), so it needs no change in the executor: exclusions
    // ("außer PDFs"), a date range as ISO day or minute ("2026-11-01", "2026-11-01T14:00"; from inclusive, to
    // exclusive) and a size range in bytes. The current rules never set them.
    val excludeKinds: List<String> = emptyList(),
    val excludeExtensions: List<String> = emptyList(),
    val excludeTerms: List<String> = emptyList(),
    val dateFrom: String = "",
    val dateTo: String = "",
    val minBytes: Long = 0,
    val maxBytes: Long = 0,
    /** Set by [CommandCheck] when the sentence asks for duplicates, never by the model. */
    val duplicates: Boolean = false
) {
    // The model only copies numbers and units, the app does the arithmetic.
    // Capped at 100 years, so a silly number can never wrap around into "every file".
    val olderThanDays: Int
        get() = days(olderThan)

    val newerThanDays: Int
        get() = days(newerThan)

    private fun days(count: Int): Int = (count.toLong() * when (ageUnit) {
        "weeks" -> 7
        "months" -> 30
        "years" -> 365
        else -> 1
    }).coerceIn(0, 36_500).toInt()

    val largerThanBytes: Long
        get() = largerThan.toLong() * if (sizeUnit == "gb") 1_000_000_000L else 1_000_000L

    val cleanExtensions: List<String>
        get() = extensions.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }

    fun toJson(): String = JSONObject()
        .put("action", action).put("source", source).put("destination", destination)
        .put("kinds", JSONArray(kinds)).put("extensions", JSONArray(extensions)).put("name_contains", nameContains)
        .put("content_contains", contentContains).put("older_than", olderThan).put("newer_than", newerThan)
        .put("age_unit", ageUnit).put("larger_than", largerThan).put("size_unit", sizeUnit).put("sort", sort)
        .put("limit", limit).put("group_by", groupBy).put("today", today)
        .put("exclude_kinds", JSONArray(excludeKinds)).put("exclude_extensions", JSONArray(excludeExtensions))
        .put("exclude_terms", JSONArray(excludeTerms)).put("date_from", dateFrom).put("date_to", dateTo)
        .put("min_bytes", minBytes).put("max_bytes", maxBytes).toString()

    companion object {
        fun parse(json: String): CommandPlan? =
            try {
                val o = JSONObject(json)
                CommandPlan(
                    action = o.optString("action", "none"),
                    source = o.optString("source"),
                    destination = o.optString("destination"),
                    kinds = o.optJSONArray("kinds").strings(),
                    extensions = o.optJSONArray("extensions").strings(),
                    nameContains = o.optString("name_contains"),
                    contentContains = o.optString("content_contains"),
                    olderThan = o.optInt("older_than").coerceAtLeast(0),
                    newerThan = o.optInt("newer_than").coerceAtLeast(0),
                    ageUnit = o.optString("age_unit", "days"),
                    largerThan = o.optInt("larger_than").coerceAtLeast(0),
                    sizeUnit = o.optString("size_unit", "mb"),
                    sort = o.optString("sort"),
                    limit = o.optInt("limit").coerceAtLeast(0),
                    groupBy = o.optString("group_by"),
                    today = o.optBoolean("today"),
                    excludeKinds = o.optJSONArray("exclude_kinds").strings(),
                    excludeExtensions = o.optJSONArray("exclude_extensions").strings(),
                    excludeTerms = o.optJSONArray("exclude_terms").strings(),
                    dateFrom = o.optString("date_from"),
                    dateTo = o.optString("date_to"),
                    minBytes = o.optLong("min_bytes").coerceAtLeast(0),
                    maxBytes = o.optLong("max_bytes").coerceAtLeast(0)
                )
            } catch (e: Exception) {
                null
            }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }
    }
}
