/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import me.zhanghai.android.files.player.queueWindow
import me.zhanghai.android.files.viewer.video.VideoRepeatMode

object VideoCommands {
    const val ACTION_SLEEP_TIMER = "me.zhanghai.android.files.video.SLEEP_TIMER"
    const val ACTION_SET_AUDIO_LANGUAGE = "me.zhanghai.android.files.video.SET_AUDIO_LANGUAGE"
    const val ACTION_SET_SUBTITLE_LANGUAGE = "me.zhanghai.android.files.video.SET_SUBTITLE_LANGUAGE"
    const val ACTION_SET_REPEAT = "me.zhanghai.android.files.video.SET_REPEAT"
    const val ACTION_CYCLE_REPEAT = "me.zhanghai.android.files.video.CYCLE_REPEAT"
    const val ACTION_GET_STATE = "me.zhanghai.android.files.video.GET_STATE"

    const val EXTRA_MINUTES = "minutes"
    const val EXTRA_END_OF_VIDEO = "endOfVideo"
    const val EXTRA_LANGUAGE = "language"
    const val EXTRA_REPEAT = "repeat"
    const val EXTRA_MEDIA_ID = "mediaId"
    const val EXTRA_POSITION_MS = "positionMs"
    const val EXTRA_DURATION_MS = "durationMs"
    const val EXTRA_PLAYING = "playing"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_SLEEP_REMAINING_MS = "sleepRemainingMs"
    const val EXTRA_AUDIO_LANGUAGE = "audioLanguage"
    const val EXTRA_SUBTITLE_LANGUAGE = "subtitleLanguage"

    val SLEEP_TIMER = SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY)
    val SET_AUDIO_LANGUAGE = SessionCommand(ACTION_SET_AUDIO_LANGUAGE, Bundle.EMPTY)
    val SET_SUBTITLE_LANGUAGE = SessionCommand(ACTION_SET_SUBTITLE_LANGUAGE, Bundle.EMPTY)
    val SET_REPEAT = SessionCommand(ACTION_SET_REPEAT, Bundle.EMPTY)
    val CYCLE_REPEAT = SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY)
    val GET_STATE = SessionCommand(ACTION_GET_STATE, Bundle.EMPTY)

    val ALL: List<SessionCommand> = listOf(
        SLEEP_TIMER, SET_AUDIO_LANGUAGE, SET_SUBTITLE_LANGUAGE, SET_REPEAT, CYCLE_REPEAT, GET_STATE
    )
}

data class VideoState(
    val mediaId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val speed: Float,
    val repeat: VideoRepeatMode,
    val sleepRemainingMs: Long,
    val sleepEndOfVideo: Boolean,
    val audioLanguage: String?,
    val subtitleLanguage: String?
) {
    fun toBundle(): Bundle = bundleOf(
        VideoCommands.EXTRA_MEDIA_ID to mediaId,
        VideoCommands.EXTRA_POSITION_MS to positionMs,
        VideoCommands.EXTRA_DURATION_MS to durationMs,
        VideoCommands.EXTRA_PLAYING to playing,
        VideoCommands.EXTRA_SPEED to speed,
        VideoCommands.EXTRA_REPEAT to repeat.name,
        VideoCommands.EXTRA_SLEEP_REMAINING_MS to sleepRemainingMs,
        VideoCommands.EXTRA_END_OF_VIDEO to sleepEndOfVideo,
        VideoCommands.EXTRA_AUDIO_LANGUAGE to audioLanguage,
        VideoCommands.EXTRA_SUBTITLE_LANGUAGE to subtitleLanguage
    )

    companion object {
        fun fromBundle(b: Bundle): VideoState = VideoState(
            mediaId = b.getString(VideoCommands.EXTRA_MEDIA_ID),
            positionMs = b.getLong(VideoCommands.EXTRA_POSITION_MS),
            durationMs = b.getLong(VideoCommands.EXTRA_DURATION_MS),
            playing = b.getBoolean(VideoCommands.EXTRA_PLAYING),
            speed = b.getFloat(VideoCommands.EXTRA_SPEED),
            repeat = VideoRepeatMode.decode(b.getString(VideoCommands.EXTRA_REPEAT)),
            sleepRemainingMs = b.getLong(VideoCommands.EXTRA_SLEEP_REMAINING_MS),
            sleepEndOfVideo = b.getBoolean(VideoCommands.EXTRA_END_OF_VIDEO),
            audioLanguage = b.getString(VideoCommands.EXTRA_AUDIO_LANGUAGE),
            subtitleLanguage = b.getString(VideoCommands.EXTRA_SUBTITLE_LANGUAGE)
        )
    }
}

/**
 * The public hook for later automation such as voice commands; the same code paths as the buttons. No AI here.
 */
interface VideoActions {
    /** Opens Ordo's video player with that file's folder as queue. */
    fun playFile(path: String): Boolean
    /** Returns the number of videos queued; 0 = nothing. */
    fun playFolder(folder: String, startPath: String? = null): Int
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setSpeed(speed: Float)
    fun setAudioLanguage(language: String?)
    fun setSubtitleLanguage(language: String?)
    fun setRepeat(mode: VideoRepeatMode)
    fun cycleRepeat()
    fun setSleepTimer(minutes: Int, endOfVideo: Boolean = false)
    fun state(): ListenableFuture<VideoState?>
}

