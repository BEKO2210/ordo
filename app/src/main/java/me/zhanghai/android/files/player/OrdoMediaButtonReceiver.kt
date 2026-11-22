/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import android.content.Intent
import android.content.ContentResolver
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import java.net.URI
import java8.nio.file.Files
import java8.nio.file.Paths

/**
 * Media3's media button receiver, except that a Play key with nothing to resume (no saved queue, or none of its files
 * left) does not start Ordo Music's service: it never went to the foreground and Ordo crashed in the background
 * (ForegroundServiceDidNotStartInTimeException, S21, 20.11.2026: Play pressed while no player was open).
 */
@UnstableApi
class OrdoMediaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean =
        PlaybackStateStore(context).read()?.paths?.any(::mediaButtonQueueItemExists) == true
}

internal fun mediaButtonQueueItemExists(pathUri: String): Boolean = try {
    val uri = URI.create(pathUri)
    mediaButtonQueueItemCanResume(uri.scheme) { Files.exists(Paths.get(uri)) }
} catch (_: Exception) {
    false
}

/** Local files are checked now; network files count as present and are checked when playback opens them. */
internal fun mediaButtonQueueItemCanResume(scheme: String?, localExists: () -> Boolean): Boolean = when (scheme) {
    ContentResolver.SCHEME_CONTENT, null -> false
    ContentResolver.SCHEME_FILE -> localExists()
    else -> true
}
