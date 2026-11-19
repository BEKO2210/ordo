/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.network

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBufferingTest {
    @Test
    fun networkSchemesAreRecognized() {
        listOf("smb", "sftp", "dav", "davs", "ftp", "ftps", "ftpes").forEach {
            assertTrue(VideoBuffering.isNetwork("$it://server/movie.mp4"))
        }
        assertTrue(VideoBuffering.isNetwork("SMB://server/movie.mp4"))
        assertFalse(VideoBuffering.isNetwork("content://media/movie"))
        assertFalse(VideoBuffering.isNetwork("file:///storage/movie.mp4"))
        assertFalse(VideoBuffering.isNetwork("/storage/movie.mp4"))
        assertFalse(VideoBuffering.isNetwork(""))
        assertFalse(VideoBuffering.isNetwork(null))
    }

    @Test
    fun uriSelectsBufferConfiguration() {
        assertSame(VideoBuffering.NETWORK, VideoBuffering.forUri("sftp://server/movie.mp4"))
        assertSame(VideoBuffering.LOCAL, VideoBuffering.forUri("content://media/movie"))
    }

    @Test
    fun retryDelayIsBounded() {
        assertEquals(1_000L, VideoBuffering.retryDelayMs(0))
        assertEquals(1_000L, VideoBuffering.retryDelayMs(1))
        assertEquals(3_000L, VideoBuffering.retryDelayMs(3))
        assertEquals(5_000L, VideoBuffering.retryDelayMs(8))
    }

    @Test
    fun bufferPercentIsClampedBelowComplete() {
        assertEquals(0, VideoBuffering.bufferPercent(-1, VideoBuffering.NETWORK))
        assertEquals(50, VideoBuffering.bufferPercent(2_500, VideoBuffering.NETWORK))
        assertEquals(99, VideoBuffering.bufferPercent(5_000, VideoBuffering.NETWORK))
        assertEquals(99, VideoBuffering.bufferPercent(Long.MAX_VALUE, VideoBuffering.NETWORK))
    }

    @Test
    fun speedUsesRequestedLocaleAndUnits() {
        assertEquals("3,1 MB/s", VideoBuffering.speedText(3_100_000, Locale.GERMAN))
        assertEquals("3.1 MB/s", VideoBuffering.speedText(3_100_000, Locale.US))
        assertEquals("42 kB/s", VideoBuffering.speedText(42_999, Locale.US))
        assertEquals("999 B/s", VideoBuffering.speedText(999, Locale.US))
    }
}
