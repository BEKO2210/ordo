/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON payloads of the Cast V2 namespaces Ordo uses, and the parts of the replies it reads.
 * Message shapes: https://developers.google.com/cast/docs/media/messages ; receiver namespace as in pychromecast (MIT).
 */
object CastProtocol {
    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    const val SENDER_ID = "sender-0"
    const val RECEIVER_ID = "receiver-0"
    /** Google's Default Media Receiver; needs no registration. */
    const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

    fun type(type: String): String = JSONObject().put("type", type).toString()

    fun launch(requestId: Int): String =
        request("LAUNCH", requestId).put("appId", DEFAULT_MEDIA_RECEIVER).toString()

    fun receiverStatus(requestId: Int): String = request("GET_STATUS", requestId).toString()

    fun stopApp(requestId: Int, sessionId: String): String =
        request("STOP", requestId).put("sessionId", sessionId).toString()

    fun setVolume(requestId: Int, level: Double? = null, muted: Boolean? = null): String {
        val volume = JSONObject()
        level?.let { volume.put("level", it.coerceIn(0.0, 1.0)) }
        muted?.let { volume.put("muted", it) }
        return request("SET_VOLUME", requestId).put("volume", volume).toString()
    }

    data class Track(
        val url: String,
        val contentType: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val imageUrl: String?
    )

    fun load(requestId: Int, track: Track, startSeconds: Double, autoplay: Boolean): String {
        // metadataType 3 = MusicTrackMediaMetadata.
        val metadata = JSONObject().put("metadataType", 3)
        track.title?.let { metadata.put("title", it) }
        track.artist?.let { metadata.put("artist", it) }
        track.album?.let { metadata.put("albumName", it) }
        track.imageUrl?.let { metadata.put("images", JSONArray().put(JSONObject().put("url", it))) }
        val media = JSONObject()
            .put("contentId", track.url)
            .put("contentUrl", track.url)
            .put("streamType", "BUFFERED")
            .put("contentType", track.contentType)
            .put("metadata", metadata)
        return request("LOAD", requestId)
            .put("media", media)
            .put("autoplay", autoplay)
            .put("currentTime", startSeconds)
            .toString()
    }

    /** PLAY, PAUSE, STOP and GET_STATUS on the media session. */
    fun media(type: String, requestId: Int, mediaSessionId: Int): String =
        request(type, requestId).put("mediaSessionId", mediaSessionId).toString()

    fun seek(requestId: Int, mediaSessionId: Int, seconds: Double): String =
        request("SEEK", requestId).put("mediaSessionId", mediaSessionId).put("currentTime", seconds).toString()

    private fun request(type: String, requestId: Int) = JSONObject().put("type", type).put("requestId", requestId)

    fun typeOf(payload: String): String? = runCatching { JSONObject(payload).optString("type") }.getOrNull()

    fun requestIdOf(payload: String): Int = runCatching { JSONObject(payload).optInt("requestId") }.getOrDefault(0)

    data class ReceiverStatus(
        /** Set while the Default Media Receiver runs on the device. */
        val transportId: String?,
        val sessionId: String?,
        val volumeLevel: Double?,
        val muted: Boolean?
    )

    /** RECEIVER_STATUS: the running Default Media Receiver (if any) and the device volume. */
    fun parseReceiverStatus(payload: String): ReceiverStatus? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (json.optString("type") != "RECEIVER_STATUS") return null
        val status = json.optJSONObject("status") ?: return null
        val app = status.optJSONArray("applications")?.let { apps ->
            (0 until apps.length()).map { apps.getJSONObject(it) }
                .firstOrNull { it.optString("appId") == DEFAULT_MEDIA_RECEIVER }
        }
        val volume = status.optJSONObject("volume")
        return ReceiverStatus(
            app?.optString("transportId")?.takeIf { it.isNotEmpty() },
            app?.optString("sessionId")?.takeIf { it.isNotEmpty() },
            volume?.takeIf { it.has("level") }?.optDouble("level"),
            volume?.takeIf { it.has("muted") }?.optBoolean("muted")
        )
    }

    data class MediaStatus(
        val mediaSessionId: Int,
        /** IDLE, BUFFERING, LOADING, PLAYING or PAUSED. */
        val playerState: String,
        /** With IDLE: FINISHED, CANCELLED, INTERRUPTED or ERROR. */
        val idleReason: String?,
        val currentTimeSeconds: Double,
        val durationSeconds: Double?,
        val requestId: Int
    )

    /** MEDIA_STATUS with its first status entry; an empty list (nothing loaded) gives null. */
    fun parseMediaStatus(payload: String): MediaStatus? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (json.optString("type") != "MEDIA_STATUS") return null
        val status = json.optJSONArray("status")?.optJSONObject(0) ?: return null
        val duration = status.optJSONObject("media")?.optDouble("duration")?.takeIf { !it.isNaN() && it > 0 }
        return MediaStatus(
            status.optInt("mediaSessionId"),
            status.optString("playerState"),
            status.optString("idleReason").takeIf { it.isNotEmpty() },
            status.optDouble("currentTime", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            duration,
            json.optInt("requestId")
        )
    }
}
