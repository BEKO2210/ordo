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
import androidx.media3.common.C
import kotlin.math.abs
import kotlin.math.ln

/** Owns effects for exactly one ExoPlayer audio session and releases them before that session changes. */
class SoundEffectController {
    // Kept as Any so API 24-27 never resolve the API 28 DynamicsProcessing type while loading this class.
    private var dynamicsProcessing: Any? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var currentSettings = AudioSettings()
    private var replayGain: ReplayGainInfo? = null

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
        runCatching {
            bassBoost?.apply {
                setStrength(currentSettings.bassBoostStrength.toShort())
                enabled = currentSettings.bassBoostEnabled
            }
        }.onFailure { releaseBassBoost() }
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

    @android.annotation.TargetApi(28)
    private fun applyDynamicsProcessing(inputGainDb: Float): Boolean {
        val effect = dynamicsProcessing as? DynamicsProcessing ?: return false
        // EqBand cutoffs are upper boundaries rather than the labels' centre frequencies.
        // https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing.EqBand
        currentSettings.bandGainsDb.forEachIndexed { index, gain ->
            effect.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(
                    currentSettings.equalizerEnabled,
                    DYNAMICS_CUTOFF_FREQUENCIES_HZ[index],
                    gain
                )
            )
        }
        // Clarity: the multiband compressor evens out loud and quiet parts per band; the limiter after it keeps the
        // make-up gain from clipping. Disabled bands pass through untouched.
        if (status.clarityAvailable) {
            val (ratio, thresholdDb, makeUpDb) = CLARITY_LEVELS[currentSettings.clarityLevel - 1]
            CLARITY_CUTOFFS_HZ.forEachIndexed { index, cutoff ->
                effect.setMbcBandAllChannelsTo(
                    index,
                    DynamicsProcessing.MbcBand(
                        currentSettings.clarityEnabled, cutoff, CLARITY_ATTACK_MS, CLARITY_RELEASE_MS, ratio,
                        thresholdDb, CLARITY_KNEE_DB, NOISE_GATE_OFF_DB, 1f, 0f, makeUpDb
                    )
                )
            }
        }
        val channelCount = effect.channelCount
        for (channel in 0 until channelCount) {
            val balanceAttenuation = when {
                channel == 0 && currentSettings.balance > 0 -> -60f * currentSettings.balance / 100f
                channel == 1 && currentSettings.balance < 0 -> 60f * currentSettings.balance / 100f
                else -> 0f
            }
            // https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing#setInputGainbyChannel(int,%20float)
            effect.setInputGainbyChannel(channel, inputGainDb + balanceAttenuation)
        }
        effect.enabled = currentSettings.equalizerEnabled || inputGainDb != 0f || currentSettings.balance != 0 ||
            (status.clarityAvailable && currentSettings.clarityEnabled)
        return true
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
            val limiter = DynamicsProcessing.Limiter(
                true, true, 0, 1f, 60f, 20f, -0.1f, 0f
            )
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
                if (effect.strengthSupported) effect else {
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
