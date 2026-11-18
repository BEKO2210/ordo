/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio.lyrics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.LyricLine
import me.zhanghai.android.files.viewer.audio.lyrics.SemanticLyrics.SyncedLyrics

/** Where lyrics came from, in the order [LyricsRepository] looks. */
enum class LyricsSource { SIDECAR_LRC, EMBEDDED_SYNCED, EMBEDDED_UNSYNCED }

/** The lyrics of one song. Times are milliseconds from the start of the song, offsets already applied. */
class Lyrics(val content: SemanticLyrics, val source: LyricsSource) {
    /**
     * Timed lines sorted by start (a translation shares its main line's start); empty when not synced. At most
     * [MAX_LINES] lines of at most [MAX_LINE_CHARS] characters: a crafted 512 KB file could otherwise hold tens of
     * thousands of lines (security review 18.11.2026).
     */
    val lines: List<LyricLine> = (content as? SyncedLyrics)?.text.orEmpty().take(MAX_LINES)
        .map { if (it.text.length > MAX_LINE_CHARS) it.copy(text = capLine(it.text), words = null) else it }

    val isSynced: Boolean
        get() = lines.isNotEmpty()

    /** Every line as plain text, synced or not, with the same caps as [lines]. */
    val text: List<String> by lazy { content.unsyncedText.take(MAX_LINES).map { capLine(it.first) } }

    /**
     * The index in [lines] of the line playing at [positionMs]: the last one that has started, or -1 before the
     * first line and for unsynced lyrics. Of lines with the same start it returns the first, the main line.
     */
    fun lineAt(positionMs: Long): Int {
        var found = lastStarted(lines.size) { lines[it].start.toLong() <= positionMs }
        while (found > 0 && lines[found - 1].start == lines[found].start) found--
        return found
    }

    /** The index in `lines[lineAt(positionMs)].words` of the last word that has started, or -1. */
    fun wordAt(positionMs: Long): Int {
        val words = lines.getOrNull(lineAt(positionMs))?.words ?: return -1
        return lastStarted(words.size) { words[it].begin.toLong() <= positionMs }
    }

    // Binary search for the last index where [started] holds; the items are sorted by time.
    private inline fun lastStarted(size: Int, started: (Int) -> Boolean): Int {
        var low = 0
        var high = size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (started(middle)) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}

/** Maps the player position to the current line index, emitting only when the line changes. */
fun Flow<Long>.lyricsLineIndex(lyrics: Lyrics): Flow<Int> = map(lyrics::lineAt).distinctUntilChanged()

internal const val MAX_LINES = 5_000
internal const val MAX_LINE_CHARS = 500

/** A line cut to [MAX_LINE_CHARS] at the last space before the limit, never inside a word, with an ellipsis. */
internal fun capLine(text: String): String {
    if (text.length <= MAX_LINE_CHARS) return text
    val space = text.lastIndexOf(' ', MAX_LINE_CHARS - 1)
    return (if (space > 0) text.substring(0, space) else text.substring(0, MAX_LINE_CHARS)).trimEnd() + " …"
}
