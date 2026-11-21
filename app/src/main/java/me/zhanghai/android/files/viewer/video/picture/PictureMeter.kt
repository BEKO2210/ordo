/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import java.util.Locale

/**
 * "Measurements" in the signal path: GPU time per effect pass, dropped frames, thermal headroom, the Klang chain's time
 * per buffer and audio underruns, one row per second, shareable as CSV. Off by default; while off nothing is wrapped
 * or timed, except the Detail plus pass, whose GPU time decides its fallback ([DetailPlus.fallBack]).
 * GL and audio threads add, the main thread takes a second's sums ([take]); no Android in here (PictureMeterTest).
 */
class PictureMeter {
    /** The whole measurement (the expanded section); also allows CPU timing where GPU timers are missing. */
    @Volatile
    var measuring = false

    /** How the GL passes are timed: "gpu" (EXT_disjoint_timer_query), "cpu" (glFinish) or "" (not yet). */
    @Volatile
    var timer = ""

    class Stat(val sum: Double, val max: Double, val count: Int) {
        val mean: Double get() = if (count == 0) 0.0 else sum / count
    }

    private val passes = LinkedHashMap<String, DoubleArray>()
    private var audioSum = 0.0
    private var audioMax = 0.0
    private var audioCount = 0
    private val rows = ArrayList<String>()

    @Synchronized
    fun addPass(pass: String, ms: Double) {
        val stat = passes.getOrPut(pass) { DoubleArray(3) }
        stat[0] += ms
        stat[1] = maxOf(stat[1], ms)
        stat[2] += 1.0
    }

    /** The Klang chain's processing time of one buffer (audio thread). */
    @Synchronized
    fun addAudio(nanos: Long) {
        val ms = nanos / 1e6
        audioSum += ms
        audioMax = maxOf(audioMax, ms)
        audioCount++
    }

    /** The sums since the last call, per pass, and the audio's; then everything starts from zero. */
    @Synchronized
    fun take(): Pair<Map<String, Stat>, Stat> {
        val taken = passes.mapValues { (_, it) -> Stat(it[0], it[1], it[2].toInt()) }
        passes.clear()
        val audio = Stat(audioSum, audioMax, audioCount)
        audioSum = 0.0
        audioMax = 0.0
        audioCount = 0
        return taken to audio
    }

    /** One second as a CSV row (dots, whatever the device's language); [headroom] NaN = unknown. */
    @Synchronized
    fun addRow(
        seconds: Long,
        upscaler: String,
        stats: Map<String, Stat>,
        dropped: Int,
        headroom: Float,
        audio: Stat,
        underruns: Int
    ) {
        val cells = mutableListOf(seconds.toString(), timer, upscaler)
        for (pass in PASSES) {
            val stat = stats[pass]
            cells += if (stat == null || stat.count == 0) listOf("", "", "0") else
                listOf(stat.mean.format(), stat.max.format(), stat.count.toString())
        }
        cells += listOf(
            dropped.toString(), if (headroom.isNaN()) "" else headroom.toDouble().format(),
            if (audio.count == 0) "" else audio.mean.format(), if (audio.count == 0) "" else audio.max.format(),
            audio.count.toString(), underruns.toString()
        )
        rows += cells.joinToString(",")
    }

    @Synchronized
    fun csv(): String = (listOf(HEADER) + rows).joinToString("\n", postfix = "\n")

    @Synchronized
    fun clear() {
        passes.clear()
        rows.clear()
        audioSum = 0.0
        audioMax = 0.0
        audioCount = 0
    }

    private fun Double.format(): String = String.format(Locale.ROOT, "%.3f", this)

    companion object {
        const val PASS_DETAIL = "detailplus"
        const val PASS_LANCZOS = "lanczos"
        const val PASS_ENHANCE = "enhance"
        val PASSES = listOf(PASS_DETAIL, PASS_LANCZOS, PASS_ENHANCE)
        val HEADER = (listOf("t_s", "timer", "upscaler") +
            PASSES.flatMap { listOf("${it}_ms", "${it}_max_ms", "${it}_frames") } +
            listOf("dropped", "thermal_headroom", "audio_ms", "audio_max_ms", "audio_buffers", "underruns"))
            .joinToString(",")
    }
}