data class VideoTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String?,
    val supported: Boolean,
    val selected: Boolean = false
)

/** The selection rules, without Android: unit-tested. */
object VideoSelection {
    private val ISO_639_2_TO_1 = mapOf(
        "deu" to "de", "ger" to "de",
        "eng" to "en",
        "fra" to "fr", "fre" to "fr",
        "spa" to "es",
        "ita" to "it",
        "tur" to "tr",
        "rus" to "ru",
        "jpn" to "ja",
        "por" to "pt",
        "pol" to "pl",
        "nld" to "nl", "dut" to "nl"
    )

    /** Lower-case ISO 639-1 primary language; blank/"und"/null → null. */
    fun normalizeLanguage(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val primary = tag.trim().lowercase().replace('_', '-').substringBefore('-')
        if (primary.isEmpty() || primary == "und") return null
        if (primary.length == 2) return primary
        if (primary.length == 3) return ISO_639_2_TO_1[primary] ?: primary
        return primary
    }

    /**
     * First supported track whose normalized language equals normalized [language]; prefers an already selected
     * one; null if none.
     */
    fun matchTrack(tracks: List<VideoTrackInfo>, language: String): VideoTrackInfo? {
        val want = normalizeLanguage(language) ?: return null
        val matches = tracks.filter { it.supported && normalizeLanguage(it.language) == want }
        return matches.firstOrNull { it.selected } ?: matches.firstOrNull()
    }

    /**
     * Paths sorted by file name with [comparator] (ties by full path); start index of [startPath] or 0. Over the
     * queue limit, keeps [queueWindow] and re-bases the start index.
     */
    fun folderQueue(
        videoPaths: List<String>,
        startPath: String?,
        comparator: Comparator<String>
    ): Pair<List<String>, Int> {
        if (videoPaths.isEmpty()) return emptyList<String>() to 0
        val sorted = videoPaths.sortedWith { a, b ->
            val byName = comparator.compare(a.substringAfterLast('/'), b.substringAfterLast('/'))
            if (byName != 0) byName else a.compareTo(b)
        }
        val startIndex = startPath?.let { sorted.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val window = queueWindow(sorted.size, startIndex)
        return sorted.slice(window) to (startIndex - window.first)
    }

    fun nextRepeat(mode: VideoRepeatMode): VideoRepeatMode = mode.next()

    fun clampSpeed(speed: Float): Float = if (speed.isNaN()) 1f else speed.coerceIn(0.25f, 4f)
}

/**
 * [VideoActions] on a connected session controller ([player] and [send], e.g. a MediaController and its
 * sendCustomCommand). [listVideos] and [open] reach Ordo's video player; [comparator] sorts file names.
 */
class ControllerVideoActions(
    private val player: Player,
    private val send: (SessionCommand, Bundle) -> ListenableFuture<SessionResult>,
    private val listVideos: (folder: String) -> List<String>,
    private val open: (paths: List<String>, startIndex: Int) -> Unit,
    private val comparator: Comparator<String>
) : VideoActions {
    override fun playFile(path: String): Boolean {
        val folder = path.substringBeforeLast('/')
        val videos = listVideos(folder)
        if (path !in videos) return false
        val (queue, start) = VideoSelection.folderQueue(videos, path, comparator)
        open(queue, start)
        return true
    }

    override fun playFolder(folder: String, startPath: String?): Int {
        val (queue, start) = VideoSelection.folderQueue(listVideos(folder), startPath, comparator)
        if (queue.isEmpty()) return 0
        open(queue, start)
        return queue.size
    }

    override fun seekTo(positionMs: Long) {
        val duration = player.duration
        val clamped =
            if (duration != C.TIME_UNSET) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
    }

    override fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(VideoSelection.clampSpeed(speed))
    }

    override fun setAudioLanguage(language: String?) {
        send(VideoCommands.SET_AUDIO_LANGUAGE, bundleOf(VideoCommands.EXTRA_LANGUAGE to language))
    }

    override fun setSubtitleLanguage(language: String?) {
        send(VideoCommands.SET_SUBTITLE_LANGUAGE, bundleOf(VideoCommands.EXTRA_LANGUAGE to language))
    }

    override fun setRepeat(mode: VideoRepeatMode) {
        send(VideoCommands.SET_REPEAT, bundleOf(VideoCommands.EXTRA_REPEAT to mode.name))
    }

    override fun cycleRepeat() {
        send(VideoCommands.CYCLE_REPEAT, Bundle.EMPTY)
    }

    override fun setSleepTimer(minutes: Int, endOfVideo: Boolean) {
        send(
            VideoCommands.SLEEP_TIMER,
            bundleOf(
                VideoCommands.EXTRA_MINUTES to minutes,
                VideoCommands.EXTRA_END_OF_VIDEO to endOfVideo
            )
        )
    }

    override fun state(): ListenableFuture<VideoState?> =
        Futures.transform(
            send(VideoCommands.GET_STATE, Bundle.EMPTY),
            { result ->
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    VideoState.fromBundle(result.extras)
                } else {
                    null
                }
            },
            MoreExecutors.directExecutor()
        )
}
