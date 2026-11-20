/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/**
 * Media3's media button receiver, except that a Play key with nothing to resume (no saved queue, or none of its files
 * left) does not start Ordo Music's service: it never went to the foreground and Ordo crashed in the background
 * (ForegroundServiceDidNotStartInTimeException, S21, 20.11.2026: Play pressed while no player was open).
 */
@UnstableApi
class OrdoMediaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean =
        PlaybackStateStore(context).read()?.paths?.any(::pathUriExists) == true
}
