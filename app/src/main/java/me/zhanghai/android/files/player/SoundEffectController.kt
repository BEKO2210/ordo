/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import kotlin.math.abs
import kotlin.math.ln

/**
 * Owns effects for exactly one ExoPlayer audio session and releases them before that session changes.
 *
 * Nothing jumps, because a jump in a gain is an audible crack: equalizer bands, input gain (pre-gain, levelling,
 * balance), clarity and bass strength glide towards their targets, at most [DB_MAX_STEP] per [GLIDE_TICK_MS].
 * Switching DynamicsProcessing or BassBoost on or off is audible (DynamicsProcessing changes its latency, it works in
 * FFT blocks, and cuts over to a plain copy), so [EffectGate] decides when: never while sound is heard; released in
 * silence once everything is neutral, so "everything off" is bit-perfect; switched on before music plays again, or
 * inside [duck] (a short fade of the player volume) while it plays. docs/AUDIO-QUALITAET-RECHERCHE.md, 1.2 and 1.3.
 */
class SoundEffectController(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val duck: (() -> Unit) -> Unit = { it() }
) {
    // Kept as Any so API 24-27 never resolve the API 28 DynamicsProcessing type while loading this class.
    private var dynamicsProcessing: Any? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var currentSettings = AudioSettings()
    private var replayGain: ReplayGainInfo? = null
    // DynamicsProcessing values as applied and as wanted: ten bands, input gain left and right, clarity amount 0..1.
    private val dynamicsApplied = FloatArray(DYNAMICS_VALUES)
    private val dynamicsTarget = FloatArray(DYNAMICS_VALUES)
    private var bassApplied = 0
    private var bassTarget = 0
    private val glideTick = Runnable { glide() }
    private val gate = EffectGate()
    /** Whether sound is being heard right now; set by the service from the player. */
    private var audible = false
    /** System media volume as a fraction of its maximum, for the equal-loudness lift. */
    private var volumeFraction = 1f
    private var limiterThreshold = LIMITER_DB

    var status = Status()
        private set

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        if (Build.VERSION.SDK_INT >= 28) createDynamicsProcessing(audioSessionId)
        else createLegacyEqualizer(audioSessionId)
        createOptionalEffects(audioSessionId)
        apply(currentSettings, replayGain)
    }

    fun apply(settings: AudioSettings, replayGain: ReplayGainInfo? = this.replayGain) {
        currentSettings = settings.sanitized()
        this.replayGain = replayGain
        runCatching { applyEqualizer() }.onFailure { disableEqualizer() }
        bassTarget = if (currentSettings.bassBoostEnabled) currentSettings.bassBoostStrength else 0
        when (gate.onSettings(neutral(), audible)) {
            EffectGate.Action.ENABLE -> setPlatformEffectsEnabled(true)
            EffectGate.Action.ENABLE_IN_FADE -> duck { setPlatformEffectsEnabled(true) }
            else -> Unit
        }
        handler.removeCallbacks(glideTick)
        glide()
    }

    /** The service reports the system media volume; with "full sound when quiet" the outer bands follow it. */
    fun setVolumeFraction(value: Float) {
        if (value == volumeFraction) return
        volumeFraction = value
        if (currentSettings.quietFullEnabled) apply(currentSettings)
    }

    /** The service reports whether sound is heard; in silence a neutral setting releases the effects. */
    fun setAudible(value: Boolean) {
        audible = value
        if (!value) releaseIfSilentAndNeutral()
    }

    private fun neutral(): Boolean = dynamicsTarget.all { it == 0f } && bassTarget == 0

    private fun releaseIfSilentAndNeutral() {
        val settled = dynamicsApplied.all { it == 0f } && bassApplied == 0
        if (gate.onSilent(neutral(), settled) == EffectGate.Action.DISABLE) setPlatformEffectsEnabled(false)
    }

    private fun setPlatformEffectsEnabled(enabled: Boolean) {
        runCatching { (dynamicsProcessing as? AudioEffect)?.enabled = enabled }
        runCatching { bassBoost?.enabled = enabled }
    }

    /** One glide step towards the targets; schedules the next one until everything has arrived. */
    private fun glide() {
        var moving = false
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { moving = glideDynamics() }.onFailure { disableEqualizer() }
        }
        runCatching {
            bassBoost?.let { effect ->
                val next = moveToward(
                    bassApplied.toFloat(), bassTarget.toFloat(), BASS_MIN_STEP, GLIDE_FRACTION, BASS_MAX_STEP
                ).toInt()
                if (next != bassApplied) {
                    bassApplied = next
                    effect.setStrength(next.toShort())
                }
                if (bassApplied != bassTarget) moving = true
            }
        }.onFailure { releaseBassBoost() }
        if (moving) handler.postDelayed(glideTick, GLIDE_TICK_MS)
        else if (!audible) releaseIfSilentAndNeutral()
    }

    @android.annotation.TargetApi(28)
    private fun glideDynamics(): Boolean {
        val effect = dynamicsProcessing as? DynamicsProcessing ?: return false
        var moving = false
        for (index in 0 until DYNAMICS_VALUES) {
            val isClarity = index == CLARITY_INDEX
            val next = if (isClarity) {
                moveToward(dynamicsApplied[index], dynamicsTarget[index], CLARITY_MIN_STEP, GLIDE_FRACTION, 1f)
            } else {
                moveToward(dynamicsApplied[index], dynamicsTarget[index], DB_MIN_STEP, GLIDE_FRACTION, DB_MAX_STEP)
            }
            if (next == dynamicsApplied[index]) continue
            dynamicsApplied[index] = next
            when {
                index < AudioSettings.BAND_COUNT -> effect.setPreEqBandAllChannelsTo(
                    // EqBand cutoffs are upper boundaries rather than the labels' centre frequencies.
                    // https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing.EqBand
                    index, DynamicsProcessing.EqBand(true, DYNAMICS_CUTOFF_FREQUENCIES_HZ[index], next)
                )
                isClarity -> applyClarity(effect, next)
                // https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing#setInputGainbyChannel(int,%20float)
                else -> {
                    val channel = index - AudioSettings.BAND_COUNT
                    if (channel < effect.channelCount) effect.setInputGainbyChannel(channel, next)
                }
            }
            if (next != dynamicsTarget[index]) moving = true
        }
        return moving
    }

    /** Clarity at [amount] 0..1: from a neutral compressor (ratio 1, no threshold, no make-up) to the chosen level. */
    @android.annotation.TargetApi(28)
    private fun applyClarity(effect: DynamicsProcessing, amount: Float) {
        if (!status.clarityAvailable) return
        val (ratio, thresholdDb, makeUpDb) = CLARITY_LEVELS[currentSettings.clarityLevel - 1]
        // The multiband compressor evens out loud and quiet parts per band; the limiter after it keeps the make-up
        // gain from clipping.
        CLARITY_CUTOFFS_HZ.forEachIndexed { band, cutoff ->
            effect.setMbcBandAllChannelsTo(
                band,
                DynamicsProcessing.MbcBand(
                    amount > 0f, cutoff, CLARITY_ATTACK_MS, CLARITY_RELEASE_MS, 1f + (ratio - 1f) * amount,
                    thresholdDb * amount, CLARITY_KNEE_DB, NOISE_GATE_OFF_DB, 1f, 0f, makeUpDb * amount
                )
            )
        }
    }

    private fun applyEqualizer() {
        val normalizationDb = if (currentSettings.normalizationEnabled && replayGain != null) {
            ReplayGain.effectiveGainDb(
                replayGain!!.gainDb, currentSettings.normalizationPreampDb, replayGain!!.peak
            )
        } else 0f
        val inputGainDb = currentSettings.preGainDb + normalizationDb
        if (Build.VERSION.SDK_INT >= 28 && applyDynamicsProcessing(inputGainDb)) return
        equalizer?.let { effect ->
            val range = effect.bandLevelRange
            for (band in 0 until effect.numberOfBands.toInt()) {
                val centerHz = effect.getCenterFreq(band.toShort()) / 1_000f
                val sourceIndex = nearestLogicalBand(centerHz)
                val millibels = (currentSettings.bandGainsDb[sourceIndex] * 100).toInt()
                    .coerceIn(range[0].toInt(), range[1].toInt())
                effect.setBandLevel(band.toShort(), millibels.toShort())
            }
            effect.enabled = currentSettings.equalizerEnabled
        }
        // Equalizer has no input-gain stage. LoudnessEnhancer is the platform session effect that
        // accepts millibel gain and safely compresses out-of-range samples on API 24-27.
        // https://developer.android.com/reference/android/media/audiofx/LoudnessEnhancer
        loudnessEnhancer?.apply {
            setTargetGain((inputGainDb * 100).toInt())
            enabled = inputGainDb != 0f
        }
    }

    /** Sets the targets; [glide] moves the effect there. */
    private fun applyDynamicsProcessing(inputGainDb: Float): Boolean {
        if (dynamicsProcessing == null) return false
        // "Full sound when quiet" adds its lift on top of the equalizer (or alone, with the equalizer off).
        val lift = if (currentSettings.quietFullEnabled) LoudnessCompensation.liftDb(volumeFraction) else null
        currentSettings.bandGainsDb.forEachIndexed { index, gain ->
            val eq = if (currentSettings.equalizerEnabled) gain else 0f
            dynamicsTarget[index] = (eq + (lift?.get(index) ?: 0f)).coerceAtMost(AudioSettings.MAX_GAIN_DB)
        }
        for (channel in 0 until 2) {
            val balanceAttenuation = when {
                channel == 0 && currentSettings.balance > 0 -> -60f * currentSettings.balance / 100f
                channel == 1 && currentSettings.balance < 0 -> 60f * currentSettings.balance / 100f
                else -> 0f
            }
            dynamicsTarget[AudioSettings.BAND_COUNT + channel] = inputGainDb + balanceAttenuation
        }
        dynamicsTarget[CLARITY_INDEX] = if (status.clarityAvailable && currentSettings.clarityEnabled) 1f else 0f
        if (Build.VERSION.SDK_INT >= 28) applyLimiter()
        return true
    }

    /**
     * Whenever something can push the level up (volume levelling, a boosted equalizer band, pre-gain, bass boost), the
     * limiter catches at -1 dB instead of -0.1 dB: EBU R 128 allows at most -1 dBTP, and the sample-peak limiter needs
     * that headroom for the peaks between samples (docs/AUDIO-QUALITAET-RECHERCHE.md, 5). Untouched music keeps -0.1 dB.
     */
    @android.annotation.TargetApi(28)
    private fun applyLimiter() {
        val effect = dynamicsProcessing as? DynamicsProcessing ?: return
        val boosted = currentSettings.normalizationEnabled || currentSettings.bassBoostEnabled ||
            (currentSettings.quietFullEnabled && LoudnessCompensation.amount(volumeFraction) > 0f) ||
            currentSettings.preGainDb > 0f ||
            (currentSettings.equalizerEnabled && currentSettings.bandGainsDb.any { it > 0f })
        val threshold = if (boosted) LIMITER_LEVELLING_DB else LIMITER_DB
        if (threshold == limiterThreshold) return
        limiterThreshold = threshold
        effect.setLimiterAllChannelsTo(limiter(threshold))
    }

    private fun nearestLogicalBand(frequencyHz: Float): Int =
        AudioSettings.BAND_FREQUENCIES_HZ.indices.minByOrNull { index ->
            abs(ln(AudioSettings.BAND_FREQUENCIES_HZ[index].toFloat()) - ln(frequencyHz))
        } ?: 0

    private fun createLegacyEqualizer(audioSessionId: Int) {
        try {
            equalizer = Equalizer(0, audioSessionId)
            status = status.copy(
                equalizerAvailable = true,
                legacyBandCount = equalizer!!.numberOfBands.toInt()
            )
        } catch (_: RuntimeException) {
            disableEqualizer()
            return
        }
        loudnessEnhancer = try {
            LoudnessEnhancer(audioSessionId)
        } catch (_: RuntimeException) {
            null
        }
        status = status.copy(inputGainAvailable = loudnessEnhancer != null)
    }

    @android.annotation.TargetApi(28)
    private fun createDynamicsProcessing(audioSessionId: Int) {
        // With the multiband compressor first; a device that refuses that configuration still gets the equalizer.
        if (!createDynamicsProcessing(audioSessionId, withClarity = true)) {
            createDynamicsProcessing(audioSessionId, withClarity = false)
        }
    }

    @android.annotation.TargetApi(28)
    private fun createDynamicsProcessing(audioSessionId: Int, withClarity: Boolean): Boolean {
        try {
            val eq = DynamicsProcessing.Eq(true, true, AudioSettings.BAND_COUNT).apply {
                DYNAMICS_CUTOFF_FREQUENCIES_HZ.forEachIndexed { index, cutoff ->
                    setBand(index, DynamicsProcessing.EqBand(true, cutoff, 0f))
                }
            }
            val limiter = limiter(LIMITER_DB)
            limiterThreshold = LIMITER_DB
            // Frequency-resolution mode is the platform variant intended for a graphic EQ.
            // https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true,
                AudioSettings.BAND_COUNT,
                withClarity,
                if (withClarity) CLARITY_CUTOFFS_HZ.size else 0,
                false,
                0,
                true
            ).setPreEqAllChannelsTo(eq).setLimiterAllChannelsTo(limiter).apply {
                if (withClarity) {
                    val mbc = DynamicsProcessing.Mbc(true, true, CLARITY_CUTOFFS_HZ.size)
                    CLARITY_CUTOFFS_HZ.forEachIndexed { index, cutoff ->
                        mbc.setBand(index, DynamicsProcessing.MbcBand(false, cutoff, CLARITY_ATTACK_MS,
                            CLARITY_RELEASE_MS, 1f, 0f, CLARITY_KNEE_DB, NOISE_GATE_OFF_DB, 1f, 0f, 0f))
                    }
                    setMbcAllChannelsTo(mbc)
                }
            }.build()
            // Created off with neutral values; EffectGate switches it on when a setting needs it.
            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config)
            status = status.copy(
                equalizerAvailable = true,
                usingDynamicsProcessing = true,
                inputGainAvailable = true,
                clarityAvailable = withClarity
            )
            return true
        } catch (_: RuntimeException) {
            disableEqualizer()
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun createOptionalEffects(audioSessionId: Int) {
        bassBoost = try {
            BassBoost(0, audioSessionId).let { effect ->
                if (effect.strengthSupported) effect.apply {
                    // Off at strength 0; EffectGate switches it together with DynamicsProcessing.
                    setStrength(0)
                } else {
                    effect.release()
                    null
                }
            }
        } catch (_: RuntimeException) {
            null
        }
        status = status.copy(bassBoostAvailable = bassBoost != null)
    }

    private fun disableEqualizer() {
        releaseEffect(dynamicsProcessing as? AudioEffect)
        releaseEffect(equalizer)
        releaseEffect(loudnessEnhancer)
        dynamicsProcessing = null
        equalizer = null
        loudnessEnhancer = null
        status = status.copy(
            equalizerAvailable = false,
            usingDynamicsProcessing = false,
            inputGainAvailable = false,
            legacyBandCount = 0,
            clarityAvailable = false
        )
    }

    private fun releaseBassBoost() {
        releaseEffect(bassBoost)
        bassBoost = null
        status = status.copy(bassBoostAvailable = false)
    }

    fun release() {
        handler.removeCallbacks(glideTick)
        dynamicsApplied.fill(0f)
        bassApplied = 0
        gate.reset()
        releaseEffect(dynamicsProcessing as? AudioEffect)
        releaseEffect(equalizer)
        releaseEffect(loudnessEnhancer)
        releaseEffect(bassBoost)
        dynamicsProcessing = null
        equalizer = null
        loudnessEnhancer = null
        bassBoost = null
        status = Status()
    }

    private fun releaseEffect(effect: AudioEffect?) {
        runCatching { effect?.enabled = false }
        runCatching { effect?.release() }
    }

    data class Status(
        val equalizerAvailable: Boolean = false,
        val usingDynamicsProcessing: Boolean = false,
        val inputGainAvailable: Boolean = false,
        val legacyBandCount: Int = 0,
        val bassBoostAvailable: Boolean = false,
        val clarityAvailable: Boolean = false
    )

    companion object {
        private const val LIMITER_DB = -0.1f
        private const val LIMITER_LEVELLING_DB = -1f

        @android.annotation.TargetApi(28)
        private fun limiter(thresholdDb: Float) = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 20f, thresholdDb, 0f)

        private const val GLIDE_TICK_MS = 10L
        /**
         * Each step covers a third of the way, at least the minimum and at most the maximum: at most 1 dB per 10 ms
         * (the research asks for ≤ 2 dB per 20 ms), so 12 dB take about 130 ms and 0.5 dB a single step.
         */
        private const val GLIDE_FRACTION = 0.3f
        private const val DB_MIN_STEP = 0.25f
        internal const val DB_MAX_STEP = 1f
        private const val CLARITY_MIN_STEP = 0.08f
        private const val BASS_MIN_STEP = 40f
        private const val BASS_MAX_STEP = 100f
        private const val DYNAMICS_VALUES = AudioSettings.BAND_COUNT + 3
        private const val CLARITY_INDEX = AudioSettings.BAND_COUNT + 2

        /**
         * One glide step from [current] to [target]: [fraction] of the way, at least [minStep], at most [maxStep],
         * never past it.
         */
        internal fun moveToward(current: Float, target: Float, minStep: Float, fraction: Float, maxStep: Float): Float {
            val distance = target - current
            val step = (abs(distance) * fraction).coerceIn(minStep, maxStep)
            return if (abs(distance) <= step) target else current + step * kotlin.math.sign(distance)
        }

        // Three bands: lows, mids (voices, most instruments), highs; the top cutoff closes the last band.
        private val CLARITY_CUTOFFS_HZ = floatArrayOf(250f, 4_000f, 22_050f)
        private const val CLARITY_ATTACK_MS = 3f
        private const val CLARITY_RELEASE_MS = 80f
        private const val CLARITY_KNEE_DB = 6f
        private const val NOISE_GATE_OFF_DB = -90f

        /** Ratio, threshold (dB) and make-up gain (dB) for light, medium and strong. */
        private val CLARITY_LEVELS = arrayOf(
            Triple(2f, -18f, 2f),
            Triple(3f, -24f, 4f),
            Triple(4f, -30f, 6f)
        )

        private val DYNAMICS_CUTOFF_FREQUENCIES_HZ = floatArrayOf(
            44f, 88f, 177f, 354f, 707f, 1_414f, 2_828f, 5_657f, 11_314f, 22_050f
        )
    }
}
