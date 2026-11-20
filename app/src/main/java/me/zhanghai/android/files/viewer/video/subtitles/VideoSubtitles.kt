/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import java.io.IOException
import java.net.URI
import java.util.Locale
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.video.VideoInfo

/**
 * Subtitles of Ordo Video, without a new library. Tracks inside the film go through Media3's track selection.
 * Subtitle files (next to the film with the same name, `film.de.srt` …, or loaded through Ordo's own picker) are read
 * here, charset first ([SubtitleFiles.decode]), then parsed by Media3's own parsers and drawn into the PlayerView's
 * SubtitleView on the player's clock: so a delay is only a shifted lookup ([SubtitleTimeline]). Text tracks inside the
 * film take the same way: their cues from Media3's text renderer wait in an [EmbeddedCueQueue] keyed on their time, so
 * the delay, the reading aid and the SDH options reach them too; running early asks the renderer ahead ([SubtitleLead]).
 * Picture tracks (PGS, VobSub, DVB) stay with Media3's own drawing and keep their timing (the sheet says so).
 *
 * Hooks for later (e.g. an assistant): [currentChoice], [choices], [choose], [loadFile] are the public surface.
 */
@OptIn(UnstableApi::class)
class VideoSubtitles(
    private val activity: AppCompatActivity,
    private val player: ExoPlayer,
    private val playerView: PlayerView,
    /** The controls' bottom bar: subtitles move above it while it shows. */
    private val bottomBar: View,
    /** Lets text tracks inside the film run early; null: they can only run late. */
    private val lead: SubtitleLead? = null
) {
    /** A subtitle file the player can show: found next to the film, remembered, or loaded by hand. */
    data class FileSubtitle(val uri: String, val fileName: String, val language: String?, val forced: Boolean)

    private val store = SubtitleStore(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaId: String? = null
    private var entry = SubtitleStore.Entry(null)
    private var files: List<FileSubtitle> = emptyList()
    private var filesReady = false
    private var initialApplied = false
    private var scanJob: Job? = null
    private var loadJob: Job? = null
    private var timeline: SubtitleTimeline<Cue>? = null
    /** The file on screen and the charset it was read with. */
    var activeFile: FileSubtitle? = null
        private set
    var usedCharset: String? = null
        private set
    // The content key of the file on screen: a manual charset belongs to it alone.
    private var activeKey: String? = null
    private var shownCues: List<Cue> = emptyList()
    // A text track inside the film on screen through Ordo's own drawing (not a picture track, not a file).
    private val embedded = EmbeddedCueQueue<Cue> { cues -> cues.joinToString("\n") { it.text?.toString().orEmpty() } }
    private var embeddedFormat: Format? = null
    private var speakers = Sdh.SpeakerColors()
    private var controlsShown = false
    private var shownLift = -1f
    private var shownRatio = -1f
    /** Closes an open sheet: it belongs to one film and to this screen. */
    var dismissSheet: (() -> Unit)? = null

    private val picker = activity.registerForActivityResult(PickSubtitleContract()) { path ->
        path?.let(::loadPicked)
    }

    private val ticker = object : Runnable {
        override fun run() {
            showCues(force = false)
            handler.removeCallbacks(this)
            if ((timeline != null || embeddedFormat != null) && player.isPlaying) handler.postDelayed(this, TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = startItem(mediaItem)

        override fun onTracksChanged(tracks: Tracks) {
            maybeApplyInitial()
            updateEmbedded()
            updateLift()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = ticker.run()

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateLift()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // The renderer sends the new place's cues again.
            embedded.clear()
            ticker.run()
        }

        // The PlayerView clears its SubtitleView when the (switched off) text renderer says so; a file stays.
        override fun onCues(cueGroup: CueGroup) {
            if (timeline == null && embeddedFormat != null) {
                val options = options()
                val time = cueGroup.presentationTimeUs.takeIf { it != C.TIME_UNSET } ?: (player.currentPosition * 1000)
                embedded.add(time, cueGroup.cues.mapNotNull { prepare(it, options, speakers) })
            }
            if (timeline != null || embeddedFormat != null) showCues(force = true)
        }
    }

    init {
        // Without a choice only forced tracks come on by themselves (an MKV's "default" flag is not a wish).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        player.addListener(listener)
        // The controls start shown; a turn, a resize or the bar's own layout moves the lines.
        controlsShown = true
        applyStyle(store.style)
        playerView.subtitleView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLift() }
        bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLift() }
    }

    fun release() {
        dismissSheet?.invoke()
        handler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        lead?.leadUs = 0
    }

    // --- Public surface (the sheet and later hooks) ---

    var style: SubtitleStyle
        get() = store.style
        set(value) {
            val reread = value.fileStyles != store.style.fileStyles || value.system != store.style.system
            store.style = value
            applyStyle(value)
            // Cues already prepared keep their old file styles: read the file on screen again, or have the renderer
            // send a track's cues again (a seek to here, one short re-buffer).
            if (reread) {
                activeFile?.let(::loadFile)
                if (activeFile == null && embeddedFormat != null) {
                    embedded.clear()
                    player.seekTo(player.currentPosition)
                }
            }
        }

    val delayMs: Long
        get() = entry.delayMs

    /** The manual charset of the file on screen; null: detected. */
    val charsetOverride: String?
        get() = entry.charsetFor(activeKey)

    /** "Off", the files, then the film's text tracks, with what is on now. */
    fun choices(): List<SubtitleChoice.Candidate> =
        listOf(SubtitleChoice.Candidate(SubtitleChoice.Off, null)) +
            fileCandidates() + trackCandidates().map { it.first }

    fun currentChoice(): SubtitleChoice {
        activeFile?.let { return SubtitleChoice.File(it.uri) }
        loadJob?.takeIf { it.isActive }?.let { (entry.choice as? SubtitleChoice.File)?.let { return it } }
        if (C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) return SubtitleChoice.Off
        return trackCandidates().firstOrNull { (_, groupIndex) ->
            val (group, index) = groupIndex
            group.isTrackSelected(index)
        }?.first?.choice ?: SubtitleChoice.Off
    }

    /**
     * "Besser verstehen" (#98): subtitles in the preferred language (else a file without a language), unless some
     * already show. Not remembered for the film. False when there is nothing to show.
     */
    fun turnOnForUnderstanding(): Boolean {
        if (currentChoice() != SubtitleChoice.Off) return true
        // No audio language: the "sound is already in my language" rule of the automatic choice does not apply.
        val choice = SubtitleChoice.pick(
            null, true, preferredLanguage(), null, fileCandidates(), trackCandidates().map { it.first }
        ) ?: return false
        choose(choice, remember = false)
        return true
    }

    /** Takes a subtitle file off the screen when something else picks the subtitles (#100's session command). */
    fun dropFile() = stopFile()

    /** Shows [choice]; a choice by the viewer is remembered for this film. */
    fun choose(choice: SubtitleChoice, remember: Boolean = true) {
        val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        when (choice) {
            SubtitleChoice.Off -> {
                stopFile()
                parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            is SubtitleChoice.Track -> {
                stopFile()
                val (group, index) = findTrack(choice) ?: return
                parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            }
            is SubtitleChoice.File -> {
                parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                files.firstOrNull { it.uri == choice.uri }?.let(::loadFile) ?: return
            }
        }
        player.trackSelectionParameters = parameters.build()
        if (remember) save(entry.copy(choice = choice))
    }

    fun changeDelay(deltaMs: Long) {
        save(entry.copy(delayMs = SubtitleTimeline.clampDelay(entry.delayMs + deltaMs)))
        val oldLead = lead?.leadUs
        updateLead()
        // A renderer jumping ahead skips the lines in between: a seek to here sends them again (one short re-buffer).
        if (lead != null && lead.leadUs != oldLead) player.seekTo(player.currentPosition)
        showCues(force = false)
    }

    /** null: detect again. The file on screen is read once more with it. */
    fun changeCharset(charset: String?) {
        val file = activeFile ?: return
        save(entry.copy(charset = charset, charsetFile = activeKey.takeIf { charset != null }))
        loadFile(file)
    }

    /** Ordo's own picker, opened in the film's folder. */
    fun pickFile() {
        picker.launch(currentPath()?.parent)
    }

    /** Subtitle files as rows of "Video info", like its rows of the film's tracks (language, then the file). */
    fun infoRows(): List<List<String>> =
        files.map { listOfNotNull(VideoInfo.language(it.language), it.fileName) }

    fun onControlsShown(shown: Boolean) {
        controlsShown = shown
        updateLift()
    }

    /**
     * Size and place in the picture, not in the view (BBC 9.2.1: a share of the active video height): in portrait the
     * view is 2.8 times the picture's height, and the lines stood large in the black bar (A8, 20.11.2026). The chosen
     * distance counts from the picture's bottom; while the controls show, the lines go above their bar. Measured on
     * screen, so letterboxing and the bar's insets count.
     */
    private fun updateLift() {
        val view = playerView.subtitleView ?: return
        val height = view.height
        if (height <= 0) return
        val viewBottom = screenTop(view) + height
        // The content frame and the surface fill the view and player.videoSize is the effect pipeline's output (Ordo
        // letterboxes in its own shader): the picture comes from the film's own format.
        val format = player.videoFormat
        val pictureHeight = pictureHeight(
            format?.width ?: 0, format?.height ?: 0, format?.rotationDegrees ?: 0,
            format?.pixelWidthHeightRatio ?: 1f, playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT,
            view.width, height
        )
        val below = (height - pictureHeight) / 2
        val style = store.style
        var lift = (below + style.bottomPercent / 100f * pictureHeight) / height
        if (controlsShown && bottomBar.height > 0) {
            lift = maxOf(lift, (viewBottom - screenTop(bottomBar)).toFloat() / height + BAR_GAP)
        }
        lift = lift.coerceAtMost(MAX_LIFT)
        val ratio = pictureHeight.toFloat() / height
        if (lift != shownLift || ratio != shownRatio) {
            shownLift = lift
            shownRatio = ratio
            view.setBottomPaddingFraction(lift)
            setTextSize(view, style, ratio)
            showCues(force = true)
        }
    }

    private fun screenTop(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[1]

    // --- Per film ---

    private fun startItem(item: MediaItem?) {
        dismissSheet?.invoke()
        stopFile()
        scanJob?.cancel()
        mediaId = item?.mediaId
        files = emptyList()
        embedded.clear()
        speakers = Sdh.SpeakerColors()
        filesReady = false
        initialApplied = false
        val path = currentPath()
        entry = path?.let { mediaId?.let(store::read) } ?: SubtitleStore.Entry(null)
        updateLead()
        // Left from the previous film: switched-off text would stay off for this one.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        if (path == null) {
            filesReady = true
            maybeApplyInitial()
            return
        }
        val remembered = entry.choice as? SubtitleChoice.File
        val forId = mediaId
        scanJob = activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { findFiles(path, remembered) }.getOrNull() }
            if (forId != mediaId) return@launch
            files = found.orEmpty()
            filesReady = true
            maybeApplyInitial()
        }
    }

    private fun findFiles(video: Path, remembered: SubtitleChoice.File?): List<FileSubtitle> {
        val folder = video.parent ?: return emptyList()
        val byName = folder.newDirectoryStream().use { stream -> stream.associateBy { it.fileName.toString() } }
        val found = SubtitleFiles.sidecars(video.fileName.toString(), byName.keys.toList()).mapNotNull { sidecar ->
            byName[sidecar.fileName]?.let {
                FileSubtitle(it.toUri().toString(), sidecar.fileName, sidecar.language, sidecar.forced)
            }
        }
        if (remembered == null || found.any { it.uri == remembered.uri }) return found
        val loaded = runCatching { Paths.get(URI.create(remembered.uri)) }.getOrNull()
            ?.takeIf { it.exists() } ?: return found
        return found + FileSubtitle(remembered.uri, loaded.fileName.toString(), null, false)
    }

    private fun maybeApplyInitial() {
        if (initialApplied || !filesReady || player.currentMediaItem?.mediaId != mediaId) return
        val tracks = player.currentTracks
        if (tracks.groups.isEmpty()) return
        initialApplied = true
        val audioLanguage = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
            ?.language
        SubtitleChoice.pick(
            entry.choice, Settings.VIDEO_SUBTITLES_AUTO.valueCompat, preferredLanguage(), audioLanguage,
            fileCandidates(), trackCandidates().map { it.first }
        )?.let { choose(it, remember = false) }
    }

    private fun preferredLanguage(): String =
        Settings.VIDEO_SUBTITLE_LANGUAGE.valueCompat.takeIf { it == "de" || it == "en" }
            ?: Locale.getDefault().language

    private fun fileCandidates(): List<SubtitleChoice.Candidate> =
        files.map { SubtitleChoice.Candidate(SubtitleChoice.File(it.uri), it.language, it.forced) }

    /** Text tracks this device can show, each with its group and index. */
    private fun trackCandidates(): List<Pair<SubtitleChoice.Candidate, Pair<Tracks.Group, Int>>> =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map { index ->
                val format = group.getTrackFormat(index)
                // Without an ID the place keeps two tracks of one language apart.
                SubtitleChoice.Candidate(
                    SubtitleChoice.Track(format.id ?: "#${group.mediaTrackGroup.id}:$index", format.language),
                    format.language,
                    format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
                ) to (group to index)
            }
        }

    private fun findTrack(choice: SubtitleChoice.Track): Pair<Tracks.Group, Int>? {
        val all = trackCandidates()
        return (all.firstOrNull { it.first.choice == choice }
            ?: all.firstOrNull { (it.first.choice as SubtitleChoice.Track).id == choice.id && choice.id != null })
            ?.second
    }

    /** The format of a text track: language, its kind, forced; for the sheet's rows. */
    fun trackFormat(choice: SubtitleChoice.Track): Format? =
        findTrack(choice)?.let { (group, index) -> group.getTrackFormat(index) }

    fun file(choice: SubtitleChoice.File): FileSubtitle? = files.firstOrNull { it.uri == choice.uri }

    private fun save(newEntry: SubtitleStore.Entry) {
        entry = newEntry
        val id = mediaId ?: return
        // As "Continue watching": nothing for other apps' content:// films, nothing with "don't remember places".
        if (currentPath() == null || Settings.VIDEO_NO_HISTORY.valueCompat) return
        store.write(id, newEntry)
    }

    private fun currentPath(): Path? =
        mediaId?.takeIf { !it.startsWith("${ContentResolver.SCHEME_CONTENT}:") }
            ?.let { runCatching { Paths.get(URI.create(it)) }.getOrNull() }

    // --- Files on screen ---

    private fun loadPicked(path: Path) {
        val name = path.fileName?.toString().orEmpty()
        if (SubtitleFiles.mimeType(name) == null) {
            Toast.makeText(activity, R.string.video_subtitles_not_subtitle, Toast.LENGTH_LONG).show()
            return
        }
        val uri = path.toUri().toString()
        if (files.none { it.uri == uri }) {
            files = files + FileSubtitle(uri, name, SubtitleFiles.sidecars(name, listOf(name)).firstOrNull()?.language, false)
        }
        choose(SubtitleChoice.File(uri))
    }

    private fun loadFile(file: FileSubtitle) {
        loadJob?.cancel()
        val forId = mediaId
        val loadEntry = entry
        val options = options()
        loadJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { parse(file, loadEntry, options) }
            }
            if (forId != mediaId) return@launch
            result.onSuccess { parsed ->
                timeline = parsed.timeline
                updateEmbedded()
                activeFile = file
                usedCharset = parsed.charset
                activeKey = parsed.contentKey
                // A Unicode file ignored the manual charset: forget it, the sheet shows "Automatic (UTF-8)" again.
                val manual = entry.charsetFor(parsed.contentKey)
                if (manual != null && !manual.equals(parsed.charset, ignoreCase = true)) {
                    save(entry.copy(charset = null, charsetFile = null))
                }
                // Our own cues carry only what prepare() kept: italics, underline, speaker colours.
                playerView.subtitleView?.setApplyEmbeddedStyles(true)
                showCues(force = true)
                ticker.run()
            }.onFailure {
                it.printStackTrace()
                stopFile()
                // A file that cannot be read is not kept as this film's choice; the film's tracks come back.
                if (entry.choice == SubtitleChoice.File(file.uri)) save(entry.copy(choice = null))
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                Toast.makeText(
                    activity,
                    if (it is TooLargeException) R.string.video_subtitles_too_large else R.string.video_subtitles_read_error,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun stopFile() {
        loadJob?.cancel()
        handler.removeCallbacks(ticker)
        if (timeline != null) {
            playerView.subtitleView?.setCues(emptyList())
            playerView.subtitleView?.setApplyEmbeddedStyles(false)
        }
        slowDown(false)
        timeline = null
        activeFile = null
        usedCharset = null
        activeKey = null
        shownCues = emptyList()
    }

    /** What the settings ask of a file or a track's text now. */
    private fun options() = Options(
        ReadingAid.level(Settings.VIDEO_SUBTITLE_READING.valueCompat),
        Settings.VIDEO_SUBTITLE_SOUNDS.valueCompat, Settings.VIDEO_SUBTITLE_SPEAKER_COLORS.valueCompat,
        store.style.let { it.fileStyles && !it.system }
    )

    /** Whether a text track inside the film shows (Ordo draws it then); a picture track stays Media3's. */
    private fun updateEmbedded() {
        val format = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
            ?.takeIf { timeline == null && !isBitmap(it) && !player.trackSelectionParameters.disabledTrackTypes
                .contains(C.TRACK_TYPE_TEXT) }
        if (format != embeddedFormat) {
            embeddedFormat = format
            embedded.clear()
            shownCues = emptyList()
            playerView.subtitleView?.setApplyEmbeddedStyles(timeline != null || format != null)
        }
        updateLead()
        ticker.run()
    }

    /** Early cues come from asking the renderer ahead; late ones wait in the queue. */
    private fun updateLead() {
        lead?.leadUs = if (embeddedFormat != null) maxOf(0L, -entry.delayMs) * 1000 else 0L
    }

    private fun showCues(force: Boolean) {
        if (timeline == null && embeddedFormat != null) {
            val cues = embedded.cuesAt(
                player.currentPosition * 1000, entry.delayMs * 1000,
                ReadingAid.level(Settings.VIDEO_SUBTITLE_READING.valueCompat)
            )
            if (!force && cues == shownCues) return
            shownCues = cues
            playerView.subtitleView?.setCues(intoPicture(cues, shownRatio))
            return
        }
        val timeline = timeline ?: return
        val cues = timeline.cuesAt(player.currentPosition * 1000, entry.delayMs * 1000)
        slowDown(Settings.VIDEO_SUBTITLE_SLOW_DOWN.valueCompat &&
            timeline.fastAt(player.currentPosition * 1000, entry.delayMs * 1000))
        if (!force && cues == shownCues) return
        shownCues = cues
        playerView.subtitleView?.setCues(intoPicture(cues, shownRatio))
    }

    // The speed before "slow down for fast text"; 0 while it is not slowed.
    private var speedBeforeSlow = 0f

    /** True while the screen's "hold for 2x" runs: it owns the speed then and restores the viewer's own after. */
    var holdActive: () -> Boolean = { false }

    /** The viewer's speed without the reading aid's 0.9x: for the saved state and for "hold for 2x". */
    fun userSpeed(current: Float): Float = if (speedBeforeSlow > 0f) speedBeforeSlow else current

    /** 0.9x while a too fast line shows (the pitch stays); the viewer's own speed change in between wins. */
    private fun slowDown(slow: Boolean) {
        if (holdActive()) {
            speedBeforeSlow = 0f
            return
        }
        val speed = player.playbackParameters.speed
        if (slow && speedBeforeSlow == 0f) {
            speedBeforeSlow = speed
            player.setPlaybackSpeed(speed * ReadingAid.SLOW_FACTOR)
        } else if (!slow && speedBeforeSlow > 0f) {
            if (kotlin.math.abs(speed - speedBeforeSlow * ReadingAid.SLOW_FACTOR) < 0.001f) {
                player.setPlaybackSpeed(speedBeforeSlow)
            }
            speedBeforeSlow = 0f
        }
    }

    private fun applyStyle(style: SubtitleStyle) {
        val view = playerView.subtitleView ?: return
        applyStyle(view, style)
        // The chosen colour and size win over a track's own; positions (ASS \pos, VTT lines) stay. A file's cues are
        // cleaned in prepare(), so their italics and speaker colours may show.
        view.setApplyEmbeddedStyles(timeline != null || embeddedFormat != null)
        shownLift = -1f
        shownRatio = -1f
        updateLift()
    }

    private class TooLargeException : IOException()

    private class PickSubtitleContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == AppCompatActivity.RESULT_OK) intent?.extraPath else null
    }

    companion object {
        private const val TICK_MS = 50L
        private const val MAX_LIFT = 0.5f
        /** Air between the lines and the controls' bar, as a fraction of the picture's height. */
        private const val BAR_GAP = 0.03f

        /**
         * The subtitle's own format: since Media3 1.4 a track inside the film is parsed while extracting and comes as
         * "application/x-media3-cues", its real kind (SRT, PGS …) then stands in codecs.
         */
        fun subtitleMimeType(format: Format?): String? =
            if (format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format?.sampleMimeType

        /** Bitmap subtitles (PGS, VobSub, DVB): Media3 draws them as pictures, the style cannot reach them. */
        fun isBitmap(format: Format?): Boolean = subtitleMimeType(format) in setOf(
            MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS
        )

        /** What the settings ask of a file: reading aid limit (0 off), sound descriptions, speaker colours. */
        private class Options(val cps: Int, val sounds: Boolean, val speakerColors: Boolean, val fileStyles: Boolean)

        private class Parsed(val timeline: SubtitleTimeline<Cue>, val charset: String, val contentKey: String)

        /** Reads, decodes (the entry's manual charset only for this very file) and parses a subtitle file. */
        private fun parse(file: FileSubtitle, entry: SubtitleStore.Entry, options: Options): Parsed {
            val path = Paths.get(URI.create(file.uri))
            val bytes = path.newInputStream().use { input ->
                val data = input.readNBytesCompat(SubtitleFiles.MAX_BYTES + 1)
                if (data.size > SubtitleFiles.MAX_BYTES) throw TooLargeException()
                data
            }
            val contentKey = SubtitleFiles.contentKey(bytes)
            val decoded = SubtitleFiles.decode(bytes, entry.charsetFor(contentKey), file.language)
            val mimeType = SubtitleFiles.mimeType(file.fileName) ?: throw IOException("Not a subtitle: ${file.fileName}")
            val parser = DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mimeType).build())
            val entries = ArrayList<SubtitleTimeline.Entry<Cue>>()
            val speakers = Sdh.SpeakerColors()
            var characters = 0L
            parser.parse(decoded.text.toByteArray(Charsets.UTF_8), SubtitleParser.OutputOptions.allCues()) cues@{
                // The caps come before any work on a cue: a crafted file costs no more than a long film.
                if (entries.size >= SubtitleTimeline.MAX_CUES || characters > SubtitleTimeline.MAX_CHARACTERS) {
                    return@cues
                }
                characters += it.cues.sumOf { cue -> cue.text?.length ?: 0 }
                val end = if (it.durationUs == C.TIME_UNSET) Long.MAX_VALUE else it.endTimeUs
                val cues = it.cues.mapNotNull { cue -> prepare(cue, options, speakers) }
                if (cues.isNotEmpty()) entries += SubtitleTimeline.Entry(it.startTimeUs, end, cues)
            }
            val extended = ReadingAid.extend(entries, options.cps) { cues ->
                cues.joinToString("\n") { it.text?.toString().orEmpty() }
            }
            return Parsed(SubtitleTimeline(extended), decoded.charset, contentKey)
        }

        /**
         * A file's cue as Ordo shows it: only italics, bold and underline of its own styling stay (the chosen colour
         * and size win), sound descriptions go if the setting says so, speaker names get their colour. null: nothing
         * left to show.
         */
        private fun prepare(cue: Cue, options: Options, speakers: Sdh.SpeakerColors): Cue? {
            val source = cue.text ?: return cue
            // Only the cheap span cleanup for a giant crafted line; the Sdh rules skip it (Sdh.MAX_LINE).
            val text = SpannableStringBuilder(source)
            if (!options.fileStyles) {
                for (span in text.getSpans(0, text.length, Any::class.java)) {
                    if (span !is StyleSpan && span !is UnderlineSpan) text.removeSpan(span)
                }
            }
            if (!options.sounds) {
                deleteRanges(text, Sdh.soundRanges(text.toString()))
                deleteRanges(text, Sdh.emptyLineRanges(text.toString()))
                if (text.isBlank()) return null
            }
            if (options.speakerColors) {
                for ((range, name) in Sdh.speakers(text.toString())) {
                    text.setSpan(
                        ForegroundColorSpan(speakers.colorOf(name)), range.first, range.last + 2,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            val builder = cue.buildUpon().setText(text)
            if (!options.fileStyles) builder.setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).clearWindowColor()
            // WebVTT's default line and ASS's bottom alignments place the line by the view's edge, past Media3's bottom
            // padding: so it sat on the controls (A8, 20.11.2026). As an unplaced line it takes Ordo's distance.
            if (isAtBottom(cue)) builder.setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.TYPE_UNSET)
            return builder.build()
        }

        /** A line its format put at the bottom: a negative line number (WebVTT "auto") or a fraction low down. */
        internal fun isAtBottom(cue: Cue): Boolean = cue.line != Cue.DIMEN_UNSET && when (cue.lineType) {
            Cue.LINE_TYPE_NUMBER -> cue.line < 0
            Cue.LINE_TYPE_FRACTION -> cue.line >= BOTTOM_FRACTION && cue.lineAnchor != Cue.ANCHOR_TYPE_START
            else -> false
        }

        private const val BOTTOM_FRACTION = 0.8f

        /**
         * Lines placed by a fraction of the height (ASS \an7-9, \pos; TTML regions) count in the picture, not in the
         * view: in portrait a top line would stand in the black bar. [ratio]: picture height / view height.
         */
        internal fun intoPicture(cues: List<Cue>, ratio: Float): List<Cue> {
            if (ratio <= 0f || ratio >= 1f) return cues
            return cues.map { cue ->
                if (cue.line == Cue.DIMEN_UNSET || cue.lineType != Cue.LINE_TYPE_FRACTION) return@map cue
                cue.buildUpon().setLine((1 - ratio) / 2 + cue.line * ratio, Cue.LINE_TYPE_FRACTION).build()
            }
        }

        /**
         * The visible picture's height in a view of [width] × [height] for a film of [videoWidth] × [videoHeight]
         * stored turned by [rotation]: letterboxed only when fitted.
         */
        internal fun pictureHeight(
            videoWidth: Int,
            videoHeight: Int,
            rotation: Int,
            pixelRatio: Float,
            fit: Boolean,
            width: Int,
            height: Int
        ): Int {
            if (!fit || videoWidth <= 0 || videoHeight <= 0 || width <= 0) return height
            val turned = rotation % 180 != 0
            val aspect = videoWidth * pixelRatio / videoHeight
            return minOf(height, (width * (if (turned) aspect else 1 / aspect)).toInt())
        }

        /**
         * Ordo's look on any SubtitleView: the player's and the sheet's preview. The text size goes with
         * [setTextSize], which needs the picture's share of the view.
         */
        fun applyStyle(view: SubtitleView, style: SubtitleStyle) {
            setTextSize(view, style, 1f)
            if (style.system) {
                view.setUserDefaultStyle()
            } else {
                val (background, edge) = when (style.background) {
                    SubtitleStyle.Background.OUTLINE -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    SubtitleStyle.Background.SHADOW -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                    SubtitleStyle.Background.BOX ->
                        ((SubtitleStyle.boxAlpha(style.color.argb) * 255).toInt() shl 24) to
                            CaptionStyleCompat.EDGE_TYPE_NONE
                }
                view.setStyle(CaptionStyleCompat(
                    style.color.argb, background, Color.TRANSPARENT, edge, Color.BLACK,
                    Typeface.create(style.font.family, Typeface.NORMAL)
                ))
            }
            view.setApplyEmbeddedFontSizes(style.fileStyles && !style.system)
        }

        /**
         * The text size as a share of the picture's height, [pictureRatio] = picture height / view height. "Like the
         * system" takes Android's caption font scale on Ordo's base size (Media3's own would count the whole view).
         */
        fun setTextSize(view: SubtitleView, style: SubtitleStyle, pictureRatio: Float) {
            val phone = view.resources.configuration.smallestScreenWidthDp < 600
            val fraction = if (style.system) {
                val captioning = view.context.getSystemService(android.view.accessibility.CaptioningManager::class.java)
                SubtitleStyle.baseFraction(phone) * (captioning?.takeIf { it.isEnabled }?.fontScale ?: 1f)
            } else {
                style.textFraction(phone)
            }
            view.setFractionalTextSize(fraction * pictureRatio)
        }

        private fun deleteRanges(text: SpannableStringBuilder, ranges: List<IntRange>) {
            ranges.sortedByDescending { it.first }.forEach { text.delete(it.first, it.last + 1) }
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (output.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
