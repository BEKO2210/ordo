/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import me.zhanghai.android.files.ai.CommandFiles
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

/**
 * The numbers behind the analysis dashboard. Everything here is computed from the scan; the charts only draw it
 * and the AI only ever picks from [suggestions] by id, so no number on screen is made up.
 */
class StorageInsights(
    /** Bytes per kind, in the fixed chart order; [KIND_SYSTEM] is what the app cannot read itself. */
    val usage: List<Pair<String, Long>>,
    val total: Long,
    val free: Long,
    /** Bytes per cleanup type; [SAFE] types first. A file can be in several lists. */
    val cleanup: List<Pair<String, Long>>,
    /** What the [SAFE] types free together, each file counted once. Large and old files are only worth a look. */
    val cleanupTotal: Long,
    /** New bytes per month for the last 12 months, oldest first, with "yyyy-MM" keys. */
    val months: List<Pair<String, Long>>,
    val suggestions: List<Suggestion>,
    /** How many files the scan read. */
    val fileCount: Int = 0,
    /** The folders that hold the space, for the map (see [AnalysisChart.tiles]). */
    val tiles: List<AnalysisChart.Tile> = emptyList()
) {
    val used: Long get() = total - free

    /** The user's own files: everything the scan could read. */
    val own: Long get() = usage.filter { it.first != KIND_SYSTEM }.sumOf { it.second }

    /** A ready-made cleanup the user can preview; [folder] is relative to the storage root. */
    data class Suggestion(val id: String, val folder: String, val olderThanDays: Int, val extensions: List<String>,
                          val bytes: Long, val count: Int)

    companion object {
        // Fixed order, never by size: colour follows the kind (validated palette, see chart_colors.xml).
        val KINDS = listOf("image", "audio", "document", "video", "apps", "other")
        const val KIND_SYSTEM = "system"
        /** Safe to free: copies (one stays), installers and the trash. Large and old files need a look first. */
        val SAFE = listOf("duplicates", "apks", "trash")
        val CLEANUP = SAFE + listOf("large", "old")

        /**
         * [part] of [whole] as a percentage that never lies by rounding: "<0.1 %", "0.7 %", "57 %", ">99 %".
         * Small shares keep one decimal, so 0.7 % is not shown as 1 %.
         */
        fun share(part: Long, whole: Long, locale: Locale = Locale.getDefault()): String {
            val format = NumberFormat.getPercentInstance(locale)
            if (part <= 0 || whole <= 0) return format.format(0)
            if (part >= whole) return format.format(1)
            val ratio = part.toDouble() / whole
            return when {
                ratio < 0.001 -> "<" + format.apply { maximumFractionDigits = 1 }.format(0.001)
                ratio < 0.0995 -> format.apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(ratio)
                ratio >= 0.995 -> ">" + format.format(0.99)
                else -> format.format(ratio)
            }
        }

        /** Top-level folders whose name says little to most people: "Android" holds app files, dot folders are hidden. */
        fun folderKind(name: String): String? = when {
            name == "Android" -> "apps"
            name.startsWith(".") -> "hidden"
            else -> null
        }

        fun kindOf(path: String): String {
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension == "apk" || extension == "apks" || extension == "xapk") return "apps"
            return CommandFiles.KINDS.entries.firstOrNull { extension in it.value }?.key
                ?.takeIf { it in KINDS } ?: "other"
        }

        fun compute(
            root: String, files: List<ScannedFile>, result: AnalysisResult, total: Long, free: Long,
            trashBytes: Long, now: Long
        ): StorageInsights {
            val byKind = files.groupBy { kindOf(it.path) }.mapValues { (_, list) -> list.sumOf { it.size } }
            val scanned = files.sumOf { it.size }
            val system = (total - free - scanned).coerceAtLeast(0)
            val usage = KINDS.map { it to (byKind[it] ?: 0L) } + (KIND_SYSTEM to system)

            val duplicates = StorageAnalysis.allButNewest(result.duplicates)
            val groups = mapOf("duplicates" to duplicates, "large" to result.large, "old" to result.old,
                "apks" to result.apks)
            val cleanup = CLEANUP.map { type ->
                type to if (type == "trash") trashBytes else groups.getValue(type).sumOf { it.size }
            }
            // Only what is safe to free counts, and a duplicate APK only once.
            val distinct = (duplicates + result.apks).distinctBy { it.path }.sumOf { it.size }

            return StorageInsights(usage, total, free, cleanup, distinct + trashBytes, months(files, now),
                suggestions(root, files, now), files.size, AnalysisChart.tiles(root, files))
        }

        private fun months(files: List<ScannedFile>, now: Long): List<Pair<String, Long>> {
            val calendar = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1) }
            val keys = (11 downTo 0).map { back ->
                val c = calendar.clone() as Calendar
                c.add(Calendar.MONTH, -back)
                String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
            }
            val sums = HashMap<String, Long>()
            val c = Calendar.getInstance()
            for (file in files) {
                if (file.lastModified <= 0) continue
                c.timeInMillis = file.lastModified
                val key = String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
                sums[key] = (sums[key] ?: 0L) + file.size
            }
            return keys.map { it to (sums[it] ?: 0L) }
        }

        // Cleanups that a command plan can express exactly: a folder, an age and optionally endings.
        private fun suggestions(root: String, files: List<ScannedFile>, now: Long): List<Suggestion> {
            val download = "$root/Download/"
            val inDownload = files.filter { it.path.startsWith(download) && !it.path.removePrefix(download).contains('/') && !it.isHidden }
            val halfYear = now - 180L * 86_400_000L
            val oldDownloads = inDownload.filter { it.lastModified in 1 until halfYear }
            // Only ".apk": that is the ending the suggested plan uses, so the preview shows the same files.
            val apks = inDownload.filter { it.path.endsWith(".apk", ignoreCase = true) }
            return listOfNotNull(
                Suggestion("old_downloads", "Download", 180, emptyList(), oldDownloads.sumOf { it.size },
                    oldDownloads.size).takeIf { it.count > 0 },
                Suggestion("download_apks", "Download", 0, listOf("apk"), apks.sumOf { it.size }, apks.size)
                    .takeIf { it.count > 0 }
            ).sortedByDescending { it.bytes }
        }
    }
}
