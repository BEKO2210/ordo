/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import kotlin.math.log10
import org.json.JSONArray
import org.json.JSONObject
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

data class AudioSettings(
    val equalizerEnabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    val bandGainsDb: List<Float> = EqualizerPreset.FLAT.gainsDb,
    val preGainDb: Float = 0f,
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0,
    // Ordo's own headphone crossfeed replaces the platform Virtualizer (deprecated since API 35); levels 1..3.
    val crossfeedEnabled: Boolean = false,
    val crossfeedLevel: Int = 2,
    /** Percent: 0 = mono, 100 = the original, 150 = wide. */
    val widthEnabled: Boolean = false,
    val widthPercent: Int = 130,
    val reverbEnabled: Boolean = false,
    val reverbPreset: ReverbPreset = ReverbPreset.STUDIO,
    val reverbMix: Int = 25,
    val warmthEnabled: Boolean = false,
    val warmthLevel: Int = 2,
    val clarityEnabled: Boolean = false,
    val clarityLevel: Int = 2,
    val normalizationEnabled: Boolean = false,
    val normalizationPreampDb: Float = 0f,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val mono: Boolean = false,
    val balance: Int = 0,
    val resumeOnHeadphones: Boolean = false,
    val focusBehavior: AudioFocusBehavior = AudioFocusBehavior.DUCK
) {
    fun sanitized(): AudioSettings = copy(
        bandGainsDb = bandGainsDb.take(BAND_COUNT).map {
            if (it.isFinite()) it.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) else 0f
        }
            .let { it + List(BAND_COUNT - it.size) { 0f } },
        preGainDb = preGainDb.takeIf { it.isFinite() }?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: 0f,
        bassBoostStrength = bassBoostStrength.coerceIn(0, 1_000),
        crossfeedLevel = crossfeedLevel.coerceIn(1, LEVELS),
        widthPercent = widthPercent.coerceIn(0, MAX_WIDTH_PERCENT),
        reverbMix = reverbMix.coerceIn(0, 100),
        warmthLevel = warmthLevel.coerceIn(1, LEVELS),
        clarityLevel = clarityLevel.coerceIn(1, LEVELS),
        normalizationPreampDb = normalizationPreampDb.takeIf { it.isFinite() }
            ?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: 0f,
        speed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
        balance = balance.coerceIn(-100, 100)
    )

    companion object {
        const val BAND_COUNT = 10
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        /** Crossfeed, warmth and clarity come in three steps: light, medium, strong. */
        const val LEVELS = 3
        const val MAX_WIDTH_PERCENT = 150
        val BAND_FREQUENCIES_HZ = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    }
}

enum class AudioFocusBehavior { PAUSE, DUCK }

/** Freeverb room size and damping (0..1 each, the original's user scale); bigger rooms ring longer and darker. */
enum class ReverbPreset(val roomSize: Float, val damping: Float) {
    ROOM(0.35f, 0.7f),
    STUDIO(0.55f, 0.5f),
    CLUB(0.72f, 0.35f),
    HALL(0.9f, 0.25f)
}

