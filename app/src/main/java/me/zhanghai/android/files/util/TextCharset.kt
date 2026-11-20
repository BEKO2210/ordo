/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Text files of unknown origin (subtitles, lyrics, playlists): a byte order mark wins, then strict UTF-8 (valid UTF-8
 * always stays UTF-8), then one legacy single-byte code page. Shared by Ordo Video and Ordo Musik.
 */
object TextCharset {
    data class Decoded(val text: String, val charset: String)

    /**
     * [language]: the file's own language tag (`film.ru.srt`), it picks the code page; without one a Cyrillic or Greek
     * file is told by its bytes ([guess]), else the device language decides.
     */
    fun decode(bytes: ByteArray, language: String? = null): Decoded {
        bom(bytes)?.let { return it }
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return Decoded(decoder.decode(ByteBuffer.wrap(bytes)).toString(), "UTF-8")
        } catch (_: CharacterCodingException) {
            val name = language?.let(::legacyFor) ?: guess(bytes) ?: legacyFor(Locale.getDefault().language)
            return Decoded(String(bytes, Charset.forName(name)), name)
        }
    }

    /** A manual choice; a byte order mark of the same family is dropped. Unknown names read as UTF-8. */
    fun decodeAs(bytes: ByteArray, charsetName: String): Decoded {
        val charset = runCatching { Charset.forName(charsetName) }.getOrElse { StandardCharsets.UTF_8 }
        return Decoded(String(bytes, charset).removePrefix("﻿"), charset.name())
    }

    private fun bom(bytes: ByteArray): Decoded? {
        fun startsWith(vararg bom: Int) = bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> Decoded(String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8), "UTF-8")
            startsWith(0xFF, 0xFE) ->
                Decoded(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE), "UTF-16LE")
            startsWith(0xFE, 0xFF) ->
                Decoded(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE), "UTF-16BE")
            else -> null
        }
    }

    // "ru-RU", "sr_Latn" choose by their primary language subtag.
    internal fun legacyFor(language: String): String =
        when (language.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)) {
            "pl", "cs", "sk", "hu", "sl", "hr", "ro", "bs", "sq" -> "windows-1250"
            "ru", "uk", "be", "bg", "sr", "mk" -> "windows-1251"
            "el" -> "windows-1253"
            "tr", "az" -> "windows-1254"
            else -> "windows-1252"
        }

    /**
     * Android has no public charset detector (android.icu's CharsetDetector is hidden), so only the one clear case:
     * Cyrillic and Greek code pages put nearly every letter at 0xC0..0xFF, Western ones only the accented few. Which of the two: the device language, Cyrillic otherwise. null: a Latin page.
     */
    internal fun guess(bytes: ByteArray, deviceLanguage: String = Locale.getDefault().language): String? {
        var letters = 0
        var high = 0
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            if (b >= 0xC0) high++
            if (b >= 0xC0 || b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code) letters++
        }
        // Over 80 %: Russian or Greek text has next to no ASCII letters; "Grüße ÄÖÜ ß" (6 of 9) stays Latin.
        if (letters == 0 || high * 5 <= letters * 4) return null
        return if (legacyFor(deviceLanguage) == "windows-1253") "windows-1253" else "windows-1251"
    }
}
