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

    /** Longer lines are left as they are: no rule runs over a crafted giant line (security review 5e0529f1). */
    const val MAX_LINE = 1000
    private val EMPTY_LINE = Regex("""(?m)^[ \t\-–]*(\n|$)|\n[ \t\-–]*$""")
    // "ANNA:", "- DR. MÜLLER:" at the start of a line, at most 30 characters, all caps.
    private val SPEAKER = Regex("""(?m)^(?:[-–][ \t]*)?(\p{Lu}[\p{Lu}\p{N} .'\-]{0,29}):""")

    /** Whether the rules may run on [text]: no line longer than [MAX_LINE]. */
    fun isProcessable(text: String): Boolean = text.lineSequence().all { it.length <= MAX_LINE }

    /**
     * What to delete for "hide sound descriptions": `[anything]` or `(ALL CAPS)` with the spaces after it; "(he says)"
     * in lower case is dialogue and stays. One linear scan, no regex backtracking.
     */
    fun soundRanges(text: String): List<IntRange> {
        if (!isProcessable(text)) return emptyList()
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val open = text[i]
            if (open != '[' && open != '(') {
                i++
                continue
            }
            val close = if (open == '[') ']' else ')'
            var j = i + 1
            var upper = false
            var lower = false
            while (j < text.length && text[j] != close && text[j] != '\n') {
                if (text[j].isUpperCase()) upper = true else if (text[j].isLowerCase()) lower = true
                j++
            }
            if (j >= text.length || text[j] != close) {
                // No closing mark on this line: no opener before j can close either.
                i = j
                continue
            }
            if (open == '[' || (upper && !lower)) {
                var end = j + 1
                while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
                ranges += i until end
                i = end
            } else {
                i++
            }
        }
        return ranges
    }

    /** Lines left empty (or only a dash) after [soundRanges] were deleted. */
    fun emptyLineRanges(text: String): List<IntRange> {
        if (!isProcessable(text)) return emptyList()
        return EMPTY_LINE.findAll(text).map { it.range }.filter { !it.isEmpty() }.toList()
    }

    /** The speaker names at line starts, as the name's range and the name. */
    fun speakers(text: String): List<Pair<IntRange, String>> {
        if (!isProcessable(text)) return emptyList()
        return SPEAKER.findAll(text).map { it.groups[1]!!.range to it.groupValues[1].trim() }.toList()
    }

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
