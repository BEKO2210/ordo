/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.viewer.audio.KlangSheet
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.util.putArgs

/**
 * What the "Video info" sheet shows: the facts a viewer cares about, not permissions or SELinux contexts. Unknown
 * values are null and their row is left out. Built from the player's tracks while a video plays, or from the platform's
 * MediaExtractor for a card in the library.
 */
data class VideoInfo(
    val title: String,
    val fileName: String? = null,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val hdr: Boolean? = null,
    val videoCodec: Pair<String, Boolean>? = null,
    val bitrate: Long = 0,
    /** Per sound track: codec, channels, language, as known. */
    val audio: List<List<String>> = emptyList(),
    val subtitles: List<List<String>> = emptyList(),
    val size: Long = -1,
    val folder: String? = null,
    val recordedMs: Long = 0
) {
    companion object {
        /** "Mono", "Stereo", "5.1", "7.1" or the plain count. */
        fun channels(count: Int): String? = when (count) {
            0, Format.NO_VALUE -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$count ch"
        }

        /** "48 kHz", "44,1 kHz" (the device's number format). */
        fun sampleRate(hz: Int, locale: Locale = Locale.getDefault()): String? {
            if (hz <= 0) return null
            return "${NumberFormat.getInstance(locale).apply { maximumFractionDigits = 1 }.format(hz / 1000.0)} kHz"
        }

        /** "1920 × 1080 · 30 fps", "3840 × 2160 · 29,97 fps" (the device's number format), or without the rate. */
        fun resolution(width: Int, height: Int, frameRate: Float, locale: Locale = Locale.getDefault()): String? {
            if (width <= 0 || height <= 0) return null
            val size = "$width × $height"
            if (frameRate <= 0f) return size
            val rate = NumberFormat.getInstance(locale).apply { maximumFractionDigits = 2 }.format(frameRate.toDouble())
            return "$size · $rate fps"
        }

        /** "8,2 Mbit/s" or "320 kbit/s". */
        fun bitrate(bitsPerSecond: Long, locale: Locale = Locale.getDefault()): String? {
            if (bitsPerSecond <= 0) return null
            val format = NumberFormat.getInstance(locale).apply { maximumFractionDigits = 1 }
            return if (bitsPerSecond >= 1_000_000) "${format.format(bitsPerSecond / 1_000_000.0)} Mbit/s"
            else "${bitsPerSecond / 1_000} kbit/s"
        }

        fun language(tag: String?): String? =
            tag?.takeIf { it.isNotBlank() && it != "und" && it != C.LANGUAGE_UNDETERMINED }
                ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }

        /** From the player's tracks: what is actually playing, whatever the source. */
        fun fromTracks(
            title: String,
            fileName: String?,
            durationMs: Long,
            tracks: Tracks,
            /** Added to a sound or subtitle track this device cannot decode ("not playable on this device"). */
            notPlayable: String? = null
        ): VideoInfo = fromFormats(
            title, fileName, durationMs,
            tracks.groups.map { Triple(it.type, it.getTrackFormat(0), it.isTrackSupported(0)) }, notPlayable
        )

        /** Per track: its type (C.TRACK_TYPE_*), its format and whether this device can decode it. */
        internal fun fromFormats(
            title: String,
            fileName: String?,
            durationMs: Long,
            tracks: List<Triple<Int, Format, Boolean>>,
            notPlayable: String?
        ): VideoInfo {
            var info = VideoInfo(title, fileName, durationMs)
            val audio = mutableListOf<List<String>>()
            val text = mutableListOf<List<String>>()
            for ((type, format, supported) in tracks) {
                when (type) {
                    C.TRACK_TYPE_VIDEO -> if (info.width == 0) {
                        info = info.copy(
                            width = format.width, height = format.height,
                            frameRate = format.frameRate.takeIf { it > 0f } ?: 0f,
                            hdr = format.colorInfo?.colorTransfer?.let {
                                it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG
                            } ?: false,
                            videoCodec = videoCodecName(
                                format.sampleMimeType, format.codecs, format.colorInfo?.lumaBitdepth ?: -1
                            ),
                            bitrate = maxOf(format.averageBitrate, format.bitrate, format.peakBitrate).toLong()
                        )
                    }
                    C.TRACK_TYPE_AUDIO -> audio += listOfNotNull(
                        audioCodecName(format.sampleMimeType) ?: format.sampleMimeType?.substringAfter('/')
                            ?.uppercase(),
                        channels(format.channelCount), sampleRate(format.sampleRate),
                        language(format.language) ?: format.label,
                        notPlayable?.takeIf { !supported }
                    )
                    C.TRACK_TYPE_TEXT -> text += listOfNotNull(
                        language(format.language) ?: format.label ?: format.sampleMimeType?.substringAfter('/'),
                        notPlayable?.takeIf { !supported }
                    )
                }
            }
            return info.copy(audio = audio, subtitles = text)
        }

        /**
         * The tracks of a file the way the player sees them: Media3's own extractors (the platform's MediaExtractor
         * hid the AC3 track of an MKV on the W90) and this device's decoders for "supported". A player that only
         * prepares, with every track type switched off, so no decoder starts; null after [TIMEOUT_MS] or an error.
         * Main thread (ExoPlayer's looper).
         */
        suspend fun probeTracks(context: Context, pathUri: String): Tracks? =
            kotlinx.coroutines.withTimeoutOrNull(TIMEOUT_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    val player = androidx.media3.exoplayer.ExoPlayer.Builder(context)
                        .setMediaSourceFactory(
                            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                                me.zhanghai.android.files.player.PathDataSource.Factory(context)
                            )
                        )
                        // As the player, so FFmpeg's AC3/DTS tracks count as supported.
                        .setRenderersFactory(
                            androidx.media3.exoplayer.DefaultRenderersFactory(context).setExtensionRendererMode(
                                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                            )
                        )
                        .build()
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    fun finish(tracks: Tracks?) {
                        player.release()
                        if (continuation.isActive) continuation.resumeWith(Result.success(tracks))
                    }
                    player.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            if (!tracks.isEmpty) finish(tracks)
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = finish(null)
                    })
                    continuation.invokeOnCancellation { player.release() }
                    player.setMediaItem(androidx.media3.common.MediaItem.fromUri(pathUri))
                    player.prepare()
                }
            }

        private const val TIMEOUT_MS = 5_000L

        /** From the file itself, when Media3 could not read it in time: the platform reads the container. */
        fun fromFile(video: VideoItem): VideoInfo {
            var info = VideoInfo(video.title, video.path.substringAfterLast('/'), video.durationMs,
                size = video.size, folder = video.folder)
            val audio = mutableListOf<List<String>>()
            val text = mutableListOf<List<String>>()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(video.path)
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    when {
                        mime.startsWith("video/") && info.width == 0 -> {
                            val profile = format.intOr(MediaFormat.KEY_PROFILE, -1)
                            info = info.copy(
                                width = format.intOr(MediaFormat.KEY_WIDTH, 0),
                                height = format.intOr(MediaFormat.KEY_HEIGHT, 0),
                                frameRate = format.numberOr(MediaFormat.KEY_FRAME_RATE),
                                hdr = format.intOr(MediaFormat.KEY_COLOR_TRANSFER, -1).let {
                                    it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG
                                },
                                videoCodec = videoCodecName(mime, null, if (isTenBit(mime, profile)) 10 else -1)
                            )
                        }
                        mime.startsWith("audio/") -> audio += listOfNotNull(
                            audioCodecName(mime) ?: mime.substringAfter('/').uppercase(),
                            channels(format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0)),
                            sampleRate(format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)),
                            language(format.getStringOrNull(MediaFormat.KEY_LANGUAGE))
                        )
                        mime.startsWith("text/") || mime.contains("subrip") || mime.contains("ttml") ->
                            text += listOfNotNull(
                                language(format.getStringOrNull(MediaFormat.KEY_LANGUAGE)) ?: mime.substringAfter('/')
                            )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                extractor.release()
            }
            // The whole file's rate: what a viewer compares between two copies.
            val bitrate = if (video.durationMs > 0 && video.size > 0) video.size * 8_000 / video.durationMs else 0
            return info.copy(audio = audio, subtitles = text, bitrate = bitrate, recordedMs = recordedAt(video.path))
        }

        /** The recording date a camera wrote into the file ("20261116T154320.000Z"), else the file's own date. */
        fun recordedAt(path: String): Long {
            val retriever = MediaMetadataRetriever()
            val tagged = try {
                retriever.setDataSource(path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseMetadataDate)
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
            return tagged ?: File(path).lastModified()
        }

        internal fun parseMetadataDate(value: String): Long? =
            runCatching {
                java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value.take(15))?.time
            }.getOrNull()?.takeIf { it > 0 }

        private fun isTenBit(mime: String, profile: Int): Boolean = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_VP9 -> profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mime == MediaFormat.MIMETYPE_VIDEO_AV1 &&
                profile != MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 && profile != -1
        }

        private fun MediaFormat.intOr(key: String, default: Int): Int =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

        private fun MediaFormat.numberOr(key: String): Float =
            if (!containsKey(key)) 0f
            else runCatching { getInteger(key).toFloat() }.recoverCatching { getFloat(key) }.getOrDefault(0f)

        private fun MediaFormat.getStringOrNull(key: String): String? =
            if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
    }
}

