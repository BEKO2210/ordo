/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import me.zhanghai.android.files.player.KlangStage
import me.zhanghai.android.files.player.LoudnessMeter
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

/** One biquad in transposed direct form II, in floats. */
internal class BiquadFilter(private val c: VideoSoundMath.Biquad) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
        return y
    }

    fun clear() {
        z1 = 0f
        z2 = 0f
    }
}

/** BS.1770 K-weighting (the two stages of [LoudnessMeter], for any sample rate). */
internal class KWeighting(sampleRate: Int) {
    private val shelf = BiquadFilter(VideoSoundMath.Biquad(LoudnessMeter.shelfCoefficients(sampleRate)))
    private val highPass = BiquadFilter(VideoSoundMath.Biquad(LoudnessMeter.highPassCoefficients(sampleRate)))

    fun process(x: Float): Float = highPass.process(shelf.process(x))

    fun clear() {
        shelf.clear()
        highPass.clear()
    }
}

/**
 * The dialog-to-rest loudness distance and the gains that steer it to a target in LU. Both parts are K-weighted and
 * averaged over [WINDOW_SECONDS] (BS.1770 short-term); every buffer the missing distance ([VideoSoundMath.dialogBoostDb])
 * is split, half up for dialog and half down for the rest, and the applied gains glide there with [GLIDE_SECONDS].
 * Gains move only while dialog is heard; in silence they hold, so a pause does not pump the music.
 */
internal class DialogSteering {
    /** Target in LU; NaN = off (the gains glide back to 0 dB). */
    @Volatile
    var targetLu = Float.NaN

    /** Tests freeze the gains to measure stems with the reached setting. */
    @Volatile
    var hold = false

    /** Last measured distance before the gains, LU (NaN while no dialog is heard); published after each update. */
    @Volatile
    var distanceLu = Float.NaN
        private set

    /** The distance reached with the gains of the same update (one consistent pair for the main thread). */
    @Volatile
    var reachedLu = Float.NaN
        private set

    /** A new film: forget the measured energy, the distance and the gains (set on main, done on the audio thread). */
    @Volatile
    var resetRequested = false

    var dialogDb = 0f
        private set
    var restDb = 0f
        private set

    private var dialogSquare = 0.0
    private var restSquare = 0.0
    private var average = 0.0
    private var sampleRate = 48_000

    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate
        average = 1.0 - kotlin.math.exp(-1.0 / (sampleRate * WINDOW_SECONDS))
    }

    fun clear() {
        dialogSquare = 0.0
        restSquare = 0.0
    }

    /** One frame of K-weighted dialog and rest energy (sums over the output channels). */
    fun measure(dialogEnergy: Float, restEnergy: Float) {
        dialogSquare += (dialogEnergy - dialogSquare) * average
        restSquare += (restEnergy - restSquare) * average
    }

    /** After a buffer of [frames]: new distance, new gains. */
    fun update(frames: Int) {
        if (resetRequested) {
            resetRequested = false
            clear()
            dialogDb = 0f
            restDb = 0f
            distanceLu = Float.NaN
            reachedLu = Float.NaN
        }
        if (hold) return
        val target = targetLu
        distanceLu = VideoSoundMath.distanceLu(dialogSquare, restSquare)
        val boost = if (target.isNaN()) 0f else VideoSoundMath.dialogBoostDb(distanceLu, target)
        if (!target.isNaN() && distanceLu.isNaN()) {
            reachedLu = Float.NaN
            return
        }
        val step = (1.0 - kotlin.math.exp(-frames.toDouble() / (sampleRate * GLIDE_SECONDS))).toFloat()
        dialogDb += (boost / 2 - dialogDb) * step
        restDb += (-boost / 2 - restDb) * step
        if (abs(dialogDb) < 0.001f && boost == 0f) dialogDb = 0f
        if (abs(restDb) < 0.001f && boost == 0f) restDb = 0f
        reachedLu = distanceLu + dialogDb - restDb
    }

    companion object {
        const val WINDOW_SECONDS = 3.0
        const val GLIDE_SECONDS = 0.5
    }
}

