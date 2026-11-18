/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import androidx.media3.common.Player
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastProtocolTest {
    @Test
    fun loadCarriesUrlTypeAndMusicMetadata() {
        val track = CastProtocol.Track(
            "http://192.168.2.158:40000/abc", "audio/flac", "Song", "Artist", "Album", "http://192.168.2.158:40000/def"
        )
        val json = JSONObject(CastProtocol.load(7, track, 12.5, autoplay = false))
        assertEquals("LOAD", json.getString("type"))
        assertEquals(7, json.getInt("requestId"))
        assertEquals(12.5, json.getDouble("currentTime"), 0.0)
        assertFalse(json.getBoolean("autoplay"))
        val media = json.getJSONObject("media")
        assertEquals(track.url, media.getString("contentId"))
        assertEquals("audio/flac", media.getString("contentType"))
        assertEquals("BUFFERED", media.getString("streamType"))
        val metadata = media.getJSONObject("metadata")
        assertEquals(3, metadata.getInt("metadataType"))
        assertEquals("Song", metadata.getString("title"))
        assertEquals("Artist", metadata.getString("artist"))
        assertEquals("Album", metadata.getString("albumName"))
        assertEquals(track.imageUrl, metadata.getJSONArray("images").getJSONObject(0).getString("url"))
    }

    @Test
    fun loadSendsTheWholeMusicTrackMetadata() {
        val track = CastProtocol.Track(
            "http://192.168.2.158:40000/a", "audio/mpeg", "Song", "Artist", "Album", "http://192.168.2.158:40000/b",
            albumArtist = "Various", trackNumber = 7
        )
        val metadata = JSONObject(CastProtocol.load(1, track, 0.0, true)).getJSONObject("media").getJSONObject("metadata")
        assertEquals(3, metadata.getInt("metadataType"))
        assertEquals("Various", metadata.getString("albumArtist"))
        assertEquals(7, metadata.getInt("trackNumber"))
        assertEquals("http://192.168.2.158:40000/b", metadata.getJSONArray("images").getJSONObject(0).getString("url"))
    }

    @Test
    fun trackNumbersAndCoverSizesForTheTv() {
        assertEquals(3, CastPlayback.trackNumber("3/12"))
        assertEquals(3, CastPlayback.trackNumber("03"))
        assertNull(CastPlayback.trackNumber("0"))
        assertNull(CastPlayback.trackNumber("A1"))
        assertNull(CastPlayback.trackNumber(null))
        assertEquals(1024 to 768, CastPlayback.coverSize(3000, 2250))
        assertEquals(512 to 1024, CastPlayback.coverSize(1000, 2000))
        assertEquals(600 to 600, CastPlayback.coverSize(600, 600))
    }

    @Test
    fun loadLeavesOutUnknownTags() {
        val track = CastProtocol.Track("http://h/x", "audio/mpeg", null, null, null, null)
        val metadata = JSONObject(CastProtocol.load(1, track, 0.0, true)).getJSONObject("media").getJSONObject("metadata")
        assertEquals(listOf("metadataType"), metadata.keys().asSequence().toList())
    }

    @Test
    fun commandsNameTheirSession() {
        val launch = JSONObject(CastProtocol.launch(3))
        assertEquals("LAUNCH", launch.getString("type"))
        assertEquals(CastProtocol.DEFAULT_MEDIA_RECEIVER, launch.getString("appId"))
        val seek = JSONObject(CastProtocol.seek(4, 9, 30.0))
        assertEquals(9, seek.getInt("mediaSessionId"))
        assertEquals(30.0, seek.getDouble("currentTime"), 0.0)
        val pause = JSONObject(CastProtocol.media("PAUSE", 5, 9))
        assertEquals("PAUSE", pause.getString("type"))
        assertEquals(9, pause.getInt("mediaSessionId"))
        assertEquals("abc", JSONObject(CastProtocol.stopApp(6, "abc")).getString("sessionId"))
        val volume = JSONObject(CastProtocol.setVolume(8, level = 1.7)).getJSONObject("volume")
        assertEquals(1.0, volume.getDouble("level"), 0.0)
        assertFalse(volume.has("muted"))
    }

    @Test
    fun readsTheDefaultMediaReceiverFromReceiverStatus() {
        val payload = """{"type":"RECEIVER_STATUS","requestId":2,"status":{"applications":[
            {"appId":"E8C28D3C","sessionId":"other","transportId":"other"},
            {"appId":"CC1AD845","displayName":"Default Media Receiver","sessionId":"s-1","transportId":"web-7"}],
            "volume":{"level":0.25,"muted":false}}}"""
        val status = CastProtocol.parseReceiverStatus(payload)!!
        assertEquals("web-7", status.transportId)
        assertEquals("s-1", status.sessionId)
        assertEquals(0.25, status.volumeLevel!!, 0.0)
        assertEquals(false, status.muted)
    }

    @Test
    fun ordosOwnReceiverIsLaunchedAndFoundByItsAppId() {
        assertEquals("59928C44", JSONObject(CastProtocol.launch(2, "59928C44")).getString("appId"))
        val payload = """{"type":"RECEIVER_STATUS","status":{"applications":[
            {"appId":"59928C44","displayName":"Ordo","sessionId":"s-9","transportId":"t-9"}]}}"""
        val status = CastProtocol.parseReceiverStatus(payload, listOf("59928C44", CastProtocol.DEFAULT_MEDIA_RECEIVER))!!
        assertEquals("t-9", status.transportId)
        assertEquals("59928C44", status.appId)
        // A sender that only knows the Default Media Receiver does not mistake Ordo's app for it.
        assertNull(CastProtocol.parseReceiverStatus(payload)!!.transportId)
    }

    @Test
    fun receiverStatusWithoutOurAppHasNoTransport() {
        val status = CastProtocol.parseReceiverStatus(
            """{"type":"RECEIVER_STATUS","status":{"volume":{"level":0.5}}}"""
        )!!
        assertNull(status.transportId)
        assertNull(status.muted)
        assertNull(CastProtocol.parseReceiverStatus("""{"type":"PONG"}"""))
        assertNull(CastProtocol.parseReceiverStatus("not json"))
    }

    @Test
    fun readsMediaStatus() {
        val payload = """{"type":"MEDIA_STATUS","requestId":11,"status":[{"mediaSessionId":4,"playerState":"PLAYING",
            "currentTime":42.5,"media":{"duration":180.2}}]}"""
        val status = CastProtocol.parseMediaStatus(payload)!!
        assertEquals(4, status.mediaSessionId)
        assertEquals("PLAYING", status.playerState)
        assertEquals(42.5, status.currentTimeSeconds, 0.0)
        assertEquals(180.2, status.durationSeconds!!, 0.0)
        assertEquals(11, status.requestId)
        assertNull(status.idleReason)
        assertNull(CastProtocol.parseMediaStatus("""{"type":"MEDIA_STATUS","status":[]}"""))
    }

    @Test
    fun mapsReceiverStatesToPlayerStates() {
        fun status(state: String, reason: String? = null) = CastProtocol.MediaStatus(1, state, reason, 0.0, null, 0)
        assertEquals(Player.STATE_READY, CastPlayback.playbackState(status("PLAYING")))
        assertEquals(Player.STATE_READY, CastPlayback.playbackState(status("PAUSED")))
        assertEquals(Player.STATE_BUFFERING, CastPlayback.playbackState(status("BUFFERING")))
        assertEquals(Player.STATE_ENDED, CastPlayback.playbackState(status("IDLE", "FINISHED")))
        assertEquals(Player.STATE_IDLE, CastPlayback.playbackState(status("IDLE", "CANCELLED")))
        assertEquals(Player.STATE_IDLE, CastPlayback.playbackState(null))
        assertTrue(CastPlayback.playWhenReady(status("PLAYING"), false))
        assertFalse(CastPlayback.playWhenReady(status("PAUSED"), true))
        assertTrue(CastPlayback.playWhenReady(status("IDLE", "CANCELLED"), true))
        assertTrue(CastPlayback.isError(status("IDLE", "ERROR")))
        assertFalse(CastPlayback.isError(status("IDLE", "FINISHED")))
    }

    @Test
    fun positionMovesOnOnlyWhilePlayingAndStopsAtTheEnd() {
        val playing = CastProtocol.MediaStatus(1, "PLAYING", null, 10.0, 12.0, 0)
        assertEquals(11_500L, CastPlayback.positionMs(playing, 1_000L, 2_500L))
        assertEquals(12_000L, CastPlayback.positionMs(playing, 1_000L, 60_000L))
        val paused = playing.copy(playerState = "PAUSED")
        assertEquals(10_000L, CastPlayback.positionMs(paused, 1_000L, 60_000L))
    }

    @Test
    fun volumeStepsMatchTheReceiverLevel() {
        assertEquals(2, CastPlayback.deviceVolume(0.1))
        assertEquals(20, CastPlayback.deviceVolume(1.3))
        assertEquals(0.5, CastPlayback.level(10), 0.0)
        assertEquals(0.0, CastPlayback.level(-3), 0.0)
    }

    @Test
    fun contentTypesFollowTheCastFormatList() {
        assertEquals("audio/mpeg", CastPlayback.contentType("Song.MP3"))
        assertEquals("audio/flac", CastPlayback.contentType("a.b.flac"))
        assertEquals("audio/ogg", CastPlayback.contentType("x.opus"))
        assertEquals("audio/mp4", CastPlayback.contentType("x.m4a"))
        assertNull(CastPlayback.contentType("x.mka"))
        assertNull(CastPlayback.contentType("noextension"))
    }

    @Test
    fun deviceComesFromItsTxtRecord() {
        val txt = mapOf(
            "id" to "abc123".toByteArray(), "fn" to "Wohnzimmer".toByteArray(),
            "md" to "Chromecast".toByteArray(), "ca" to "465413".toByteArray(), "rs" to null
        )
        val device = CastDevice.fromTxt("Chromecast-abc123", txt, "192.168.2.37", 8009)
        assertEquals(CastDevice("abc123", "Wohnzimmer", "Chromecast", "192.168.2.37", 8009, true), device)
        val speaker = CastDevice.fromTxt("Nest", mapOf("ca" to "199172".toByteArray()), "10.0.0.2", 8009)
        assertFalse(speaker.hasVideo)
        assertEquals("Nest", speaker.name)
        assertEquals("Nest", speaker.id)
    }
}