enum class EqualizerPreset(val gainsDb: List<Float>) {
    FLAT(gains(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
    BASS(gains(7, 6, 5, 3, 1, 0, 0, -1, -1, -1)),
    TREBLE(gains(-2, -2, -1, 0, 0, 1, 3, 5, 6, 7)),
    VOCAL(gains(-3, -2, -1, 1, 3, 5, 5, 3, 1, -1)),
    ROCK(gains(5, 4, 2, 0, -2, -1, 2, 4, 5, 5)),
    POP(gains(-1, 1, 3, 4, 3, 0, -1, -1, 1, 2)),
    JAZZ(gains(4, 3, 1, 2, -1, -1, 1, 3, 4, 5)),
    CLASSICAL(gains(5, 4, 3, 1, -1, -1, 0, 2, 4, 5)),
    LOUDNESS_AT_NIGHT(gains(-4, -3, -2, 0, 2, 3, 3, 1, -1, -3)),
    CUSTOM(gains(0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
}

// Top level, not in the companion: enum entries are built before their companion object exists.
private fun gains(vararg values: Int) = values.map(Int::toFloat)

/** Pure codec kept separate from SharedPreferences so corrupt or newer settings degrade to safe defaults. */
object AudioSettingsCodec {
    fun encode(value: AudioSettings): String = with(value.sanitized()) {
        JSONObject()
            .put("eq", equalizerEnabled)
            .put("preset", preset.name)
            .put("bands", JSONArray(bandGainsDb))
            .put("preGain", preGainDb.toDouble())
            .put("bassOn", bassBoostEnabled)
            .put("bass", bassBoostStrength)
            .put("crossfeedOn", crossfeedEnabled)
            .put("crossfeed", crossfeedLevel)
            .put("widthOn", widthEnabled)
            .put("width", widthPercent)
            .put("reverbOn", reverbEnabled)
            .put("reverbPreset", reverbPreset.name)
            .put("reverbMix", reverbMix)
            .put("warmthOn", warmthEnabled)
            .put("warmth", warmthLevel)
            .put("clarityOn", clarityEnabled)
            .put("clarity", clarityLevel)
            .put("normalization", normalizationEnabled)
            .put("normalizationPreamp", normalizationPreampDb.toDouble())
            .put("speed", speed.toDouble())
            .put("skipSilence", skipSilence)
            .put("mono", mono)
            .put("balance", balance)
            .put("resumeHeadphones", resumeOnHeadphones)
            .put("focus", focusBehavior.name)
            .toString()
    }

    fun decode(encoded: String?): AudioSettings {
        if (encoded.isNullOrBlank()) return AudioSettings()
        return try {
            val json = JSONObject(encoded)
            val preset = enumValueOrDefault(json.optString("preset"), EqualizerPreset.FLAT)
            val bandsJson = json.optJSONArray("bands")
            val bands = if (bandsJson == null) preset.gainsDb else buildList {
                for (index in 0 until bandsJson.length()) add(bandsJson.optDouble(index, 0.0).toFloat())
            }
            AudioSettings(
                equalizerEnabled = json.optBoolean("eq", false),
                preset = preset,
                bandGainsDb = bands,
                preGainDb = json.optDouble("preGain", 0.0).toFloat(),
                bassBoostEnabled = json.optBoolean("bassOn", false),
                bassBoostStrength = json.optInt("bass", 0),
                // Settings from 0.13.x and earlier stored the platform Virtualizer; its switch and strength move
                // over to the crossfeed that replaces it.
                crossfeedEnabled = json.optBoolean("crossfeedOn", json.optBoolean("virtualizerOn", false)),
                crossfeedLevel = if (json.has("crossfeed")) json.optInt("crossfeed", 2)
                else if (json.has("virtualizer")) levelFromStrength(json.optInt("virtualizer", 0)) else 2,
                widthEnabled = json.optBoolean("widthOn", false),
                widthPercent = json.optInt("width", 130),
                reverbEnabled = json.optBoolean("reverbOn", false),
                reverbPreset = enumValueOrDefault(json.optString("reverbPreset"), ReverbPreset.STUDIO),
                reverbMix = json.optInt("reverbMix", 25),
                warmthEnabled = json.optBoolean("warmthOn", false),
                warmthLevel = json.optInt("warmth", 2),
                clarityEnabled = json.optBoolean("clarityOn", false),
                clarityLevel = json.optInt("clarity", 2),
                normalizationEnabled = json.optBoolean("normalization", false),
                normalizationPreampDb = json.optDouble("normalizationPreamp", 0.0).toFloat(),
                speed = json.optDouble("speed", 1.0).toFloat(),
                skipSilence = json.optBoolean("skipSilence", false),
                mono = json.optBoolean("mono", false),
                balance = json.optInt("balance", 0),
                resumeOnHeadphones = json.optBoolean("resumeHeadphones", false),
                focusBehavior = enumValueOrDefault(json.optString("focus"), AudioFocusBehavior.DUCK)
            ).sanitized()
        } catch (_: Exception) {
            AudioSettings()
        }
    }

    /** Virtualizer strength 0..1000 in thirds: 0-333 light, 334-667 medium, 668-1000 strong. */
    internal fun levelFromStrength(strength: Int): Int =
        (strength.coerceIn(0, 1_000) * AudioSettings.LEVELS / 1_001 + 1)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

object AudioSettingsStore {
    fun read(): AudioSettings = AudioSettingsCodec.decode(Settings.AUDIO_PLAYER_SOUND_SETTINGS.valueCompat)

    fun write(value: AudioSettings) {
        Settings.AUDIO_PLAYER_SOUND_SETTINGS.putValue(AudioSettingsCodec.encode(value))
    }
}

data class ReplayGainInfo(val gainDb: Float, val peak: Float? = null)

object ReplayGain {
    /** Limits ReplayGain plus its user pre-amp so a known peak never exceeds 0 dBFS. */
    fun effectiveGainDb(trackGainDb: Float, preampDb: Float, peak: Float?): Float {
        val requested = trackGainDb + preampDb
        if (peak == null || !peak.isFinite() || peak <= 0f) return requested
        val headroomDb = (-20.0 * log10(peak.toDouble())).toFloat()
        return minOf(requested, headroomDb)
    }

    fun parseDb(value: String?): Float? = value?.trim()
        ?.let { if (it.endsWith("dB", ignoreCase = true)) it.dropLast(2).trim() else it }
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() }

    fun parsePeak(value: String?): Float? = value?.trim()?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it > 0f }

    /** R128_TRACK_GAIN and R128_ALBUM_GAIN store signed Q7.8 dB values.
     * https://datatracker.ietf.org/doc/html/rfc7845#section-5.2.1
     */
    fun parseR128(value: String?): Float? = value?.trim()?.toIntOrNull()?.div(256f)
}
