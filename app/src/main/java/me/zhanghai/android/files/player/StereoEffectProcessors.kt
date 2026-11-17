/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Base of Ordo's own sound effects: 16-bit stereo in and out (anything else bypasses it, like [MonoAudioProcessor]),
 * a plain copy while off, and one frame of floats in -1..1 at a time while on. Settings are @Volatile fields the UI
 * thread writes; subclasses read them once per buffer in [prepare], so a change applies live without a restart.
 * Nothing switches hard, because a hard switch is an audible crack: on and off cross-fade dry and wet over
 * [CROSSFADE_SECONDS], and subclasses glide their parameters per frame with [glide] (see ClickFreeTest).
 * Without float output enabled, DefaultAudioSink hands every processor 16-bit PCM.
 * https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder
 */
abstract class StereoEffectProcessor : BaseAudioProcessor() {
    @Volatile
    var effectEnabled: Boolean = false

    /** Sample rate of the active format; valid from the first flush on. */
    protected var sampleRate = DEFAULT_SAMPLE_RATE
        private set
    private var running = false
    protected var outLeft = 0f
    protected var outRight = 0f

    /** Share of the processed signal, 0 (dry) to 1 (wet); moves towards [effectEnabled] by [mixStep] per frame. */
    private var mix = 0f
    private var mixStep = 1f

    /** One-pole factor per frame for parameter glides: reaches 63 % of a change in [GLIDE_SECONDS]. */
    protected var glide = 1f
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    // A flush (seek, new format) starts the next buffer from silence, and a new format may bring a new sample rate.
    override fun onFlush() {
        sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        onSampleRate(sampleRate)
        mixStep = 1f / (sampleRate * CROSSFADE_SECONDS)
        glide = (1.0 - exp(-1.0 / (sampleRate * GLIDE_SECONDS))).toFloat()
        // A stream starts in the state it is set to; only changes while it plays fade.
        mix = if (effectEnabled) 1f else 0f
        running = false
    }

    override fun onReset() {
        running = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 polls with EMPTY_BUFFER, which before the first data is also our output buffer: never copy it.
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val target = if (effectEnabled) 1f else 0f
        if (mix == 0f && target == 0f) {
            running = false
            output.put(inputBuffer)
        } else {
            // Switching on starts from silence, so no stale echo or filter state from an earlier song plays.
            if (!running) {
                clear()
                running = true
            }
            prepare()
            while (inputBuffer.remaining() >= BYTES_PER_STEREO_FRAME) {
                if (mix != target) mix = if (target > mix) minOf(target, mix + mixStep) else maxOf(target, mix - mixStep)
                val left = inputBuffer.getShort() / PCM_SCALE
                val right = inputBuffer.getShort() / PCM_SCALE
                processFrame(left, right)
                if (mix < 1f) {
                    outLeft = left + (outLeft - left) * mix
                    outRight = right + (outRight - right) * mix
                }
                output.putShort(toPcm(outLeft))
                output.putShort(toPcm(outRight))
            }
        }
        output.flip()
    }

    /** Allocates what depends on the sample rate; called on flush, never while samples flow. */
    protected open fun onSampleRate(sampleRate: Int) {}

    /** Reads the current settings before a buffer is processed. */
    protected open fun prepare() {}

    /** Forgets all filter and delay state; parameters jump straight to their targets on the next [prepare]. */
    protected abstract fun clear()

    /** Processes one stereo frame and leaves the result in [outLeft] and [outRight]. */
    protected abstract fun processFrame(left: Float, right: Float)

    companion object {
        private const val BYTES_PER_STEREO_FRAME = 4
        private const val PCM_SCALE = 32_768f
        internal const val DEFAULT_SAMPLE_RATE = 44_100
        internal const val CROSSFADE_SECONDS = 0.03f
        internal const val GLIDE_SECONDS = 0.015
        private const val LIMIT_KNEE = 0.9f

        /**
         * Clipping protection: unchanged up to the knee (-0.9 dBFS), then a soft curve that approaches full scale but
         * never passes it.
         */
        internal fun softLimit(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude <= LIMIT_KNEE) return value
            return sign(value) * (LIMIT_KNEE + (1f - LIMIT_KNEE) * tanh((magnitude - LIMIT_KNEE) / (1f - LIMIT_KNEE)))
        }

