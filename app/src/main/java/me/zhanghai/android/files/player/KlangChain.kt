/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import me.zhanghai.android.files.player.output.HiResAudioSink
import me.zhanghai.android.files.player.output.HiResOutput
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Ordo's whole sound chain in one pass (docs/AUDIO-QUALITAET-RECHERCHE.md, 4): 16-bit stereo in, once to float,
 * every active [KlangStage] in turn, one peak limiter, one TPDF dither, once back to 16 bit. Before, each of five
 * processors read, limited and rounded the 16-bit signal on its own, five quantisations without dither. With every
 * stage off the buffer is copied untouched (bit for bit, no dither). Anything other than 16-bit stereo passes by
 * (mono sources, float). Without float output enabled, DefaultAudioSink hands every processor 16-bit PCM.
 * https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder
 */
class KlangAudioProcessor(
    private val stages: Array<KlangStage>,
    private val dither: Boolean = true
) : BaseAudioProcessor() {
    private val active = BooleanArray(stages.size)

    /** Whether the last buffer went through at least one stage (also while one fades out); audio thread. */
    @Volatile
    var processing = false
        private set
    private var seed = 0x2545F491
    private var limiterGain = 1f
    private var release = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    // A flush (seek, new format) starts from silence, and a new format may bring a new sample rate.
    override fun onFlush() {
        val sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        stages.forEach { it.flush(sampleRate) }
        release = (1.0 - exp(-1.0 / (sampleRate * RELEASE_SECONDS))).toFloat()
        limiterGain = 1f
    }

    override fun onReset() {
        stages.forEach { it.reset() }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 polls with EMPTY_BUFFER, which before the first data is also our output buffer: never copy it.
        if (!inputBuffer.hasRemaining()) return
        var any = false
        for (index in stages.indices) {
            active[index] = stages[index].begin()
            any = any || active[index]
        }
        processing = any
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!any) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= BYTES_PER_STEREO_FRAME) {
                var left = inputBuffer.getShort() / PCM_SCALE
                var right = inputBuffer.getShort() / PCM_SCALE
                for (index in stages.indices) {
                    if (!active[index]) continue
                    val stage = stages[index]
                    stage.process(left, right)
                    left = stage.outLeft
                    right = stage.outRight
                }
                // The limiter: the gain drops at once to keep the louder channel at the ceiling and recovers slowly,
                // so a peak is turned down as a whole instead of being bent sample by sample (no clipping distortion).
                var gain = limiterGain
                if (gain < 1f) {
                    gain += (1f - gain) * release
                    if (gain > 0.99999f) gain = 1f
                }
                val level = maxOf(abs(left), abs(right))
                if (level * gain > CEILING) gain = CEILING / level
                limiterGain = gain
                output.putShort(quantize(left * limiterGain))
                output.putShort(quantize(right * limiterGain))
            }
        }
        output.flip()
    }

    /** The one dither of the chain, then 16 bit. */
    private fun quantize(value: Float): Short {
        var scaled = value * PCM_SCALE
        if (dither) scaled += nextUniform() + nextUniform()
        return scaled.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Uniform in -0.5..0.5 LSB from a xorshift; two of them make triangular (TPDF) dither of 2 LSB peak to peak, as
     * Lipshitz, Wannamaker and Vanderkooy recommend (JAES 40(5), 1992).
     */
    private fun nextUniform(): Float {
        var x = seed
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        seed = x
        return (x ushr 8) / 16_777_216f - 0.5f
    }

    companion object {
        private const val BYTES_PER_STEREO_FRAME = 4
        private const val PCM_SCALE = 32_768f
        internal const val DEFAULT_SAMPLE_RATE = 44_100
        /**
         * Peak ceiling of the chain, -1.5 dBFS: with the dither on top and the peaks between samples, the output
         * stays at or below -1 dBTP (EBU R 128's maximum), measured with 4x oversampling in KlangMeasurementTest.
         */
        internal const val CEILING = 0.8414f
        /** Back to full gain with this time constant once the peak has passed. */
        private const val RELEASE_SECONDS = 0.08
    }
}

/**
 * One effect of the chain, frame by frame in floats (-1..1). Settings are @Volatile fields the UI thread writes; a
 * stage reads them once per buffer in [prepare], so a change applies live without a restart. Nothing switches hard,
 * because a hard switch is an audible crack: on and off cross-fade dry and wet over [CROSSFADE_SECONDS], and stages
 * glide their parameters per frame with [glide] (see ClickFreeTest).
 */
abstract class KlangStage {
    @Volatile
    var effectEnabled: Boolean = false

