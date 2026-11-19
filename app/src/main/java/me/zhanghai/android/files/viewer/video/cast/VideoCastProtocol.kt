/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.cast

import org.json.JSONArray
import org.json.JSONObject

/**
 * LOAD for a film on Google's Default Media Receiver: MovieMediaMetadata and the sidecar subtitles as WebVTT text
 * tracks, the first one switched on. Shapes: https://developers.google.com/cast/docs/media/messages (MediaInformation,
 * Track, LoadRequest.activeTrackIds).
 */
object VideoCastProtocol {
    class TextTrack(val url: String, val name: String, val language: String?)

    fun load(
        requestId: Int,
        url: String,
        contentType: String,
        title: String,
        subtitles: List<TextTrack>,
        startSeconds: Double,
        autoplay: Boolean
    ): String {
        // metadataType 1 = MovieMediaMetadata.
        val metadata = JSONObject().put("metadataType", 1).put("title", title)
        val media = JSONObject()
            .put("contentId", url)
            .put("contentUrl", url)
            .put("streamType", "BUFFERED")
            .put("contentType", contentType)
            .put("metadata", metadata)
        if (subtitles.isNotEmpty()) {
            val tracks = JSONArray()
            subtitles.forEachIndexed { index, track ->
                tracks.put(JSONObject()
                    .put("trackId", index + 1)
                    .put("type", "TEXT")
                    .put("subtype", "SUBTITLES")
                    .put("trackContentId", track.url)
                    .put("trackContentType", "text/vtt")
                    .put("name", track.name)
                    .apply { track.language?.let { put("language", it) } })
            }
            media.put("tracks", tracks)
        }
        return JSONObject()
            .put("type", "LOAD")
            .put("requestId", requestId)
            .put("media", media)
            .put("autoplay", autoplay)
            .put("currentTime", startSeconds)
            .apply { if (subtitles.isNotEmpty()) put("activeTrackIds", JSONArray().put(1)) }
            .toString()
    }
}
