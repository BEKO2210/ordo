/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFilesTest {
    @Test
    fun sidecarsMatchTagsAndSortPredictably() {
        val sidecars = SubtitleFiles.sidecars(
            "film.mkv",
            listOf(
                "film.pt-BR.vtt",
                "filmX.srt",
                "film.en.forced.srt",
                "Film.DEU.ass",
                "film2.de.srt",
                "film.German.srt",
                "film.de.srt",
                "FILM.SRT",
                "film.srt",
                "film.txt"
            )
        )

        assertEquals(
            listOf(
                SubtitleFiles.Sidecar("FILM.SRT", null, false),
                SubtitleFiles.Sidecar("film.srt", null, false),
                SubtitleFiles.Sidecar("Film.DEU.ass", "de", false),
                SubtitleFiles.Sidecar("film.German.srt", "de", false),
                SubtitleFiles.Sidecar("film.de.srt", "de", false),
                SubtitleFiles.Sidecar("film.en.forced.srt", "en", true),
                SubtitleFiles.Sidecar("film.pt-BR.vtt", "pt-BR", false)
            ),
            sidecars
        )
        assertFalse(sidecars.any { it.fileName == "filmX.srt" || it.fileName == "film2.de.srt" })
    }

    @Test
    fun languageTagsAreNormalizedOnlyWhenKnown() {
        assertEquals("de", SubtitleFiles.languageCode("de"))
        assertEquals("de", SubtitleFiles.languageCode("DEU"))
        assertEquals("de", SubtitleFiles.languageCode("ger"))
        assertEquals("de", SubtitleFiles.languageCode("German"))
        assertEquals("pt-BR", SubtitleFiles.languageCode("pt-BR"))
        assertEquals("pt-BR", SubtitleFiles.languageCode("pt_br"))
        assertNull(SubtitleFiles.languageCode("forced"))
        assertNull(SubtitleFiles.languageCode("sdh"))
        assertNull(SubtitleFiles.languageCode("hi"))
        assertNull(SubtitleFiles.languageCode("12"))
    }

    @Test
    fun mimeTypesIgnoreExtensionCase() {
        assertEquals("application/x-subrip", SubtitleFiles.mimeType("a.SRT"))
        assertNull(SubtitleFiles.mimeType("a.txt"))
    }

    @Test
    fun decodeUtf8WithAndWithoutBom() {
        val text = "Größe, Übermaß"
        assertEquals(SubtitleFiles.Decoded(text, "UTF-8"), SubtitleFiles.decode(text.toByteArray(), null, "de"))
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()
        assertEquals(SubtitleFiles.Decoded(text, "UTF-8"), SubtitleFiles.decode(withBom, null, "de"))
    }

    @Test
    fun decodeUtf16LittleEndianBom() {
        val text = "Größe, Übermaß"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(StandardCharsets.UTF_16LE)

        assertEquals(SubtitleFiles.Decoded(text, "UTF-16LE"), SubtitleFiles.decode(bytes, null, "de"))
    }

    @Test
    fun decodeLegacyEncodingForLanguage() {
        val westernText = "Größe, Übermaß"
        val westernBytes = westernText.toByteArray(Charset.forName("windows-1252"))
        assertEquals(
            SubtitleFiles.Decoded(westernText, "windows-1252"),
            SubtitleFiles.decode(westernBytes, null, "de")
        )

        val cyrillicText = "Привет"
        val cyrillicBytes = cyrillicText.toByteArray(Charset.forName("windows-1251"))
        assertEquals(
            SubtitleFiles.Decoded(cyrillicText, "windows-1251"),
            SubtitleFiles.decode(cyrillicBytes, null, "ru")
        )
    }

    @Test
    fun overrideWinsEvenWhenBytesAreUtf8() {
        val text = "Größe, Übermaß"
        val decoded = SubtitleFiles.decode(text.toByteArray(), "ISO-8859-15", "de")

        assertEquals("ISO-8859-15", decoded.charset)
        assertFalse(text == decoded.text)
    }
}
