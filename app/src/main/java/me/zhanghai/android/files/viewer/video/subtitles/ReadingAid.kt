/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/**
 * "Reading aid" for subtitle files: a line shorter than it takes to read stays longer, up to the start of the next
 * line and never over it. Reading time = the longest of characters ÷ limit (Netflix Timed Text Style Guide: 20
 * characters/s adults, 17 children, 13 Swedish children), 0.3 s per word (BBC Subtitle Guidelines) and 1 s per line.
 * Pure arithmetic, unit-tested in ReadingAidTest; the film's timing itself is never touched.
 */
object ReadingAid {
    /** The setting's levels in characters per second; 0 = off. */
    val LEVELS = listOf(0, 13, 17, 20)
    const val DEFAULT_CPS = 17
    const val WORD_US = 300_000L
    const val LINE_US = 1_000_000L
    /** "Slow down for fast text": the film plays at this share of its speed while such a line shows. */
    const val SLOW_FACTOR = 0.9f

    fun level(value: String?): Int = value?.toIntOrNull()?.takeIf { it in LEVELS } ?: DEFAULT_CPS

    /** Characters as a reader sees them: line breaks and runs of spaces count as one space (Netflix counts spaces). */
    fun characters(text: String): Int =
        text.trim().replace(WHITESPACE, " ").let { it.codePointCount(0, it.length) }

    fun words(text: String): Int = text.split(WHITESPACE).count { it.isNotEmpty() }

    fun lines(text: String): Int = text.lines().count { it.isNotBlank() }

    fun cps(text: String, durationUs: Long): Double =
        if (durationUs <= 0) Double.POSITIVE_INFINITY else characters(text) * 1_000_000.0 / durationUs

    /** How long [text] needs on screen at [cps]; 0 for no text (a picture subtitle). */
    fun readingTimeUs(text: String, cps: Int): Long {
        if (text.isBlank()) return 0
        // Rounded up: the line never ends up a hair faster than the limit.
        val forCharacters = (characters(text) * 1_000_000L + cps - 1) / cps
        return maxOf(forCharacters, words(text) * WORD_US, lines(text) * LINE_US)
    }

    /**
     * The entries with their reading time where the file gave too little, never past the next entry's start (one
     * that starts later); overlaps the file already has stay as they are. [cps] 0 returns them unchanged.
     * Consecutive pieces of one line (WebVTT/TTML come in segments) are joined first, so they count as one line.
     */
    fun <T> extend(
        entries: List<SubtitleTimeline.Entry<T>>,
        cps: Int,
        text: (List<T>) -> String
    ): List<SubtitleTimeline.Entry<T>> {
        if (cps <= 0) return entries
        val joined = join(entries.sortedBy { it.startUs })
        return joined.mapIndexed { index, entry ->
            val words = text(entry.cues)
            val duration = entry.endUs - entry.startUs
            val fast = words.isNotBlank() && cps(words, duration) > cps
            val wanted = entry.startUs + readingTimeUs(words, cps)
            val nextStart = joined.asSequence().drop(index + 1).firstOrNull { it.startUs > entry.startUs }?.startUs
                ?: Long.MAX_VALUE
            val end = if (wanted > entry.endUs) maxOf(entry.endUs, minOf(wanted, nextStart)) else entry.endUs
            SubtitleTimeline.Entry(entry.startUs, end, entry.cues, fast)
        }
    }

    private fun <T> join(entries: List<SubtitleTimeline.Entry<T>>): List<SubtitleTimeline.Entry<T>> {
        val joined = ArrayList<SubtitleTimeline.Entry<T>>()
        for (entry in entries) {
            val last = joined.lastOrNull()
            if (last != null && last.endUs == entry.startUs && last.cues == entry.cues) {
                joined[joined.lastIndex] = SubtitleTimeline.Entry(last.startUs, entry.endUs, last.cues)
            } else {
                joined += entry
            }
        }
        return joined
    }

    // \s plus the no-break spaces subtitle files use between numbers and units.
    private val WHITESPACE = Regex("[\\s\u00A0\u2007\u202F]+")
}
