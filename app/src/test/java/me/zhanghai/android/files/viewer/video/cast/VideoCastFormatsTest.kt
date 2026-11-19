/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCastFormatsTest {
    @Test
    fun contentTypesAndExtensions() {
        listOf("mp4", "m4v", "mov").forEach {
            assertEquals("video/mp4", VideoCastFormats.contentType("movie.$it"))
        }
        assertEquals("video/webm", VideoCastFormats.contentType("movie.WEBM"))
        assertEquals("video/x-matroska", VideoCastFormats.contentType("movie.mkv"))
        listOf("avi", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "3gp").forEach {
            assertNull(VideoCastFormats.contentType("movie.$it"))
        }
        assertNull(VideoCastFormats.contentType("movie"))
        assertEquals("AVI", VideoCastFormats.extension("movie.Avi"))
        assertEquals("", VideoCastFormats.extension("movie"))
    }

    @Test
    fun srtBecomesCleanWebVtt() {
        val srt = "\uFEFF1\r\n0:00:01,000 --> 0:00:02,500 position:10%\r\n{\\an8}<i>Größe</i>\r\n\r\n" +
            "2\r\n00:00:03.000 --> 00:00:04,000\r\n{\\i1}<b>Zwei</b>\r\n"

        assertEquals(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\n<i>Größe</i>\n\n" +
                "2\n00:00:03.000 --> 00:00:04.000\n<b>Zwei</b>\n",
            VideoCastFormats.srtToVtt(srt)
        )
    }

    @Test
    fun existingWebVttOnlyHasNewlinesNormalized() {
        val vtt = "\uFEFFWEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000 align:start\rText\r"
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:start\nText\n",
            VideoCastFormats.srtToVtt(vtt)
        )
    }

    @Test
    fun subtitleBytesUseUtf8OrWindows1252() {
        assertEquals("Größe", VideoCastFormats.decode("\uFEFFGröße".toByteArray(Charsets.UTF_8)))
        val windows1252 = byteArrayOf(0x47, 0x72, 0xF6.toByte(), 0xDF.toByte(), 0x65)
        assertEquals("Größe", VideoCastFormats.decode(windows1252))
    }

    @Test
    fun sidecarsMatchAndPutDeviceLanguageFirst() {
        val siblings = listOf(
            "movie.srt",
            "MOVIE.de.vtt",
            "movie.ger.forced.srt",
            "movie.eng.srt",
            "movie.forced.vtt",
            "movie.fr.srt",
            "other.de.srt",
            "movie.txt"
        )

        assertEquals(
            listOf(
                VideoCastFormats.Subtitle("MOVIE.de.vtt", "de"),
                VideoCastFormats.Subtitle("movie.ger.forced.srt", "de"),
                VideoCastFormats.Subtitle("movie.forced.vtt", null),
                VideoCastFormats.Subtitle("movie.srt", null),
                VideoCastFormats.Subtitle("movie.eng.srt", "en"),
                VideoCastFormats.Subtitle("movie.fr.srt", "fr")
            ),
            VideoCastFormats.sidecarSubtitles("Movie.MKV", siblings, "de")
        )
    }
}
