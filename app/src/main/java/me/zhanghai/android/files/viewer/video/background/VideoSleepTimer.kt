/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import me.zhanghai.android.files.player.SleepTimerSchedule

/**
 * The video's sleep timer, like Ordo Music's: after some minutes or at the end of the current video the sound fades
 * over the last 10 s ([SleepTimerSchedule]) and the video pauses. Runs on [handler]'s thread, also in the background.
 */
class VideoSleepTimer(private val player: Player, private val handler: Handler) {
    private var deadlineElapsedMs: Long? = null
    var endOfVideo = false
        private set
    var minutes = 0
        private set

    val isActive: Boolean
        get() = deadlineElapsedMs != null || endOfVideo

    val remainingMs: Long
        get() = deadlineElapsedMs?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) } ?: 0L

    private val tick = object : Runnable {
        override fun run() {
            val deadline = deadlineElapsedMs
            val volume = when {
                deadline != null -> SleepTimerSchedule.volume(deadline, SystemClock.elapsedRealtime())
                endOfVideo -> SleepTimerSchedule.endOfTrackVolume(player.duration, player.currentPosition)
                else -> return
            }
            player.volume = volume
            if (volume <= 0f) stopNow() else handler.postDelayed(this, TICK_MS)
        }
    }

    // The end of the video: whatever the repeat button says, it does not go on.
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (endOfVideo && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) stopNow()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (endOfVideo && playbackState == Player.STATE_ENDED) cancel()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (endOfVideo && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) cancel()
        }
    }

    init {
        player.addListener(listener)
    }

    /** 0 [minutes] and no [endOfVideo] switch it off. */
    fun set(minutes: Int, endOfVideo: Boolean) {
        cancel()
        this.minutes = minutes.coerceAtLeast(0)
        this.endOfVideo = endOfVideo && this.minutes == 0
        deadlineElapsedMs = this.minutes.takeIf { it > 0 }
            ?.let { SleepTimerSchedule.deadline(SystemClock.elapsedRealtime(), it) }
        if (isActive) handler.post(tick)
    }

    /** Picks up a running timer on a recreated screen: the same [minutes] (for the sheet), [remainingMs] left. */
    fun restore(minutes: Int, remainingMs: Long, endOfVideo: Boolean) {
        set(0, endOfVideo)
        if (minutes <= 0) return
        this.minutes = minutes
        deadlineElapsedMs = SystemClock.elapsedRealtime() + remainingMs.coerceAtLeast(0L)
        handler.post(tick)
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        deadlineElapsedMs = null
        endOfVideo = false
        minutes = 0
        player.volume = 1f
    }

    fun release() {
        cancel()
        player.removeListener(listener)
    }

    private fun stopNow() {
        player.pause()
        cancel()
    }

    companion object {
        private const val TICK_MS = 250L
    }
}
