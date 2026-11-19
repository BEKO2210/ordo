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
 * envelope; nothing is allocated per sample.
 */
internal class Compressor {
    private var params = VideoSoundMath.NIGHT
    private var attack = 0f
    private var release = 0f
    private var envelope = 0f
    private var gain = 1f
    private var countdown = 0

    fun configure(params: VideoSoundMath.CompressorParams, sampleRate: Int) {
        this.params = params
        attack = VideoSoundMath.coefficient(params.attackMs, sampleRate)
        release = VideoSoundMath.coefficient(params.releaseMs, sampleRate)
        countdown = 0
    }

    fun clear() {
        envelope = 0f
        countdown = 0
    }

    /** The linear gain for a frame whose detector level (peak, 0..1) is [level]. */
    fun gain(level: Float): Float {
        val coefficient = if (level > envelope) attack else release
        envelope = coefficient * envelope + (1f - coefficient) * level
        if (--countdown <= 0) {
            countdown = GAIN_EVERY
            val levelDb = 20f * log10(max(envelope, SILENCE))
            val gainDb = VideoSoundMath.gainReductionDb(levelDb, params.thresholdDb, params.ratio, params.kneeDb) +
                params.makeUpDb
            gain = 10f.pow(gainDb / 20f)
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
 */
class DialogStage : KlangStage() {
    /** [VideoSoundMath.DIALOG_LIGHT] or [VideoSoundMath.DIALOG_STRONG]; Off is effectEnabled = false. */
    @Volatile
    var level: Int = VideoSoundMath.DIALOG_LIGHT

    private var appliedLevel = -1
    private var appliedRate = 0
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f
    private val compressor = Compressor()

    override fun prepare() {
        val level = level
        if (level == appliedLevel && sampleRate == appliedRate) return
        val params = VideoSoundMath.dialogParams(level) ?: VideoSoundMath.dialogParams(VideoSoundMath.DIALOG_LIGHT)!!
        // shortcut: a level change swaps the filter coefficients between buffers (no glide); a peaking filter in
        // transposed direct form takes that without a crack in listening, ClickFree-style proof is still open.
        val peak = VideoSoundMath.peaking(sampleRate, params.presenceHz, params.presenceQ, params.presenceGainDb)
        b0 = peak.b0
        b1 = peak.b1
        b2 = peak.b2
        a1 = peak.a1
        a2 = peak.a2
        compressor.configure(VideoSoundMath.dialogCompressor(params), sampleRate)
        appliedLevel = level
        appliedRate = sampleRate
    }

    override fun clear() {
        z1 = 0f
        z2 = 0f
        compressor.clear()
        appliedLevel = -1
    }

    override fun processFrame(left: Float, right: Float) {
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
