/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.seek

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Frames for the seek preview: [MediaMetadataRetriever] on one background thread, the nearest key frame
 * (OPTION_CLOSEST_SYNC, fast; up to one key-frame interval away from the exact spot) at [widthPx] wide, kept in a
 * memory LRU of [CACHE_BYTES]. Only for local files and `content://` URIs; network paths (SMB, SFTP …) get none,
 * because a second reader over the network would compete with playback. Only the latest wish is decoded: a finger
 * that moved on skips the frames in between.
 */
internal class SeekThumbnails(private val context: Context, private val widthPx: Int) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "OrdoSeekThumbs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }
    // Bumped for every new source and every new wish; a task that is no longer the latest does nothing.
    private val generation = AtomicLong()
    // Bumped per source only: a frame of the previous video never lands in the new one's cache.
    private val sourceGeneration = AtomicLong()
    private var retriever: MediaMetadataRetriever? = null
    private var source: Any? = null

    /** A local file path or a content:// URI; null for a source without previews. */
    fun setSource(newSource: Any?) {
        if (newSource == source || executor.isShutdown) return
        source = newSource
        cache.evictAll()
        generation.incrementAndGet()
        sourceGeneration.incrementAndGet()
        executor.execute { openRetriever(newSource) }
    }

    val hasSource: Boolean
        get() = source != null

    /** The cached frame for [timeMs] now, or null and [onReady] on the main thread once it is decoded. */
    fun request(timeMs: Long, onReady: (Long, Bitmap) -> Unit): Bitmap? {
        cache.get(timeMs)?.let { return it }
        if (source == null || executor.isShutdown) return null
        val wish = generation.incrementAndGet()
        val wishSource = sourceGeneration.get()
        executor.execute {
            if (generation.get() != wish) return@execute
            val bitmap = decode(timeMs) ?: return@execute
            main.post {
                if (sourceGeneration.get() != wishSource) return@post
                cache.put(timeMs, bitmap)
                if (generation.get() == wish) onReady(timeMs, bitmap)
            }
        }
        return null
    }

    fun release() {
        source = null
        generation.incrementAndGet()
        executor.execute { openRetriever(null) }
        executor.shutdown()
        cache.evictAll()
    }

    // Background thread only.
    private fun openRetriever(newSource: Any?) {
        retriever?.let { runCatching { it.release() } }
        retriever = null
        if (newSource == null) return
        retriever = runCatching {
            MediaMetadataRetriever().apply {
                when (newSource) {
                    is Uri -> setDataSource(context, newSource)
                    else -> setDataSource(newSource.toString())
                }
            }
        }.getOrNull()
    }

    // Background thread only.
    private fun decode(timeMs: Long): Bitmap? {
        val retriever = retriever ?: return null
        return runCatching {
            val timeUs = timeMs * 1000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, widthPx, widthPx
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val height = (full.height.toLong() * widthPx / full.width.coerceAtLeast(1)).toInt()
                    Bitmap.createScaledBitmap(full, widthPx, height.coerceAtLeast(1), true)
                        .also { if (it !== full) full.recycle() }
                }
            }
        }.getOrNull()
    }

    companion object {
        private const val CACHE_BYTES = 12 * 1024 * 1024
    }
}
