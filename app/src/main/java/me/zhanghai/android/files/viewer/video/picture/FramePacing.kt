/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The proof for "Match frame rate": how many screen refreshes (vsyncs) each video frame stays. Fed with the release
 * times Media3 schedules for each frame (VideoFrameMetadataListener, already snapped to the vsync grid by
 * VideoFrameReleaseHelper); the actual presentation on the panel is checked by e2e/video-picture.sh with
 * `dumpsys SurfaceFlinger --latency`. 24p on 120 Hz should hold every frame exactly 5 vsyncs (target 99.9 %,
 * ordo-team/WISSENSCHAFT.md §1.2.6); 24p on 60 Hz shows the 3:2 pulldown. Thread-safe: frames come from the playback
 * thread, the sheet reads on the main thread.
 */
class FramePacing(private val capacity: Int = DEFAULT_CAPACITY) {
    private val intervals = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var lastNs = 0L

    @Synchronized
    fun onFrame(releaseTimeNs: Long) {
        val interval = releaseTimeNs - lastNs
        lastNs = releaseTimeNs
        // A pause, a seek or the first frame: no interval.
        if (interval <= 0 || interval > MAX_INTERVAL_NS) return
        intervals[next] = interval
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** After a seek, a new video or a speed change the old cadence says nothing. */
    @Synchronized
    fun reset() {
        count = 0
        next = 0
        lastNs = 0L
    }

    @Synchronized
    fun intervals(): LongArray = LongArray(count) { intervals[(next - count + it + capacity) % capacity] }

    /** [counts]: how many frames stood for 1, 2, 3 … vsyncs. */
    data class Summary(val counts: Map<Int, Int>, val total: Int)

    sealed class Verdict {
        /** The rate divides the refresh: [share] of the frames held exactly [vsyncs]. */
        data class Even(val vsyncs: Int, val share: Double, val frames: Int) : Verdict()

        /**
         * It does not: frames alternate between [longer] and [shorter] vsyncs (3:2 for 24p on 60 Hz). [share] of the
         * frames held one of the two; [longShare] held the longer one, a clean cadence has [expectedLongShare] (½ for
         * 3:2).
         */
        data class Pulldown(
            val longer: Int,
            val shorter: Int,
            val share: Double,
            val longShare: Double,
            val expectedLongShare: Double,
            val frames: Int
        ) : Verdict()
    }

    companion object {
        const val DEFAULT_CAPACITY = 1200
        /** Fewer frames than this (2 s of 24p) say nothing yet. */
        const val MIN_FRAMES = 48
        private const val MAX_INTERVAL_NS = 250_000_000L
        // 23.976 fps on 120 Hz is 0.1 % off and counts as even (its rare repeat shows in the share); 25 on 120 is not.
        private const val INTEGER_TOLERANCE = 0.005

        fun summarize(intervalsNs: LongArray, vsyncNs: Double): Summary {
            val counts = HashMap<Int, Int>()
            for (interval in intervalsNs) {
                val vsyncs = (interval / vsyncNs).roundToInt().coerceAtLeast(1)
                counts[vsyncs] = (counts[vsyncs] ?: 0) + 1
            }
            return Summary(counts, intervalsNs.size)
        }

        /** [frameRate] is the film's rate times the playback speed; null until [MIN_FRAMES] were seen. */
        fun verdict(summary: Summary, frameRate: Float, refreshRate: Float): Verdict? {
            if (summary.total < MIN_FRAMES || frameRate <= 0f || refreshRate <= 0f) return null
            val perFrame = refreshRate.toDouble() / frameRate
            val nearest = perFrame.roundToInt()
            if (nearest >= 1 && abs(perFrame - nearest) <= INTEGER_TOLERANCE * perFrame) {
                return Verdict.Even(nearest, (summary.counts[nearest] ?: 0).toDouble() / summary.total, summary.total)
            }
            val shorter = floor(perFrame).toInt().coerceAtLeast(1)
            val longer = ceil(perFrame).toInt()
            val longFrames = summary.counts[longer] ?: 0
            val share = ((summary.counts[shorter] ?: 0) + longFrames).toDouble() / summary.total
            return Verdict.Pulldown(
                longer, shorter, share, longFrames.toDouble() / summary.total, perFrame - shorter, summary.total
            )
        }
    }
}
