/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/**
 * Subtitles for the deaf and hard of hearing in subtitle files, by plain rules (no model): sound descriptions such as
 * `[MUSIK]`, `[door slams]` or `(LAUGHS)` can be hidden, speaker names such as `ANNA:` get a colour per speaker in the
 * BBC Subtitle Guidelines' order (yellow, cyan, green; white is the text). Ranges, so spans of the cue survive.
 */
object Sdh {
    /** BBC Subtitle Guidelines, colours for speakers after white. */
    val SPEAKER_COLORS = intArrayOf(0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt())

    // [anything] or (ALL CAPS) with the spaces after it; "(he says)" in lower case is dialogue and stays.
    private val SOUND = Regex("""(\[[^\]\n]*]|\((?=[^)\n]*\p{Lu})[^)\p{Ll}\n]*\))[ \t]*""")
    private val EMPTY_LINE = Regex("""(?m)^[ \t\-–]*(\n|$)|\n[ \t\-–]*$""")
    // "ANNA:", "- DR. MÜLLER:" at the start of a line, at most 30 characters, all caps.
    private val SPEAKER = Regex("""(?m)^(?:[-–][ \t]*)?(\p{Lu}[\p{Lu}\p{N} .'\-]{0,29}):""")

    /** What to delete for "hide sound descriptions": the descriptions, then what that left empty. */
    fun soundRanges(text: String): List<IntRange> = SOUND.findAll(text).map { it.range }.toList()

    /** Lines left empty (or only a dash) after [soundRanges] were deleted. */
    fun emptyLineRanges(text: String): List<IntRange> =
        EMPTY_LINE.findAll(text).map { it.range }.filter { !it.isEmpty() }.toList()

    /** The speaker names at line starts, as the name's range and the name. */
    fun speakers(text: String): List<Pair<IntRange, String>> =
        SPEAKER.findAll(text).map { it.groups[1]!!.range to it.groupValues[1].trim() }.toList()

    /** Removes [ranges] from [text]; the String twin of what the player does on a Spannable (for tests). */
    fun delete(text: String, ranges: List<IntRange>): String {
        val builder = StringBuilder(text)
        ranges.sortedByDescending { it.first }.forEach { builder.delete(it.first, it.last + 1) }
        return builder.toString()
    }

    /** Colours in order of first appearance across the file, so ANNA keeps hers all film long. */
    class SpeakerColors {
        private val bySpeaker = HashMap<String, Int>()

        fun colorOf(name: String): Int =
            bySpeaker.getOrPut(name) { SPEAKER_COLORS[bySpeaker.size % SPEAKER_COLORS.size] }
    }
}
