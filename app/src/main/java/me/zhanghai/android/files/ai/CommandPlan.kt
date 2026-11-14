/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.json.JSONArray
import org.json.JSONObject

/** What the model may answer. Same contract as utils/command-bench/bench.py (SCHEMA). */
data class CommandPlan(
    val action: String,
    val source: String,
    val destination: String = "",
    val kinds: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val nameContains: String = "",
    val olderThan: Int = 0,
    val ageUnit: String = "days",
    val largerThan: Int = 0,
    val sizeUnit: String = "mb",
    val groupBy: String = ""
) {
    // The model only copies numbers and units, the app does the arithmetic.
    // Capped at 100 years, so a silly number can never wrap around into "every file".
    val olderThanDays: Int
        get() = (olderThan.toLong() * when (ageUnit) {
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
        .put("older_than", olderThan).put("age_unit", ageUnit).put("larger_than", largerThan)
        .put("size_unit", sizeUnit).put("group_by", groupBy).toString()

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
                    olderThan = o.optInt("older_than").coerceAtLeast(0),
                    ageUnit = o.optString("age_unit", "days"),
                    largerThan = o.optInt("larger_than").coerceAtLeast(0),
                    sizeUnit = o.optString("size_unit", "mb"),
                    groupBy = o.optString("group_by")
                )
            } catch (e: Exception) {
                null
            }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }
    }
}
