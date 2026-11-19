/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/**
 * The cues of a subtitle file on its own clock, so a delay is a shift of the lookup time and never a re-parse or a
 * rebuffer of the film. Overlapping entries (SRT and ASS allow them) show together, in file order.
 */
class SubtitleTimeline<T>(entries: List<Entry<T>>) {
    class Entry<T>(val startUs: Long, val endUs: Long, val cues: List<T>)

    private val entries = entries.sortedBy { it.startUs }

    val isEmpty: Boolean
        get() = entries.isEmpty()

    /**
     * What shows at [positionUs] of the film when the subtitles run [delayUs] late (negative: early).
     * shortcut: a linear scan, fine for the few thousand cues of a film; index it if a file ever has far more.
     */
    fun cuesAt(positionUs: Long, delayUs: Long): List<T> {
        val time = positionUs - delayUs
        val shown = ArrayList<T>()
        for (entry in entries) {
            if (entry.startUs > time) break
            if (time < entry.endUs) shown += entry.cues
        }
        return shown
    }

    companion object {
        /** The delay steps of the sheet: ±0.1 s and ±1 s, capped at ±10 min. */
        const val MAX_DELAY_MS = 600_000L

        fun clampDelay(delayMs: Long): Long = delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
    }
}
