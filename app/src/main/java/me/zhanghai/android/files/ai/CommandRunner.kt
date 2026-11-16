/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import java.io.File
import java.io.IOException

/**
 * Carries out a confirmed plan file by file and writes down exactly what happened, so undo only reverses
 * what this command did. Every file and folder is checked again right before it is touched: the preview
 * may be minutes old and another app may have changed or replaced things since.
 */
class CommandRunner(
    private val root: File,
    private val cameraNamed: Boolean,
    /** Moves a file to the trash; returns how to put it back, or null when it could not be trashed. */
    private val trash: (File) -> Trashed?,
    /** Puts a trashed file back; true on success. */
    private val untrash: (Trashed) -> Boolean
) {
    /** A file this command placed somewhere, with the size and time it had, to recognise it on undo. */
    data class Placed(val from: File, val to: File, val length: Long, val modified: Long)

    data class Trashed(val original: File, val handle: Any)

    data class Journal(
        val action: String,
        val placed: List<Placed>,
        val trashed: List<Trashed>,
        val skipped: Int,
        /** Part of [skipped]: files with another size or time than in the preview, so maybe not the same file. */
        val changed: Int = 0
    ) {
        val done: Int get() = placed.size + trashed.size
    }

    /** [seen] holds size and time of each file at preview time ([CommandFiles.snapshot]); a file not in it is skipped. */
    fun run(
        plan: CommandPlan, source: File, destination: File?, files: List<File>, kindNames: Map<String, String>,
        seen: Map<String, Pair<Long, Long>>
    ): Journal {
        val placed = mutableListOf<Placed>()
        val trashed = mutableListOf<Trashed>()
        var skipped = 0
        var changed = 0
        val sourceOk = isSafeDir(source)
        for (file in files) {
            if (!sourceOk || !isSafeFile(file, source)) {
                skipped++
                continue
            }
            // Still there is not enough: a file replaced since the preview has the same name but is another file.
            val before = seen[file.path]
            if (before == null || file.length() != before.first || !sameTime(file.lastModified(), before.second)) {
                skipped++
                changed++
                continue
            }
            val ok = when (plan.action) {
                "delete" -> trash(file)?.also { trashed += it } != null
                "move", "copy", "organize" -> {
                    val target = when (plan.action) {
                        "organize" -> File(source, CommandFiles.organizeFolder(file, plan.groupBy, kindNames))
                        else -> destination
                    }
                    target != null && place(file, target, plan.action == "copy")?.also { placed += it } != null
                }
                else -> false
            }
            if (!ok) skipped++
        }
        return Journal(plan.action, placed, trashed, skipped, changed)
    }

    /** Reverses [journal]; files that changed since (other size or time) are left alone. Returns the count. */
    fun undo(journal: Journal): Int {
        var undone = 0
        for (entry in journal.placed.asReversed()) {
            val to = entry.to
            if (!to.isFile || to.length() != entry.length || !sameTime(to.lastModified(), entry.modified)) continue
            val ok = if (journal.action == "copy") {
                trash(to) != null
            } else {
                !entry.from.exists() && to.renameTo(entry.from)
            }
            if (ok) undone++
        }
        for (entry in journal.trashed.asReversed()) {
            if (!entry.original.exists() && untrash(entry)) undone++
        }
        return undone
    }

    // Shared storage (FUSE) reports milliseconds right after a write and whole seconds later on.
    private fun sameTime(a: Long, b: Long): Boolean = kotlin.math.abs(a - b) < 2_000

    private fun place(file: File, dir: File, copy: Boolean): Placed? {
        if (!dir.isDirectory && !dir.mkdirs() || !isSafeDir(dir)) return null
        val target = File(dir, file.name)
        // Never overwrite: an existing file of the same name is skipped and stays untouched.
        if (target.exists()) return null
        val length = file.length()
        if (copy) {
            try {
                file.copyTo(target, overwrite = false)
            } catch (e: IOException) {
                target.delete()
                return null
            }
            target.setLastModified(file.lastModified())
        } else if (!file.renameTo(target)) {
            return null
        }
        return Placed(file, target, length, target.lastModified())
    }

    private fun isSafeDir(dir: File): Boolean =
        dir.isDirectory && !isLink(dir) && !CommandCheck.isProtected(dir, root, cameraNamed)

    /** A plain file, no link, still directly inside [source]. */
    private fun isSafeFile(file: File, source: File): Boolean =
        file.isFile && !isLink(file) && file.canonicalFile.parentFile == source.canonicalFile

    // The entry itself is a link when resolving it changes more than its parent folder.
    private fun isLink(file: File): Boolean {
        val parent = file.absoluteFile.parentFile ?: return false
        return file.canonicalPath != File(parent.canonicalPath, file.name).path
    }
}
