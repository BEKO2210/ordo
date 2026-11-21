/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/** libordo_df (utils/deepfilter/build.sh): DeepFilterNet3 for one mono stream. Only arm64-v8a and x86_64 have it. */
internal object DeepFilterNative {
    val available: Boolean = try {
        System.loadLibrary("ordo_df")
        true
    } catch (_: Throwable) {
        false
    }

    external fun create(modelPath: String): Long
    external fun hop(handle: Long): Int
    external fun sampleRate(handle: Long): Int
    external fun delay(handle: Long): Int
    external fun process(handle: Long, input: FloatArray, output: FloatArray): Float
    external fun free(handle: Long)
}

/** A loaded model. [process] and [close] may come from different threads; after [close] it does nothing. */
internal class DeepFilter private constructor(private var handle: Long) {
    val hop = DeepFilterNative.hop(handle)
    val sampleRate = DeepFilterNative.sampleRate(handle)
    val delay = DeepFilterNative.delay(handle)

    /** Speech estimate of one hop of [input] into [output], [delay] samples late; false once closed or on an error. */
    @Synchronized
    fun process(input: FloatArray, output: FloatArray): Boolean {
        if (handle == 0L || input.size != hop || output.size != hop) return false
        return !DeepFilterNative.process(handle, input, output).isNaN()
    }

    @Synchronized
    fun close() {
        if (handle != 0L) DeepFilterNative.free(handle)
        handle = 0L
    }

    companion object {
        fun load(modelPath: String): DeepFilter? {
            if (!DeepFilterNative.available) return null
            val handle = DeepFilterNative.create(modelPath)
            return if (handle == 0L) null else DeepFilter(handle)
        }
    }
}

/**
 * "Dialog freistellen plus" (~/ordo-team/LOKALE-QUALITAETS-KI.md 1.1): a speech model estimates the dialog S in the
 * mid M = (L + R) / 2; the rest of the mid (M − S) is lowered by [restGain], the side stays: mid' = S + g·(M − S),
 * L = mid' + side, R = mid' − side. The model answers [delay] samples late, so mid and side wait as long and the first
 * [delay] output frames after a flush are dropped: frame i of the output is frame i of the input, the picture stays
 * in sync. Off, the delayed input passes unchanged (same timing, no jump when switching); the switch glides over
 * [GLIDE_SECONDS]. [separate] is the model (a fake in tests).
 */
internal class DialogIsolation(
    private val hop: Int,
    private val delay: Int,
    sampleRate: Int,
    private val separate: (FloatArray, FloatArray) -> Boolean
) {
    @Volatile
    var enabled = false

    @Volatile
    var restGain = REST_GAIN

    /** Set when the model failed (or was closed); from then on the input passes unchanged until the next [clear]. */
    var failed = false
        private set

    private val mid = FloatArray(hop)
    private val side = FloatArray(hop)
    private val speech = FloatArray(hop)
    private val ringSize = delay + hop
    private val midRing = FloatArray(ringSize)
    private val sideRing = FloatArray(ringSize)
    private var ringPos = 0
    private var fill = 0
    private var toDrop = delay
    private var framesIn = 0L
    private var framesOut = 0L
    private var mix = 0f
    // Hops the model runs before it is heard: after a flush or a pause of the model its history is stale.
    private val warmupHops = (delay + hop - 1) / hop + 1
    private var warmup = warmupHops
    private var modelRan = false
    private val glideStep = 1f / (sampleRate * GLIDE_SECONDS)

    /** After a flush: the model's history is stale, the delay starts again. */
    fun clear() {
        midRing.fill(0f)
        sideRing.fill(0f)
        ringPos = 0
        fill = 0
        toDrop = delay
        framesIn = 0
        framesOut = 0
        failed = false
        mix = 0f
        warmup = warmupHops
        modelRan = false
    }

    /** One stereo input frame; [emit] gets output frames as whole hops become ready. */
    fun push(left: Float, right: Float, emit: (Float, Float) -> Unit) {
        mid[fill] = (left + right) * 0.5f
        side[fill] = (left - right) * 0.5f
        framesIn++
        if (++fill == hop) runHop(emit, framesIn)
    }

    /** End of stream: the frames still held come out (padded with silence through the model). */
    fun finish(emit: (Float, Float) -> Unit) {
        val end = framesIn
        while (framesOut < end) {
            mid[fill] = 0f
            side[fill] = 0f
            if (++fill == hop) runHop(emit, end)
        }
    }

    private fun runHop(emit: (Float, Float) -> Unit, limit: Long) {
        fill = 0
        val wanted = if (enabled && !failed) 1f else 0f
        val modelRuns = wanted > 0f || mix > 0f
        if (modelRuns && !modelRan) warmup = warmupHops
        modelRan = modelRuns
        if (modelRuns && !separate(mid, speech)) failed = true
        // Until the model has seen fresh sound, it is not heard (the glide starts after that).
        val target = if (warmup > 0) mix.coerceAtMost(wanted) else wanted
        if (modelRuns && warmup > 0) warmup--
        val g = restGain
        for (i in 0 until hop) {
            // The ring holds the last delay + hop samples: what was written delay samples ago comes out now.
            val readPos = (ringPos + hop) % ringSize
            midRing[ringPos] = mid[i]
            sideRing[ringPos] = side[i]
            val m = midRing[readPos]
            val s = sideRing[readPos]
            ringPos = (ringPos + 1) % ringSize
            if (mix != (if (failed) 0f else target)) {
                val to = if (failed) 0f else target
                mix = if (to > mix) minOf(to, mix + glideStep) else maxOf(to, mix - glideStep)
            }
            val outMid = if (mix > 0f && !failed) {
                val isolated = speech[i] + g * (m - speech[i])
                m + (isolated - m) * mix
            } else m
            if (toDrop > 0) {
                toDrop--
                continue
            }
            if (framesOut >= limit) continue
            framesOut++
            emit(outMid + s, outMid - s)
        }
    }

    companion object {
        /** −9 dB on everything in the middle that is not speech (the research note's example value). */
        val REST_GAIN = 10f.pow(-9f / 20f)
        const val GLIDE_SECONDS = 0.2f
    }
}

