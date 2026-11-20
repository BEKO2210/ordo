/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.subtitles

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
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
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = ticker.run()

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
        applyStyle(store.style)
        // The controls start shown; a turn or a resize moves their bar.
        playerView.subtitleView?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) onControlsShown(controlsShown)
        }
        controlsShown = true
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
            store.style = value
            applyStyle(value)
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
        val view = playerView.subtitleView ?: return
        val height = view.height
        val covered = ((bottomBar.parent as? View)?.height ?: 0) - bottomBar.top
        view.setBottomPaddingFraction(
            if (shown && height > 0 && covered > 0) {
                (covered.toFloat() / height).coerceIn(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION, MAX_LIFT)
            } else {
                SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
            }
        )
    }

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
        Settings.VIDEO_SUBTITLE_SOUNDS.valueCompat, Settings.VIDEO_SUBTITLE_SPEAKER_COLORS.valueCompat
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
            playerView.subtitleView?.setCues(cues)
            return
        }
        val timeline = timeline ?: return
        val cues = timeline.cuesAt(player.currentPosition * 1000, entry.delayMs * 1000)
        slowDown(Settings.VIDEO_SUBTITLE_SLOW_DOWN.valueCompat &&
            timeline.fastAt(player.currentPosition * 1000, entry.delayMs * 1000))
        if (!force && cues == shownCues) return
        shownCues = cues
        playerView.subtitleView?.setCues(cues)
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
        val (background, edge) = when (style.background) {
            SubtitleStyle.Background.NONE -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleStyle.Background.SHADOW -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            SubtitleStyle.Background.BOX ->
                ((SubtitleStyle.boxAlpha(style.color.argb) * 255).toInt() shl 24) to CaptionStyleCompat.EDGE_TYPE_NONE
        }
        view.setStyle(CaptionStyleCompat(style.color.argb, background, Color.TRANSPARENT, edge, Color.BLACK, null))
        // The chosen colour and size win over a track's own; positions (ASS \pos, VTT lines) stay. A file's cues are
        // cleaned in prepare(), so their italics and speaker colours may show.
        view.setApplyEmbeddedStyles(timeline != null || embeddedFormat != null)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.size.factor)
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
        private class Options(val cps: Int, val sounds: Boolean, val speakerColors: Boolean)

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
            for (span in text.getSpans(0, text.length, Any::class.java)) {
                if (span !is StyleSpan && span !is UnderlineSpan) text.removeSpan(span)
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
            return cue.buildUpon().setText(text).setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).clearWindowColor().build()
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
