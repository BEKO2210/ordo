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
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
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
        virtualizerStrength = virtualizerStrength.coerceIn(0, 1_000),
        normalizationPreampDb = normalizationPreampDb.takeIf { it.isFinite() }
            ?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: 0f,
        speed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
        balance = balance.coerceIn(-100, 100)
    )

    companion object {
        const val BAND_COUNT = 10
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        val BAND_FREQUENCIES_HZ = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    }
}

enum class AudioFocusBehavior { PAUSE, DUCK }

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
    CUSTOM(gains(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    companion object {
        private fun gains(vararg values: Int) = values.map(Int::toFloat)
    }
}

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
            .put("virtualizerOn", virtualizerEnabled)
            .put("virtualizer", virtualizerStrength)
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
                virtualizerEnabled = json.optBoolean("virtualizerOn", false),
                virtualizerStrength = json.optInt("virtualizer", 0),
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
