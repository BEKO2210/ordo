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
        // "Papierkorb"/"trash" alone is not enough: "open the trash" must never authorise a delete.
        "lösch", "losch", "loesch", "entfern", "wirf", "schmeiß", "schmeiss", "delete", "remove", "erase",
        "get rid"
    )

    // "doppelt heruntergeladen", "Duplikate", "duplicates": the selection is narrowed to extra copies only.
    private val DUPLICATE_WORDS = listOf("doppel", "duplikat", "duplicat", "dupe", "mehrfach")

    /** A delete of more files than this needs a second confirmation. */
    const val MANY_FILES = 50

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

    fun check(text: String, model: CommandPlan, here: File?, root: File): Outcome {
        if (model.action !in ACTIONS) {
            return Outcome.Refused(Reason.UNSUPPORTED)
        }
        val words = intentWords(text, model, withoutFolders = true)
        // The plan schema has no field for duplicates, so the model answers with the whole folder; the app narrows it.
        val plan = model.copy(duplicates = said(words, DUPLICATE_WORDS))
        if (plan.action == "delete" && !said(words, DELETE_WORDS)) {
            return Outcome.Refused(Reason.UNSURE_DELETE)
        }
        // The camera folder only counts as chosen when the user names it as the folder, not in a file name
        // and not to exclude it ("nicht die Kamera").
        val cameraNamed = listOf(plan.source, plan.destination).any { normalize(it) in CAMERA_WORDS } &&
            said(intentWords(text, plan, withoutFolders = false), CAMERA_WORDS.toList())
        // On Android a backslash is an ordinary character; a folder name with one is never what was meant.
        if ('\\' in plan.source || '\\' in plan.destination) {
            return Outcome.Refused(Reason.PROTECTED_FOLDER)
        }
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
        if (found == null && plan.destination.trim().startsWith("/")) {
            // An absolute path outside shared storage is never turned into a new folder of that name.
            return Outcome.Refused(Reason.PROTECTED_FOLDER)
        }
        val destination = (found ?: File(root, plan.destination.trim().removeFolderWord())).canonicalFile
        if (isProtected(destination, root, cameraNamed) || destination == source.canonicalFile) {
            return Outcome.Refused(Reason.PROTECTED_FOLDER)
        }
        return Outcome.Ready(plan, source, destination, found == null)
    }

    /**
     * True for a delete that sweeps a whole top-level folder (or the storage itself) without a filter, or more
     * than [MANY_FILES] files: the user confirms it a second time with count and size.
     */
    fun needsSecondConfirm(plan: CommandPlan, source: File, root: File, count: Int): Boolean {
        if (plan.action != "delete") return false
        val unfiltered = plan.extensions.isEmpty() && plan.kinds.isEmpty() && plan.nameContains.isBlank() &&
            plan.olderThanDays == 0 && plan.largerThanBytes == 0L && !plan.duplicates
        val dir = source.canonicalFile
        val top = dir == root.canonicalFile || dir.parentFile == root.canonicalFile
        return count > MANY_FILES || unfiltered && top
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
            val matches = level.flatMap { dir -> dir.childDirs().filter { sameName(it.name, clean) } }
            if (matches.isNotEmpty()) return matches
            level = level.flatMap { it.childDirs() }
        }
        return emptyList()
    }

    private val NEGATIONS = setOf(
        "nicht", "nichts", "kein", "keine", "keinen", "keiner", "ohne", "außer", "ausser",
        "not", "no", "don't", "dont", "never", "except", "without"
    )

    /**
     * True when one of [stems] starts a word with no negation among the three words before or five after it,
     * within the same clause ("nicht löschen", "lösche die Dateien nicht"; but "lösche X, nicht Y" is fine).
     */
    private fun said(words: String, stems: List<String>): Boolean =
        words.split(',', ';', '.', '!', '?').any { clause ->
            val tokens = clause.split(Regex("[^\\p{L}'/]+")).filter { it.isNotEmpty() }
            tokens.indices.any { i ->
                stems.any { stem -> tokens[i].startsWith(stem) || (stem.contains(' ') && clause.contains(stem)) } &&
                    tokens.subList((i - 3).coerceAtLeast(0), (i + 6).coerceAtMost(tokens.size))
                        .none { it in NEGATIONS }
            }
        }

    // Composed and decomposed umlauts ("é" as one or two characters) count as the same name.
    private fun sameName(a: String, b: String): Boolean =
        java.text.Normalizer.normalize(a, java.text.Normalizer.Form.NFC)
            .equals(java.text.Normalizer.normalize(b, java.text.Normalizer.Form.NFC), ignoreCase = true)

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
        // Backslashes and control characters are legal in Android names but never in a folder someone means.
        return parts.any { it.startsWith(".") || '\\' in it || it.any { c -> c < ' ' } } ||
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
