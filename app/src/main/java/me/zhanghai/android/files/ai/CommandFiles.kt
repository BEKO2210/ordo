/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import me.zhanghai.android.files.analysis.ScannedFile
import me.zhanghai.android.files.analysis.StorageAnalysis
import me.zhanghai.android.files.filelist.ContentSearch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finds the files a checked plan touches: for a change only the top level of the source folder, for "show" every
 * folder below it. Hidden files never.
 */
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
        val filter = Filter.of(plan, now) ?: return emptyList()
        val files = (source.listFiles() ?: return emptyList()).filter { file ->
            file.isFile && !file.name.startsWith(".") && filter.matches(file)
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        return ordered(if (plan.duplicates) extraCopies(files) else files, plan, byName = true)
    }

    /**
     * The files a "show" plan lists: every folder below [sources], hidden ones and Android/data never, Android/media
     * (WhatsApp) yes. Read-only: nothing here changes a file.
     */
    /** What [show] found; [capped] when it stopped early (too many results or too much text to read). */
    class Found(val files: List<File>, val capped: Boolean)

    /**
     * [active] is asked between files, so a closed sheet or a newer load stops the walk; [stopAfter] ends it after
     * that many matches (1 to see whether anything matches at all). Sources and folders that resolve outside [root]
     * (a link, "..") are never read.
     */
    fun show(
        plan: CommandPlan, sources: List<File>, root: File, now: Long = System.currentTimeMillis(),
        active: () -> Boolean = { !Thread.currentThread().isInterrupted }, stopAfter: Int = MAX_RESULTS
    ): Found {
        val filter = Filter.of(plan, now) ?: return Found(emptyList(), false)
        val found = LinkedHashMap<String, File>()
        val visited = HashSet<String>()
        val rootPath = root.canonicalPath
        var stopped = false
        fun walk(dir: File) {
            // canonical paths, so a link or two overlapping sources never visit a folder twice or leave the storage
            val path = runCatching { dir.canonicalPath }.getOrNull() ?: return
            if (path != rootPath && !path.startsWith("$rootPath/") || !visited.add(path)) return
            val parts = path.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }
            if (parts.firstOrNull() == "Android" && parts.size >= 2 && parts[1] != "media") return
            for (child in dir.listFiles() ?: return) {
                if (stopped || !active()) return
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile &&
                    // inside the storage first: a link to a file outside is never opened, not even to read its text
                    runCatching { child.canonicalPath.startsWith("$rootPath/") }.getOrDefault(false) &&
                    filter.matches(child)) {
                    found[child.path] = child
                    if (found.size >= stopAfter) stopped = true
                }
            }
        }
        sources.forEach { walk(it) }
        return Found(ordered(found.values.toList(), plan, byName = false), stopped && stopAfter == MAX_RESULTS ||
            filter.textCapped)
    }

    /** Sorted as asked (largest or newest first; else by name, or newest for a list to look at) and cut to the limit. */
    private fun ordered(files: List<File>, plan: CommandPlan, byName: Boolean): List<File> {
        val sorted = when {
            plan.sort == "size" -> files.sortedByDescending { it.length() }
            plan.sort == "smallest" -> files.sortedBy { it.length() }
            plan.sort == "oldest" -> files.sortedBy { it.lastModified() }
            plan.sort == "newest" || !byName -> files.sortedByDescending { it.lastModified() }
            else -> files
        }
        return if (plan.limit > 0) sorted.take(plan.limit) else sorted
    }

    /** Size and time of each file when the preview was made, to recognise a file replaced since. */
    fun snapshot(files: List<File>): Map<String, Pair<Long, Long>> =
        files.associate { it.path to (it.length() to it.lastModified()) }

    /** The plan's filters: ending or kind, name, word, age, size. */
    internal class Filter(
        private val plan: CommandPlan, private val extensions: Set<String>, private val excluded: Set<String>,
        private val from: Long, private val to: Long, now: Long
    ) {
        private val olderLimit = now - plan.olderThanDays * DAY
        private val newerLimit = now - plan.newerThanDays * DAY
        private val midnight = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        private val word = plan.contentContains.trim()
        private val nameTerms = plan.nameTerms.map { CommandNet.fold(it) }
        private val textTerms = plan.textTerms.map { CommandNet.fold(it) to it }
        private val excludeTerms = plan.excludeTerms.filter { it.isNotBlank() }.map { CommandNet.fold(it.trim()) }
        /**
         * Every word this plan looks for inside files, with its test on the file's bytes. The net's words are folded
         * stems ("steur" for "Steuer"), so the text is folded too (phase0.c_filters); the shipped word as before.
         */
        val searches: Map<String, (FileText) -> Boolean> = LinkedHashMap<String, (FileText) -> Boolean>().also { m ->
            plan.textTerms.forEach { term ->
                val folded = CommandNet.fold(term)
                m[term] = { text -> folded in text.folded }
            }
            if (word.isNotEmpty()) ContentSearch(word, false, true).let { s -> m[word] = { text -> s.contentMatches(text.stream()) } }
        }

        /** The file is read (and folded) at most once, however many words the plan looks for; the budget once per file. */
        fun matches(file: File): Boolean {
            var read = false
            var text: FileText? = null
            return matches(file.name, file.length(), file.lastModified()) { w ->
                if (!read) {
                    read = true
                    text = readText(file)
                }
                text?.let { searches.getValue(w)(it) } ?: false
            }
        }

        /** [inText] answers whether the file's text holds a word of [searches] (only called when the name does not). */
        fun matches(name: String, length: Long, modified: Long, inText: (String) -> Boolean): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return (extensions.isEmpty() || extension in extensions) &&
                (plan.nameContains.isBlank() || name.contains(plan.nameContains.trim(), true)) &&
                (plan.olderThanDays == 0 || modified < olderLimit) &&
                (plan.newerThanDays == 0 || modified >= newerLimit) &&
                (!plan.today || modified >= midnight) &&
                (plan.largerThanBytes == 0L || length > plan.largerThanBytes) &&
                (word.isEmpty() || name.contains(word, true) || inText(word)) &&
                extension !in excluded &&
                // ä = a, ß = ss, as the lab matched (phase0.c_filters); only the net designs set terms
                (excludeTerms.isEmpty() || CommandNet.fold(name).let { n -> excludeTerms.none { it in n } }) &&
                (nameTerms.isEmpty() || CommandNet.fold(name).let { n -> nameTerms.all { it in n } }) &&
                (textTerms.isEmpty() || CommandNet.fold(name).let { n -> textTerms.all { (term, raw) -> term in n || inText(raw) } }) &&
                (from == 0L || modified >= from) && (to == 0L || modified < to) &&
                (plan.minBytes == 0L || length >= plan.minBytes) &&
                (plan.maxBytes == 0L || length <= plan.maxBytes)
        }

        // Only plain text is read: PDF and Office files need a text extraction Ordo does not have yet, so for
        // them the word is found in the name only (docs/KI-ZEIGEN-PHASE0B.md, 6.5).
        /** True once [MAX_TEXT_TOTAL] was read: later text files were matched by name only. */
        var textCapped = false
        private var textLeft = MAX_TEXT_TOTAL

        private fun readText(file: File): FileText? {
            if (file.extension.lowercase(Locale.ROOT) !in TEXT_EXTENSIONS || file.length() > MAX_TEXT_SIZE) return null
            if (textLeft < file.length()) {
                textCapped = true
                return null
            }
            textLeft -= file.length()
            textReads.incrementAndGet()
            return try {
                FileText(file.readBytes())
            } catch (e: java.io.IOException) {
                null
            }
        }

        companion object {
            /** Null when a filter was asked for but means nothing: that selects nothing, never everything. */
            fun of(plan: CommandPlan, now: Long): Filter? {
                // A named ending is more exact than a kind: "MP3s" must not take every audio file.
                val extensions = plan.cleanExtensions.toSet()
                    .ifEmpty { plan.kinds.flatMap { KINDS[it] ?: emptySet() }.toSet() }
                if (extensions.isEmpty() && (plan.extensions.isNotEmpty() || plan.kinds.isNotEmpty())) return null
                val excluded = plan.excludeExtensions.map { it.trim().trimStart('.').lowercase(Locale.ROOT) }.toSet() +
                    plan.excludeKinds.flatMap { KINDS[it] ?: emptySet() }
                // A date that cannot be read selects nothing, like a kind that means nothing.
                val from = if (plan.dateFrom.isBlank()) 0L else isoTime(plan.dateFrom) ?: return null
                val to = if (plan.dateTo.isBlank()) 0L else isoTime(plan.dateTo) ?: return null
                return Filter(plan, extensions, excluded, from, to, now)
            }

            /** Local time of "2026-11-01" (midnight) or "2026-11-01T14:00", or null. */
            fun isoTime(value: String): Long? = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd").firstNotNullOfOrNull {
                val format = SimpleDateFormat(it, Locale.ROOT).apply { isLenient = false }
                val position = java.text.ParsePosition(0)
                format.parse(value.trim(), position)?.takeIf { position.index == value.trim().length }?.time
            }
        }
    }

    /** A file's text, read once; folded (ä = a) only when a net word asks, and then only once. */
    class FileText(private val bytes: ByteArray) {
        val folded: String by lazy(LazyThreadSafetyMode.NONE) { CommandNet.fold(bytes.toString(Charsets.UTF_8)) }
        fun stream(): java.io.InputStream = bytes.inputStream()
    }

    /** Every file opened to read its text, counted for CommandShowTest. */
    internal val textReads = java.util.concurrent.atomic.AtomicInteger()

    /** Endings whose text Ordo can search today. */
    val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "log", "json", "xml", "html", "htm", "rtf", "ics", "vcf")
    internal const val MAX_TEXT_SIZE = 20L * 1024 * 1024
    // A list to scroll through, not an export: 2,000 rows load in seconds; past that the list says it stopped early.
    const val MAX_RESULTS = 2_000
    // Text read per search: enough for every note and document of a normal phone, small enough that a word search
    // over the whole storage cannot read gigabytes of logs.
    internal const val MAX_TEXT_TOTAL = 200L * 1024 * 1024
    private const val DAY = 86_400_000L

    /**
     * Files with the same content as another file in [files], all but the original of each group: the oldest,
     * on a tie the shortest name ("Rechnung.pdf" stays, "Rechnung (1).pdf" goes).
     */
    fun extraCopies(files: List<File>): List<File> {
        val scanned = files.map { ScannedFile(it.path, it.length(), it.lastModified()) }
        val extra = StorageAnalysis.duplicates(scanned).flatMap { group ->
            group.files.sortedWith(compareBy({ it.lastModified }, { it.name.length })).drop(1)
        }.map { it.path }.toSet()
        return files.filter { it.path in extra }
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
