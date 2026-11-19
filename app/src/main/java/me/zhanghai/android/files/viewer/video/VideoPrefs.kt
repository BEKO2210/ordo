/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

/**
 * What the values of Ordo Video's settings page mean, without Android in it (unit-tested). The stored values are the
 * ListPreference entry values in donottranslate_ordo_prefs.xml; anything unknown (an older or edited value) falls back
 * to the default instead of failing.
 */
object VideoPrefs {
    /** "Resume": ask with the pill (default), jump there at once, or always start at the beginning. */
    enum class Resume { ASK, ALWAYS, NEVER }

    enum class Sort { NEWEST, NAME, SIZE, LENGTH }

    enum class Orientation { VIDEO, SYSTEM, LANDSCAPE }

    val JUMP_SECONDS = listOf(5, 10, 15, 30)
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    val HOLD_SPEEDS = listOf(1.5f, 2f, 3f)

    fun resume(value: String?): Resume = enumOf(value, Resume.ASK)

    fun sort(value: String?): Sort = enumOf(value, Sort.NEWEST)

    fun orientation(value: String?): Orientation = enumOf(value, Orientation.VIDEO)

    /** The double tap's and the ±buttons' jump in seconds: 5, 10 (default), 15 or 30. */
    fun jumpSeconds(value: String?): Int = value?.toIntOrNull()?.takeIf { it in JUMP_SECONDS } ?: 10

    fun speed(value: String?): Float = value?.toFloatOrNull()?.takeIf { it in SPEEDS } ?: 1f

    fun holdSpeed(value: String?): Float = value?.toFloatOrNull()?.takeIf { it in HOLD_SPEEDS } ?: 2f

    private inline fun <reified E : Enum<E>> enumOf(value: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
}
