/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainMetadataTest {
    @Test
    fun r128GainsAreShiftedFromMinus23ToMinus18Lufs() {
        // RFC 7845's own example: R128_TRACK_GAIN=-573 is -2.24 dB to reach -23 LUFS, so +2.76 dB to reach -18 LUFS.
        val info = ReplayGainMetadata.fromEntries(listOf(VorbisComment("R128_TRACK_GAIN", "-573")))!!
        assertEquals(-573 / 256f + 5f, info.gainDb, 0.001f)
        val album = ReplayGainMetadata.fromEntries(listOf(VorbisComment("R128_ALBUM_GAIN", "111")))!!
        assertEquals(111 / 256f + 5f, album.gainDb, 0.001f)
    }

    @Test
    fun replayGainTagsStayAsTheyAre() {
        val info = ReplayGainMetadata.fromEntries(
            listOf(
                VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.5 dB"),
                VorbisComment("R128_TRACK_GAIN", "-512"),
                VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.9")
            )
        )!!
        assertEquals(-6.5f, info.gainDb, 0f)
        assertEquals(0.9f, info.peak!!, 0f)
        assertNull(ReplayGainMetadata.fromEntries(emptyList()))
    }
}
