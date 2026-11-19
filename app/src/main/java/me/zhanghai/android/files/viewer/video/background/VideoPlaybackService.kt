/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import me.zhanghai.android.files.R

/**
 * Keeps the video's sound going with the screen off or another app in front ("Sound only"). Its own service, not
 * AudioPlaybackService: the video never touches Ordo Music's queue. It hosts the session of the open video player
 * ([VideoBackground.session]); the player stays the activity's. Not exported and without the MediaSessionService
 * intent filter: Media3's MediaButtonReceiver expects exactly one such service (Ordo Music's).
 */
@UnstableApi
class VideoPlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        // Its own notification ID and channel: Media3's default (1001) is the music's notification.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.video_background_channel)
                .build()
        )
        syncSession()
    }

    // Each start comes from the player screen in front: it hosts that screen's session, not an older one's.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncSession()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun syncSession() {
        val session = VideoBackground.session ?: return stopSelf()
        sessions.filter { it !== session }.forEach(::removeSession)
        if (session !in sessions) addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = VideoBackground.session

    companion object {
        private const val NOTIFICATION_ID = 2_001
        private const val CHANNEL_ID = "video_playback"
    }
}
