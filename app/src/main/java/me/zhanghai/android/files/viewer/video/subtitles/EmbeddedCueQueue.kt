/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

/**
 * The text cues of a track inside the film, as Media3's text renderer hands them over (Player.Listener.onCues: each
 * group replaces the one before, from its presentation time on), kept by that time so Ordo can show them shifted: a
 * delay is a later lookup on the playback position, so pause and speed need nothing of their own. A seek starts over
 * ([clear]); the renderer sends the groups of the new place again. Running early works because the renderer is asked
 * for cues ahead of the film ([SubtitleLead]); the groups then arrive before their time and wait here.
 * Pure Kotlin, unit-tested in EmbeddedCueQueueTest.
 */
class EmbeddedCueQueue<T>(private val text: (List<T>) -> String) {
    private class Group<T>(val timeUs: Long, val cues: List<T>)

    // Sorted by time; a group sent again for the same time replaces the old one.
    private val groups = ArrayList<Group<T>>()

    val size: Int
        get() = groups.size

    fun add(timeUs: Long, cues: List<T>) {
        var index = groups.size
        while (index > 0 && groups[index - 1].timeUs > timeUs) index--
        if (index > 0 && groups[index - 1].timeUs == timeUs) {
            groups[index - 1] = Group(timeUs, cues)
        } else {
            groups.add(index, Group(timeUs, cues))
        }
        if (groups.size > MAX_GROUPS) groups.subList(0, groups.size - MAX_GROUPS).clear()
    }

    fun clear() = groups.clear()

    /**
     * What shows at [positionUs] when the cues run [delayUs] late (negative: early). With a reading aid of [cps]
     * characters a second (0: off) a line that went too soon stays for its reading time, never over the next line.
     */
    fun cuesAt(positionUs: Long, delayUs: Long, cps: Int): List<T> {
        val time = positionUs - delayUs
        var index = groups.size - 1
        while (index >= 0 && groups[index].timeUs > time) index--
        if (index < 0) return emptyList()
        // Older groups than the furthest delay back are never asked for again.
        if (index > 1) {
            // From the position, not the shifted time: an early delay turned late again still finds them.
            val keepFrom = groups.indexOfLast { it.timeUs < minOf(time, positionUs) - KEEP_US }.coerceAtMost(index - 1)
            if (keepFrom > 0) {
                groups.subList(0, keepFrom).clear()
                index -= keepFrom
            }
        }
        val current = groups[index]
        if (current.cues.isNotEmpty() || cps <= 0) return current.cues
        val previous = groups.getOrNull(index - 1)?.takeIf { it.cues.isNotEmpty() } ?: return emptyList()
        val readingUs = ReadingAid.readingTimeUs(text(previous.cues), cps)
        return if (time < previous.timeUs + readingUs) previous.cues else emptyList()
    }

    companion object {
        /** As far back as the delay goes, so turning it up still finds the lines just shown. */
        private const val KEEP_US = SubtitleTimeline.MAX_DELAY_MS * 1000
        /** Far more groups than ten minutes of a film have; a stream beyond it loses its oldest. */
        private const val MAX_GROUPS = 4_000
    }
}