    /** Sample rate of the active format; valid from the first flush on. */
    protected var sampleRate = KlangAudioProcessor.DEFAULT_SAMPLE_RATE
        private set
    private var running = false
    var outLeft = 0f
        protected set
    var outRight = 0f
        protected set

    /** Share of the processed signal, 0 (dry) to 1 (wet); moves towards [effectEnabled] by [mixStep] per frame. */
    private var mix = 0f
    private var target = 0f
    private var mixStep = 1f

    /** One-pole factor per frame for parameter glides: reaches 63 % of a change in [GLIDE_SECONDS]. */
    protected var glide = 1f
        private set

    fun flush(sampleRate: Int) {
        this.sampleRate = sampleRate
        onSampleRate(sampleRate)
        mixStep = 1f / (sampleRate * CROSSFADE_SECONDS)
        glide = (1.0 - exp(-1.0 / (sampleRate * GLIDE_SECONDS))).toFloat()
        // A stream starts in the state it is set to; only changes while it plays fade.
        mix = if (effectEnabled) 1f else 0f
        running = false
    }

    fun reset() {
        running = false
    }

    /** Before each buffer: false while the stage is fully off (then the chain skips it). */
    fun begin(): Boolean {
        target = if (effectEnabled) 1f else 0f
        if (mix == 0f && target == 0f) {
            running = false
            return false
        }
        // Switching on starts from silence, so no stale echo or filter state from an earlier song plays; that holds
        // too when the previous buffer only just faded out to nothing.
        if (!running || mix == 0f) {
            clear()
            running = true
        }
        prepare()
        return true
    }

    fun process(left: Float, right: Float) {
        if (mix != target) mix = if (target > mix) minOf(target, mix + mixStep) else maxOf(target, mix - mixStep)
        processFrame(left, right)
        if (mix < 1f) {
            outLeft = left + (outLeft - left) * mix
            outRight = right + (outRight - right) * mix
        }
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
        internal const val CROSSFADE_SECONDS = 0.03f
        internal const val GLIDE_SECONDS = 0.015
    }
}

/** Both channels replaced by the constant-gain mono mix (half of each). */
class MonoStage : KlangStage() {
    override fun clear() {}

    override fun processFrame(left: Float, right: Float) {
        // https://developer.android.com/reference/androidx/media3/common/audio/ChannelMixingMatrix
        val mono = (left + right) * 0.5f
        outLeft = mono
        outRight = mono
    }
}

/** Mid/side width: M = (L + R) / 2, S = (L − R) / 2, L' = M + w·S, R' = M − w·S. */
class WidthStage : KlangStage() {
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
class WarmthStage : KlangStage() {
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

/**
 * Ordo's own effects in the order the research chose: Mono → crossfeed → width → warmth → reverb, with the highs
 * right after mono, so its cut detector hears the file before any other stage adds overtones.
 */
class KlangProcessors(dither: Boolean = true) {
    val mono = MonoStage()
    val highs = HighsStage()
    val crossfeed = CrossfeedStage()
    val width = WidthStage()
    val warmth = WarmthStage()
    val reverb = ReverbStage()
    val processor = KlangAudioProcessor(arrayOf(mono, highs, crossfeed, width, warmth, reverb), dither)
    // Armed by what the chain actually did with the buffer before, so a stage fading out is still caught;
    // untouched music stays bit-perfect.
    val truePeak = TruePeakLimiter { processor.processing }

    val chain: Array<AudioProcessor> get() = arrayOf(processor, truePeak)

    /**
     * Crossfeed only makes sense in headphones; crossfeed and width have nothing left to work on after the mono mix.
     */
    /** Loudness levelling to the chosen R 128 s2 target, before the true-peak limiter. */
    fun setLoudness(settings: AudioSettings, info: ReplayGainInfo?) {
        truePeak.gainDb = LoudnessTargets.gainDb(settings, info)
    }

    fun apply(settings: AudioSettings, headphones: Boolean) {
        mono.effectEnabled = settings.mono
        highs.level = settings.highsLevel
        highs.effectEnabled = settings.highsEnabled
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

class AudioRenderersFactory(
    context: Context,
    private val audioProcessors: Array<AudioProcessor>,
    // Sets the output's mixer per song (rate match, bit-perfect); null keeps Android's mixer as it is.
    private val output: HiResOutput? = null
) : DefaultRenderersFactory(context) {
    init {
        // Platform decoders first, FFmpeg (AC3/E-AC3/DTS/TrueHD) only for what the device cannot decode.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink = output?.let { HiResAudioSink(context, audioProcessors, it) } ?: DefaultAudioSink.Builder(context)
        // PCM processors are the Media3-supported insertion point before AudioTrack playback.
        // https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder
        .setAudioProcessors(audioProcessors)
        .build()
}
