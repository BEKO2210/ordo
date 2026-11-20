/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** One headphone model's parametric correction from AutoEq: pre-amp plus filters (type, Fc, gain, Q). */
class AutoEqProfile(
    val name: String,
    /** Who measured it (oratory1990, crinacle, Rtings, ...): shown with the name, AutoEq credits them. */
    val source: String,
    val preampDb: Float,
    val types: IntArray,
    val frequenciesHz: FloatArray,
    val gainsDb: FloatArray,
    val qs: FloatArray
)

/**
 * The 500 headphone models measured by the most independent sources in AutoEq's results (MIT licence,
 * github.com/jaakkopasanen/AutoEq), shipped in the APK as autoeq_presets.json (~100 KB, built by
 * tools/headphones/build_autoeq.py). Read once, on first use.
 */
object AutoEqPresets {
    // Absolute: R8 may move this class to another package, never the resource.
    private const val RESOURCE = "/me/zhanghai/android/files/player/headphones/autoeq_presets.json"

    val all: List<AutoEqProfile> by lazy {
        val text = AutoEqPresets::class.java.getResourceAsStream(RESOURCE)!!.use { it.readBytes().decodeToString() }
        parse(text)
    }

    fun find(name: String): AutoEqProfile? = all.firstOrNull { it.name == name }

    /**
     * Models whose name contains every word of [query], ignoring case, accents and punctuation ("hd650" finds
     * "Sennheiser HD 650"); everything for an empty query.
     */
    fun search(query: String, profiles: List<AutoEqProfile> = all): List<AutoEqProfile> {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return profiles
        return profiles.filter { profile ->
            val name = fold(profile.name)
            val compact = name.replace(" ", "")
            words.all { it in name || it in compact }
        }
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    internal fun parse(text: String): List<AutoEqProfile> {
        val profiles = JSONObject(text).getJSONArray("profiles")
        return List(profiles.length()) { index ->
            val row = profiles.getJSONArray(index)
            val flat: JSONArray = row.getJSONArray(3)
            val count = flat.length() / 4
            AutoEqProfile(
                name = row.getString(0),
                source = row.getString(1),
                preampDb = row.getDouble(2).toFloat(),
                types = IntArray(count) { flat.getInt(it * 4) },
                frequenciesHz = FloatArray(count) { flat.getDouble(it * 4 + 1).toFloat() },
                gainsDb = FloatArray(count) { flat.getDouble(it * 4 + 2).toFloat() },
                qs = FloatArray(count) { flat.getDouble(it * 4 + 3).toFloat() }
            )
        }
    }
}
