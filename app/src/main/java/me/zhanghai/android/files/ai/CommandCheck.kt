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

        /** [candidates] is non-empty when the name fits several folders and the user has to pick one. */
        data class AskFolder(
            val plan: CommandPlan,
            val forDestination: Boolean,
            val candidates: List<File> = emptyList()
        ) : Outcome

        data class Refused(val reason: Reason) : Outcome
    }

    enum class Reason { UNSUPPORTED, UNSURE_DELETE, PROTECTED_FOLDER }

    val ACTIONS = setOf("move", "copy", "delete", "organize")

    private val DELETE_WORDS = listOf(
        "lösch", "losch", "loesch", "entfern", "wirf", "schmeiß", "schmeiss", "papierkorb", "delete", "remove",
        "trash", "erase", "get rid"
    )

    private val CAMERA_WORDS = setOf("kamera", "camera", "dcim", "dcim/camera")

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
        val words = intentWords(text, plan, withoutFolders = true)
        if (plan.action == "delete" && DELETE_WORDS.none { Regex("(^|[^\\p{L}])$it").containsMatchIn(words) }) {
            return Outcome.Refused(Reason.UNSURE_DELETE)
        }
        // The camera folder only counts as chosen when the user names it as the folder, not in a file name.
        val cameraNamed = listOf(plan.source, plan.destination).any { normalize(it) in CAMERA_WORDS } &&
            CAMERA_WORDS.any { it in intentWords(text, plan, withoutFolders = false) }
        val sources = resolveAll(plan.source, here, root)
        if (sources.size > 1) {
            return Outcome.AskFolder(plan, forDestination = false, candidates = sources)
        }
        val source = sources.firstOrNull() ?: return Outcome.AskFolder(plan, forDestination = false)
        if (isProtected(source, root, cameraNamed)) {
            return Outcome.Refused(Reason.PROTECTED_FOLDER)
        }
        if (plan.action != "move" && plan.action != "copy") {
            return Outcome.Ready(plan, source, null, false)
        }
        if (normalize(plan.destination) in HERE && here == null) {
            return Outcome.AskFolder(plan, forDestination = true)
        }
        val destinations = resolveAll(plan.destination, here, root)
        if (destinations.size > 1) {
            return Outcome.AskFolder(plan, forDestination = true, candidates = destinations)
        }
        val found = destinations.firstOrNull()
        val destination = found ?: File(root, plan.destination.trim().removeFolderWord())
        if (isProtected(destination, root, cameraNamed) || destination == source) {
            return Outcome.Refused(Reason.PROTECTED_FOLDER)
        }
        return Outcome.Ready(plan, source, destination, found == null)
    }

    /** An existing folder for a spoken name, or null. "." means [here]. */
    fun resolve(name: String, here: File?, root: File): File? = resolveAll(name, here, root).firstOrNull()

    /** Every folder of that name on the shallowest level that has one; more than one means ambiguous. */
    fun resolveAll(name: String, here: File?, root: File): List<File> {
        val key = normalize(name)
        if (key in HERE) {
            return listOfNotNull(here)
        }
        ALIASES[key]?.let { alias -> File(root, alias).takeIf { it.isDirectory }?.let { return listOf(it) } }
        val clean = name.trim().removeFolderWord()
        if (clean.startsWith("/")) {
            return listOfNotNull(
                File(clean).canonicalFile.takeIf { it.isDirectory && it.path.startsWith(root.canonicalPath + "/") }
            )
        }
        // Breadth first, three levels deep, so nested folders such as "Projekte/2026/Fertig" are found.
        var level = listOf(root)
        repeat(3) {
            val matches = level.flatMap { dir -> dir.childDirs().filter { it.name.equals(clean, ignoreCase = true) } }
            if (matches.isNotEmpty()) return matches
            level = level.flatMap { it.childDirs() }
        }
        return emptyList()
    }

    /** The command without the names the model copied out of it (file name part, folders, endings). */
    private fun intentWords(text: String, plan: CommandPlan, withoutFolders: Boolean): String {
        var words = text.lowercase(Locale.ROOT).replace(Regex("\\S+\\.[\\p{L}\\d]{1,5}\\b"), " ")
        val folders = if (withoutFolders) listOf(plan.source, plan.destination) else emptyList()
        (listOf(plan.nameContains) + folders + plan.extensions)
            .map { it.trim().lowercase(Locale.ROOT) }.filter { it.length > 1 }
            .forEach { words = words.replace(it, " ") }
        return words
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
