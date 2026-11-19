/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.background

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaController
import java.text.Collator
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.VideoPlayerActivityBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.viewer.video.VideoPlayerActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.video.VideoPrefs
import me.zhanghai.android.files.viewer.video.VideoRepeatMode

/** The open video player's session, for [VideoPlaybackService] and the notification's way back to it. */
object VideoBackground {
    @Volatile
    var session: MediaSession? = null
        internal set
    @Volatile
    var taskId: Int = -1
        internal set

    /**
     * [VideoActions] for Ordo's own code (later automation): [controller] is a MediaController connected to
     * [session]'s token, e.g. `MediaController.Builder(context, session.token).buildAsync()`. Playing a file or folder
     * opens the player screen (from the foreground only, Android's rule for starting activities).
     */
    fun actions(context: Context, controller: MediaController): VideoActions {
        val collator = Collator.getInstance()
        return ControllerVideoActions(
            controller, controller::sendCustomCommand,
            listVideos = { folder ->
                runCatching {
                    Paths.get(folder).newDirectoryStream().use { stream ->
                        stream.filter { MimeType.guessFromPath(it.toString()).isVideo }.map { it.toString() }
                    }
                }.getOrDefault(emptyList())
            },
            open = { paths, startIndex ->
                val intent = Intent(context, VideoPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                VideoPlayerActivity.putExtras(intent, paths.map { Paths.get(it) }, startIndex)
                context.startActivity(intent)
            },
            comparator = { a, b -> collator.compare(a, b) }
        )
    }
}

/**
 * Everything of phase 3 for one player screen: "Sound only" in the background ([VideoPlaybackService]),
 * picture-in-picture with ±jump and play/pause, the sleep timer, and the session's commands for trusted controllers
 * ([VideoCommands]). The player stays the activity's; this class builds its session.
 */
@UnstableApi
class VideoBackgroundController(
    private val activity: AppCompatActivity,
    private val binding: VideoPlayerActivityBinding,
    private val player: ExoPlayer,
    sessionId: String,
    private val repeat: () -> VideoRepeatMode,
    private val setRepeat: (VideoRepeatMode) -> Unit,
    // Tresor videos: no "Sound only" service and notification, no picture-in-picture (library-pro, #101).
    private val vault: Boolean = false,
    // A subtitle language set by command replaces the subtitle file on screen (#99): one source at a time.
    private val onSubtitleTracksChosen: () -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    val sleepTimer = VideoSleepTimer(player, handler)
    private val frameLoader = VideoFrameLoader(activity.applicationContext) { player }
    private val pipSupported = !vault && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    // Picture-in-picture was used since the screen was last in front: closing that window pauses.
    private var pipUsed = false
    private var soundOnly = false

    val session: MediaSession = MediaSession.Builder(activity, player)
        .setId(sessionId)
        .setCallback(SessionCallback())
        .setBitmapLoader(frameLoader)
        .setSessionActivity(
            PendingIntent.getActivity(
                activity, 0, Intent(activity, VideoSessionActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_VIDEO_SIZE_CHANGED
                )) updatePip()
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val jumpMs = VideoPrefs.jumpSeconds(Settings.VIDEO_JUMP.valueCompat) * 1_000L
            when (intent.getIntExtra(EXTRA_CONTROL, -1)) {
                CONTROL_BACK -> player.seekTo((player.currentPosition - jumpMs).coerceAtLeast(0L))
                CONTROL_AHEAD -> {
                    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    val target = player.currentPosition + jumpMs
                    player.seekTo(duration?.let { target.coerceAtMost(it) } ?: target)
                }
                CONTROL_PLAY_PAUSE -> if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0L)
                    player.play()
                } else {
                    // Not VideoPicture.prepare (#104): the PiP button resumes at once, the pipeline choice stays.
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    Util.handlePlayPauseButtonAction(player)
                }
            }
        }
    }

    init {
        // A Tresor session is never the one VideoPlaybackService or other controllers pick up (#101).
        if (!vault) {
            VideoBackground.session = session
            VideoBackground.taskId = activity.taskId
        }
        player.addListener(playerListener)
        if (pipSupported) {
            ContextCompat.registerReceiver(
                activity, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            binding.toolbar.menu.findItem(R.id.actionPip)?.isVisible = true
            // The window grows out of where the picture is (sourceRectHint).
            binding.playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePip() }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // The screen in front is the active player; the service takes its session (onStartCommand).
                if (!vault) {
                    VideoBackground.session = session
                    VideoBackground.taskId = activity.taskId
                }
                if (soundOnly) {
                    soundOnly = false
                    setVideoDisabled(false)
                }
                if (!vault && VideoBackgroundPrefs.BACKGROUND.valueCompat == VideoBackgroundPrefs.BACKGROUND_AUDIO) {
                    // Started while the screen is in front, so the service may go to the foreground when the video
                    // plays; Android 12+ forbids that from the background.
                    runCatching { activity.startService(Intent(activity, VideoPlaybackService::class.java)) }
                } else {
                    activity.stopService(Intent(activity, VideoPlaybackService::class.java))
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                pipUsed = false
            }
        })
        updatePip()
    }

    /** From the activity's onStop: true when the video keeps playing as sound only, false when it should pause. */
    fun onStop(): Boolean {
        val keep = VideoBackgroundRules.keepsPlaying(
            VideoBackgroundPrefs.BACKGROUND.valueCompat, player.isPlaying, pipUsed, activity.isFinishing, vault
        )
        if (keep) {
            soundOnly = true
            setVideoDisabled(true)
        }
        return keep
    }

    fun onUserLeaveHint() {
        // Android 12+ enters by itself (setAutoEnterEnabled), more smoothly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            VideoBackgroundRules.entersPip(VideoBackgroundPrefs.PIP.valueCompat, pipSupported, player.isPlaying)) {
            enterPip()
        }
    }

    fun enterPip() {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // The user may have switched picture-in-picture off for Ordo in the system settings.
        runCatching { activity.enterPictureInPictureMode(pipParams()) }
    }

    fun onPictureInPictureModeChanged(inPip: Boolean) {
        if (inPip) pipUsed = true
        // The controls, hints and pills stay as they are but draw nothing in the small window; only the picture
        // (with its subtitles) is left. Their own show/hide logic keeps running and is right again afterwards.
        val root = binding.root
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child !== binding.playerView) child.clipBounds = if (inPip) Rect() else null
        }
        // The window was closed, not expanded: the video pauses (onStop may come before or after this).
        if (!inPip && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.pause()
    }

    fun release() {
        player.removeListener(playerListener)
        sleepTimer.release()
        if (pipSupported) runCatching { activity.unregisterReceiver(pipReceiver) }
        // A second player may have taken over meanwhile: only the active one ends the service.
        if (VideoBackground.session === session) {
            VideoBackground.session = null
            activity.stopService(Intent(activity, VideoPlaybackService::class.java))
        }
        frameLoader.release()
    }

    private fun setVideoDisabled(disabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled).build()
    }

    private fun updatePip() {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { activity.setPictureInPictureParams(pipParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val size = player.videoSize
        VideoBackgroundRules.pipAspect(size.width, size.height, size.pixelWidthHeightRatio)?.let { (width, height) ->
            builder.setAspectRatio(Rational(width, height))
        }
        val content = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
            ?: binding.playerView
        val rect = Rect()
        if (content.getGlobalVisibleRect(rect) && !rect.isEmpty) builder.setSourceRectHint(rect)
        val jump = VideoPrefs.jumpSeconds(Settings.VIDEO_JUMP.valueCompat)
        val playing = !Util.shouldShowPlayButton(player)
        val (backIcon, aheadIcon) = when (jump) {
            5 -> R.drawable.video_replay_5_icon_24dp to R.drawable.video_forward_5_icon_24dp
            30 -> R.drawable.video_replay_30_icon_24dp to R.drawable.video_forward_30_icon_24dp
            // Material Symbols has no replay_15; the 10 s symbols are the closest.
            else -> R.drawable.video_replay_10_icon_24dp to R.drawable.video_forward_10_icon_24dp
        }
        val actions = listOf(
            action(CONTROL_BACK, backIcon, activity.getString(R.string.video_player_seek_back_n, jump)),
            if (playing) {
                action(CONTROL_PLAY_PAUSE, R.drawable.pause_icon_24dp, activity.getString(R.string.video_player_pause))
            } else {
                action(CONTROL_PLAY_PAUSE, R.drawable.play_icon_24dp, activity.getString(R.string.video_player_play))
            },
            action(CONTROL_AHEAD, aheadIcon, activity.getString(R.string.video_player_seek_ahead_n, jump))
        )
        builder.setActions(actions.take(activity.maxNumPictureInPictureActions.coerceAtLeast(1)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                VideoBackgroundRules.entersPip(VideoBackgroundPrefs.PIP.valueCompat, true, player.isPlaying)
            )
            // A video keeps its shape; a seamless resize would stretch it mid-way.
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun action(control: Int, @DrawableRes icon: Int, title: String): RemoteAction =
        RemoteAction(
            Icon.createWithResource(activity, icon), title, title,
            PendingIntent.getBroadcast(
                activity, control,
                Intent(ACTION_PIP).setPackage(activity.packageName).putExtra(EXTRA_CONTROL, control),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

    /** Chips, never a slider: off, 15/30/45/60 min, end of the video. The checked one is what runs now. */
    fun showSleepTimerSheet() {
        val dialog = BottomSheetDialog(activity)
        val padding = activity.resources.getDimensionPixelSize(R.dimen.phi_21)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(activity).apply {
            setText(R.string.video_player_sleep_timer)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        if (sleepTimer.isActive) {
            content.addView(TextView(activity).apply {
                text = if (sleepTimer.endOfVideo) {
                    activity.getString(R.string.video_sleep_remaining_end)
                } else {
                    activity.getString(
                        R.string.video_sleep_remaining_format, ((sleepTimer.remainingMs + 59_999) / 60_000).toInt()
                    )
                }
            })
        }
        val group = ChipGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        fun chip(text: String, checked: Boolean, onClick: () -> Unit) {
            group.addView(Chip(activity, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                this.text = text
                isCheckable = true
                isChecked = checked
                setOnClickListener {
                    onClick()
                    dialog.dismiss()
                }
            })
        }
        chip(activity.getString(R.string.video_sleep_off), !sleepTimer.isActive) { setSleepTimer(0, false) }
        for (minutes in SLEEP_MINUTES) {
            chip(activity.getString(R.string.video_sleep_minutes_format, minutes), sleepTimer.minutes == minutes) {
                setSleepTimer(minutes, false)
            }
        }
        chip(activity.getString(R.string.video_sleep_end_of_video), sleepTimer.endOfVideo) { setSleepTimer(0, true) }
        content.addView(group, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = padding / 2 })
        dialog.setContentView(content)
        dialog.show()
    }

    private fun setSleepTimer(minutes: Int, endOfVideo: Boolean) {
        sleepTimer.set(minutes, endOfVideo)
        val message = when {
            sleepTimer.endOfVideo -> activity.getString(R.string.video_sleep_set_end)
            sleepTimer.isActive -> activity.getString(R.string.video_sleep_set_format, sleepTimer.minutes)
            else -> activity.getString(R.string.video_sleep_cancelled)
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun tracks(type: Int): List<VideoTrackInfo> =
        player.currentTracks.groups.withIndex().filter { it.value.type == type }.flatMap { (groupIndex, group) ->
            (0 until group.length).map { index ->
                VideoTrackInfo(
                    groupIndex, index, group.getTrackFormat(index).language, group.isTrackSupported(index),
                    group.isTrackSelected(index)
                )
            }
        }

    private fun selectedLanguage(type: Int): String? =
        tracks(type).firstOrNull { it.selected }?.let { VideoSelection.normalizeLanguage(it.language) }

    /** [language] blank: audio goes back to the player's own choice, subtitles go off. False if no track matches. */
    private fun setLanguage(type: Int, language: String?): Boolean {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (language.isNullOrBlank()) {
            if (type == C.TRACK_TYPE_TEXT) {
                onSubtitleTracksChosen()
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            player.trackSelectionParameters = builder.build()
            return true
        }
        val track = VideoSelection.matchTrack(tracks(type), language) ?: return false
        if (type == C.TRACK_TYPE_TEXT) onSubtitleTracksChosen()
        val group = player.currentTracks.groups[track.groupIndex]
        builder.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
        // Later videos of the queue prefer the same language.
        if (type == C.TRACK_TYPE_AUDIO) builder.setPreferredAudioLanguage(language)
        else builder.setPreferredTextLanguage(language)
        player.trackSelectionParameters = builder.build()
        return true
    }

    private fun state(): VideoState = VideoState(
        mediaId = player.currentMediaItem?.mediaId,
        positionMs = player.currentPosition,
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
        playing = player.isPlaying,
        speed = player.playbackParameters.speed,
        repeat = repeat(),
        sleepRemainingMs = sleepTimer.remainingMs,
        sleepEndOfVideo = sleepTimer.endOfVideo,
        audioLanguage = selectedLanguage(C.TRACK_TYPE_AUDIO),
        subtitleLanguage = selectedLanguage(C.TRACK_TYPE_TEXT)
    )

    /**
     * Controllers (media keys, the notification, the system) may play and seek but never hand the player items of
     * their own: that would bypass the player's launch rule. Ordo's own controllers and trusted (system) ones also
     * get [VideoCommands].
     */
    private inner class SessionCallback : MediaSession.Callback {
        private fun mayCommand(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
            controller.isTrusted ||
                (controller.packageName == activity.packageName && !session.isMediaNotificationController(controller))

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (mayCommand(session, controller)) {
                builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .apply { VideoCommands.ALL.forEach(::add) }.build()
                )
            }
            return builder.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (!mayCommand(session, controller)) return result(SessionResult.RESULT_ERROR_PERMISSION_DENIED)
            return when (customCommand.customAction) {
                VideoCommands.ACTION_SLEEP_TIMER -> {
                    sleepTimer.set(
                        args.getInt(VideoCommands.EXTRA_MINUTES), args.getBoolean(VideoCommands.EXTRA_END_OF_VIDEO)
                    )
                    result(SessionResult.RESULT_SUCCESS)
                }
                VideoCommands.ACTION_SET_AUDIO_LANGUAGE ->
                    languageResult(setLanguage(C.TRACK_TYPE_AUDIO, args.getString(VideoCommands.EXTRA_LANGUAGE)))
                VideoCommands.ACTION_SET_SUBTITLE_LANGUAGE ->
                    languageResult(setLanguage(C.TRACK_TYPE_TEXT, args.getString(VideoCommands.EXTRA_LANGUAGE)))
                VideoCommands.ACTION_SET_REPEAT -> {
                    setRepeat(VideoRepeatMode.decode(args.getString(VideoCommands.EXTRA_REPEAT)))
                    result(SessionResult.RESULT_SUCCESS)
                }
                VideoCommands.ACTION_CYCLE_REPEAT -> {
                    val mode = VideoSelection.nextRepeat(repeat())
                    setRepeat(mode)
                    Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(VideoCommands.EXTRA_REPEAT to mode.name))
                    )
                }
                VideoCommands.ACTION_GET_STATE ->
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, state().toBundle()))
                else -> result(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<androidx.media3.common.MediaItem>
        ): ListenableFuture<MutableList<androidx.media3.common.MediaItem>> =
            Futures.immediateFailedFuture(UnsupportedOperationException())

        private fun languageResult(found: Boolean) =
            result(if (found) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_BAD_VALUE)

        private fun result(code: Int): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(code))
    }

    companion object {
        private const val ACTION_PIP = "me.zhanghai.android.files.video.PIP_CONTROL"
        private const val EXTRA_CONTROL = "control"
        private const val CONTROL_BACK = 1
        private const val CONTROL_PLAY_PAUSE = 2
        private const val CONTROL_AHEAD = 3
        private val SLEEP_MINUTES = intArrayOf(15, 30, 45, 60)
    }
}

/**
 * Where the media notification and the lock screen lead: back to the player that is playing, in whatever task it
 * lives (a new player instance would have nothing to play). Shows nothing itself.
 */
class VideoSessionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = VideoBackground.taskId
        getSystemService(ActivityManager::class.java)?.appTasks
            ?.firstOrNull { task -> runCatching { task.taskInfo?.let(::taskIdOf) }.getOrNull() == id }
            ?.moveToFront()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun taskIdOf(info: ActivityManager.RecentTaskInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.id
}
