/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import me.zhanghai.android.files.theme.custom.AppAccentActivity
import me.zhanghai.android.files.settings.SettingLiveData
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.Util
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import java.text.Collator
import java.util.Formatter
import java.net.URI
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.VideoLauncherActivityBinding
import me.zhanghai.android.files.databinding.VideoLibraryItemBinding
import me.zhanghai.android.files.databinding.VideoLibraryRowBinding
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.audio.musicContentWidthDp
import me.zhanghai.android.files.viewer.video.network.VideoBuffering
import me.zhanghai.android.files.viewer.video.network.VideoNetworkLibrary
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.trash.Trash
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.viewer.video.library.ContinueCard
import me.zhanghai.android.files.viewer.video.library.Series
import me.zhanghai.android.files.viewer.video.library.VideoShelves
import me.zhanghai.android.files.viewer.video.library.VideoWatchedStore

/**
 * What the "Ordo Video" icon opens: "Continue watching" from the player's saved places, then every video on the
 * device (MediaStore, newest first), filterable by folder. A tap plays the video with its folder as the queue.
 */
class VideoLauncherActivity : AppActivity(), AppAccentActivity {
    override val appAccent: SettingLiveData<String>
        get() = Settings.VIDEO_ACCENT

    private lateinit var binding: VideoLauncherActivityBinding
    private var library: VideoLibrary? = null
    private var shelves: VideoShelves? = null
    private var continueCards = emptyList<ContinueCard>()
    // Saved places by path (progress bars on every card) and "watched" marks by VideoShelves.watchedKey.
    private var positions = emptyMap<String, Long>()
    private var watched = emptyMap<String, Long>()
    private var folder: String? = null
    // The "Network" chip's videos (bookmarked server folders), read only once it is tapped.
    private var networkVideos: List<VideoItem>? = null
    private var networkLoading = false
    // The series shown on its own page (its seriesKey), the search text and the "Unwatched only" filter.
    private var openSeries: String? = null
    private var query = ""
    private var unwatchedOnly = false
    // Selected videos by path, in the order they were picked; non-empty means selection mode.
    private val selected = LinkedHashSet<String>()
    // Sent to the trash or the vault: gone from the list before MediaStore notices.
    private val removed = HashSet<String>()
    private val adapter = Adapter()
    private val timeBuilder = StringBuilder()
    private val timeFormatter = Formatter(timeBuilder)
    private var columns = 2
    private var listMode = false
    private var side = 0
    // A few screens of 16:9 thumbnails; the platform caches them on disk anyway (MediaStore).
    private val thumbnails = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val accessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = VideoLauncherActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        folder = savedInstanceState?.getString(STATE_FOLDER)
        openSeries = savedInstanceState?.getString(STATE_SERIES)
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        unwatchedOnly = savedInstanceState?.getBoolean(STATE_UNWATCHED) == true
        binding.accessButton.setOnClickListener { requestAccess() }
        binding.toolbar.setOnMenuItemClickListener { onMenuItem(it.itemId) }
        binding.toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, backCallback)
        updateToolbar()
        val recycler = binding.recyclerView
        recycler.adapter = adapter
        recycler.layoutManager = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.getItemViewType(position).let { it == TYPE_VIDEO || it == TYPE_ROW }) 1 else spanCount
            }
        }
        recycler.addItemDecoration(Spacing())
        binding.root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) recycler.post { updateWidths() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FOLDER, folder)
        outState.putString(STATE_SERIES, openSeries)
        outState.putString(STATE_QUERY, query)
        outState.putBoolean(STATE_UNWATCHED, unwatchedOnly)
    }

    // Again on every return: new videos, and the place just watched for "Continue watching".
    override fun onStart() {
        super.onStart()
        load()
    }

    private fun hasStorageAccess(): Boolean =
        if (Environment::class.supportsExternalStorageManager()) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Environment::class.supportsExternalStorageManager()) {
            accessLauncher.launch(Environment::class.createManageAppAllFilesAccessPermissionIntent(packageName))
        } else {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun load() {
        if (!hasStorageAccess()) {
            library = null
            render()
            return
        }
        val context = applicationContext
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        val sort = VideoPrefs.sort(Settings.VIDEO_SORT.valueCompat)
        val hiddenFolders = Settings.VIDEO_HIDDEN_FOLDERS.valueCompat
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val collator = Collator.getInstance()
                val loaded = VideoLibrary(queryVideos(context), showHiddenFiles, collator, sort, hiddenFolders)
                val entries = VideoResumeStore(context).readAll()
                val resumed = loaded.continueWatching(entries, Int.MAX_VALUE)
                val watched = VideoWatchedStore(context).all()
                val shelves = VideoShelves(loaded.videos, collator)
                Loaded(
                    loaded, shelves, shelves.continueCards(resumed, entries, watched),
                    resumed.associate { it.video.path to it.positionMs }, watched
                )
            }
            library = loaded.library
            // MediaStore and the files are the truth again: a failed trash or vault job brings its videos back.
            removed.clear()
            shelves = loaded.shelves
            continueCards = loaded.continueCards
            positions = loaded.positions
            watched = loaded.watched
            selected.retainAll(loaded.library.videos.map { it.path }.toSet())
            updateToolbar()
            // The view (grid or list) may have changed on the settings page.
            if (listMode != (Settings.VIDEO_VIEW.valueCompat == "list")) updateWidths()
            render()
        }
    }

    private fun render() {
        val library = library
        val folders = library?.folders.orEmpty()
        val hasNetwork = VideoNetworkLibrary.folders().isNotEmpty()
        if (folder == NETWORK && !hasNetwork) folder = null
        if (folder != null && folder != NETWORK && folders.none { it.path == folder }) folder = null
        renderFolders(folders, hasNetwork)
        val rows = ArrayList<Row>()
        val shelves = shelves
        val series = shelves?.series?.find { seriesKey(it) == openSeries }
        if (series == null && openSeries != null && shelves != null) {
            openSeries = null
            updateToolbar()
        }
        // The "Network" chip lists the server folders; a search still searches the device library.
        val showNetwork = folder == NETWORK && query.isBlank()
        if (showNetwork) {
            val videos = networkVideos ?: VideoNetworkLibrary.cachedOrNull()?.also { networkVideos = it }
            if (videos == null) loadNetwork()
            if (!videos.isNullOrEmpty()) {
                rows += Row.Section(getString(R.string.video_library_network))
                videos.mapTo(rows) { Row.Video(it) }
            }
        } else if (library != null && shelves != null) {
            val shown = { video: VideoItem -> video.path !in removed && !(unwatchedOnly && isWatched(video)) }
            when {
                query.isNotBlank() -> {
                    val found = library.videos.filter {
                        shown(it) && VideoShelves.matches(it, shelves.seriesOf(it)?.name, query)
                    }
                    if (found.isNotEmpty()) rows += Row.Section(getString(R.string.video_library_results))
                    found.mapTo(rows) { Row.Video(it) }
                }
                // A series' own page: its seasons in order, each episode with its number.
                series != null -> for (season in series.seasons) {
                    val episodes = season.episodes.filter { shown(it.video) }
                    if (episodes.isEmpty()) continue
                    rows += Row.Section(getString(R.string.video_library_season_format, season.number))
                    episodes.mapTo(rows) {
                        Row.Video(it.video, getString(R.string.video_library_episode_format, it.tag.episode))
                    }
                }
                else -> {
                    val shownContinue = continueCards
                        .filter { (folder == null || it.video.folder == folder) && shown(it.video) }
                        .takeIf { Settings.VIDEO_SHOW_CONTINUE.valueCompat }.orEmpty()
                    addShelf(rows, R.string.video_library_continue, shownContinue.map(::continueCard))
                    if (folder == null) {
                        addShelf(rows, R.string.video_library_series, shelves.series
                            .filter { s -> s.episodes.any { shown(it.video) } }.map { Card.Show(it) })
                        addShelf(rows, R.string.video_library_films,
                            shelves.films.filter(shown).take(VideoShelves.SHELF_MAX).map { Card.Video(it, null) })
                        addShelf(rows, R.string.video_library_recent,
                            shelves.recentlyAdded(System.currentTimeMillis()).filter(shown)
                                .map { Card.Video(it, null) })
                    }
                    val videos = (folders.find { it.path == folder }?.videos ?: library.videos).filter(shown)
                    if (videos.isNotEmpty()) {
                        rows += Row.Section(
                            folders.find { it.path == folder }?.name ?: getString(R.string.video_library_all_videos)
                        )
                        videos.mapTo(rows) { Row.Video(it) }
                    }
                }
            }
        }
        adapter.submit(rows)
        val hasAccess = library != null || hasStorageAccess()
        binding.emptyText.setText(
            when {
                showNetwork -> R.string.video_library_network_empty
                !hasAccess -> R.string.video_library_access_message
                query.isNotBlank() -> R.string.video_library_no_results
                unwatchedOnly && library?.videos?.isNotEmpty() == true -> R.string.video_library_no_unwatched
                else -> R.string.video_library_empty
            }
        )
        binding.accessButton.isVisible = !hasAccess && !showNetwork
        binding.emptyGroup.isVisible = if (showNetwork) {
            rows.isEmpty() && networkVideos != null
        } else {
            rows.isEmpty() && (library != null || !hasAccess)
        }
    }

    /**
     * "All", one chip per folder (newest video first) and "Network" when there are bookmarked server folders; hidden
     * when there is nothing to choose.
     */
    private fun renderFolders(folders: List<VideoFolder>, hasNetwork: Boolean) {
        val group = binding.folders
        binding.folderScroller.isVisible = (folders.size > 1 || hasNetwork) && openSeries == null && query.isBlank()
        val wanted = listOf<String?>(null) + folders.map { it.path } + listOfNotNull(NETWORK.takeIf { hasNetwork })
        if (group.childCount != wanted.size || wanted.indices.any { group.getChildAt(it).tag != wanted[it] }) {
            group.removeAllViews()
            for (path in wanted) {
                group.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                    id = View.generateViewId()
                    tag = path
                    text = folders.find { it.path == path }?.name ?: getString(
                        if (path == NETWORK) R.string.video_library_network else R.string.video_library_all
                    )
                    isCheckable = true
                    setOnClickListener {
                        if (folder != path) {
                            folder = path
                            render()
                            binding.recyclerView.scrollToPosition(0)
                        }
                    }
                })
            }
        }
        for (index in 0 until group.childCount) {
            val chip = group.getChildAt(index) as Chip
            chip.isChecked = chip.tag == folder
        }
    }

    /** A grid as wide as the music library's content on tablets; at least two columns of about 180 dp. */
    private fun updateWidths() {
        val density = resources.displayMetrics.density
        val windowWidthDp = resources.configuration.screenWidthDp
        val width = binding.root.width.takeIf { it > 0 } ?: (windowWidthDp * density).toInt()
        val gutter = resources.getDimensionPixelSize(R.dimen.space_gutter)
        side = if (windowWidthDp >= 600) {
            maxOf(gutter, (width - (musicContentWidthDp(windowWidthDp) * density).toInt()) / 2)
        } else {
            gutter
        }
        val content = width - 2 * side
        listMode = Settings.VIDEO_VIEW.valueCompat == "list"
        columns = if (listMode) 1 else (content / (CELL_DP * density)).toInt().coerceAtLeast(2)
        val recycler = binding.recyclerView
        recycler.setPaddingRelative(side, recycler.paddingTop, side, recycler.paddingBottom)
        (recycler.layoutManager as GridLayoutManager).spanCount = columns
        binding.folderScroller.setPaddingRelative(side, 0, side, 0)
        recycler.invalidateItemDecorations()
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    /** Reads the bookmarked server folders once the "Network" chip is chosen; a server that is away is skipped. */
    private fun loadNetwork() {
        if (networkLoading) return
        networkLoading = true
        lifecycleScope.launch {
            val videos = try {
                withContext(Dispatchers.IO) { VideoNetworkLibrary.load() }
            } finally {
                networkLoading = false
            }
            networkVideos = videos
            if (folder == NETWORK) render()
        }
    }

    private fun play(queue: List<VideoItem>, video: VideoItem, resume: Boolean) {
        val index = queue.indexOf(video).coerceAtLeast(0)
        val intent = Intent(this, VideoPlayerActivity::class.java)
        // Network videos carry their path's URI (smb://…); MediaStore's are plain file paths.
        val paths = queue.map { if (VideoBuffering.isNetwork(it.path)) Paths.get(URI.create(it.path)) else Paths.get(it.path) }
        VideoPlayerActivity.putExtras(intent, paths, index, resume)
        startActivitySafe(intent)
    }

    private fun formatTime(milliseconds: Long): String =
        Util.getStringForTime(timeBuilder, timeFormatter, milliseconds.coerceAtLeast(0L))

    /** The parts of a card, from the grid's card or the list's row (same ids). */
    private class CardViews(
        val root: View,
        val thumbnail: android.widget.ImageView,
        val duration: TextView,
        val progress: com.google.android.material.progressindicator.LinearProgressIndicator,
        val title: TextView,
        val info: TextView,
        val dot: View,
        val check: android.widget.ImageView
    )

    private fun cardViews(view: View, row: Boolean): CardViews =
        if (row) {
            VideoLibraryRowBinding.bind(view).let {
                CardViews(it.root, it.thumbnail, it.duration, it.progress, it.title, it.info, it.unwatchedDot,
                    it.selectedCheck)
            }
        } else {
            VideoLibraryItemBinding.bind(view).let {
                CardViews(it.root, it.thumbnail, it.duration, it.progress, it.title, it.info, it.unwatchedDot,
                    it.selectedCheck)
            }
        }

    /**
     * A video's card: its picture, length, how far it was watched (a full bar once watched), a dot when never
     * started, and a check while selected. [label] replaces the second line ("Episode 3", "Up next · S1 E3").
     */
    private fun bind(
        item: CardViews, video: VideoItem, positionMs: Long?, label: String? = null, spoken: String? = null
    ) {
        val title = displayTitle(video)
        item.title.text = title
        val duration = video.durationMs.takeIf { it > 0 }?.let(::formatTime)
        item.duration.text = duration
        item.duration.isVisible = duration != null
        val left = positionMs?.let { getString(R.string.video_library_left_format, formatTime(video.durationMs - it)) }
        item.info.text = label ?: left ?: if (folder == null) {
            video.folder.substringAfterLast('/')
        } else {
            DateUtils.formatDateTime(this, video.dateAddedMs, DateUtils.FORMAT_SHOW_DATE)
        }
        val isWatched = isWatched(video)
        val place = positionMs ?: positions[video.path]
        // Watched wins over an older place outside "Continue watching" (marked by hand after stopping).
        val progress = when {
            isWatched && positionMs == null -> 1000
            place != null && video.durationMs > 0 -> (place * 1000 / video.durationMs).toInt()
            else -> null
        }
        item.progress.isVisible = progress != null
        progress?.let { item.progress.progress = it }
        item.dot.isVisible = !isWatched && place == null
        val isSelected = video.path in selected
        item.check.isVisible = isSelected
        item.thumbnail.colorFilter = if (isSelected) SELECTED_SCRIM else null
        val state = when {
            isWatched -> getString(R.string.video_library_watched)
            item.dot.isVisible -> getString(R.string.video_library_unwatched)
            // The bar's meaning for TalkBack: "12:03 left".
            place != null && positionMs == null && video.durationMs > 0 ->
                getString(R.string.video_library_left_format, formatTime(video.durationMs - place))
            else -> null
        }
        item.root.contentDescription = listOfNotNull(
            title, video.title.takeIf { it != title }, duration, spoken ?: item.info.text, state
        ).joinToString(", ")
        ViewCompat.setStateDescription(
            item.root, getString(R.string.video_library_selected).takeIf { isSelected }
        )
        item.root.setOnClickListener {
            if (VideoBuffering.isNetwork(video.path)) {
                // Server videos are not selectable (trash, vault, share need a local file).
                if (selected.isNotEmpty()) return@setOnClickListener
                // The videos of the same server folder, by name, as the queue.
                val queue = networkVideos.orEmpty().filter { it.folder == video.folder }.sortedBy { it.title.lowercase() }
                return@setOnClickListener play(queue, video, resume = false)
            }
            if (selected.isNotEmpty()) {
                toggleSelection(video)
                return@setOnClickListener
            }
            val library = library ?: return@setOnClickListener
            // An episode plays with its season, so "Play next automatically" goes on within the season.
            play(shelves?.seasonQueue(video) ?: library.queueOf(video), video, resume = positionMs != null)
        }
        // A long press starts selecting; "Video info" is in the selection's menu. Not for server videos.
        val selectable = !VideoBuffering.isNetwork(video.path)
        item.root.setOnLongClickListener {
            if (selectable) toggleSelection(video)
            selectable
        }
        ViewCompat.replaceAccessibilityAction(
            item.root, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
                .ACTION_LONG_CLICK,
            if (selectable) getString(if (isSelected) R.string.video_library_deselect else R.string.video_library_select)
            else null, null
        )
        loadThumbnail(item.thumbnail, video)
    }

    /** "Video info" for one video: the same tracks the player shows, the file's own size, folder and date. */
    private fun showInfo(video: VideoItem) {
        lifecycleScope.launch {
            val path = Paths.get(video.path)
            val tracks = VideoInfo.probeTracks(applicationContext, path.toUri().toString())
            val info = if (tracks != null) {
                val recorded = withContext(Dispatchers.IO) { VideoInfo.recordedAt(video.path) }
                VideoInfo.fromTracks(
                    video.title, video.path.substringAfterLast('/'), video.durationMs, tracks,
                    getString(R.string.video_info_not_playable)
                ).copy(
                    size = video.size, folder = video.folder, recordedMs = recorded,
                    // The file's average rate, as for the platform path: what a viewer compares between copies.
                    bitrate = if (video.durationMs > 0) video.size * 8_000 / video.durationMs else 0
                )
            } else {
                withContext(Dispatchers.IO) { VideoInfo.fromFile(video) }
            }
            if (!isFinishing && !supportFragmentManager.isStateSaved) showVideoInfo(info, Paths.get(video.path))
        }
    }

    /** A series on a shelf: its name, seasons and episodes, a dot while some are unwatched; a tap opens its page. */
    private fun bindSeries(item: CardViews, series: Series) {
        item.title.text = series.name
        item.duration.isVisible = false
        // Without what was just sent to the trash or the vault.
        val present = series.episodes.filter { it.video.path !in removed }.ifEmpty { series.episodes }
        val count = present.size
        val episodes = getQuantityString(R.plurals.video_library_episodes_format, count, count)
        item.info.text = if (series.seasons.size > 1) {
            getString(
                R.string.video_library_series_info_format,
                getQuantityString(R.plurals.video_library_seasons_format, series.seasons.size, series.seasons.size),
                episodes
            )
        } else {
            episodes
        }
        val unwatched = present.filter { !isWatched(it.video) }
        item.progress.isVisible = false
        item.dot.isVisible = unwatched.isNotEmpty()
        item.check.isVisible = false
        item.thumbnail.colorFilter = null
        item.root.contentDescription = listOfNotNull(
            series.name, item.info.text,
            getString(R.string.video_library_unwatched).takeIf { unwatched.isNotEmpty() }
        ).joinToString(", ")
        ViewCompat.setStateDescription(item.root, null)
        item.root.setOnClickListener {
            if (selected.isNotEmpty()) return@setOnClickListener
            openSeries = seriesKey(series)
            updateToolbar()
            render()
            binding.recyclerView.scrollToPosition(0)
        }
        item.root.setOnLongClickListener(null)
        item.root.isLongClickable = false
        ViewCompat.replaceAccessibilityAction(
            item.root, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
                .ACTION_LONG_CLICK, null, null
        )
        loadThumbnail(item.thumbnail, (unwatched.firstOrNull() ?: present.first()).video)
    }

    private fun isWatched(video: VideoItem): Boolean = VideoShelves.watchedKey(video) in watched

    private fun seriesKey(series: Series): String = series.episodes.first().tag.seriesKey

    private fun addShelf(rows: MutableList<Row>, @androidx.annotation.StringRes title: Int, cards: List<Card>) {
        if (cards.isEmpty()) return
        rows += Row.Section(getString(title))
        rows += Row.Shelf(cards)
    }

    /** A saved place, or the next episode of a series ("Up next · S1 E3"). */
    private fun continueCard(card: ContinueCard): Card.Video {
        val episode = card.episode
        if (card.positionMs != null || episode == null) return Card.Video(card.video, card.positionMs)
        return Card.Video(
            card.video, null,
            getString(R.string.video_library_next_episode_format, episode.season, episode.episode),
            getString(R.string.video_library_next_episode_description_format, episode.season, episode.episode)
        )
    }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }

    /** Back leaves the selection, then the search, then the series' page. */
    private fun goBack() {
        val search = binding.toolbar.menu.findItem(R.id.actionSearch)
        when {
            selected.isNotEmpty() -> {
                selected.clear()
                updateToolbar()
                render()
            }
            search?.isActionViewExpanded == true -> search.collapseActionView()
            openSeries != null -> {
                openSeries = null
                updateToolbar()
                render()
            }
            // Nothing left to close (the search was closed just now): back leaves the screen as usual.
            else -> {
                backCallback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun toggleSelection(video: VideoItem) {
        if (!selected.remove(video.path)) selected += video.path
        updateToolbar()
        render()
    }

    /**
     * The library's menu (search, "Unwatched only", settings) or, while selecting, the selection's actions; the
     * title is "Ordo Video", the series' name or the number selected.
     */
    private fun updateToolbar() {
        val toolbar = binding.toolbar
        val selecting = selected.isNotEmpty()
        val menu = if (selecting) R.menu.video_library_selection else R.menu.video_library
        if (toolbar.getTag(R.id.toolbar) != menu) {
            // Tagged first: clearing collapses the search, whose listener calls back in here.
            toolbar.setTag(R.id.toolbar, menu)
            toolbar.menu.clear()
            toolbar.inflateMenu(menu)
            if (!selecting) setUpSearch()
        }
        val seriesName = shelves?.series?.find { seriesKey(it) == openSeries }?.name
        toolbar.title = when {
            selecting -> getQuantityString(R.plurals.video_library_selected_format, selected.size, selected.size)
            seriesName != null -> seriesName
            else -> getString(R.string.launcher_video_label)
        }
        if (selecting || openSeries != null) {
            toolbar.setNavigationIcon(
                if (selecting) R.drawable.close_icon_control_normal_24dp
                else androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            toolbar.setNavigationIconTint(
                com.google.android.material.color.MaterialColors.getColor(
                    toolbar, com.google.android.material.R.attr.colorOnSurface
                )
            )
            toolbar.navigationContentDescription = getString(
                if (selecting) R.string.video_library_close_selection
                else androidx.appcompat.R.string.abc_action_bar_up_description
            )
        } else {
            toolbar.navigationIcon = null
        }
        if (selecting) {
            val videos = selectedVideos()
            val allWatched = videos.isNotEmpty() && videos.all(::isWatched)
            toolbar.menu.findItem(R.id.actionWatchedSelection)?.setTitle(
                if (allWatched) R.string.video_library_mark_unwatched else R.string.video_library_mark_watched
            )
            toolbar.menu.findItem(R.id.actionInfoSelection)?.isVisible = videos.size == 1
            toolbar.menu.findItem(R.id.actionTrashSelection)?.setTitle(
                if (Trash.isEnabled) R.string.video_library_trash else R.string.video_library_delete
            )
        } else {
            toolbar.menu.findItem(R.id.actionUnwatchedOnly)?.isChecked = unwatchedOnly
        }
        backCallback.isEnabled = selecting || openSeries != null || query.isNotEmpty() ||
            toolbar.menu.findItem(R.id.actionSearch)?.isActionViewExpanded == true
    }

    private fun setUpSearch() {
        val item = binding.toolbar.menu.findItem(R.id.actionSearch) ?: return
        val searchView = item.actionView as androidx.appcompat.widget.SearchView
        searchView.queryHint = getString(R.string.video_library_search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        // Back on: a query typed before selecting (or before the screen was rebuilt) is still there.
        if (query.isNotEmpty()) {
            item.expandActionView()
            searchView.setQuery(query, false)
            searchView.clearFocus()
        }
        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(text: String?): Boolean {
                if (query == text.orEmpty()) return true
                query = text.orEmpty()
                backCallback.isEnabled = true
                render()
                binding.recyclerView.scrollToPosition(0)
                return true
            }
        })
        item.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(menuItem: android.view.MenuItem): Boolean {
                backCallback.isEnabled = true
                return true
            }

            override fun onMenuItemActionCollapse(menuItem: android.view.MenuItem): Boolean {
                // Collapsing for the selection's menu keeps the query; only the user's own close clears it.
                if (selected.isEmpty()) {
                    query = ""
                    render()
                }
                updateToolbar()
                return true
            }
        })
    }

    private fun selectedVideos(): List<VideoItem> {
        val byPath = library?.videos.orEmpty().associateBy { it.path }
        return selected.mapNotNull { byPath[it] }
    }

    private fun onMenuItem(id: Int): Boolean {
        val videos = selectedVideos()
        when (id) {
            R.id.actionSettings -> startActivitySafe(
                // Ordo Video's own settings: its colour, playback, gestures, library and privacy.
                me.zhanghai.android.files.settings.SettingsActivity.createIntent(
                    me.zhanghai.android.files.settings.SettingsActivity.SECTION_VIDEO
                )
            )
            R.id.actionUnwatchedOnly -> {
                unwatchedOnly = !unwatchedOnly
                updateToolbar()
                render()
            }
            R.id.actionPlaySelection -> if (videos.isNotEmpty()) {
                endSelection()
                play(videos, videos.first(), resume = false)
            }
            R.id.actionWatchedSelection -> {
                val watch = !videos.all(::isWatched)
                val keys = videos.map(VideoShelves::watchedKey)
                val context = applicationContext
                endSelection()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { VideoWatchedStore(context).set(keys, watch) }
                    load()
                }
            }
            R.id.actionShareSelection -> {
                startActivitySafe(
                    videos.map { Paths.get(it.path).fileProviderUri }
                        .createSendStreamIntent(videos.map { MimeType.guessFromPath(it.path) })
                        .withChooser()
                )
                endSelection()
            }
            R.id.actionTrashSelection -> confirm(
                if (Trash.isEnabled) R.plurals.video_library_trash_title_format
                else R.plurals.video_library_delete_title_format,
                if (Trash.isEnabled) R.string.video_library_trash_message else R.string.video_library_delete_message,
                if (Trash.isEnabled) R.string.video_library_trash else R.string.video_library_delete, videos
            ) { FileJobService.delete(it, this) }
            R.id.actionVaultSelection -> confirm(
                null, null, R.string.vault_move_confirm_action, videos
            ) { FileJobService.moveToVault(it, this) }
            R.id.actionInfoSelection -> videos.singleOrNull()?.let {
                endSelection()
                showInfo(it)
            }
            else -> return false
        }
        return true
    }

    /** Asks before [action] takes the videos out of the library (trash, delete, vault); null texts: the vault's. */
    private fun confirm(
        title: Int?, message: Int?, positive: Int, videos: List<VideoItem>,
        action: (List<java8.nio.file.Path>) -> Unit
    ) {
        if (videos.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(
                if (title != null) getQuantityString(title, videos.size, videos.size)
                else getString(R.string.vault_move_title)
            )
            .setMessage(
                if (message != null) getString(message)
                else getQuantityString(R.plurals.vault_move_message_format, videos.size, videos.size)
            )
            .setPositiveButton(positive) { _, _ ->
                action(videos.map { Paths.get(it.path) })
                videos.mapTo(removed) { it.path }
                endSelection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun endSelection() {
        selected.clear()
        updateToolbar()
        render()
    }

    private fun loadThumbnail(view: android.widget.ImageView, video: VideoItem) {
        (view.getTag(R.id.thumbnail) as? Job)?.cancel()
        val key = video.contentUri ?: video.path
        val cached = thumbnails.get(key)
        showThumbnail(view, cached)
        if (cached != null) return
        val context = applicationContext
        val (width, height) = THUMBNAIL_WIDTH to THUMBNAIL_WIDTH * 9 / 16
        view.setTag(R.id.thumbnail, lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadVideoThumbnail(context, video, width, height) }
                ?: return@launch
            thumbnails.put(key, bitmap)
            showThumbnail(view, bitmap)
        })
    }

    /** The picture, or until there is one (or for a file without any) a calm video glyph in the middle. */
    private fun showThumbnail(view: android.widget.ImageView, bitmap: Bitmap?) {
        if (bitmap != null) {
            view.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            view.imageTintList = null
            view.setImageBitmap(bitmap)
        } else {
            view.scaleType = android.widget.ImageView.ScaleType.CENTER
            view.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    view, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            view.setImageResource(R.drawable.video_icon_white_24dp)
        }
    }

    /**
     * Camera names ("VID_20261118_141941_…") read as the moment they were taken, "18. Nov. · 14:19" in the
     * device's language; any other name stays as it is. TalkBack still hears the real name.
     */
    private fun displayTitle(video: VideoItem): String {
        if (!Settings.VIDEO_CAMERA_NAMES.valueCompat) return video.title
        val time = VideoLibrary.cameraTime(video.title) ?: return video.title
        val calendar = java.util.Calendar.getInstance().apply {
            clear()
            set(time[0], time[1] - 1, time[2], time[3], time[4])
        }
        val millis = calendar.timeInMillis
        return getString(
            R.string.video_library_camera_title_format,
            DateUtils.formatDateTime(this, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
            android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(millis))
        )
    }

    private sealed class Row {
        class Section(val title: String) : Row()
        /** A sideways row of cards: "Continue watching", series, films, recently added. */
        class Shelf(val cards: List<Card>) : Row()
        class Video(val video: VideoItem, val label: String? = null) : Row()
    }

    private sealed class Card {
        class Video(
            val video: VideoItem, val positionMs: Long?, val label: String? = null, val spoken: String? = null
        ) : Card()
        class Show(val series: Series) : Card()
    }

    private class Loaded(
        val library: VideoLibrary,
        val shelves: VideoShelves,
        val continueCards: List<ContinueCard>,
        val positions: Map<String, Long>,
        val watched: Map<String, Long>
    )

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows = emptyList<Row>()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Section -> TYPE_SECTION
            is Row.Shelf -> TYPE_CONTINUE
            is Row.Video -> if (listMode) TYPE_ROW else TYPE_VIDEO
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                TYPE_SECTION -> Holder(TextView(parent.context).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                    setTextColor(currentTextColorOnSurface())
                    val padding = resources.getDimensionPixelSize(R.dimen.phi_13)
                    setPadding(0, padding * 2, 0, padding)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    ViewCompat.setAccessibilityHeading(this, true)
                })
                TYPE_CONTINUE -> Holder(RecyclerView(parent.context).apply {
                    layoutManager = LinearLayoutManager(parent.context, RecyclerView.HORIZONTAL, false)
                    clipToPadding = false
                    // Its cards reach to the screen edge while scrolling, aligned with the grid at rest.
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
                TYPE_ROW ->
                    Holder(VideoLibraryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false).root)
                else -> Holder(VideoLibraryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false).root)
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Section -> (holder.itemView as TextView).text = row.title
                is Row.Shelf -> {
                    val list = holder.itemView as RecyclerView
                    (list.layoutParams as ViewGroup.MarginLayoutParams).apply {
                        marginStart = -side
                        marginEnd = -side
                    }
                    list.setPaddingRelative(side, 0, side, 0)
                    list.adapter = ShelfAdapter(row.cards)
                }
                is Row.Video ->
                    bind(cardViews(holder.itemView, holder.itemViewType == TYPE_ROW), row.video, null, row.label)
            }
        }
    }

    private inner class ShelfAdapter(private val items: List<Card>) :
        RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val item = VideoLibraryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val density = resources.displayMetrics.density
            item.root.layoutParams = RecyclerView.LayoutParams(
                ((if (resources.configuration.screenWidthDp >= 600) 280 else 240) * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.phi_13) }
            return Holder(item.root)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val views = cardViews(holder.itemView, row = false)
            when (val card = items[position]) {
                is Card.Video -> bind(views, card.video, card.positionMs, card.label, card.spoken)
                is Card.Show -> bindSeries(views, card.series)
            }
        }
    }

    private fun currentTextColorOnSurface(): Int =
        com.google.android.material.color.MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )

    /** The gaps between grid cells; the outer edges come from the list's padding. */
    private inner class Spacing : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION || adapter.getItemViewType(position) != TYPE_VIDEO) return
            val column = (view.layoutParams as GridLayoutManager.LayoutParams).spanIndex
            val gap = resources.getDimensionPixelSize(R.dimen.phi_13)
            outRect.left = gap * column / columns
            outRect.right = gap - gap * (column + 1) / columns
        }
    }

    companion object {
        private const val STATE_FOLDER = "folder"
        private const val NETWORK = "ordo:network"
        private const val STATE_SERIES = "series"
        private const val STATE_QUERY = "query"
        private const val STATE_UNWATCHED = "unwatchedOnly"
        // Darkens a selected card's picture under its check.
        private val SELECTED_SCRIM =
            android.graphics.PorterDuffColorFilter(0x66000000, android.graphics.PorterDuff.Mode.SRC_ATOP)
        private const val TYPE_SECTION = 0
        private const val TYPE_CONTINUE = 1
        private const val TYPE_VIDEO = 2
        private const val TYPE_ROW = 3
        private const val CELL_DP = 180
        private const val THUMBNAIL_WIDTH = 512
    }
}
