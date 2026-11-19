/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import me.zhanghai.android.files.player.KlangStage
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Feed-forward compressor with a peak envelope (attack/release) and the soft-knee gain computer of
 * [VideoSoundMath.gainReductionDb]. The dB maths runs once every [GAIN_EVERY] frames (0.3 ms at 48 kHz) on a smooth
 * envelope; nothing is allocated per sample. Threshold, ratio and make-up are plain fields so a stage can glide them.
 */
internal class Compressor {
    var thresholdDb = 0f
    var ratio = 1f
    var kneeDb = 0f
    var makeUpDb = 0f
    private var attack = 0f
    private var release = 0f
    // Below zero: not started; the first frame's level primes it, so switching on over loud sound starts compressed
    // instead of letting the full make-up through for the attack time.
    private var envelope = -1f
    private var gain = 1f
    private var countdown = 0

    fun configure(params: VideoSoundMath.CompressorParams, sampleRate: Int) {
        thresholdDb = params.thresholdDb
        ratio = params.ratio
        kneeDb = params.kneeDb
        makeUpDb = params.makeUpDb
        attack = VideoSoundMath.coefficient(params.attackMs, sampleRate)
        release = VideoSoundMath.coefficient(params.releaseMs, sampleRate)
        countdown = 0
    }

    fun clear() {
        envelope = -1f
        countdown = 0
    }

    /** The linear gain for a frame whose detector level (peak, 0..1) is [level]. */
    fun gain(level: Float): Float {
        if (envelope < 0f) envelope = level
        val coefficient = if (level > envelope) attack else release
        envelope = coefficient * envelope + (1f - coefficient) * level
        if (--countdown <= 0) {
            countdown = GAIN_EVERY
            val levelDb = 20f * log10(max(envelope, SILENCE))
            gain = 10f.pow((VideoSoundMath.gainReductionDb(levelDb, thresholdDb, ratio, kneeDb) + makeUpDb) / 20f)
        }
        return gain
    }

    private companion object {
        const val GAIN_EVERY = 16
        const val SILENCE = 1e-5f
    }
}

/**
 * "Dialog-Verstärker": voices sit in the centre (mid = (L + R) / 2) and carry their intelligibility at 1–4 kHz. The
 * mid signal gets a gentle compressor and a presence peak (RBJ peaking EQ), the side signal (music, effects, room)
 * stays as it is. With 5.1 sources the downmix also raises the centre channel ([VideoSoundMath.centreGain]).
 *
 * Light ↔ Strong glides: presence gain, threshold, ratio and make-up move towards the new level with a time constant
 * of [RAMP_SECONDS] (one-pole, per frame); the filter is recomputed every [COEFFICIENTS_EVERY] frames on the way.
 */
class DialogStage : KlangStage() {
    /** [VideoSoundMath.DIALOG_LIGHT] or [VideoSoundMath.DIALOG_STRONG]; Off is effectEnabled = false. */
    @Volatile
    var level: Int = VideoSoundMath.DIALOG_LIGHT

    private var target = VideoSoundMath.dialogParams(VideoSoundMath.DIALOG_LIGHT)!!
    private var presenceDb = 0f
    private var snap = true
    private var step = 1f
    private var countdown = 0
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f
    private val compressor = Compressor()

    override fun onSampleRate(sampleRate: Int) {
        step = (1.0 - kotlin.math.exp(-1.0 / (sampleRate * RAMP_SECONDS))).toFloat()
        snap = true
    }

    override fun prepare() {
        target = VideoSoundMath.dialogParams(level) ?: VideoSoundMath.dialogParams(VideoSoundMath.DIALOG_LIGHT)!!
        if (!snap) return
        compressor.configure(VideoSoundMath.dialogCompressor(target), sampleRate)
        presenceDb = target.presenceGainDb
        setFilter()
        snap = false
    }

    override fun clear() {
        z1 = 0f
        z2 = 0f
        compressor.clear()
        snap = true
    }

    private fun setFilter() {
        val peak = VideoSoundMath.peaking(sampleRate, target.presenceHz, target.presenceQ, presenceDb)
        b0 = peak.b0
        b1 = peak.b1
        b2 = peak.b2
        a1 = peak.a1
        a2 = peak.a2
    }

    /** One glide step of every parameter; true while something still moves. */
    private fun glideParameters(): Boolean {
        var moving = false
        fun toward(current: Float, goal: Float): Float {
            if (abs(goal - current) < ARRIVED) return goal
            moving = true
            return current + (goal - current) * step
        }
        presenceDb = toward(presenceDb, target.presenceGainDb)
        compressor.thresholdDb = toward(compressor.thresholdDb, target.thresholdDb)
        compressor.ratio = toward(compressor.ratio, target.ratio)
        compressor.makeUpDb = toward(compressor.makeUpDb, target.makeUpDb)
        return moving
    }

    override fun processFrame(left: Float, right: Float) {
        if (presenceDb != target.presenceGainDb || compressor.makeUpDb != target.makeUpDb ||
            compressor.thresholdDb != target.thresholdDb || compressor.ratio != target.ratio
        ) {
            val moving = glideParameters()
            if (--countdown <= 0 || !moving) {
                countdown = COEFFICIENTS_EVERY
                setFilter()
            }
        }
        // Evened out first, then the presence peak: the compressor sees the level, not the boosted band.
        val mid = (left + right) * 0.5f
        val even = mid * compressor.gain(abs(mid))
        val side = (left - right) * 0.5f
        // Transposed direct form II.
        val voice = b0 * even + z1
        z1 = b1 * even - a1 * voice + z2
        z2 = b2 * even - a2 * voice
        outLeft = voice + side
        outRight = voice - side
    }

    internal val presenceGainDb: Float get() = presenceDb

    companion object {
        internal const val RAMP_SECONDS = 0.03
        private const val COEFFICIENTS_EVERY = 32
        private const val ARRIVED = 0.01f
    }
}

/**
 * "Nachtmodus": one linked compressor on both channels, so explosions are held down and quiet speech is lifted by
 * the make-up gain ([VideoSoundMath.NIGHT]); the chain's limiter catches what is left.
 */
class NightStage : KlangStage() {
    private val compressor = Compressor()
    private var appliedRate = 0

    override fun prepare() {
        if (sampleRate == appliedRate) return
        compressor.configure(VideoSoundMath.NIGHT, sampleRate)
        appliedRate = sampleRate
    }

    override fun clear() {
        compressor.clear()
        appliedRate = 0
    }

    override fun processFrame(left: Float, right: Float) {
        val gain = compressor.gain(max(abs(left), abs(right)))
        outLeft = left * gain
        outRight = right * gain
    }
}
