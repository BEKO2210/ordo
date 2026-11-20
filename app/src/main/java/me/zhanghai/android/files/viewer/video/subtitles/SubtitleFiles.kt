/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

object SubtitleFiles {
    /** Literal values keep this utility independent of Android and Media3 at test time. */
    val MIME_TYPES: Map<String, String> = mapOf(
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "ttml" to "application/ttml+xml",
        "dfxp" to "application/ttml+xml"
    )

    /** A bound avoids loading an accidentally selected video into memory. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** A short fixed list keeps manual recovery predictable across devices. */
    val CHARSETS = listOf(
        "UTF-8",
        "windows-1252",
        "ISO-8859-15",
        "windows-1250",
        "windows-1251",
        "windows-1253",
        "windows-1254"
    )

    data class Sidecar(val fileName: String, val language: String?, val forced: Boolean)

    /** Case folding here makes files copied from case-insensitive systems work consistently. */
    fun mimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return MIME_TYPES[extension.lowercase(Locale.ROOT)]
    }

    /** Requiring a dot boundary prevents similarly prefixed films from sharing subtitles. */
    fun sidecars(videoFileName: String, fileNames: List<String>): List<Sidecar> {
        val videoBase = videoFileName.substringBeforeLast('.', videoFileName)
        return fileNames.mapNotNull { fileName ->
            if (mimeType(fileName) == null) return@mapNotNull null
            val subtitleBase = fileName.substringBeforeLast('.')
            val tags = when {
                subtitleBase.equals(videoBase, ignoreCase = true) -> emptyList()
                subtitleBase.length > videoBase.length &&
                    subtitleBase.regionMatches(0, videoBase, 0, videoBase.length, ignoreCase = true) &&
                    subtitleBase[videoBase.length] == '.' ->
                    subtitleBase.substring(videoBase.length + 1).split('.')
                else -> return@mapNotNull null
            }
            Sidecar(
                fileName,
                tags.firstNotNullOfOrNull(::languageCode),
                tags.any { it.equals("forced", ignoreCase = true) }
            )
        }.sortedWith(
            compareBy<Sidecar> { it.language != null }
                .thenBy { it.language }
                .thenBy { it.fileName }
        )
    }

    /** Normalizing known tags avoids presenting arbitrary release-name fragments as languages. */
    fun languageCode(tag: String): String? {
        val parts = tag.split('-', '_')
        if (parts.size !in 1..2 || parts.any { it.isEmpty() }) return null
        if (parts.size == 2 && (parts[1].length != 2 || !parts[1].all(Char::isLetter))) return null

        val rawLanguage = parts[0]
        if (rawLanguage.lowercase(Locale.ROOT) in NON_LANGUAGE_TAGS) return null
        val language = when {
            rawLanguage.length == 2 && rawLanguage.all(Char::isLetter) ->
                rawLanguage.lowercase(Locale.ROOT).takeIf { it in ISO_LANGUAGES }
            rawLanguage.length == 3 && rawLanguage.all(Char::isLetter) ->
                THREE_LETTER_LANGUAGES[rawLanguage.lowercase(Locale.ROOT)]
            else -> ENGLISH_LANGUAGE_NAMES[rawLanguage.lowercase(Locale.ROOT)]
        } ?: return null
        return if (parts.size == 2) {
            "$language-${parts[1].uppercase(Locale.ROOT)}"
        } else {
            language
        }
    }

    data class Decoded(val text: String, val charset: String)

    /** Strict UTF-8 detection prevents silent replacement from hiding a legacy encoding. */
    fun decode(bytes: ByteArray, override: String?, language: String): Decoded {
        if (override != null) {
            val charset = runCatching { Charset.forName(override) }.getOrElse { StandardCharsets.UTF_8 }
            return Decoded(String(bytes, charset).removePrefix("\uFEFF"), charset.name())
        }
        if (bytes.startsWith(UTF_8_BOM)) {
            return Decoded(String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8), "UTF-8")
        }
        if (bytes.startsWith(UTF_16_LE_BOM)) {
            return Decoded(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.startsWith(UTF_16_BE_BOM)) {
            return Decoded(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE), "UTF-16BE")
        }
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return Decoded(decoder.decode(ByteBuffer.wrap(bytes)).toString(), "UTF-8")
        } catch (_: CharacterCodingException) {
            val charsetName = legacyCharset(language)
            return Decoded(String(bytes, Charset.forName(charsetName)), charsetName)
        }
    }

    // "ru-RU", "sr_Latn" choose by their primary language subtag.
    private fun legacyCharset(language: String): String =
        when (language.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
            "pl", "cs", "sk", "hu", "sl", "hr", "ro", "bs", "sq" -> "windows-1250"
            "ru", "uk", "be", "bg", "sr", "mk" -> "windows-1251"
            "el" -> "windows-1253"
            "tr", "az" -> "windows-1254"
            else -> "windows-1252"
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val ISO_LANGUAGES = Locale.getISOLanguages().toSet()

    private val BIBLIOGRAPHIC_LANGUAGES = mapOf(
        "ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs",
        "gre" to "el", "rum" to "ro", "slo" to "sk", "per" to "fa", "alb" to "sq",
        "arm" to "hy", "baq" to "eu", "geo" to "ka", "ice" to "is", "mac" to "mk",
        "may" to "ms", "wel" to "cy"
    )

    private val THREE_LETTER_LANGUAGES: Map<String, String> = buildMap {
        for (language in ISO_LANGUAGES) {
            runCatching { Locale(language).isO3Language }.getOrNull()?.let { put(it, language) }
        }
        putAll(BIBLIOGRAPHIC_LANGUAGES)
    }

    private val ENGLISH_LANGUAGE_NAMES: Map<String, String> = buildMap {
        for (language in ISO_LANGUAGES) {
            put(Locale(language).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), language)
        }
    }

    // "hi" is Hindi: as a later tag (film.en.hi.srt) it means hearing impaired, but sidecars() takes the first
    // language tag only, so a later "hi" never replaces the language.
    private val NON_LANGUAGE_TAGS = setOf("cc", "sdh", "forced")

    private val UTF_8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF_16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF_16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