/**
 * Runs [DialogIsolation] on 16-bit stereo at the model's sample rate (48 kHz), after the downmix and before the Klang
 * chain. Active only while a model is loaded ([filter], set before the format is configured); anything else passes.
 */
class DialogIsolationProcessor : BaseAudioProcessor() {
    @Volatile
    internal var filter: DeepFilter? = null

    @Volatile
    var enabled = false

    private var isolation: DialogIsolation? = null
    private var hop = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val filter = filter
        if (filter == null || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2 ||
            inputAudioFormat.sampleRate != filter.sampleRate
        ) {
            isolation = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        hop = filter.hop
        isolation = DialogIsolation(filter.hop, filter.delay, filter.sampleRate) { input, output ->
            filter.process(input, output)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        tail = null
        isolation?.apply {
            enabled = this@DialogIsolationProcessor.enabled
            clear()
        }
    }

    override fun onReset() {
        isolation = null
        tail = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val isolation = isolation ?: return
        isolation.enabled = enabled
        val frames = inputBuffer.remaining() / BYTES_PER_FRAME
        // At most one hop more than came in: the frames held back from the last buffer.
        val output = replaceOutputBuffer((frames + hop) * BYTES_PER_FRAME)
        repeat(frames) {
            isolation.push(inputBuffer.getShort() / PCM_SCALE, inputBuffer.getShort() / PCM_SCALE) { l, r ->
                output.putShort(toShort(l))
                output.putShort(toShort(r))
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    // The frames still held at the end of the stream. Written in getOutput, not here: the pipeline may not have taken
    // the last output yet when it ends this processor's input, and the output buffer is reused.
    private var tail: ShortArray? = null

    override fun onQueueEndOfStream() {
        val isolation = isolation ?: return
        val pending = mutableListOf<Short>()
        isolation.finish { l, r ->
            pending += toShort(l)
            pending += toShort(r)
        }
        if (pending.isNotEmpty()) tail = pending.toShortArray()
    }

    override fun getOutput(): ByteBuffer {
        val tail = tail
        if (tail != null && !hasPendingOutput()) {
            this.tail = null
            val output = replaceOutputBuffer(tail.size * 2)
            tail.forEach { output.putShort(it) }
            output.flip()
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && tail == null

    private fun toShort(value: Float): Short =
        (value * PCM_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private companion object {
        const val PCM_SCALE = 32_768f
        const val BYTES_PER_FRAME = 4
    }
}
