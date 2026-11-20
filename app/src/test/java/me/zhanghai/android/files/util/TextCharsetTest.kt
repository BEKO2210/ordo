/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.util

import me.zhanghai.android.files.viewer.video.subtitles.SubtitleChoice
import me.zhanghai.android.files.viewer.video.subtitles.SubtitleFiles
import me.zhanghai.android.files.viewer.video.subtitles.SubtitleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Real files (app/src/test/resources/charset, made by iconv), not strings encoded by the test itself. */
class TextCharsetTest {
    private fun bytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/charset/$name")!!.use { it.readBytes() }

    private val lines = "Grüße aus Köln – Größe, Übermaß, schön\r\nÄ Ö Ü ä ö ü ß"

    /** The owner's Tab S9, 20.11.2026: the crawler's UTF-8 file read "Untertitel fÃ¼r den Crawler". */
    @Test
    fun crawlerUtf8WithoutBomStaysUtf8() {
        val decoded = TextCharset.decode(bytes("crawl-utf8.srt"), "de")
        assertEquals("UTF-8", decoded.charset)
        assertEquals(true, "Untertitel für den Crawler" in decoded.text)
        // Also without a language tag and with every legacy guess the device language could make.
        for (language in listOf(null, "de", "ru", "pl", "el", "tr")) {
            assertEquals("UTF-8", TextCharset.decode(bytes("crawl-utf8.srt"), language).charset)
        }
        // The crawler presses every charset chip: no manual choice garbles it either.
        for (manual in SubtitleFiles.CHARSETS) {
            val decoded = SubtitleFiles.decode(bytes("crawl-utf8.srt"), manual, null)
            assertEquals(true, "Untertitel für den Crawler" in decoded.text)
        }
    }

    /** The cause: a charset chosen by hand (the crawler presses every chip) held for every file of the film. */
    @Test
    fun manualCharsetHoldsForItsOwnFileOnly() {
        val utf8 = bytes("crawl-utf8.srt")
        val other = bytes("de-cp1252.srt")
        val entry = SubtitleStore.Entry(
            SubtitleChoice.File("file:///m/crawl.srt"), 0, "windows-1252", SubtitleFiles.contentKey(other)
        )
        assertNull(entry.charsetFor(SubtitleFiles.contentKey(utf8)))
        val decoded = SubtitleFiles.decode(utf8, entry.charsetFor(SubtitleFiles.contentKey(utf8)), null)
        assertEquals("UTF-8", decoded.charset)
        assertEquals(true, "für" in decoded.text)
        assertEquals("windows-1252", entry.charsetFor(SubtitleFiles.contentKey(other)))
        // Same name, new content (the crawler's folder is made again each run): the choice is gone.
        assertNull(entry.charsetFor(SubtitleFiles.contentKey(other + 0x0A)))
    }

    @Test
    fun storeDropsTheOldPerFilmCharset() {
        // A 0.17.0 entry (four fields): the charset held for the whole film and is dropped, choice and delay stay.
        val old = listOf("", "-1300", "windows-1252", "42").joinToString("\u001e")
        val decoded = SubtitleStore.decode(old)!!
        assertNull(decoded.charset)
        assertEquals(-1300L, decoded.delayMs)
        val entry = SubtitleStore.Entry(null, 0, "windows-1250", "60:123")
        assertEquals(entry, SubtitleStore.decode(SubtitleStore.encode(entry, 7)))
    }

    @Test
    fun legacyWesternFiles() {
        assertEquals(TextCharset.Decoded("1\r\n00:00:00,300 --> 00:00:29,500\r\n$lines\r\n", "windows-1252"),
            TextCharset.decode(bytes("de-cp1252.srt"), "de"))
        // Latin-1 reads the same in Windows-1252 (a superset for every printable Latin-1 byte).
        val latin1 = TextCharset.decode(bytes("de-latin1.srt"), null)
        assertEquals("windows-1252", latin1.charset)
        assertEquals(true, "Grüße aus Köln, Größe, Übermaß, schön" in latin1.text)
    }

    @Test
    fun untaggedCyrillicAndGreekAreToldByTheirBytes() {
        val russian = "1\n00:00:01,000 --> 00:00:02,000\nПривет, как дела?\n".toByteArray(charset("windows-1251"))
        assertEquals("windows-1251", TextCharset.guess(russian, "de"))
        val greek = "Καλημέρα κόσμε".toByteArray(charset("windows-1253"))
        assertEquals("windows-1253", TextCharset.guess(greek, "el"))
        assertNull(TextCharset.guess(bytes("de-cp1252.srt"), "de"))
        assertNull(TextCharset.guess(bytes("de-latin1.srt"), "ru"))
        assertNull(TextCharset.guess("Grüße ÄÖÜ ß".toByteArray(charset("windows-1252")), "de"))
    }

    @Test
    fun unicodeFiles() {
        val expected = "1\r\n00:00:00,300 --> 00:00:29,500\r\n$lines\r\n"
        assertEquals(TextCharset.Decoded(expected, "UTF-8"), TextCharset.decode(bytes("de-utf8.srt")))
        assertEquals(TextCharset.Decoded(expected, "UTF-8"), TextCharset.decode(bytes("de-utf8-bom.srt")))
        assertEquals(TextCharset.Decoded(expected, "UTF-16LE"), TextCharset.decode(bytes("de-utf16le-bom.srt")))
    }

    @Test
    fun invalidFileNeverThrows() {
        val decoded = TextCharset.decode(bytes("invalid.srt"), "de")
        assertEquals("windows-1252", decoded.charset)
        assertFalse(decoded.text.isEmpty())
        // An unknown manual name reads as UTF-8 instead of failing.
        assertEquals("UTF-8", TextCharset.decodeAs(bytes("de-utf8.srt"), "x-evil").charset)
    }

    @Test
    fun lyricsUseTheSameDetector() {
        assertEquals(
            "[00:01.00]Über den Wolken muss die Freiheit wohl grenzenlos sein\n",
            me.zhanghai.android.files.viewer.audio.lyrics.LyricsRepository.decodeText(bytes("lyrics-cp1252.lrc"))
        )
    }
}
