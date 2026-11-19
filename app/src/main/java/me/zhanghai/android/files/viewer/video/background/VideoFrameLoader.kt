/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The media notification's and lock screen's cover: a frame of the playing video. Media3 asks with the item's
 * metadata, which has no artwork; the frame comes from the current file at the current place (at least 10 % in, so a
 * film that just started does not show its black first frame). Local files and `content://` only; over the network
 * there is no cover.
 */
@UnstableApi
class VideoFrameLoader(private val context: Context, private val player: () -> Player?) : BitmapLoader {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        Futures.submit(Callable {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IllegalArgumentException("Not an image")
        }, executor)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = frame(uri, 0L)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        val player = player() ?: return null
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return null
        if (uri.scheme != ContentResolver.SCHEME_FILE && uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        return frame(uri, maxOf(player.currentPosition, duration / 10))
    }

    private fun frame(uri: Uri, positionMs: Long): ListenableFuture<Bitmap> =
        Futures.submit(Callable {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == ContentResolver.SCHEME_FILE) retriever.setDataSource(uri.path)
                else retriever.setDataSource(context, uri)
                val timeUs = positionMs * 1000
                val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                (if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, option, SIZE_PX, SIZE_PX)
                } else {
                    retriever.getFrameAtTime(timeUs, option)?.let(::scaled)
                }) ?: throw IllegalStateException("No frame")
            } finally {
                retriever.release()
            }
        }, executor)

    private fun scaled(bitmap: Bitmap): Bitmap {
        val scale = SIZE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    fun release() = executor.shutdownNow()

    companion object {
        private const val SIZE_PX = 512
    }
}
