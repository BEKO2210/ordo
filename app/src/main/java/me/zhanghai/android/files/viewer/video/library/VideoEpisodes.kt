/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.zhanghai.android.files.viewer.video.library

import java.util.Locale

/** A video's place in a series. */
data class EpisodeTag(val series: String, val season: Int, val episode: Int) {
    /** Grouping key: series lower-cased (Locale.ROOT), whitespace collapsed and trimmed. */
    val seriesKey: String
        get() = series.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

object VideoEpisodes {
    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)
    private val SXXEYY =
        Regex("(?<![\\p{L}0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?!\\d)", OPTIONS)
    // "1x02" but not "1920x1080" or "10x264" (codec).
    private val NXNN = Regex("(?<![\\dxX])(\\d{1,2})x(?!26[45])(\\d{1,3})(?![\\dpP])", OPTIONS)
    private val EPISODE_WORD =
        // A bare "E03" only when the digits follow directly; "Teil 2" of a TV multi-parter; Tatort counts past 1000.
        Regex("(?<![\\p{L}0-9])(?:folge|episode|ep\\.?|teil|e(?=\\d{1,3}(?!\\d)))[ ._-]*(\\d{1,4})(?!\\d)", OPTIONS)
    private val SEASON_WORD =
        Regex("(?<!\\p{L})(?:staffel|season|s)[ .]?(\\d{1,2})(?!\\d)", OPTIONS)
    // Fansubs pad to two digits ("- 03"): "Iron Man - 2" stays a film; resolutions are no episode.
    private val ANIME = Regex(
        "^(.*?[^\\s_])[\\s_]+-[\\s_]+(?!(?:480|576|720)(?!\\d))(\\d{2,3})(?:v\\d)?(?=$|[\\s_\\[(])", OPTIONS
    )
    private val SEASON_FOLDER =
        Regex("(?:staffel|season|s)[ ._-]*(\\d{1,2})", OPTIONS)
    private val SPECIALS_FOLDER = Regex("specials?|ova|extras", OPTIONS)
    private val BRACKETS = Regex("\\[[^\\]]*]|\\([^)]*\\)")
    private val SEPARATORS = Regex("[._]")
    private val SPACES = Regex("\\s+")
    private val TRIM_CHARS = " -–_.,:".toSet()

    /** [title] = file name without extension, [folder] = full folder path ("/storage/emulated/0/Serien/Dark/Staffel 1"). Null when the name is not an episode. */
    fun parse(title: String, folder: String): EpisodeTag? {
        val segments = folder.split('/', '\\').filter { it.isNotBlank() }
        val last = segments.lastOrNull().orEmpty()
        // "Specials"/"OVA" is season 0, as TV databases count it.
        val folderSeason = SEASON_FOLDER.matchEntire(last.trim())?.groupValues?.get(1)?.toInt()
            ?: 0.takeIf { SPECIALS_FOLDER.matches(last.trim()) }
        val seriesFolder = if (folderSeason != null) segments.getOrNull(segments.size - 2) else last
        val fallbackSeries = cleanName(seriesFolder.orEmpty())

        fun tag(prefix: String, season: Int, episode: Int): EpisodeTag? {
            val series = cleanName(prefix).ifEmpty { fallbackSeries }
            return if (series.isEmpty()) null else EpisodeTag(series, season, episode)
        }

        SXXEYY.find(title)?.let {
            return tag(title.substring(0, it.range.first), it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        NXNN.find(title)?.let {
            return tag(title.substring(0, it.range.first), it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        EPISODE_WORD.find(title)?.let { episode ->
            val season = SEASON_WORD.find(title)
            val start = minOf(episode.range.first, season?.range?.first ?: Int.MAX_VALUE)
            val seasonNumber = season?.groupValues?.get(1)?.toInt() ?: folderSeason ?: 1
            return tag(title.substring(0, start), seasonNumber, episode.groupValues[1].toInt())
        }
        ANIME.find(title)?.let {
            return tag(it.groupValues[1], folderSeason ?: 1, it.groupValues[2].toInt())
        }
        return null
    }

    /** "Dark.S01E02" prefix "Dark." -> "Dark"; removes [..] and (..) groups, turns . and _ into spaces, trims " -–_.,:" and collapses spaces. */
    fun cleanName(raw: String): String =
        raw.replace(BRACKETS, " ")
            .replace(SEPARATORS, " ")
            .replace(SPACES, " ")
            .trim { it in TRIM_CHARS }
            .replace(SPACES, " ")
}
