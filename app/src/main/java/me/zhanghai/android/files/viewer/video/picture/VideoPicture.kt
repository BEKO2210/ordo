/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.getSystemService
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.effect.LanczosResample
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.NumberFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.video.VideoInfo
import me.zhanghai.android.files.viewer.video.audioCodecName
import me.zhanghai.android.files.viewer.video.showExpanded
import me.zhanghai.android.files.viewer.video.videoCodecName
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Off
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Pipeline

/**
 * Ordo Video's picture: the screen's refresh rate follows the film ("Match frame rate"), SDR video smaller than the
 * screen is upscaled with Lanczos 3 and goes through one pass of debanding, CAS sharpening and dither ("Picture
 * enhancement"), and the "Signal path" sheet says what happens to picture and sound on the way.
 *
 * The player starts without Media3's OpenGL pipeline, so HDR, Dolby Vision and every video the enhancement does not
 * help reach the screen straight from the decoder. Media3 only builds the pipeline when the video renderer is reset
 * and keeps it for the renderer's lifetime (MediaCodecVideoRenderer.onEnabled), so the choice is made before
 * prepare() from a probe of the first and next video ([prepare], [PicturePolicy.preEngage]). Only when that was not
 * possible (network sources, timeout, a wrong probe, the ⋮ toggle or a rotation later) does switching it on set the
 * effects and re-prepare once; switching it off for another SDR video sets an empty list; an HDR or DRM video after
 * it rebuilds the screen, which then starts plain again ([PicturePolicy.pipeline]).
 */