/**
 * The sheet itself, in Klang cards (file, picture, sound): label over value, a long press copies the value; side by
 * side on wide sheets. At the bottom "Signal path" and "All file details" (the file manager's properties for owner,
 * permissions and checksums).
 */
fun FragmentActivity.showVideoInfo(info: VideoInfo, path: Path?, onSignalPath: (() -> Unit)? = null) {
    val sheet = KlangSheet(this, R.drawable.information_icon_white_24dp, R.string.video_info_title)
    val grid = sheet.grid()
    fun card(icon: Int, title: Int, rows: List<Pair<Int, String?>>) {
        val shown = rows.filter { !it.second.isNullOrBlank() }
        if (shown.isEmpty()) return
        val card = sheet.card(icon, getString(title), parent = grid)
        for ((label, value) in shown) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = resources.getDimensionPixelSize(R.dimen.klang_row_min_height)
                setPaddingRelative(resources.getDimensionPixelSize(R.dimen.klang_text_edge), 0, 0, 0)
                isFocusable = true
                contentDescription = "${getString(label)}: $value"
                addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Info).apply {
                    setText(label)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Text).apply {
                    text = value
                    setTextAppearance(R.style.TextAppearance_Ordo_Klang_Body)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                setOnLongClickListener {
                    getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(getString(label), value))
                    Toast.makeText(context, R.string.video_info_copied, Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }
    }
    card(R.drawable.file_video_icon, R.string.video_more_file, listOf(
        R.string.video_info_name to info.title,
        R.string.video_info_file_name to info.fileName?.takeIf { it != info.title },
        R.string.video_info_duration to info.durationMs.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it / 1000) },
        R.string.video_info_size to info.size.takeIf { it >= 0 }?.let { android.text.format.Formatter.formatFileSize(this, it) },
        R.string.video_info_folder to info.folder,
        R.string.video_info_recorded to info.recordedMs.takeIf { it > 0 }?.let {
            DateUtils.formatDateTime(this, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_SHOW_YEAR)
        }
    ))
    card(R.drawable.image_icon_white_24dp, R.string.video_signal_picture, listOf(
        R.string.video_info_resolution to VideoInfo.resolution(info.width, info.height, info.frameRate),
        R.string.video_info_hdr to info.hdr?.let { getString(if (it) R.string.video_info_yes else R.string.video_info_no) },
        R.string.video_info_codec to info.videoCodec?.let { (name, tenBit) ->
            if (tenBit) getString(R.string.video_player_ten_bit_format, name) else name
        },
        R.string.video_info_bitrate to VideoInfo.bitrate(info.bitrate)
    ))
    card(R.drawable.audio_speaker_icon_24dp, R.string.video_signal_sound, listOf(
        R.string.video_info_audio to info.audio.joinToString("\n") { it.joinToString(" · ") }
            .ifEmpty { getString(R.string.video_info_none) },
        R.string.video_info_subtitles to info.subtitles.joinToString("\n") { it.joinToString(" · ") }
            .ifEmpty { getString(R.string.video_info_none) }
    ))
    // From the player: what happens to picture and sound on the way (viewer/video/picture).
    if (onSignalPath != null) {
        sheet.textButton(sheet.body, getString(R.string.video_info_signal_path), R.drawable.video_signal_icon_24dp) {
            sheet.dialog.dismiss()
            onSignalPath()
        }
    }
    if (path != null) {
        sheet.textButton(sheet.body, getString(R.string.video_info_all_details), R.drawable.information_icon_white_24dp) {
            sheet.dialog.dismiss()
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) { runCatching { path.loadFileItem() }.getOrNull() }
                    ?: return@launch
                if (isFinishing || supportFragmentManager.isStateSaved) return@launch
                FilePropertiesDialogFragment().putArgs(FilePropertiesDialogFragment.Args(file))
                    .show(supportFragmentManager, null)
            }
        }
    }
    sheet.show()
}