/**
 * "Dialog-Verstärker" for stereo (and 5.1 the system plays itself): a loudness distance in LU instead of "+x dB". Voices
 * sit in the middle (mid = (L + R) / 2) and in the speech band; the mid is split with a band-pass applied twice
 * ([VideoSoundMath.speechBand], [VideoSoundMath.SPEECH_LOW_HZ]–[VideoSoundMath.SPEECH_HIGH_HZ], 0 dB and no phase
 * shift at its centre) into dialog and the rest of the mid (mid minus dialog), so the two add up to the mid exactly;
 * twice, because bass in the middle leaked into a single band at about -15 dB and was then lifted with the voice. Dialog vs. rest (mid outside the band plus the side) is measured K-weighted and
 * [DialogSteering] moves them apart to the target. A heuristic, no speech model: music in the middle and the speech
 * band counts as dialog. With 5.1 the downmix steers the real centre channel instead.
 */
class DialogStage : KlangStage() {
    /** [VideoSoundMath.DIALOG_LIGHT] (10 LU) or [VideoSoundMath.DIALOG_STRONG] (15 LU); Off is effectEnabled = false. */
    @Volatile
    var level: Int = VideoSoundMath.DIALOG_LIGHT

    internal val steering = DialogSteering()
    private var band = BiquadFilter(VideoSoundMath.speechBand(48_000))
    private var band2 = BiquadFilter(VideoSoundMath.speechBand(48_000))
    private var kDialog = KWeighting(48_000)
    private var kRest = KWeighting(48_000)
    private var kSide = KWeighting(48_000)
    private var dialogGain = 1f
    private var restGain = 1f
    private var dialogTarget = 1f
    private var restTarget = 1f
    private var frames = 0

    /** What a sheet or the signal path shows: measured distance and the distance reached, in LU. */
    val distanceLu: Float get() = steering.distanceLu
    val reachedLu: Float get() = steering.reachedLu

    override fun onSampleRate(sampleRate: Int) {
        band = BiquadFilter(VideoSoundMath.speechBand(sampleRate))
        band2 = BiquadFilter(VideoSoundMath.speechBand(sampleRate))
        kDialog = KWeighting(sampleRate)
        kRest = KWeighting(sampleRate)
        kSide = KWeighting(sampleRate)
        steering.configure(sampleRate)
    }

    override fun prepare() {
        steering.targetLu = VideoSoundMath.dialogTargetLu(level) ?: Float.NaN
        steering.update(frames)
        frames = 0
        dialogTarget = 10f.pow(steering.dialogDb / 20f)
        restTarget = 10f.pow(steering.restDb / 20f)
    }

    override fun clear() {
        band.clear()
        band2.clear()
        kDialog.clear()
        kRest.clear()
        kSide.clear()
        // The measured distance and the gains stay over a seek: they describe the film, not the last buffer.
        frames = 0
    }

    override fun processFrame(left: Float, right: Float) {
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        val dialog = band2.process(band.process(mid))
        val restMid = mid - dialog
        // Energy of each part in both output channels: mid and side each appear in L and R.
        val d = kDialog.process(dialog)
        val r = kRest.process(restMid)
        val s = kSide.process(side)
        steering.measure(2 * d * d, 2 * (r * r + s * s))
        frames++
        // Between buffers the gains move in small per-frame steps, never in one jump.
        dialogGain += (dialogTarget - dialogGain) * GAIN_STEP
        restGain += (restTarget - restGain) * GAIN_STEP
        val outMid = dialog * dialogGain + restMid * restGain
        val outSide = side * restGain
        outLeft = outMid + outSide
        outRight = outMid - outSide
    }

    private companion object {
        const val GAIN_STEP = 0.002f
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