@OptIn(UnstableApi::class)
class VideoPicture(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    /** A calm one-time line at the top of the picture (the player's notice). */
    private val onNotice: (String) -> Unit,
    /** The sound's own processing stages in plain words (Klang's), for the signal path; the player has none yet. */
    private val soundStages: () -> List<String> = { emptyList() }
) : AnalyticsListener, DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val surfaceView = playerView.videoSurfaceView as? SurfaceView
    private val frameRate = surfaceView?.let(::FrameRateMatcher)
    private var player: ExoPlayer? = null
    private var engaged = false
    private var recreating = false
    private var tooSlow = false
    private var noticeShown = false
    private var started = false
    private var inPip = false
    // What the pipeline got last: the same size and values set again would rebuild it for nothing.
    private var appliedKey: Any? = null

    /** The ⋮ toggle; starts as "the setting is not off". */
    var switchedOn = PicturePolicy.level(Settings.VIDEO_PICTURE.valueCompat) != PicturePolicy.Level.OFF
        private set
    var decision: PicturePolicy.Decision? = null
        private set

    private var videoFormat: Format? = null
    private var videoDecoder: String? = null
    private var audioFormat: Format? = null
    private var audioDecoder: String? = null
    private var audioTrack: AudioSink.AudioTrackConfig? = null
    private val hardware = HashMap<String, Boolean>()

    // Frame pacing: how many vsyncs each frame stands (playback thread writes, the sheet reads).
    private val pacing = FramePacing()
    private val frameListener = VideoFrameMetadataListener { _, releaseTimeNs, _, _ ->
        pacing.onFrame(releaseTimeNs)
        // The player and the activity belong to the main thread.
        if (++pacedFrames % PACING_LOG_FRAMES == 0L && Log.isLoggable(PICTURE_LOG_TAG, Log.DEBUG)) {
            handler.post(::logPacing)
        }
    }
    @Volatile
    private var pacedFrames = 0L

    // Room light (BT.2390-12): the sensor's lux, smoothed into the gamma the shader gets every frame.
    private val live = PictureLive()
    private val sensors = activity.getSystemService<SensorManager>()
    private val lightSensor = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var lux = -1f
    private var gamma = AmbientGamma.REFERENCE_GAMMA
    private var ambientActive = false
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lux = event.values.firstOrNull() ?: return
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    private val ambientTicker = object : Runnable {
        override fun run() {
            if (lux >= 0f) {
                val target = AmbientGamma.gamma(AmbientGamma.surroundLuminance(lux))
                gamma = AmbientGamma.step(gamma, target, AMBIENT_TICK_MS / 1000.0)
                live.gammaRatio = AmbientGamma.ratio(gamma)
            }
            handler.postDelayed(this, AMBIENT_TICK_MS)
        }
    }

    // The "Bild" sheet: per video, remembered by PictureAdjustStore's rules.
    private val adjustStore = PictureAdjustStore(activity)
    private var adjustId: String? = null
    var adjustments = PictureAdjustments()
        private set
    /** What the playing video's format says about interlacing: true, false or null (unknown). */
    var deinterlaceDetected: Boolean? = null
        private set
    private var deinterlaceOn = false

    private val evaluateRunnable = Runnable { evaluate() }
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) scheduleEvaluate()
    }
    private val pipListener = Consumer<PictureInPictureModeChangedInfo> {
        inPip = it.isInPictureInPictureMode
        frameRate?.setActive(started && !inPip)
        if (!inPip) scheduleEvaluate()
    }

    init {
        activity.lifecycle.addObserver(this)
        activity.addOnPictureInPictureModeChangedListener(pipListener)
        surfaceView?.addOnLayoutChangeListener(layoutListener)
    }

    /** Called once with the screen's player, before or after prepare(). */
    fun attach(player: ExoPlayer) {
        this.player = player
        player.addAnalyticsListener(this)
        player.setVideoFrameMetadataListener(frameListener)
        evaluate()
    }

    /**
     * Prepares [player] (instead of calling prepare() directly), after deciding whether it starts with Media3's
     * OpenGL pipeline: the first video and the next one are probed (local files and content://, at most 400 ms), so
     * an enhanced video is never prepared twice. An empty effect list is enough to build the pipeline; the real
     * effects follow as soon as the tracks and the surface are known. Unknown sources start plain.
     */
    fun prepare(player: ExoPlayer) {
        val index = player.currentMediaItemIndex
        val currentId = player.takeIf { it.mediaItemCount > 0 }?.getMediaItemAt(index)?.mediaId
        // Remembered adjustments (or forced deinterlacing) need the pass at any size.
        val adjusted = currentId?.let(adjustStore::read)?.let { !it.isNeutral || it.deinterlace == true } == true
        if ((!switchedOn && !adjusted) || failedInProcess || currentId == null || currentId in softwareMediaIds) {
            player.prepare()
            return
        }
        val nextId = if (index + 1 < player.mediaItemCount) player.getMediaItemAt(index + 1).mediaId else null
        activity.lifecycleScope.launch {
            val next = nextId?.let { async { PictureProbe.probe(activity, it) } }
            val current = PictureProbe.probe(activity, currentId)
            val metrics = activity.resources.displayMetrics
            // Someone pressed play meanwhile (prepare after an error): too late for the pipeline, the fallback rules.
            if (this@VideoPicture.player !== player || player.playbackState != Player.STATE_IDLE) return@launch
            if (PicturePolicy.preEngage(
                    switchedOn, failedInProcess, current, next?.await(), metrics.widthPixels, metrics.heightPixels,
                    adjusted
                )) {
                player.setVideoEffects(emptyList())
                engaged = true
                appliedKey = CLEARED
            }
            if (Log.isLoggable(PICTURE_LOG_TAG, Log.DEBUG)) Log.d(PICTURE_LOG_TAG, "prepare: pipeline $engaged")
            player.prepare()
        }
    }

    /** The ⋮ toggle: compare with and without. Switching it on again also forgets "too slow". */
    fun toggle(): Boolean {
        switchedOn = !switchedOn
        if (switchedOn) tooSlow = false
        evaluate()
        return switchedOn
    }

    /** The "Bild" sheet changed a step: live in the pass, remembered for this video where allowed. */
    fun adjust(value: PictureAdjustments) {
        // Before the first evaluate with tracks: this video's, so evaluate does not read the store over it.
        if (adjustId == null) adjustId = player?.currentMediaItem?.mediaId
        adjustments = value
        adjustId?.let { adjustStore.write(it, value) }
        applyLive()
        evaluate()
        player?.let(::redrawIfPaused)
    }

    /** False on HDR, DRM or a GPU that failed: the sheet says so. */
    val adjustable: Boolean
        get() = decision?.let(PicturePolicy::adjustable) ?: true

    /** Whether this video's adjustments are kept (not Tresor, not content://, not with "Keine Stellen merken"). */
    val remembersAdjustments: Boolean
        get() = PictureAdjustStore.remembers(adjustId)

    private fun applyLive() {
        live.colorMatrix = PictureAdjust.colorMatrix(adjustments)
        live.sharpnessStep = adjustments.sharpness
    }

    /** What the toggle's hint says: on, off, or on but not used on this video and why. */
    fun stateText(): String {
        val off = decision?.off
        return when {
            !switchedOn -> activity.getString(R.string.video_picture_off)
            off == null -> activity.getString(R.string.video_picture_on)
            else -> activity.getString(R.string.video_picture_on_idle_format, offText(off))
        }
    }

    private fun scheduleEvaluate() {
        handler.removeCallbacks(evaluateRunnable)
        handler.post(evaluateRunnable)
    }

    private fun evaluate() {
        val player = player ?: return
        if (recreating) return
        // Between videos and while re-preparing there are no tracks: everything stays as it is (a cleared rate
        // would switch the screen's mode twice under "always").
        val format = selectedVideoFormat(player.currentTracks) ?: return
        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId != adjustId) {
            adjustId = mediaId
            adjustments = mediaId?.let(adjustStore::read) ?: PictureAdjustments()
            applyLive()
        }
        deinterlaceDetected = Deinterlace.detect(format.sampleMimeType, format.initializationData, format.frameRate)
        deinterlaceOn = Deinterlace.active(adjustments.deinterlace, deinterlaceDetected)
        val plan = PicturePolicy.frameRatePlan(
            PicturePolicy.frameRateMode(Settings.VIDEO_FRAME_RATE.valueCompat), Build.VERSION.SDK_INT, format.frameRate
        )
        player.setVideoChangeFrameRateStrategy(plan.media3Strategy)
        frameRate?.setRate(plan.ownRate)
        val (width, height) = displaySize(format)
        // Pinch zoom (#102) enlarges the view up to 5x; the pass only needs what the screen area can show.
        val parent = playerView.parent as? View
        val surfaceWidth = (surfaceView?.width ?: 0).let { if (parent != null && parent.width > 0) minOf(it, parent.width) else it }
        val surfaceHeight = (surfaceView?.height ?: 0).let { if (parent != null && parent.height > 0) minOf(it, parent.height) else it }
        // Not laid out yet: the layout listener comes back.
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val level = PicturePolicy.level(Settings.VIDEO_PICTURE.valueCompat)
        val isHdr = ColorInfo.isTransferHdr(format.colorInfo) || format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        val decision = PicturePolicy.decide(
            level, switchedOn, isHdr, format.drmInitData != null, width, height, surfaceWidth, surfaceHeight, tooSlow,
            failedInProcess, player.currentMediaItem?.mediaId in softwareMediaIds
        )
        this.decision = decision
        val action = PicturePolicy.pipeline(engaged, decision, !adjustments.isNeutral || deinterlaceOn)
        if (Log.isLoggable(PICTURE_LOG_TAG, Log.DEBUG)) {
            Log.d(PICTURE_LOG_TAG, "${width}x$height on ${surfaceWidth}x$surfaceHeight: $decision -> $action, $plan")
        }
        when (action) {
            Pipeline.KEEP_PLAIN -> {}
            Pipeline.ENGAGE -> {
                setEffects(player, decision, surfaceWidth, surfaceHeight, redraw = false)
                engaged = true
                // stop() resets the renderers (not in foreground mode), so prepare() enables them with the pipeline;
                // position, items and play-when-ready stay.
                player.stop()
                player.prepare()
            }
            Pipeline.APPLY -> setEffects(player, decision, surfaceWidth, surfaceHeight)
            Pipeline.CLEAR -> if (appliedKey != CLEARED) {
                appliedKey = CLEARED
                player.setVideoEffects(emptyList())
                redrawIfPaused(player)
            }
            // Not in the picture-in-picture window (#100): it would leave it; it happens once the window closes.
            Pipeline.RECREATE -> if (!inPip) {
                recreating = true
                activity.recreate()
            }
        }
    }

    private fun setEffects(
        player: ExoPlayer,
        decision: PicturePolicy.Decision,
        width: Int,
        height: Int,
        redraw: Boolean = true
    ) {
        val params = if (decision.on) {
            PicturePolicy.shaderParams(decision.level, decision.scale)
        } else {
            PicturePolicy.ADJUST_ONLY
        }
        val key = listOf(params, width, height, decision.on, deinterlaceOn)
        if (appliedKey == key) return
        appliedKey = key
        // Deinterlacing sees the decoded lines; Lanczos fits the frame into the surface, so the final copy onto the
        // screen is pixel for pixel. Only the adjustments: no scaling, the screen scales as without the pass.
        player.setVideoEffects(listOfNotNull(
            DeinterlaceEffect.takeIf { deinterlaceOn },
            LanczosResample.scaleToFit(width, height).takeIf { decision.on },
            PictureEnhanceEffect(params, live)
        ))
        if (redraw) redrawIfPaused(player)
    }

    /**
     * New effects only reach the next decoded frame; paused (comparing with the ⋮ toggle), the shown frame is decoded
     * again by seeking to where it is. Media3's REDRAW would need its replayable frame cache, which ExoPlayer's video
     * renderer does not switch on.
     */
    private fun redrawIfPaused(player: ExoPlayer) {
        if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
            player.seekTo(player.currentPosition)
        }
    }

    /** The picture's size as shown: pixel aspect applied, turned for 90° and 270°. */
    private fun displaySize(format: Format): Pair<Int, Int> {
        val width = (format.width * format.pixelWidthHeightRatio).toInt()
        return if (format.rotationDegrees % 180 == 90) format.height to width else width to format.height
    }

    private fun selectedVideoFormat(tracks: Tracks): Format? = tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }

    // AnalyticsListener

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) = scheduleEvaluate()

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        videoFormat = format
        pacing.reset()
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: androidx.media3.common.Player.PositionInfo,
        newPosition: androidx.media3.common.Player.PositionInfo,
        reason: Int
    ) = pacing.reset()

    override fun onPlaybackParametersChanged(
        eventTime: AnalyticsListener.EventTime,
        playbackParameters: androidx.media3.common.PlaybackParameters
    ) = pacing.reset()

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        videoDecoder = decoderName
        // FFmpeg took over (the chip can't): never the GL pipeline for this video in this process, not even after
        // the screen is rebuilt for it.
        if (SignalPath.isFfmpegDecoderName(decoderName) && !eventTime.timeline.isEmpty) {
            softwareMediaIds += eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
            scheduleEvaluate()
        }
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        audioFormat = format
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        audioDecoder = decoderName
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        audioTrack = audioTrackConfig
    }

    /** Batches of dropped frames while the enhancement runs: a device that can't keep up gets the plain picture. */
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        if (Log.isLoggable(PICTURE_LOG_TAG, Log.DEBUG)) {
            Log.d(PICTURE_LOG_TAG, "dropped $droppedFrames frames in $elapsedMs ms, enhancement ${decision?.on}")
        }
        // The CPU can't keep up with FFmpeg: say it once per process, calmly; nothing else can be done.
        if (player?.currentMediaItem?.mediaId in softwareMediaIds && !softwareNoticeShown && PicturePolicy.tooSlow(droppedFrames, elapsedMs)) {
            softwareNoticeShown = true
            onNotice(activity.getString(R.string.video_software_slow_notice))
            return
        }
        if (decision?.on != true || !PicturePolicy.tooSlow(droppedFrames, elapsedMs)) return
        tooSlow = true
        evaluate()
        if (!noticeShown) {
            noticeShown = true
            onNotice(activity.getString(R.string.video_picture_too_slow_notice))
        }
    }

    /** A GPU that can't build or run the pass: never again in this process, the screen starts over plain. */
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        if (!engaged || error.errorCode !in VIDEO_PROCESSING_ERRORS) return
        Log.w(PICTURE_LOG_TAG, "picture pipeline failed", error)
        failedInProcess = true
        recreating = true
        activity.recreate()
    }

    // Lifecycle: the screen's rate is only asked for while the player is visible and not in picture-in-picture.

    override fun onStart(owner: LifecycleOwner) {
        started = true
        frameRate?.setActive(!inPip)
        setAmbient(Settings.VIDEO_AMBIENT_GAMMA.valueCompat)
        scheduleEvaluate()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        frameRate?.setActive(false)
        setAmbient(false)
    }

    /** The light sensor only listens while the player is on screen and the setting is on. */
    private fun setAmbient(on: Boolean) {
        val active = on && lightSensor != null
        if (active == ambientActive) return
        ambientActive = active
        if (active) {
            sensors?.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
            handler.postDelayed(ambientTicker, AMBIENT_TICK_MS)
        } else {
            sensors?.unregisterListener(lightListener)
            handler.removeCallbacks(ambientTicker)
            lux = -1f
            gamma = AmbientGamma.REFERENCE_GAMMA
            live.gammaRatio = 1f
        }
    }

    private fun logPacing() {
        val refresh = display()?.refreshRate ?: return
        val summary = FramePacing.summarize(pacing.intervals(), 1e9 / refresh)
        val fps = (videoFormat?.frameRate ?: 0f) * (player?.playbackParameters?.speed ?: 1f)
        Log.d(PICTURE_LOG_TAG, "pacing fps=$fps hz=$refresh vsyncs=${summary.counts.toSortedMap()} " +
            "verdict=${FramePacing.verdict(summary, fps, refresh)}")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        setAmbient(false)
        handler.removeCallbacksAndMessages(null)
        player?.clearVideoFrameMetadataListener(frameListener)
        activity.removeOnPictureInPictureModeChangedListener(pipListener)
        surfaceView?.removeOnLayoutChangeListener(layoutListener)
        frameRate?.release()
        player?.removeAnalyticsListener(this)
        player = null
    }

    // Signal path

    /** The sheet; its values follow the playback once a second while it is open. */
    fun showSignalPath() {
        val dialog = BottomSheetDialog(activity)
        val padding = activity.resources.getDimensionPixelSize(R.dimen.phi_21)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(heading(R.string.video_signal_title, title = true))
        // Fixed rows per section, filled by position; a row without a value hides.
        fun section(@StringRes title: Int, labels: List<Int>): List<Pair<View, TextView>> {
            content.addView(heading(title, title = false).apply { setPadding(0, padding, 0, 0) })
            return labels.map { label ->
                val value = TextView(activity).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = activity.resources.getDimensionPixelSize(R.dimen.phi_55)
                    setPadding(0, padding / 2, 0, 0)
                    isFocusable = true
                    addView(TextView(activity).apply {
                        setText(label)
                        setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption
                        )
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(value)
                }
                content.addView(row)
                row to value
            }
        }
        val pictureRows = section(R.string.video_signal_picture, PICTURE_ROWS)
        val soundRows = section(R.string.video_signal_sound, SOUND_ROWS)
        fun fill(rows: List<Pair<View, TextView>>, labels: List<Int>, values: List<String?>) {
            for ((index, views) in rows.withIndex()) {
                val value = values.getOrNull(index)
                views.first.isVisible = !value.isNullOrBlank()
                if (value != null && views.second.text.toString() != value) {
                    views.second.text = value
                    views.first.contentDescription = "${activity.getString(labels[index])}: $value"
                }
            }
        }
        fun refresh() {
            fill(pictureRows, PICTURE_ROWS, pictureValues())
            fill(soundRows, SOUND_ROWS, soundValues())
        }
        refresh()
        val ticker = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, SIGNAL_REFRESH_MS)
            }
        }
        handler.postDelayed(ticker, SIGNAL_REFRESH_MS)
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }
        dialog.setContentView(androidx.core.widget.NestedScrollView(activity).apply { addView(content) })
        dialog.showExpanded()
    }

    private fun heading(@StringRes text: Int, title: Boolean) = TextView(activity).apply {
        setText(text)
        setTextAppearance(
            if (title) {
                com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6
            } else {
                com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
            }
        )
        ViewCompat.setAccessibilityHeading(this, true)
    }

    /**
     * The frame's size on screen: fitted into the surface with its aspect kept, as Lanczos and the plain scaler draw it
     * (the surface fills the screen: S21 showed "640 × 360 → 1440 × 3200" for a 1440 × 810 picture).
     */
    private fun fitted(format: Format): String {
        val width = surfaceView?.width ?: 0
        val height = surfaceView?.height ?: 0
        if (format.width <= 0 || format.height <= 0 || width <= 0 || height <= 0) return "$width × $height"
        val scale = minOf(width.toFloat() / format.width, height.toFloat() / format.height)
        return "${kotlin.math.round(format.width * scale).toInt()} × ${kotlin.math.round(format.height * scale).toInt()}"
    }

    /** In the order of [PICTURE_ROWS]. */
    private fun pictureValues(): List<String?> {
        val player = player
        val format = player?.let { selectedVideoFormat(it.currentTracks) } ?: videoFormat
            ?: return listOf(activity.getString(R.string.video_signal_waiting))
        val decision = decision
        val enhanced = engaged && decision?.on == true
        val display = display()
        val hdrType = hdrType(format)
        val hdrDirect = hdrType != null && display?.supportsHdr(hdrType) == true
        val size = "${format.width} × ${format.height}"
        val surface = fitted(format)
        val mode = activity.resources.getStringArray(R.array.video_settings_frame_rate_entries)[
            PicturePolicy.frameRateMode(Settings.VIDEO_FRAME_RATE.valueCompat).ordinal]
        val hz = display?.refreshRate?.let(::formatNumber) ?: "?"
        val changes = SignalPath.videoChanges(enhanced, hdrType != null, hdrDirect)
        return listOf(
            videoDecoder?.let { decoderText(it, format.sampleMimeType) },
            videoCodecName(format.sampleMimeType, format.codecs,
                format.colorInfo?.lumaBitdepth ?: Format.NO_VALUE)?.let { (name, tenBit) ->
                if (tenBit) activity.getString(R.string.video_player_ten_bit_format, name) else name
            },
            when {
                enhanced -> activity.getString(R.string.video_signal_resolution_lanczos_format, size, surface)
                size == surface -> activity.getString(R.string.video_signal_resolution_same_format, size)
                else -> activity.getString(R.string.video_signal_resolution_scaled_format, size, surface)
            },
            when {
                hdrType == null -> activity.getString(R.string.video_signal_sdr)
                hdrDirect -> activity.getString(R.string.video_signal_hdr_direct_format, hdrName(format))
                else -> activity.getString(R.string.video_signal_hdr_mapped_format, hdrName(format))
            },
            if (format.frameRate > 0f) {
                activity.getString(R.string.video_signal_frame_rate_format, formatNumber(format.frameRate), hz, mode)
            } else {
                activity.getString(R.string.video_signal_frame_rate_unknown_format, hz, mode)
            },
            pacingText(format, hz),
            when {
                decision == null -> null
                enhanced -> activity.getString(
                    if (decision.level == PicturePolicy.Level.MAX) {
                        R.string.video_signal_stages_max
                    } else {
                        R.string.video_signal_stages_balanced
                    }
                )
                else -> activity.getString(R.string.video_signal_off_format, offText(decision.off ?: Off.NO_VIDEO))
            },
            ambientText(enhanced),
            player?.videoDecoderCounters?.droppedBufferCount?.toString(),
            resultText(changes.map {
                when (it) {
                    SignalPath.VideoChange.ENHANCED -> R.string.video_signal_change_enhanced
                    SignalPath.VideoChange.TONE_MAPPED -> R.string.video_signal_change_tone_mapped
                }
            })
        )
    }

    /** In the order of [SOUND_ROWS]. */
    private fun soundValues(): List<String?> {
        val format = audioFormat ?: return emptyList()
        val track = audioTrack
        val audio = activity.getSystemService<AudioManager>()
        val passthrough = track != null && !Util.isEncodingLinearPcm(track.encoding)
        val device = audio?.let(::outputDevice)
        val mixerRate = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val trackRate = track?.sampleRate ?: format.sampleRate
        val trackMask = track?.channelConfig ?: 0
        val channels = if (trackMask != 0) Integer.bitCount(trackMask) else format.channelCount
        val spatialized = !passthrough && audio != null && Build.VERSION.SDK_INT >= 32 &&
            isSpatialized(audio, trackRate, trackMask)
        val deviceChannels = device?.let { it.channelCounts.maxOrNull() ?: if (it.type in STEREO_DEVICES) 2 else 0 }
            ?: 0
        val speed = player?.playbackParameters?.speed ?: 1f
        val stages = soundStages() +
            listOfNotNull(speed.takeIf { it != 1f }?.let {
                activity.getString(R.string.video_signal_speed_format, formatNumber(it) + "×")
            })
        val changes = SignalPath.audioChanges(
            passthrough, soundStages(), speed, trackRate, mixerRate, channels, deviceChannels, spatialized
        )
        val channelName = VideoInfo.channels(channels)
        return listOf(
            listOfNotNull(
                audioCodecName(format.sampleMimeType) ?: format.sampleMimeType, VideoInfo.channels(format.channelCount),
                VideoInfo.sampleRate(format.sampleRate)
            ).joinToString(" · "),
            when {
                passthrough -> activity.getString(R.string.video_signal_passthrough)
                else -> audioDecoder?.let { decoderText(it, format.sampleMimeType) }
            },
            stages.joinToString(", ").ifEmpty {
                activity.getString(R.string.video_signal_none)
            },
            device?.let(::deviceName),
            VideoInfo.sampleRate(trackRate)?.let { rate ->
                val mixer = VideoInfo.sampleRate(mixerRate)
                if (SignalPath.AudioChange.RESAMPLED in changes && mixer != null) {
                    activity.getString(R.string.video_signal_rate_converted_format, rate, mixer)
                } else {
                    activity.getString(R.string.video_signal_rate_same_format, rate)
                }
            },
            channelName?.let {
                when {
                    SignalPath.AudioChange.SPATIALIZED in changes ->
                        activity.getString(R.string.video_signal_channels_spatial_format, it)
                    SignalPath.AudioChange.DOWNMIXED in changes ->
                        activity.getString(R.string.video_signal_channels_downmix_format, it, deviceChannels)
                    else -> it
                }
            },
            resultText(changes.map {
                when (it) {
                    SignalPath.AudioChange.STAGES -> R.string.video_signal_change_stages
                    SignalPath.AudioChange.SPEED -> R.string.video_signal_change_speed
                    SignalPath.AudioChange.RESAMPLED -> R.string.video_signal_change_resampled
                    SignalPath.AudioChange.DOWNMIXED -> R.string.video_signal_change_downmixed
                    SignalPath.AudioChange.SPATIALIZED -> R.string.video_signal_change_spatialized
                }
            })
        )
    }

    /** "24 fps on 120 Hz: 99.9 % even (5 vsyncs per frame)" or the pulldown, once enough frames were seen. */
    private fun pacingText(format: Format, hz: String): String? {
        val refresh = display()?.refreshRate ?: return null
        val fps = format.frameRate * (player?.playbackParameters?.speed ?: 1f)
        if (fps <= 0f) return null
        val verdict = FramePacing.verdict(FramePacing.summarize(pacing.intervals(), 1e9 / refresh), fps, refresh)
            ?: return activity.getString(R.string.video_signal_pacing_measuring)
        val rate = "${formatNumber(fps)} fps"
        val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
        return when (verdict) {
            is FramePacing.Verdict.Even -> activity.getString(
                R.string.video_signal_pacing_even_format, rate, hz, percent.format(verdict.share), verdict.vsyncs,
                verdict.frames
            )
            is FramePacing.Verdict.Pulldown -> activity.getString(
                R.string.video_signal_pacing_pulldown_format, rate, hz, verdict.longer, verdict.shorter,
                percent.format(verdict.share), percent.format(verdict.longShare),
                percent.format(verdict.expectedLongShare), verdict.frames
            )
        }
    }

    /** The room light and the gamma it gives; null while the setting is off. */
    private fun ambientText(enhanced: Boolean): String? {
        if (!ambientActive) return null
        if (lux < 0f) return activity.getString(R.string.video_signal_pacing_measuring)
        val luxText = NumberFormat.getIntegerInstance().format(lux.toDouble())
        if (!enhanced) return activity.getString(R.string.video_signal_ambient_idle_format, luxText)
        val number = NumberFormat.getInstance().apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return activity.getString(
            R.string.video_signal_ambient_format, luxText, number.format(gamma),
            number.format(AmbientGamma.REFERENCE_GAMMA)
        )
    }

    private fun resultText(changes: List<Int>): String =
        if (changes.isEmpty()) {
            activity.getString(R.string.video_signal_untouched)
        } else {
            activity.getString(
                R.string.video_signal_changed_format, changes.joinToString(", ") { activity.getString(it) }
            )
        }

    private fun offText(off: Off): String = activity.getString(
        when (off) {
            Off.NO_VIDEO -> R.string.video_picture_off_no_video
            Off.HDR -> R.string.video_picture_off_hdr
            Off.DRM -> R.string.video_picture_off_drm
            Off.SOFTWARE -> R.string.video_picture_off_software
            Off.FAILED -> R.string.video_picture_off_failed
            Off.SETTING -> R.string.video_picture_off_setting
            Off.SWITCHED_OFF -> R.string.video_picture_off_switched
            Off.TOO_SLOW -> R.string.video_picture_off_too_slow
            Off.NOT_SMALLER -> R.string.video_picture_off_not_smaller
        }
    )

    private fun decoderText(name: String, mimeType: String?): String {
        if (SignalPath.isFfmpegDecoderName(name)) return activity.getString(R.string.video_signal_ffmpeg_format, name)
        val isHardware = hardware.getOrPut(name) {
            runCatching {
                mimeType?.let { mime ->
                    MediaCodecUtil.getDecoderInfos(mime, false, false).firstOrNull { it.name == name }
                        ?.hardwareAccelerated
                }
            }.getOrNull() ?: !SignalPath.isSoftwareDecoderName(name)
        }
        return activity.getString(
            if (isHardware) R.string.video_signal_hardware_format else R.string.video_signal_software_format, name
        )
    }

    @Suppress("DEPRECATION")
    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay

    @Suppress("DEPRECATION")
    private fun Display.supportsHdr(type: Int): Boolean = hdrCapabilities?.supportedHdrTypes?.contains(type) == true

    private fun hdrType(format: Format): Int? = when {
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> Display.HdrCapabilities.HDR_TYPE_HDR10
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> Display.HdrCapabilities.HDR_TYPE_HLG
        else -> null
    }

    private fun hdrName(format: Format): String = when (hdrType(format)) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        else -> "HDR10"
    }

    /** Where media sound goes: Android says so from API 33; before, the most likely of the connected outputs. */
    private fun outputDevice(audio: AudioManager): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audio.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).maxByOrNull { devicePriority(it.type) }
        }

    private fun devicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> 5
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 4
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
        AudioDeviceInfo.TYPE_HDMI -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    private fun deviceName(device: AudioDeviceInfo): String {
        val kind = activity.getString(
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.video_signal_device_speaker
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                    R.string.video_signal_device_wired
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID ->
                    R.string.video_signal_device_bluetooth
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> R.string.video_signal_device_usb
                AudioDeviceInfo.TYPE_HDMI -> R.string.video_signal_device_hdmi
                else -> R.string.video_signal_device_other
            }
        )
        // The speaker's "product name" is the phone's model; a headphone's is its own name, worth showing.
        val name = device.productName?.toString()?.takeIf {
            it.isNotBlank() && device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it != Build.MODEL
        }
        return listOfNotNull(kind, name).joinToString(" · ")
    }

    @androidx.annotation.RequiresApi(32)
    private fun isSpatialized(audio: AudioManager, rate: Int, channelMask: Int): Boolean = runCatching {
        val spatializer = audio.spatializer
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .apply { if (rate > 0) setSampleRate(rate) }
            .apply { if (channelMask != 0) setChannelMask(channelMask) }
            .build()
        spatializer.isAvailable && spatializer.isEnabled && spatializer.canBeSpatialized(MEDIA_ATTRIBUTES, format)
    }.getOrDefault(false)

    private fun formatNumber(value: Float): String =
        NumberFormat.getInstance().apply { maximumFractionDigits = 3 }.format(value.toDouble())

    companion object {
        /** Set when the GPU failed the pipeline: no further attempt until the process restarts. */
        @Volatile
        private var failedInProcess = false

        /** Videos FFmpeg decodes (main thread): they never get the pipeline in this process. */
        private val softwareMediaIds = HashSet<String>()
        private var softwareNoticeShown = false

        private val CLEARED = Any()
        private const val SIGNAL_REFRESH_MS = 1_000L
        private const val AMBIENT_TICK_MS = 250L
        private const val PACING_LOG_FRAMES = 240L
        private val VIDEO_PROCESSING_ERRORS = setOf(
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
        )
        private val PICTURE_ROWS = listOf(
            R.string.video_signal_decoder, R.string.video_signal_codec, R.string.video_signal_resolution,
            R.string.video_signal_hdr, R.string.video_signal_frame_rate, R.string.video_signal_pacing,
            R.string.video_signal_enhancement, R.string.video_signal_ambient, R.string.video_signal_dropped,
            R.string.video_signal_result
        )
        private val SOUND_ROWS = listOf(
            R.string.video_signal_codec, R.string.video_signal_decoder, R.string.video_signal_sound_stages,
            R.string.video_signal_output, R.string.video_signal_sample_rate, R.string.video_signal_channels,
            R.string.video_signal_result
        )
        private val STEREO_DEVICES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        )
        private val MEDIA_ATTRIBUTES: android.media.AudioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    }
}