        internal fun toPcm(value: Float): Short =
            (softLimit(value) * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/** Mid/side width: M = (L + R) / 2, S = (L − R) / 2, L' = M + w·S, R' = M − w·S. */
class StereoWidthAudioProcessor : StereoEffectProcessor() {
    /** 0 = mono, 100 = unchanged, 150 = wide. */
    @Volatile
    var widthPercent: Int = 100

    private var width = 1f
    private var targetWidth = 1f
    private var snap = true

    override fun prepare() {
        targetWidth = widthPercent.coerceIn(0, AudioSettings.MAX_WIDTH_PERCENT) / 100f
        if (snap) width = targetWidth
        snap = false
    }

    override fun clear() {
        snap = true
    }

    override fun processFrame(left: Float, right: Float) {
        width += (targetWidth - width) * glide
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f * width
        outLeft = mid + side
        outRight = mid - side
    }
}

/**
 * Gentle saturation: a tanh curve with a small bias, so it adds mostly second and third harmonics. The bias is
 * subtracted again (no DC at silence) and the slope at zero is 1, so quiet passages keep their level and only peaks
 * round off.
 */
class WarmthAudioProcessor : StereoEffectProcessor() {
    /** 1 light, 2 medium, 3 strong. */
    @Volatile
    var level: Int = 2

    private var drive = DRIVES[1]
    private var targetDrive = DRIVES[1]
    private var offset = 0f
    private var normalize = 1f
    private var snap = true

    override fun prepare() {
        targetDrive = DRIVES[level.coerceIn(1, AudioSettings.LEVELS) - 1]
        if (snap) setDrive(targetDrive)
        snap = false
    }

    private fun setDrive(value: Float) {
        drive = value
        offset = fastTanh(drive * BIAS)
        normalize = 1f / (drive * fastTanhSlope(drive * BIAS))
    }

    override fun clear() {
        snap = true
    }

    override fun processFrame(left: Float, right: Float) {
        if (drive != targetDrive) {
            val next = drive + (targetDrive - drive) * glide
            setDrive(if (abs(next - targetDrive) < 1e-4f) targetDrive else next)
        }
        outLeft = shape(left)
        outRight = shape(right)
    }

    private fun shape(x: Float): Float = (fastTanh(drive * (x + BIAS)) - offset) * normalize

    companion object {
        /**
         * Padé approximation of tanh, x(27 + x²) / (27 + 9x²), exactly ±1 from |x| = 3 on. Math.tanh twice per frame
         * cost 3.7 % of a core on the API 36 emulator (17.11.2026); within 0.03 of tanh, which the ear cannot tell
         * apart in a saturation curve.
         */
        internal fun fastTanh(x: Float): Float {
            if (x >= 3f) return 1f
            if (x <= -3f) return -1f
            val square = x * x
            return x * (27f + square) / (27f + 9f * square)
        }

        /** Derivative of [fastTanh], so the curve can be normalised to a slope of 1 at silence. */
        internal fun fastTanhSlope(x: Float): Float {
            if (x >= 3f || x <= -3f) return 0f
            val square = x * x
            val denominator = 27f + 9f * square
            // The quotient rule simplifies to 9 (x² − 9)² / (27 + 9x²)²: never negative, 0 at the clamp.
            val distance = square - 9f
            return 9f * distance * distance / (denominator * denominator)
        }

        private val DRIVES = floatArrayOf(0.6f, 1f, 1.6f)
        private const val BIAS = 0.05f
    }
}

/** Ordo's own processors in the order the research chose: Mono → crossfeed → width → warmth → reverb. */
class KlangProcessors {
    val mono = MonoAudioProcessor()
    val crossfeed = CrossfeedAudioProcessor()
    val width = StereoWidthAudioProcessor()
    val warmth = WarmthAudioProcessor()
    val reverb = ReverbAudioProcessor()

    val chain: Array<AudioProcessor> get() = arrayOf(mono, crossfeed, width, warmth, reverb)

    /**
     * Crossfeed only makes sense in headphones; crossfeed and width have nothing left to work on after the mono mix.
     */
    fun apply(settings: AudioSettings, headphones: Boolean) {
        mono.monoEnabled = settings.mono
        crossfeed.level = settings.crossfeedLevel
        crossfeed.effectEnabled = settings.crossfeedEnabled && headphones && !settings.mono
        width.widthPercent = settings.widthPercent
        width.effectEnabled = settings.widthEnabled && !settings.mono
        warmth.level = settings.warmthLevel
        warmth.effectEnabled = settings.warmthEnabled
        reverb.preset = settings.reverbPreset
        reverb.mixPercent = settings.reverbMix
        reverb.effectEnabled = settings.reverbEnabled
    }
}
