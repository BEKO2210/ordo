/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.player.KlangStage
import kotlin.math.abs
import kotlin.math.exp

/**
 * The equal-loudness shelves ([EqualLoudness]). The gains glide (one step every [STEP_FRAMES] frames, coefficients
 * recomputed only then), so turning the volume knob never cracks.
 */
class QuietLoudnessStage : KlangStage() {
    @Volatile
    var bassDb: Float = 0f

    @Volatile
    var trebleDb: Float = 0f

    private val low = StereoBiquad()
    private val high = StereoBiquad()
    private var bass = 0f
    private var treble = 0f
    private var targetBass = 0f
    private var targetTreble = 0f
    private var countdown = 0
    private var stepGlide = 1f
    private var snap = true

    override fun onSampleRate(sampleRate: Int) {
        // The chain's glide time constant, applied once per step instead of once per frame.
        stepGlide = (1.0 - exp(-STEP_FRAMES / (sampleRate * GLIDE_SECONDS))).toFloat()
    }

    override fun prepare() {
        targetBass = bassDb
        targetTreble = trebleDb
        if (snap) {
            bass = targetBass
            treble = targetTreble
            design()
            snap = false
        }
    }

    override fun clear() {
        low.clear()
        high.clear()
        countdown = 0
        snap = true
    }

    override fun processFrame(left: Float, right: Float) {
        if (--countdown <= 0) {
            countdown = STEP_FRAMES
            if (bass != targetBass || treble != targetTreble) {
                bass = approach(bass, targetBass)
                treble = approach(treble, targetTreble)
                design()
            }
        }
        outLeft = high.left(low.left(left))
        outRight = high.right(low.right(right))
    }

    private fun approach(value: Float, target: Float): Float {
        val next = value + (target - value) * stepGlide
        return if (abs(next - target) < 0.01f) target else next
    }

    private fun design() {
        Rbj.coefficients(
            Rbj.LOW_SHELF, EqualLoudness.LOW_SHELF_HZ, bass.toDouble(), EqualLoudness.LOW_SHELF_Q, sampleRate, low.c
        )
        Rbj.coefficients(
            Rbj.HIGH_SHELF, EqualLoudness.HIGH_SHELF_HZ, treble.toDouble(), EqualLoudness.HIGH_SHELF_Q, sampleRate,
            high.c
        )
    }

    private companion object {
        const val STEP_FRAMES = 32
    }
}

/**
 * AutoEq's parametric correction for one headphone model: pre-amp and ten biquads. A new model while it plays runs
 * both models side by side for [KlangStage.CROSSFADE_SECONDS] and cross-fades from the old one to the new one, so
 * nothing cracks and the level never dips to the uncorrected sound in between.
 */
class HeadphoneEqStage : KlangStage() {
    @Volatile
    var profile: AutoEqProfile? = null

    private var active: AutoEqProfile? = null
    private var current = Correction.NONE
    private var previous = Correction.NONE
    /** Share of [current]; below 1 while the previous model fades out. */
    private var blend = 1f
    private var blendStep = 1f

    override fun onSampleRate(sampleRate: Int) {
        blendStep = 1f / (sampleRate * CROSSFADE_SECONDS)
        active = null
    }

    override fun prepare() {
        val wanted = profile
        if (active == null) {
            // Switched on (or a new format): straight in, the chain's own fade covers it.
            active = wanted
            current = Correction.of(wanted, sampleRate)
            previous = Correction.NONE
            blend = 1f
        } else if (wanted !== active && blend >= 1f) {
            // Built here, once per change, not per frame.
            active = wanted
            previous = current
            current = Correction.of(wanted, sampleRate)
            blend = 0f
        }
    }

    override fun clear() {
        active = null
    }

    override fun processFrame(left: Float, right: Float) {
        current.process(left, right)
        if (blend >= 1f) {
            outLeft = current.left
            outRight = current.right
            return
        }
        previous.process(left, right)
        blend = minOf(1f, blend + blendStep)
        outLeft = previous.left + (current.left - previous.left) * blend
        outRight = previous.right + (current.right - previous.right) * blend
        if (blend >= 1f) previous = Correction.NONE
    }

    private class Correction(private val gain: Float, private val filters: Array<StereoBiquad>) {
        var left = 0f
            private set
        var right = 0f
            private set

        fun process(inLeft: Float, inRight: Float) {
            var l = inLeft * gain
            var r = inRight * gain
            for (filter in filters) {
                l = filter.left(l)
                r = filter.right(r)
            }
            left = l
            right = r
        }

        companion object {
            val NONE get() = Correction(1f, emptyArray())

            fun of(profile: AutoEqProfile?, sampleRate: Int): Correction {
                if (profile == null) return NONE
                return Correction(dbToGain(profile.preampDb), Array(profile.types.size) { index ->
                    StereoBiquad().also {
                        Rbj.coefficients(
                            profile.types[index], profile.frequenciesHz[index].toDouble(),
                            profile.gainsDb[index].toDouble(), profile.qs[index].toDouble(), sampleRate, it.c
                        )
                    }
                })
            }
        }
    }
}

/**
 * The headphone pack's two stages for one Klang chain (music and video each have one), and how the settings reach
 * them. Virtual surround is not here: it works on the 5.1/7.1 channels before the downmix (DownmixProcessor).
 */
class HeadphoneStages {
    val quiet = QuietLoudnessStage()
    val correction = HeadphoneEqStage()

    /**
     * [attenuationDb]: the system media volume in dB below full ([HeadphoneOutput.attenuationDb]). The correction only belongs to
     * the headphones it was measured for, so it is off on the speaker; the loudness lift works on both.
     */
    fun apply(settings: AudioSettings, headphones: Boolean, attenuationDb: Float) {
        val gains = EqualLoudness.shelfGainsDb(
            EqualLoudness.listeningPhon(attenuationDb, settings.quietCalibrationDb)
        )
        quiet.bassDb = gains[0].toFloat()
        quiet.trebleDb = gains[1].toFloat()
        // Off at the reference level, not just flat: then the chain copies the music bit for bit.
        quiet.effectEnabled = settings.quietFullEnabled && (gains[0] > 0.0 || gains[1] > 0.0)
        val profile = if (headphones && settings.headphoneEq.isNotEmpty()) {
            AutoEqPresets.find(settings.headphoneEq)
        } else null
        if (profile != null) correction.profile = profile
        correction.effectEnabled = profile != null
    }
}

internal fun dbToGain(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
