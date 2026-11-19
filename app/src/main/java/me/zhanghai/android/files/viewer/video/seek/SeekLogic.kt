/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.seek

import kotlin.math.floor
import kotlin.math.roundToLong

/** The pure parts of seek preview, chapters, frame step, A-B repeat and zoom: no Android in here (unit-tested). */
internal object SeekLogic {
    /** About this many thumbnails over a whole film, never closer than 1 s nor further apart than 10 s. */
    const val THUMB_BUCKETS = 200
    const val MIN_BUCKET_MS = 1_000L
    const val MAX_BUCKET_MS = 10_000L

    fun bucketSizeMs(durationMs: Long): Long =
        if (durationMs <= 0) MIN_BUCKET_MS else (durationMs / THUMB_BUCKETS).coerceIn(MIN_BUCKET_MS, MAX_BUCKET_MS)

    /**
     * The time whose frame stands for [positionMs]: the middle of its bucket, so a finger moving slowly reuses one
     * decoded frame, and inside the film (never at or after its end, where there is no frame).
     */
    fun thumbnailTimeMs(positionMs: Long, durationMs: Long): Long {
        val size = bucketSizeMs(durationMs)
        val clamped = if (durationMs > 0) positionMs.coerceIn(0L, durationMs - 1) else positionMs.coerceAtLeast(0L)
        val middle = clamped / size * size + size / 2
        return if (durationMs > 0) middle.coerceAtMost(durationMs - 1) else middle
    }

    class Chapter(val startMs: Long, val title: String?)

    /** Visible chapters by start, one per start time; a film with fewer than two has no chapters worth showing. */
    fun normalizeChapters(chapters: List<Chapter>): List<Chapter> {
        val sorted = chapters.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }
        return if (sorted.size < 2) emptyList() else sorted
    }

    /** Index of the chapter playing at [positionMs] in [chapters] (sorted), -1 before the first. */
    fun chapterIndexAt(chapters: List<Chapter>, positionMs: Long): Int {
        var low = 0
        var high = chapters.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (chapters[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Frame rate when the file names none (or a nonsense one). */
    const val FALLBACK_FPS = 30f

    /**
     * Where one frame on or back from [positionMs] starts, in ms. The player's position is in whole ms, so the
     * current frame is the one whose start is nearest; ExoPlayer's exact seek then shows the first frame at or after
     * the target, which is why the target is that frame's start rounded down. Exact for constant frame rates; with a
     * variable rate a step can skip or repeat a frame.
     */
    fun frameStepTargetMs(positionMs: Long, fps: Float, forward: Boolean, durationMs: Long): Long {
        val rate = if (fps.isFinite() && fps in 1f..1000f) fps else FALLBACK_FPS
        val frameMs = 1000.0 / rate
        val current = (positionMs / frameMs).roundToLong()
        val index = (if (forward) current + 1 else current - 1).coerceAtLeast(0L)
        val target = floor(index * frameMs).toLong()
        return if (durationMs > 0) target.coerceAtMost(durationMs - 1) else target
    }

    /** A-B repeat: nothing → A set → A and B set (looping) → nothing. */
    data class AbRepeat(val aMs: Long = UNSET, val bMs: Long = UNSET) {
        val isLooping: Boolean
            get() = aMs != UNSET && bMs != UNSET
        val hasA: Boolean
            get() = aMs != UNSET

        /**
         * The next state for a press at [positionMs]. B before A swaps them; a loop shorter than [MIN_LOOP_MS] is too
         * short to watch and keeps waiting for B.
         */
        fun next(positionMs: Long): AbRepeat = when {
            isLooping -> AbRepeat()
            !hasA -> AbRepeat(aMs = positionMs.coerceAtLeast(0L))
            kotlin.math.abs(positionMs - aMs) < MIN_LOOP_MS -> this
            positionMs < aMs -> AbRepeat(positionMs.coerceAtLeast(0L), aMs)
            else -> AbRepeat(aMs, positionMs)
        }

        companion object {
            const val UNSET = -1L
            const val MIN_LOOP_MS = 500L
        }
    }

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f

    fun clampZoom(zoom: Float): Float = if (zoom.isNaN()) MIN_ZOOM else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** How far the zoomed picture may move along one axis of [sizePx] before an edge would come inside the screen. */
    fun clampPan(panPx: Float, zoom: Float, sizePx: Int): Float {
        val limit = (clampZoom(zoom) - 1f) * sizePx / 2f
        return panPx.coerceIn(-limit, limit)
    }
}
