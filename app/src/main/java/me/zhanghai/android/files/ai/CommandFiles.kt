/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Finds the files a checked plan touches. Only the top level of the source folder, hidden files never. */
object CommandFiles {
    val KINDS = mapOf(
        "audio" to setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mid"),
        "video" to setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "wmv"),
        "image" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "svg"),
        "document" to setOf("pdf", "doc", "docx", "odt", "txt", "rtf", "xls", "xlsx", "ods", "ppt",
            "pptx", "odp", "csv", "md", "epub"),
        "archive" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    )

    fun collect(plan: CommandPlan, source: File, now: Long = System.currentTimeMillis()): List<File> {
        // A named ending is more exact than a kind: "MP3s" must not take every audio file.
        val extensions = plan.cleanExtensions.toSet().ifEmpty { plan.kinds.flatMap { KINDS[it] ?: emptySet() }.toSet() }
        // A filter that was asked for but means nothing selects nothing, never everything.
        if (extensions.isEmpty() && (plan.extensions.isNotEmpty() || plan.kinds.isNotEmpty())) {
            return emptyList()
        }
        val ageLimit = now - plan.olderThanDays * 86_400_000L
        return (source.listFiles() ?: return emptyList()).filter { file ->
            file.isFile && !file.name.startsWith(".") &&
                (extensions.isEmpty() || file.extension.lowercase(Locale.ROOT) in extensions) &&
                (plan.nameContains.isBlank() || file.name.contains(plan.nameContains.trim(), true)) &&
                (plan.olderThanDays == 0 || file.lastModified() < ageLimit) &&
                (plan.largerThanBytes == 0L || file.length() > plan.largerThanBytes)
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** Subfolder name for "organize": the kind ([kindNames] holds the shown names) or the month. */
    fun organizeFolder(file: File, groupBy: String, kindNames: Map<String, String>): String {
        if (groupBy == "month") {
            return SimpleDateFormat("yyyy-MM", Locale.ROOT).format(Date(file.lastModified()))
        }
        val extension = file.extension.lowercase(Locale.ROOT)
        val kind = KINDS.entries.firstOrNull { extension in it.value }?.key ?: "other"
        return kindNames[kind] ?: kind
    }
}
