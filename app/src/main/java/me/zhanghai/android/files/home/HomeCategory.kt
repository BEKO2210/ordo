/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import me.zhanghai.android.files.R
import java.util.Locale

enum class HomeCategory(
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    @ColorRes val colorRes: Int
) {
    IMAGES(R.string.home_category_images, R.drawable.image_icon_white_24dp, R.color.home_images),
    VIDEOS(R.string.home_category_videos, R.drawable.video_icon_white_24dp, R.color.home_videos),
    AUDIO(R.string.home_category_audio, R.drawable.audio_icon_white_24dp, R.color.home_audio),
    DOCUMENTS(R.string.home_category_documents, R.drawable.document_icon_white_24dp, R.color.home_documents),
    DOWNLOADS(R.string.home_category_downloads, R.drawable.download_icon_white_24dp, R.color.home_downloads),
    APKS(R.string.home_category_apks, R.drawable.android_icon_white_24dp, R.color.home_apks),
    ARCHIVES(R.string.home_category_archives, R.drawable.archive_icon_white_24dp, R.color.home_archives);

    companion object {
        private val documentExtensions = setOf(
            "doc", "docx", "epub", "htm", "html", "md", "odf", "odg", "odp", "ods",
            "odt", "pages", "pdf", "ppt", "pptx", "rtf", "tex", "txt", "xls", "xlsx"
        )
        private val archiveExtensions = setOf(
            "7z", "bz", "bz2", "cab", "cpio", "gz", "jar", "lz", "lzma", "rar", "tar",
            "tbz", "tbz2", "tgz", "txz", "xz", "zip", "zst"
        )

        // A file in Download/ counts for Downloads and for its type, like Samsung My Files.
        fun classify(mimeType: String?, fileName: String, relativePath: String?): Set<HomeCategory> {
            val normalizedPath = relativePath.orEmpty().replace('\\', '/').lowercase(Locale.ROOT)
            val isDownload = normalizedPath == "download" || normalizedPath.startsWith("download/")
            val type = classifyType(mimeType, fileName)
            return setOfNotNull(type, if (isDownload) DOWNLOADS else null)
        }

        private fun classifyType(mimeType: String?, fileName: String): HomeCategory? {
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (mimeType == "application/vnd.android.package-archive" || extension == "apk") {
                return APKS
            }
            if (extension in archiveExtensions || mimeType.orEmpty().let {
                    it == "application/zip" || it == "application/x-7z-compressed" ||
                        it == "application/vnd.rar" || it == "application/x-rar-compressed" ||
                        it == "application/x-tar" || it == "application/gzip"
                }) {
                return ARCHIVES
            }
            return when {
                mimeType?.startsWith("image/") == true -> IMAGES
                mimeType?.startsWith("video/") == true -> VIDEOS
                mimeType?.startsWith("audio/") == true -> AUDIO
                mimeType?.startsWith("text/") == true || extension in documentExtensions -> DOCUMENTS
                else -> null
            }
        }
    }
}
