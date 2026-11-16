/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * File counts for one net decision (rules2's coverage check tries word subsets, one count each). Each source is
 * listed once, every count filters that list in memory, each file's text is read at most once (with the usual size
 * caps), equal plans are counted once, and [active] stops the walk when the sheet closes. Like the app's lists: a
 * "show" searches below its folders (no hidden folders, Android/ only media), a change only the folder itself.
 */
internal class CommandNetCount(
    private val root: File, private val text: String, private val active: () -> Boolean = { true },
    private val now: Long = System.currentTimeMillis()
) {
    private class Entry(val file: File, val name: String, val length: Long, val modified: Long)

    /** File-system listings done (folders read), for the test that each source is listed once per decision. */
    var listings = 0
        private set
    private val lists = HashMap<String, List<Entry>>()
    private val counts = HashMap<CommandPlan, Int>()
    private val texts = HashMap<String, MutableMap<String, Boolean>>()
    private var textLeft = CommandFiles.MAX_TEXT_TOTAL

    fun count(plan: CommandPlan): Int = counts.getOrPut(plan) {
        val filter = CommandFiles.Filter.of(plan, now) ?: return@getOrPut 0
        val entries = entries(plan) ?: return@getOrPut 0
        val matched = entries.filter { e ->
            checkActive()
            filter.matches(e.name, e.length, e.modified) { word -> inText(e, word, filter.searches) }
        }
        // a change is narrowed to extra copies later (CommandCheck.check), so count as it will be
        val n = if (plan.action != "show" && CommandCheck.duplicatesSaid(text, plan)) {
            CommandFiles.extraCopies(matched.map { it.file }).size
        } else {
            matched.size
        }
        if (plan.limit > 0) minOf(n, plan.limit) else n
    }

    private fun checkActive() {
        if (!active()) throw CancellationException("command sheet closed")
    }

    private fun entries(plan: CommandPlan): List<Entry>? = if (plan.action == "show") {
        val sources = CommandCheck.showSources(plan.source, root) ?: return null
        lists.getOrPut("show:" + sources.joinToString("\u0000") { it.path }) { walk(sources) }
    } else {
        val dir = CommandCheck.resolveAll(plan.source, null, root, paths = true).singleOrNull() ?: return null
        lists.getOrPut("dir:" + dir.path) {
            listings++
            (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.startsWith(".") }
                .map { Entry(it, it.name, it.length(), it.lastModified()) }
        }
    }

    /** As CommandFiles.show walks: canonical paths inside [root], no hidden entries, Android/ only media. */
    private fun walk(sources: List<File>): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        val visited = HashSet<String>()
        val rootPath = root.canonicalPath
        fun go(dir: File) {
            checkActive()
            val path = runCatching { dir.canonicalPath }.getOrNull() ?: return
            if (path != rootPath && !path.startsWith("$rootPath/") || !visited.add(path)) return
            val parts = path.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }
            if (parts.firstOrNull() == "Android" && parts.size >= 2 && parts[1] != "media") return
            listings++
            for (child in dir.listFiles() ?: return) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    go(child)
                } else if (child.isFile && runCatching { child.canonicalPath.startsWith("$rootPath/") }.getOrDefault(false)) {
                    out[child.path] = Entry(child, child.name, child.length(), child.lastModified())
                }
            }
        }
        sources.forEach { go(it) }
        return out.values.toList()
    }

    /** Whether the file's text holds [word]; the first question reads the file once and answers every word the plan has. */
    private fun inText(e: Entry, word: String, searches: Map<String, ContentSearch>): Boolean {
        val known = texts.getOrPut(e.file.path) { HashMap() }
        known[word]?.let { return it }
        val extension = e.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val bytes = if (extension !in CommandFiles.TEXT_EXTENSIONS || e.length > CommandFiles.MAX_TEXT_SIZE ||
            textLeft < e.length) null else try {
            textLeft -= e.length
            e.file.readBytes()
        } catch (ex: IOException) {
            null
        }
        for ((w, search) in searches) {
            if (w !in known) known[w] = bytes != null && runCatching { search.contentMatches(bytes.inputStream()) }.getOrDefault(false)
        }
        return known[word] ?: false
    }
}
