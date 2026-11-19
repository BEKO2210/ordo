/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.library

import java.net.URI
import java.text.Normalizer
import me.zhanghai.android.files.viewer.video.ContinueItem
import me.zhanghai.android.files.viewer.video.VideoItem
import me.zhanghai.android.files.viewer.video.VideoResumeStore

data class Episode(val video: VideoItem, val tag: EpisodeTag)

data class Season(val number: Int, val episodes: List<Episode>)

data class Series(val name: String, val seasons: List<Season>) {
    val episodes: List<Episode>
        get() = seasons.flatMap { it.episodes }
}

/** A card of "Continue watching": a saved place ([positionMs]) or, with null, the next episode of a series. */
data class ContinueCard(val video: VideoItem, val positionMs: Long?, val episode: EpisodeTag?, val atMs: Long)

/**
 * The shelves of Ordo Video's library from its list (in the library's order): series by name, films, recently
 * added; plus the watched rules and the search. No Android in here, so it is unit-tested.
 */
class VideoShelves(videos: List<VideoItem>, collator: Comparator<in String>) {
    private val tags: Map<String, EpisodeTag> = videos.mapNotNull { video ->
        VideoEpisodes.parse(video.title, video.folder)?.let { video.path to it }
    }.toMap()

    /** Only names with two or more episodes count as a series: one "Konzert - 12" is just a video. */
    val series: List<Series> = videos.filter { it.path in tags }
        .groupBy { tags.getValue(it.path).seriesKey }
        .values.filter { it.size >= 2 }
        .map { list ->
            val episodes = list.map { Episode(it, tags.getValue(it.path)) }
            Series(
                episodes.first().tag.series,
                episodes.groupBy { it.tag.season }.toSortedMap().map { (number, inSeason) ->
                    Season(number, inSeason.sortedWith(
                        compareBy<Episode> { it.tag.episode }.thenBy(collator) { it.video.path }
                    ))
                }
            )
        }
        // Newest addition first, like the rest of the library.
        .sortedWith(compareByDescending<Series> { s -> s.episodes.maxOf { it.video.dateAddedMs } }
            .thenBy(collator) { it.name })

    private val seriesByPath: Map<String, Series> =
        series.flatMap { s -> s.episodes.map { it.video.path to s } }.toMap()

    /** Long videos (over 40 min) that belong to no series. */
    val films: List<VideoItem> = videos.filter { it.durationMs > FILM_MIN_MS && it.path !in seriesByPath }

    private val all = videos

    fun recentlyAdded(nowMs: Long, max: Int = SHELF_MAX): List<VideoItem> =
        all.filter { nowMs - it.dateAddedMs in 0..RECENT_MS }.sortedByDescending { it.dateAddedMs }.take(max)

    fun seriesOf(video: VideoItem): Series? = seriesByPath[video.path]

    fun episodeOf(video: VideoItem): EpisodeTag? = seriesByPath[video.path]?.let { tags[video.path] }

    /** The player's queue for an episode: its season in order, so "Play next automatically" stays in the season. */
    fun seasonQueue(video: VideoItem): List<VideoItem>? {
        val tag = episodeOf(video) ?: return null
        return seriesOf(video)?.seasons?.find { it.number == tag.season }?.episodes?.map { it.video }
    }

    /** The episode after [video]: the next in its season, else the first of the next season. */
    fun nextEpisode(video: VideoItem): VideoItem? {
        val episodes = seriesOf(video)?.episodes ?: return null
        val index = episodes.indexOfFirst { it.video.path == video.path }
        return episodes.getOrNull(index + 1)?.video
    }

    /**
     * "Continue watching": saved places, plus for each series whose last watched episode is done the next one not
     * yet watched ("Next episode"); last activity first. A place older than a "watched" mark is over.
     */
    fun continueCards(
        resumed: List<ContinueItem>,
        entries: List<VideoResumeStore.Entry>,
        watchedAt: Map<String, Long>,
        max: Int = CONTINUE_MAX
    ): List<ContinueCard> {
        // By path and size: an entry of a replaced file of the same name must not count.
        val savedAt = entries.mapNotNull { entry ->
            runCatching { URI.create(entry.pathUri) }.getOrNull()?.takeIf { it.scheme == "file" }?.path
                ?.let { watchedKey(it, entry.size) to entry.savedAtMs }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
        val places = resumed.mapNotNull { item ->
            val at = savedAt[watchedKey(item.video)] ?: 0L
            if ((watchedAt[watchedKey(item.video)] ?: Long.MIN_VALUE) > at) return@mapNotNull null
            ContinueCard(item.video, item.positionMs, episodeOf(item.video), at)
        }
        val inProgress = places.map { it.video.path }.toSet()
        val next = series.mapNotNull { s ->
            val episodes = s.episodes
            val last = episodes.withIndex().filter { watchedKey(it.value.video) in watchedAt }
                .maxByOrNull { watchedAt.getValue(watchedKey(it.value.video)) } ?: return@mapNotNull null
            val upcoming = episodes.drop(last.index + 1).firstOrNull { watchedKey(it.video) !in watchedAt }
                ?: return@mapNotNull null
            if (upcoming.video.path in inProgress) return@mapNotNull null
            // A series watched further on in its own place card is already there.
            if (episodes.any { it.video.path in inProgress }) return@mapNotNull null
            ContinueCard(upcoming.video, null, upcoming.tag, watchedAt.getValue(watchedKey(last.value.video)))
        }
        return (places + next).sortedByDescending { it.atMs }.distinctBy { it.video.path }.take(max)
    }

    companion object {
        const val FILM_MIN_MS = 40 * 60_000L
        const val RECENT_MS = 30L * 24 * 60 * 60 * 1000
        const val SHELF_MAX = 20
        const val CONTINUE_MAX = 20
        private const val FINISHED_FRACTION = 0.95
        private const val FINISHED_LEFT_MS = 10_000L

        /** The watched mark's key: path and size, so a replaced file of the same name starts unwatched. */
        fun watchedKey(video: VideoItem): String = watchedKey(video.path, video.size)

        fun watchedKey(path: String, size: Long): String = "$path|$size"

        /** Watched: at least 95 % seen or less than 10 s left. */
        fun isFinished(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs > 0 &&
                (positionMs >= durationMs * FINISHED_FRACTION || durationMs - positionMs < FINISHED_LEFT_MS)

        /** The watched key from the player's resume key ("file:///…|size|mtime"); null for anything not local. */
        fun watchedKeyFromResumeKey(resumeKey: String): String? {
            val (pathUri, size) = VideoResumeStore.parseKey(resumeKey) ?: return null
            val path = runCatching { URI.create(pathUri) }.getOrNull()?.takeIf { it.scheme == "file" }?.path
                ?: return null
            return watchedKey(path, size)
        }

        /**
         * Search: every word of [query] must appear in the title, the folder's name or the series name; case and
         * accents do not matter ("lowenzahn" finds "Löwenzahn").
         */
        fun matches(video: VideoItem, seriesName: String?, query: String): Boolean {
            val words = fold(query).split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return true
            val haystack = fold(listOfNotNull(video.title, video.folder.substringAfterLast('/'), seriesName)
                .joinToString(" "))
            return words.all { it in haystack }
        }

        private val MARKS = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[\\s._-]+")

        private fun fold(text: String): String =
            MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
                .replace(SEPARATORS, " ").trim()
    }
}
