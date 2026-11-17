/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import androidx.media3.common.Player
import java.util.Locale
import kotlin.math.roundToInt

/** How a receiver's MEDIA_STATUS reads as Media3 player state. Pure, so it is tested on the JVM. */
object CastPlayback {
    /** Volume steps the system volume keys walk through; the receiver itself takes 0.0–1.0. */
    const val VOLUME_STEPS = 20

    fun playbackState(status: CastProtocol.MediaStatus?): Int = when (status?.playerState) {
        "PLAYING", "PAUSED" -> Player.STATE_READY
        "BUFFERING", "LOADING" -> Player.STATE_BUFFERING
        "IDLE" -> if (status.idleReason == "FINISHED") Player.STATE_ENDED else Player.STATE_IDLE
        else -> Player.STATE_IDLE
    }

    fun playWhenReady(status: CastProtocol.MediaStatus?, previous: Boolean): Boolean = when (status?.playerState) {
        "PLAYING", "BUFFERING", "LOADING" -> true
        "PAUSED" -> false
        else -> previous
    }

    fun isError(status: CastProtocol.MediaStatus?): Boolean =
        status?.playerState == "IDLE" && status.idleReason == "ERROR"

    /** The receiver reports the position when it sends a status; while it plays, time moves on from there. */
    fun positionMs(status: CastProtocol.MediaStatus, receivedAtMs: Long, nowMs: Long): Long {
        val base = (status.currentTimeSeconds * 1000).toLong()
        val moved = if (status.playerState == "PLAYING") (nowMs - receivedAtMs).coerceAtLeast(0L) else 0L
        val duration = status.durationSeconds?.let { (it * 1000).toLong() }
        return (base + moved).let { if (duration != null) it.coerceAtMost(duration) else it }.coerceAtLeast(0L)
    }

    fun deviceVolume(level: Double): Int = (level * VOLUME_STEPS).roundToInt().coerceIn(0, VOLUME_STEPS)

    fun level(deviceVolume: Int): Double = deviceVolume.coerceIn(0, VOLUME_STEPS).toDouble() / VOLUME_STEPS

    /**
     * Content type for the receiver, by extension; formats from https://developers.google.com/cast/docs/media .
     * Null for formats Cast does not list (ALAC/M4A is sent as audio/mp4 and the receiver decides).
     */
    fun contentType(fileName: String): String? =
        when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "webm", "weba" -> "audio/webm"
            else -> null
        }
}
