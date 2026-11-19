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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
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
        fun fromTracks(title: String, fileName: String?, durationMs: Long, tracks: Tracks): VideoInfo {
            var info = VideoInfo(title, fileName, durationMs)
            val audio = mutableListOf<List<String>>()
            val text = mutableListOf<List<String>>()
            for (group in tracks.groups) {
                val format = group.getTrackFormat(0)
                when (group.type) {
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
                        channels(format.channelCount), language(format.language) ?: format.label
                    )
                    C.TRACK_TYPE_TEXT -> text += listOfNotNull(
                        language(format.language) ?: format.label ?: format.sampleMimeType?.substringAfter('/')
                    )
                }
            }
            return info.copy(audio = audio, subtitles = text)
        }

        /** From the file itself, for a card in the library (local files; the platform reads the container). */
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
 * The sheet itself: label over value, a long press copies the value; at the bottom "All file details" opens the file
 * manager's properties for whoever wants owner, permissions and checksums.
 */
fun FragmentActivity.showVideoInfo(info: VideoInfo, path: Path?) {
    val dialog = BottomSheetDialog(this)
    val padding = resources.getDimensionPixelSize(R.dimen.phi_21)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }
    content.addView(TextView(this).apply {
        setText(R.string.video_info_title)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
        ViewCompat.setAccessibilityHeading(this, true)
    })
    fun row(label: Int, value: String?) {
        if (value.isNullOrBlank()) return
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.phi_55)
            setPadding(0, padding / 2, 0, 0)
            isFocusable = true
            contentDescription = "${getString(label)}: $value"
            addView(TextView(context).apply {
                setText(label)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(TextView(context).apply {
                text = value
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnLongClickListener {
                getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(getString(label), value))
                Toast.makeText(context, R.string.video_info_copied, Toast.LENGTH_SHORT).show()
                true
            }
        })
    }
    row(R.string.video_info_name, info.title)
    row(R.string.video_info_file_name, info.fileName?.takeIf { it != info.title })
    row(R.string.video_info_duration, info.durationMs.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it / 1000) })
    row(R.string.video_info_resolution, VideoInfo.resolution(info.width, info.height, info.frameRate))
    row(R.string.video_info_hdr, info.hdr?.let {
        getString(if (it) R.string.video_info_yes else R.string.video_info_no)
    })
    row(R.string.video_info_codec, info.videoCodec?.let { (name, tenBit) ->
        if (tenBit) getString(R.string.video_player_ten_bit_format, name) else name
    })
    row(R.string.video_info_bitrate, VideoInfo.bitrate(info.bitrate))
    row(R.string.video_info_audio, info.audio.joinToString("\n") { it.joinToString(" · ") }.ifEmpty {
        getString(R.string.video_info_none)
    })
    row(R.string.video_info_subtitles, info.subtitles.joinToString("\n") { it.joinToString(" · ") }.ifEmpty {
        getString(R.string.video_info_none)
    })
    row(R.string.video_info_size, info.size.takeIf { it >= 0 }?.let {
        android.text.format.Formatter.formatFileSize(this, it)
    })
    row(R.string.video_info_folder, info.folder)
    row(R.string.video_info_recorded, info.recordedMs.takeIf { it > 0 }?.let {
        DateUtils.formatDateTime(this, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_SHOW_YEAR)
    })
    if (path != null) {
        content.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.video_info_all_details)
            isAllCaps = false
            minHeight = resources.getDimensionPixelSize(R.dimen.phi_55)
            setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    val file = withContext(Dispatchers.IO) { runCatching { path.loadFileItem() }.getOrNull() }
                        ?: return@launch
                    if (isFinishing || supportFragmentManager.isStateSaved) return@launch
                    FilePropertiesDialogFragment().putArgs(FilePropertiesDialogFragment.Args(file))
                        .show(supportFragmentManager, null)
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = padding / 2 })
    }
    dialog.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(content) })
    dialog.showExpanded()
}

/**
 * Opens a sheet fully and readable everywhere: expanded at once (no peek, the W90 in landscape showed only 60 px
 * under the navigation bar), at most 90 % of the window high with its own scrolling, above the navigation bar, and
 * on wide windows centred at the Klang sheet's width.
 */
internal fun BottomSheetDialog.showExpanded() {
    setOnShowListener {
        val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
        val metrics = context.resources.displayMetrics
        val width = minOf(metrics.widthPixels, context.resources.getDimensionPixelSize(R.dimen.klang_sheet_max_width))
        sheet.layoutParams = sheet.layoutParams.apply { this.width = width }
        (sheet.layoutParams as? android.widget.FrameLayout.LayoutParams)?.gravity =
            android.view.Gravity.CENTER_HORIZONTAL
        behavior.maxWidth = width
        behavior.maxHeight = (metrics.heightPixels * 0.9f).toInt()
        behavior.skipCollapsed = true
        behavior.isFitToContents = true
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        // The last row and the button stay above the navigation bar (gesture strip or three buttons).
        val content = (sheet as? android.view.ViewGroup)?.getChildAt(0) ?: return@setOnShowListener
        val basePadding = content.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(content)
    }
    show()
}
