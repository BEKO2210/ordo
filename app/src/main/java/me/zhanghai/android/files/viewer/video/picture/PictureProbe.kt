/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A quick look at a video's size and HDR before the player opens it, so the decision about Media3's OpenGL pipeline
 * is made before prepare() ([PicturePolicy.preEngage]). Only local files and `content://` (MediaExtractor reads the
 * header, typically a few ms); network and archive paths are not probed, they would cost the start more than the
 * fallback re-prepare. Results are kept per media ID for the process.
 */
internal object PictureProbe {
    private const val TIMEOUT_MS = 400L
    private val cache = LruCache<String, PicturePolicy.Probe>(64)

    suspend fun probe(context: Context, mediaId: String): PicturePolicy.Probe? {
        cache.get(mediaId)?.let { return it }
        val uri = Uri.parse(mediaId)
        if (uri.scheme != "file" && uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        // The extractor's native read can't be interrupted: after the timeout it finishes unseen.
        val probe = withTimeoutOrNull(TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) { read(context.applicationContext, uri) }
        }
        if (Log.isLoggable(PICTURE_LOG_TAG, Log.DEBUG)) Log.d(PICTURE_LOG_TAG, "probe $mediaId: $probe")
        probe?.let { cache.put(mediaId, it) }
        return probe
    }

    private fun read(context: Context, uri: Uri): PicturePolicy.Probe? {
        val extractor = MediaExtractor()
        return try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path ?: return null)
            else extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            PicturePolicy.probeFrom(
                format.getString(MediaFormat.KEY_MIME),
                format.intOr(MediaFormat.KEY_WIDTH),
                format.intOr(MediaFormat.KEY_HEIGHT),
                format.intOr(MediaFormat.KEY_ROTATION),
                format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                format.intOrNull(MediaFormat.KEY_PROFILE),
                extractor.psshInfo?.isNotEmpty() == true
            )
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.intOr(key: String): Int = intOrNull(key) ?: 0
}
