/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import java.io.FileNotFoundException
import me.zhanghai.android.files.compat.use
import okio.Buffer

/**
 * The cover of one song: the picture embedded in its file, else its album's cover from MediaStore. MediaStore gives
 * all songs of an album (and every untagged song of a folder, whose album is the folder's name) one album cover,
 * so a folder showed the same picture on every row (Galaxy Tab S9 Ultra, 18.11.2026).
 */
data class SongCover(val path: String, val albumArtUri: String?, val modifiedMs: Long = 0, val sizeBytes: Long = 0)

class SongCoverKeyer : Keyer<SongCover> {
    // A changed file (the tag editor) gets a new key, so its new cover shows at once. Change time and size are the
    // ones MediaStore already gave the song (it counts seconds, the size tells two edits in one second apart): the
    // keyer runs on the main thread and must not touch the disk.
    override fun key(data: SongCover, options: Options): String = key(data)

    companion object {
        fun key(data: SongCover): String = "song:${data.path}:${data.modifiedMs}:${data.sizeBytes}"
    }
}

class SongCoverFetcher(
    private val data: SongCover,
    private val options: Options,
    private val context: Context
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val embedded = try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(data.path)
                retriever.embeddedPicture
            }
        } catch (e: Exception) {
            null
        }
        if (embedded != null && embedded.isNotEmpty()) {
            return SourceResult(
                ImageSource(Buffer().write(embedded), options.context), null, DataSource.DISK
            )
        }
        val albumArt = data.albumArtUri
        if (albumArt != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bitmap = context.contentResolver.loadThumbnail(Uri.parse(albumArt), Size(512, 512), null)
            return DrawableResult(bitmap.toDrawable(context.resources), true, DataSource.DISK)
        }
        if (albumArt != null) {
            // Android 7 to 9: the old albumart URI is an image stream.
            val bytes = context.contentResolver.openInputStream(Uri.parse(albumArt))?.use { it.readBytes() }
            if (bytes != null) {
                return SourceResult(ImageSource(Buffer().write(bytes), options.context), null, DataSource.DISK)
            }
        }
        throw FileNotFoundException("No cover for ${data.path}")
    }

    class Factory(private val context: Context) : Fetcher.Factory<SongCover> {
        override fun create(data: SongCover, options: Options, imageLoader: ImageLoader): Fetcher =
            SongCoverFetcher(data, options, context)
    }
}
