/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.cast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCastProtocolTest {
    @Test
    fun loadCarriesFilmAndSubtitleTracks() {
        val json = JSONObject(VideoCastProtocol.load(
            9, "http://10.0.0.2:4000/a", "video/x-matroska", "Der Film",
            listOf(
                VideoCastProtocol.TextTrack("http://10.0.0.2:4000/b", "Deutsch", "de"),
                VideoCastProtocol.TextTrack("http://10.0.0.2:4000/c", "Untertitel", null)
            ),
            61.5, autoplay = true
        ))
        assertEquals("LOAD", json.getString("type"))
        assertEquals(9, json.getInt("requestId"))
        assertEquals(61.5, json.getDouble("currentTime"), 0.0)
        assertTrue(json.getBoolean("autoplay"))
        val media = json.getJSONObject("media")
        assertEquals("http://10.0.0.2:4000/a", media.getString("contentUrl"))
        assertEquals("video/x-matroska", media.getString("contentType"))
        assertEquals(1, media.getJSONObject("metadata").getInt("metadataType"))
        assertEquals("Der Film", media.getJSONObject("metadata").getString("title"))
        val tracks = media.getJSONArray("tracks")
        assertEquals(2, tracks.length())
        val first = tracks.getJSONObject(0)
        assertEquals(1, first.getInt("trackId"))
        assertEquals("TEXT", first.getString("type"))
        assertEquals("text/vtt", first.getString("trackContentType"))
        assertEquals("http://10.0.0.2:4000/b", first.getString("trackContentId"))
        assertEquals("de", first.getString("language"))
        assertFalse(tracks.getJSONObject(1).has("language"))
        assertEquals(1, json.getJSONArray("activeTrackIds").getInt(0))
    }

    @Test
    fun loadWithoutSubtitlesHasNoTracks() {
        val json = JSONObject(VideoCastProtocol.load(1, "u", "video/mp4", "T", emptyList(), 0.0, autoplay = false))
        assertFalse(json.getJSONObject("media").has("tracks"))
        assertFalse(json.has("activeTrackIds"))
    }
}
