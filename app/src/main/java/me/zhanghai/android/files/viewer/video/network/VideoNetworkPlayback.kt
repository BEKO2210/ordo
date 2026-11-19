/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import me.zhanghai.android.files.R
import me.zhanghai.android.files.player.PathDataSource

/**
 * Films from SMB, SFTP, WebDAV and FTP: a bigger buffer with a back buffer (seeking back a few seconds reads nothing
 * again), chunked read-ahead, patient retries, a calm "Loading … 42 % · 3.1 MB/s" pill and, after the server went
 * away, a new try at the same place as soon as the network is back. Local files keep Media3's defaults.
 */
@UnstableApi
class VideoNetworkPlayback private constructor(val config: VideoBuffering.Config) {
    private val bytesRead = AtomicLong()
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pill: TextView? = null
    private var connectivity: ConnectivityManager? = null
    private var reconnecting = false
    private var lastBytes = 0L
    private var lastTickMs = 0L
    private var speed = 0L
    private var retries = 0

    val isNetwork: Boolean get() = config === VideoBuffering.NETWORK

    /** Media3's own pieces, tuned for this source; local files get exactly the defaults. */
    fun applyTo(builder: ExoPlayer.Builder, context: Context): ExoPlayer.Builder {
        val dataSources = if (isNetwork) {
            ReadAheadDataSource.Factory(PathDataSource.Factory(context), config.readAheadBytes, bytesRead)
        } else {
            PathDataSource.Factory(context)
        }
        val mediaSources = DefaultMediaSourceFactory(dataSources)
        if (isNetwork) mediaSources.setLoadErrorHandlingPolicy(RetryPolicy(config.retries))
        return builder.setMediaSourceFactory(mediaSources).setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(config.minBufferMs, config.maxBufferMs, config.playbackMs, config.rebufferMs)
                .setBackBuffer(config.backBufferMs, true)
                .build()
        )
    }

    /** Wires the pill and the reconnect to the built player; nothing happens for local files. */
    fun attach(player: ExoPlayer, root: FrameLayout) {
        if (!isNetwork) return
        this.player = player
        val context = root.context
        val density = context.resources.displayMetrics.density
        pill = TextView(context).apply {
            // The same pill as the player's hints.
            setBackgroundResource(R.drawable.audio_player_seek_bubble)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xCC202020.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val horizontal = (16 * density).toInt()
            val vertical = (8 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            gravity = Gravity.CENTER
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isVisible = false
            // Below the centre buttons (Play is at most 88 dp high), never on top of them.
            translationY = 76 * density
        }
        root.addView(pill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                handler.removeCallbacks(showPill)
                when (playbackState) {
                    Player.STATE_BUFFERING -> handler.postDelayed(showPill, PILL_DELAY_MS)
                    Player.STATE_READY -> {
                        reconnecting = false
                        retries = 0
                        hidePill()
                    }
                    else -> hidePill()
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                if (wasCanceled || reconnecting) return
                reconnecting = true
                pill?.let { it.announceForAccessibility(it.context.getString(R.string.video_network_reconnecting)) }
                if (player.playbackState == Player.STATE_BUFFERING) showPill.run()
            }
        })
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    /** "Server not reachable" for an input/output failure of a network film; null leaves the usual message. */
    fun errorMessage(context: Context, error: PlaybackException): String? {
        if (!isNetwork) return null
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> context.getString(R.string.video_network_error_missing)
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> null
            in 2000..2999 -> {
                scheduleRetry()
                context.getString(R.string.video_network_error_unreachable)
            }
            else -> null
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        connectivity = null
        player = null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post(retry)
        }
    }

    // After the error the player sits idle at the same place; prepare() picks the film up right there.
    private val retry = Runnable {
        val player = player ?: return@Runnable
        if (player.playerError == null || player.playbackState != Player.STATE_IDLE) return@Runnable
        player.prepare()
    }

    private fun scheduleRetry() {
        if (++retries > MAX_AUTO_RETRIES) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, AUTO_RETRY_MS)
    }

    private val showPill: Runnable = object : Runnable {
        override fun run() {
            val player = player ?: return
            val pill = pill ?: return
            if (player.playbackState != Player.STATE_BUFFERING) return hidePill()
            val now = System.currentTimeMillis()
            val bytes = bytesRead.get()
            if (lastTickMs > 0 && now > lastTickMs) speed = (bytes - lastBytes) * 1000 / (now - lastTickMs)
            lastBytes = bytes
            lastTickMs = now
            pill.text = if (reconnecting) {
                pill.context.getString(R.string.video_network_reconnecting)
            } else {
                val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                val percent = VideoBuffering.bufferPercent(ahead, config)
                pill.context.getString(
                    R.string.video_network_buffering_format, percent,
                    VideoBuffering.speedText(speed, Locale.getDefault())
                )
            }
            pill.isVisible = true
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun hidePill() {
        handler.removeCallbacks(showPill)
        lastTickMs = 0
        pill?.isVisible = false
    }

    /** Media3's default policy, but more tries with calm, growing pauses: a Wi-Fi hiccup becomes a short buffering. */
    private class RetryPolicy(retries: Int) : DefaultLoadErrorHandlingPolicy(retries) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val delay = super.getRetryDelayMsFor(loadErrorInfo)
            return if (delay == C.TIME_UNSET) delay else VideoBuffering.retryDelayMs(loadErrorInfo.errorCount)
        }
    }

    companion object {
        private const val PILL_DELAY_MS = 700L
        private const val TICK_MS = 500L
        private const val AUTO_RETRY_MS = 10_000L
        private const val MAX_AUTO_RETRIES = 30

        fun forUri(uri: String?) = VideoNetworkPlayback(VideoBuffering.forUri(uri))
    }
}
