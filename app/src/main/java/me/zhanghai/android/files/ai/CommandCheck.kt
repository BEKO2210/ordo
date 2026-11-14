/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import java.io.File
import java.util.Locale

/**
 * Turns a model plan into something the app may show. The model is never trusted: delete needs a delete
 * word in the sentence, folders must exist (or are asked for), and protected folders stay untouched.
 */
object CommandCheck {
    sealed interface Outcome {
        /** [destination] is null for delete and organize; [newDestination] means it gets created. */
        data class Ready(
            val plan: CommandPlan,
            val source: File,
            val destination: File?,
            val newDestination: Boolean
        ) : Outcome

        data class AskFolder(val plan: CommandPlan, val forDestination: Boolean) : Outcome

        data class Refused(val reason: Reason) : Outcome
    }

    enum class Reason { UNSUPPORTED, UNSURE_DELETE, PROTECTED_FOLDER }

    val ACTIONS = setOf("move", "copy", "delete", "organize")

    private val DELETE_WORDS = listOf(
        "lösch", "losch", "entfern", "wirf", "schmeiß", "schmeiss", "papierkorb", "delete", "remove",
        "trash", "erase", "get rid"
    )

    private val CAMERA_WORDS = listOf("kamera", "camera", "dcim")

    private val HERE = setOf("", ".", "hier", "here", "this folder", "diesen ordner", "diesem ordner",
        "current folder")

    // Spoken name -> real top-level folder on shared storage.
    private val ALIASES = mapOf(
        "download" to "Download", "downloads" to "Download",
        "musik" to "Music", "music" to "Music",
        "dokumente" to "Documents", "documents" to "Documents",
        "bilder" to "Pictures", "pictures" to "Pictures",
        "filme" to "Movies", "movies" to "Movies",
        "kamera" to "DCIM/Camera", "camera" to "DCIM/Camera", "dcim" to "DCIM"
    )

    fun check(text: String, plan: CommandPlan, here: File?, root: File): Outcome {
        if (plan.action !in ACTIONS) {
            return Outcome.Refused(Reason.UNSUPPORTED)
        }
        val lower = text.lowercase(Locale.ROOT)
        if (plan.action == "delete" && DELETE_WORDS.none { it in lower }) {
            return Outcome.Refused(Reason.UNSURE_DELETE)
        }
        val source = resolve(plan.source, here, root)
            ?: return Outcome.AskFolder(plan, forDestination = false)
        val cameraNamed = CAMERA_WORDS.any { it in lower }
        if (isProtected(source, root, cameraNamed)) {
            return Outcome.Refused(Reason.PROTECTED_FOLDER)
        }
        if (plan.action != "move" && plan.action != "copy") {
            return Outcome.Ready(plan, source, null, false)
        }
        if (normalize(plan.destination) in HERE && here == null) {
            return Outcome.AskFolder(plan, forDestination = true)
        }
        val found = resolve(plan.destination, here, root)
        val destination = found ?: File(root, plan.destination.trim().removeFolderWord())
        if (isProtected(destination, root, cameraNamed) || destination == source) {
            return Outcome.Refused(Reason.PROTECTED_FOLDER)
        }
        return Outcome.Ready(plan, source, destination, found == null)
    }

    /** An existing folder for a spoken name, or null. "." means [here]. */
    fun resolve(name: String, here: File?, root: File): File? {
        val key = normalize(name)
        if (key in HERE) {
            return here
        }
        ALIASES[key]?.let { alias -> File(root, alias).takeIf { it.isDirectory }?.let { return it } }
        val clean = name.trim().removeFolderWord()
        if (clean.startsWith("/")) {
            return File(clean).canonicalFile.takeIf { it.isDirectory && it.path.startsWith(root.canonicalPath + "/") }
        }
        // Breadth first, three levels deep, so nested folders such as "Projekte/2026/Fertig" are found.
        var level = listOf(root)
        repeat(3) {
            level.firstNotNullOfOrNull { dir ->
                dir.childDirs().firstOrNull { it.name.equals(clean, ignoreCase = true) }
            }?.let { return it }
            level = level.flatMap { it.childDirs() }
        }
        return null
    }

    fun isProtected(dir: File, root: File, cameraNamed: Boolean): Boolean {
        // Canonical paths, so "Music/../Android" or a symlink cannot slip past the checks below.
        val path = dir.canonicalPath
        val rootPath = root.canonicalPath
        if (path != rootPath && !path.startsWith("$rootPath/")) {
            return true
        }
        // Shared storage ignores case, so "android/data" is the same folder as "Android/data".
        val parts = path.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
        return parts.any { it.startsWith(".") } ||
            parts.firstOrNull() == "android" ||
            (!cameraNamed && parts.take(2) == listOf("dcim", "camera"))
    }

    private fun File.childDirs(): List<File> =
        listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
            ?.sortedBy { it.name } ?: emptyList()

    private fun normalize(name: String): String =
        name.trim().removeFolderWord().lowercase(Locale.ROOT)

    private fun String.removeFolderWord(): String =
        replace(Regex("^(den |dem |the )?(ordner|folder) ", RegexOption.IGNORE_CASE), "").trim()
}
