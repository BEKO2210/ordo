/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.common.util.concurrent.ListenableFuture
import java.net.URI
import java.text.Collator
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.MusicLauncherActivityBinding
import me.zhanghai.android.files.databinding.MusicLibraryActionsBinding
import me.zhanghai.android.files.databinding.MusicArtBinding
import me.zhanghai.android.files.databinding.MusicLibraryAlbumBinding
import me.zhanghai.android.files.databinding.MusicLibraryHeaderBinding
import me.zhanghai.android.files.databinding.MusicLibraryRowBinding
import me.zhanghai.android.files.player.AudioPlaybackService
import me.zhanghai.android.files.player.PlaybackStateStore
import me.zhanghai.android.files.player.pathUriExists
import me.zhanghai.android.files.player.queueWindow
import me.zhanghai.android.files.player.restorableState
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.valueCompat

/**
 * What the "Ordo Musik" icon opens: the music library (songs, albums, artists, folders from MediaStore) with a mini
 * player at the bottom while something plays or can be resumed. It never jumps into the player on its own.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicLauncherActivity : AppActivity() {
    private lateinit var binding: MusicLauncherActivityBinding
    private val adapter = Adapter()
    private var library: MusicLibrary? = null
    private var songsByPath = emptyMap<String, MusicSong>()
    private var tab = TAB_SONGS
    // The album, artist or folder (by MusicGroup.key) open inside the current tab.
    private var detailKey: String? = null
    private var query = ""
    private var shownSongs = emptyList<MusicSong>()
    private var saved: PlaybackStateStore.State? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var miniArtKey: Any? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateMiniProgress()
            if (controller?.isPlaying == true) handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateMiniPlayer()
    }
    // Back closes the search first, then an open album, artist or folder; a toolbar outside the action bar does
    // not collapse its search on its own.
    private val backInLibrary = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (searchItem.isActionViewExpanded) searchItem.collapseActionView() else openDetail(null)
        }
    }
    private val searchItem: MenuItem
        get() = binding.toolbar.menu.findItem(R.id.actionSearch)
    private val accessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MusicLauncherActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        savedInstanceState?.let {
            tab = it.getInt(STATE_TAB)
            detailKey = it.getString(STATE_DETAIL)
            query = it.getString(STATE_QUERY).orEmpty()
        }
        onBackPressedDispatcher.addCallback(this, backInLibrary)
        binding.recyclerView.adapter = adapter
        ThemedFastScroller.create(binding.recyclerView)
        setUpTabs()
        binding.recyclerView.addItemDecoration(GridSpacing())
        // The list and the mini player keep a readable width on tablets: centred, at most 610 dp.
        binding.recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) binding.recyclerView.post { updateWidths() }
        }
        binding.tabs.getTabAt(tab)?.select()
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selected: TabLayout.Tab) {
                tab = selected.position
                render(scrollToTop = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) = binding.recyclerView.scrollToPosition(0)
        })
        binding.toolbar.setNavigationOnClickListener { openDetail(null) }
        setUpSearch()
        binding.accessButton.setOnClickListener { requestAccess() }
        binding.miniPlayer.root.setOnClickListener {
            startActivitySafe(AudioPlayerActivity.createResumeIntent(this))
        }
        binding.miniPlayer.playPause.setOnClickListener {
            // With nothing in the session, play() makes the service resume the saved queue.
            controller?.let { Util.handlePlayPauseButtonAction(it) }
        }
        binding.miniPlayer.next.setOnClickListener { controller?.seekToNextMediaItem() }
    }

    private fun setUpTabs() {
        listOf(
            R.string.music_tab_songs to R.drawable.audio_icon_white_24dp,
            R.string.music_tab_albums to R.drawable.album_icon_white_24dp,
            R.string.music_tab_artists to R.drawable.person_icon_white_24dp,
            R.string.music_tab_folders to R.drawable.directory_icon_white_24dp
        ).forEach { (text, icon) -> binding.tabs.addTab(binding.tabs.newTab().setText(text).setIcon(icon)) }
        // The pill takes Material 3's secondary container; the Material 2 theme has none, so primary at 16 %.
        val tabs = binding.tabs
        val primary = MaterialColors.getColor(tabs, androidx.appcompat.R.attr.colorPrimary)
        val pill = MaterialColors.getColor(
            tabs, com.google.android.material.R.attr.colorSecondaryContainer,
            MaterialColors.compositeARGBWithAlpha(primary, 41)
        )
        val selected = MaterialColors.getColor(tabs, com.google.android.material.R.attr.colorOnSecondaryContainer, primary)
        val normal = MaterialColors.getColor(
            tabs, com.google.android.material.R.attr.colorOnSurfaceVariant,
            MaterialColors.getColor(tabs, android.R.attr.textColorSecondary)
        )
        val colors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(selected, normal)
        )
        tabs.setSelectedTabIndicatorColor(pill)
        tabs.tabTextColors = colors
        tabs.tabIconTint = colors
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        outState.putString(STATE_DETAIL, detailKey)
        outState.putString(STATE_QUERY, query)
    }

    override fun onStart() {
        super.onStart()
        load()
        connectController()
    }

    override fun onStop() {
        handler.removeCallbacks(progressUpdater)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun setUpSearch() {
        val item = searchItem
        val searchView = item.actionView as SearchView
        searchView.queryHint = getString(R.string.music_search_hint)
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                // The large title would run under the search field.
                binding.collapsingToolbar.isTitleEnabled = false
                binding.appBar.setExpanded(false, Motion.any)
                backInLibrary.isEnabled = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                binding.collapsingToolbar.isTitleEnabled = true
                query = ""
                render()
                // The item still counts as expanded while this runs.
                backInLibrary.isEnabled = detailKey != null
                return true
            }
        })
        if (query.isNotEmpty()) {
            val restored = query
            item.expandActionView()
            searchView.setQuery(restored, false)
            query = restored
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(text: String): Boolean {
                if (text == query) return true
                query = text
                render(scrollToTop = true)
                return true
            }
        })
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
        lifecycleScope.launch {
            val (loaded, savedState) = withContext(Dispatchers.IO) {
                MusicLibrary(queryMusic(context), showHiddenFiles, Collator.getInstance()) to
                    PlaybackStateStore(context).read()?.let { restorableState(it, ::pathUriExists) }
            }
            library = loaded
            songsByPath = loaded.songs.associateBy { it.path }
            saved = savedState
            render()
            updateMiniPlayer()
        }
    }

    private fun groupsOf(library: MusicLibrary, tab: Int): List<MusicGroup> =
        when (tab) {
            TAB_ALBUMS -> library.albums
            TAB_ARTISTS -> library.artists
            TAB_FOLDERS -> library.folders
            else -> emptyList()
        }

    private fun openDetail(key: String?) {
        // A search that found the album would otherwise filter its songs too.
        searchItem.collapseActionView()
        detailKey = key
        // A page has its own big cover and name; the large title folds away for it.
        binding.appBar.setExpanded(key == null, Motion.any)
        render(scrollToTop = true)
    }

    private fun render(scrollToTop: Boolean = false) {
        val library = library
        val group = library?.let { lib -> groupsOf(lib, tab).find { it.key == detailKey } }
        // A page whose album, artist or folder is gone after a reload closes.
        if (library != null && group == null) detailKey = null
        val rows = ArrayList<Row>()
        shownSongs = emptyList()
        when {
            library == null -> {}
            group != null -> {
                shownSongs = group.songs.filter { MusicLibrary.matches(it, query) }
                rows += Row.Header(group)
                if (shownSongs.isNotEmpty()) rows += Row.Actions(shuffleOnly = false)
                shownSongs.forEachIndexed { index, song ->
                    // Only what the page does not already say: an album names its artist once, at the top.
                    rows += when (tab) {
                        TAB_ALBUMS -> Row.Song(
                            song, index, song.artist?.takeIf { it != group.artist },
                            (song.track % 1000).takeIf { it > 0 } ?: (index + 1)
                        )
                        TAB_ARTISTS -> Row.Song(song, index, song.album ?: getString(R.string.music_unknown_album))
                        else -> Row.Song(song, index, artistAndAlbum(song))
                    }
                }
            }
            tab == TAB_SONGS -> {
                shownSongs = library.songs.filter { MusicLibrary.matches(it, query) }
                if (shownSongs.isNotEmpty()) rows += Row.Actions(shuffleOnly = true)
                shownSongs.forEachIndexed { index, song -> rows += Row.Song(song, index, artistAndAlbum(song)) }
            }
            else -> groupsOf(library, tab).filter { MusicLibrary.matches(it, query) }
                .mapTo(rows) { if (tab == TAB_ALBUMS) Row.Album(it) else Row.Group(it, tab == TAB_FOLDERS) }
        }
        val grid = group == null && tab == TAB_ALBUMS
        val layoutManager = binding.recyclerView.layoutManager
        if (layoutManager == null || (layoutManager is GridLayoutManager) != grid) {
            binding.recyclerView.layoutManager =
                if (grid) GridLayoutManager(this, albumColumns()) else LinearLayoutManager(this)
        }
        updateWidths()
        adapter.submit(rows)
        if (scrollToTop) binding.recyclerView.scrollToPosition(0)

        binding.collapsingToolbar.title = group?.let { groupName(it, tab) } ?: getString(R.string.launcher_music_start_title)
        binding.tabs.isVisible = group == null
        backInLibrary.isEnabled = group != null || searchItem.isActionViewExpanded
        if (group != null) {
            binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
        } else {
            binding.toolbar.navigationIcon = null
        }
        val hasAccess = library != null || hasStorageAccess()
        binding.emptyText.text = when {
            !hasAccess -> getString(R.string.music_access_message)
            library == null -> null
            query.isNotBlank() -> getString(R.string.music_no_results, query.trim())
            else -> getString(R.string.music_empty)
        }
        binding.accessButton.isVisible = !hasAccess
        binding.emptyGroup.isVisible = rows.isEmpty() && (library != null || !hasAccess)
        binding.recyclerView.isVisible = rows.isNotEmpty()
    }

    private fun groupName(group: MusicGroup, tab: Int): String =
        group.name ?: getString(
            if (tab == TAB_ALBUMS) R.string.music_unknown_album else R.string.audio_player_unknown_artist
        )

    private fun artistAndAlbum(song: MusicSong): String =
        getString(
            R.string.audio_player_artist_album_format,
            song.artist ?: getString(R.string.audio_player_unknown_artist),
            song.album ?: getString(R.string.music_unknown_album)
        )

    // Columns of at least 144 dp between 21 dp edges and 13 dp gutters: two on a phone, four on a portrait tablet.
    private fun albumColumns(): Int {
        val width = resources.configuration.screenWidthDp * resources.displayMetrics.density
        val outer = resources.getDimension(R.dimen.phi_21)
        val gutter = resources.getDimension(R.dimen.phi_13)
        val cell = resources.getDimension(R.dimen.music_album_min_width)
        return ((width - 2 * outer + gutter) / (cell + gutter)).toInt().coerceAtLeast(2)
    }

    /** Lists and the mini player at most 610 dp wide and centred; the album grid uses the whole width. */
    private fun updateWidths() {
        val width = binding.root.width.takeIf { it > 0 } ?: return
        val side = ((width - resources.getDimensionPixelSize(R.dimen.phi_content_max)) / 2).coerceAtLeast(0)
        val recycler = binding.recyclerView
        val listSide = if (recycler.layoutManager is GridLayoutManager) 0 else side
        if (recycler.paddingStart != listSide) {
            recycler.setPaddingRelative(listSide, recycler.paddingTop, listSide, recycler.paddingBottom)
        }
        val mini = binding.miniPlayer.root
        val params = mini.layoutParams as ViewGroup.MarginLayoutParams
        val margin = maxOf(side, resources.getDimensionPixelSize(R.dimen.phi_8))
        if (params.marginStart != margin) {
            params.marginStart = margin
            params.marginEnd = margin
            mini.layoutParams = params
        }
    }

    private fun placeholder(name: String?, @DrawableRes glyph: Int): Drawable =
        MusicPlaceholderDrawable(this, name, glyph)

    /**
     * One cover, or a collage of up to four for an artist or a folder; behind them the name's placeholder, which
     * shows wherever a cover is missing. [inset] leaves a frame of the placeholder around a folder's covers.
     */
    private fun bindArt(
        art: MusicArtBinding,
        background: Drawable,
        covers: List<String?>,
        radius: Float,
        inset: Int = 0
    ) {
        art.root.radius = radius
        val distinct = covers.filterNotNull().distinct().take(4)
        if (distinct.size <= 1) {
            art.collage.isVisible = false
            art.cover.loadCover(distinct.firstOrNull(), background)
            return
        }
        art.cover.dispose()
        art.cover.setImageDrawable(background)
        art.collage.isVisible = true
        art.collage.setPadding(inset, inset, inset, inset)
        listOf(art.cell1, art.cell2, art.cell3, art.cell4).forEachIndexed { index, cell ->
            // Two covers repeat diagonally, three repeat the first: the square stays full.
            cell.loadCover(distinct[if (distinct.size == 2) (index + index / 2) % 2 else index % distinct.size], null)
        }
    }

    private fun albumCovers(songs: List<MusicSong>): List<String?> = songs.distinctBy { it.albumId }.map { it.artUri }

    private fun formatTotal(songs: List<MusicSong>): String =
        getString(
            R.string.audio_player_artist_album_format,
            resources.getQuantityString(R.plurals.music_song_count, songs.size, songs.size),
            formatDuration(songs.sumOf { it.durationMs })
        )

    private fun play(songs: List<MusicSong>, index: Int) {
        val window = queueWindow(songs.size, index)
        val intent = Intent(this, AudioPlayerActivity::class.java)
        AudioPlayerActivity.putExtras(intent, songs.slice(window).map { Paths.get(it.path) }, index - window.first)
        startActivitySafe(intent)
    }

    private fun connectController() {
        val future = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        ).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || isFinishing) return@addListener
            val newController = try {
                future.get()
            } catch (e: Exception) {
                e.printStackTrace()
                return@addListener
            }
            controller = newController
            newController.addListener(playerListener)
            updateMiniPlayer()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun songAt(pathUri: String?): MusicSong? =
        pathUri?.let { runCatching { URI.create(it).path }.getOrNull() }?.let { songsByPath[it] }

    private fun updateMiniPlayer() {
        val mini = binding.miniPlayer
        val player = controller
        val item = player?.currentMediaItem
        val savedState = saved
        val title: CharSequence
        val artist: CharSequence?
        val art: Any?
        val album: String?
        val showPlay: Boolean
        if (item != null) {
            val metadata = player.mediaMetadata
            val song = songAt(item.mediaId)
            title = metadata.title?.takeIf { it.isNotBlank() } ?: song?.title ?: metadata.displayTitle
                ?: getString(R.string.audio_player_unknown_title)
            artist = metadata.artist?.takeIf { it.isNotBlank() } ?: song?.artist
            art = metadata.artworkData ?: song?.artUri
            album = song?.album ?: metadata.albumTitle?.toString()
            showPlay = Util.shouldShowPlayButton(player)
        } else if (savedState != null && player != null) {
            val pathUri = savedState.paths[savedState.currentIndex]
            val song = songAt(pathUri)
            title = song?.title ?: pathUri.substringAfterLast('/')
            artist = song?.artist
            art = song?.artUri
            album = song?.album
            showPlay = true
        } else {
            mini.root.isVisible = false
            handler.removeCallbacks(progressUpdater)
            return
        }
        mini.title.text = title
        mini.artist.text = artist ?: getString(R.string.audio_player_unknown_artist)
        mini.root.contentDescription = getString(R.string.music_now_playing, title, mini.artist.text)
        mini.playPause.setImageResource(if (showPlay) R.drawable.play_icon_24dp else R.drawable.pause_icon_24dp)
        mini.playPause.contentDescription =
            getString(if (showPlay) R.string.audio_player_play else R.string.audio_player_pause)
        val hasNext = item != null && player.hasNextMediaItem()
        mini.next.isEnabled = hasNext
        mini.next.alpha = if (hasNext) 1f else DISABLED_ALPHA
        val artKey = (art as? ByteArray)?.contentHashCode() ?: art
        if (artKey != miniArtKey || !mini.root.isVisible) {
            miniArtKey = artKey
            mini.art.collage.isVisible = false
            mini.art.cover.loadCover(art, placeholder(album, R.drawable.audio_icon_white_24dp))
        }
        if (!mini.root.isVisible) {
            mini.root.isVisible = true
            if (Motion.any) {
                mini.root.alpha = 0f
                mini.root.animate().alpha(1f).setDuration(MINI_PLAYER_FADE_MS).start()
            }
        }
        handler.removeCallbacks(progressUpdater)
        progressUpdater.run()
    }

    private fun updateMiniProgress() {
        val player = controller ?: return
        val (position, duration) = if (player.currentMediaItem != null) {
            player.currentPosition to player.duration
        } else {
            val savedState = saved ?: return
            savedState.positionMs to (songAt(savedState.paths[savedState.currentIndex])?.durationMs ?: 0L)
        }
        val progress = if (duration > 0 && duration != C.TIME_UNSET) {
            (position.coerceIn(0L, duration) * PROGRESS_MAX / duration).toInt()
        } else 0
        binding.miniPlayer.progress.setProgressCompat(progress, false)
    }

    private fun ImageView.loadCover(data: Any?, placeholder: Drawable?) {
        if (data == null) {
            dispose()
            setImageDrawable(placeholder)
            return
        }
        load(data) {
            placeholder(placeholder)
            error(placeholder)
            crossfade(Motion.any)
        }
    }

    private sealed class Row {
        class Header(val group: MusicGroup) : Row()
        class Actions(val shuffleOnly: Boolean) : Row()
        class Song(val song: MusicSong, val index: Int, val info: String?, val number: Int? = null) : Row()
        class Album(val group: MusicGroup) : Row()
        class Group(val group: MusicGroup, val isFolder: Boolean) : Row()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows = emptyList<Row>()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            when (rows[position]) {
                is Row.Header -> TYPE_HEADER
                is Row.Actions -> TYPE_ACTIONS
                is Row.Album -> TYPE_ALBUM
                else -> TYPE_ROW
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> Holder(MusicLibraryHeaderBinding.inflate(inflater, parent, false))
                TYPE_ACTIONS -> Holder(MusicLibraryActionsBinding.inflate(inflater, parent, false))
                TYPE_ALBUM -> Holder(MusicLibraryAlbumBinding.inflate(inflater, parent, false))
                else -> Holder(MusicLibraryRowBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val binding = (holder as Holder<*>).binding
            when (val row = rows[position]) {
                is Row.Actions -> (binding as MusicLibraryActionsBinding).apply {
                    play.isVisible = !row.shuffleOnly
                    shuffle.text = if (row.shuffleOnly) {
                        getString(R.string.music_shuffle_all, shownSongs.size)
                    } else {
                        getString(R.string.music_shuffle)
                    }
                    play.setOnClickListener { play(shownSongs, 0) }
                    shuffle.setOnClickListener { play(MusicLibrary.shuffled(shownSongs), 0) }
                }
                is Row.Header -> (binding as MusicLibraryHeaderBinding).apply {
                    val group = row.group
                    // An album's cover about two thirds of a phone's width, 233 dp at most on a tablet.
                    val size = minOf(
                        resources.displayMetrics.widthPixels * 2 / 3,
                        resources.getDimensionPixelSize(R.dimen.phi_233)
                    )
                    art.root.layoutParams = art.root.layoutParams.apply {
                        width = size
                        height = size
                    }
                    title.text = groupName(group, tab)
                    subtitle.text = when (tab) {
                        TAB_ALBUMS -> group.artist ?: getString(R.string.audio_player_unknown_artist)
                        TAB_FOLDERS -> displayFolder(group.key)
                        else -> null
                    }
                    subtitle.isVisible = !subtitle.text.isNullOrEmpty()
                    info.text = formatTotal(group.songs)
                    when (tab) {
                        TAB_ALBUMS -> bindArt(
                            art, placeholder(group.name, R.drawable.album_icon_white_24dp),
                            listOf(group.songs.firstOrNull()?.artUri), resources.getDimension(R.dimen.phi_21)
                        )
                        // Artists are round, like a portrait.
                        TAB_ARTISTS -> bindArt(
                            art, placeholder(group.name, R.drawable.person_icon_white_24dp),
                            albumCovers(group.songs), size / 2f
                        )
                        else -> bindArt(
                            art, placeholder(group.name, R.drawable.directory_icon_white_24dp),
                            albumCovers(group.songs), resources.getDimension(R.dimen.phi_21), size / 12
                        )
                    }
                }
                is Row.Song -> (binding as MusicLibraryRowBinding).apply {
                    val song = row.song
                    title.text = song.title
                    info.text = row.info
                    info.isVisible = row.info != null
                    duration.isVisible = true
                    duration.text = formatDuration(song.durationMs)
                    number.isVisible = row.number != null
                    number.text = row.number?.toString()
                    art.root.isVisible = row.number == null
                    if (row.number == null) {
                        bindArt(
                            art, placeholder(song.album, R.drawable.audio_icon_white_24dp), listOf(song.artUri),
                            resources.getDimension(R.dimen.phi_radius_s)
                        )
                    }
                    root.setOnClickListener { play(shownSongs, row.index) }
                }
                is Row.Group -> (binding as MusicLibraryRowBinding).apply {
                    val group = row.group
                    val count = resources.getQuantityString(R.plurals.music_song_count, group.songs.size, group.songs.size)
                    title.text = groupName(group, tab)
                    info.isVisible = true
                    info.text = if (row.isFolder) {
                        getString(R.string.audio_player_artist_album_format, count, displayFolder(group.key))
                    } else {
                        count
                    }
                    duration.isVisible = false
                    number.isVisible = false
                    art.root.isVisible = true
                    val size = resources.getDimensionPixelSize(R.dimen.music_row_art_size)
                    if (row.isFolder) {
                        bindArt(
                            art, placeholder(group.name, R.drawable.directory_icon_white_24dp),
                            albumCovers(group.songs), resources.getDimension(R.dimen.phi_radius_s), size / 12
                        )
                    } else {
                        bindArt(
                            art, placeholder(group.name, R.drawable.person_icon_white_24dp),
                            albumCovers(group.songs), size / 2f
                        )
                    }
                    root.setOnClickListener { openDetail(group.key) }
                }
                is Row.Album -> (binding as MusicLibraryAlbumBinding).apply {
                    val group = row.group
                    title.text = groupName(group, TAB_ALBUMS)
                    info.text = group.artist ?: getString(R.string.audio_player_unknown_artist)
                    bindArt(
                        art, placeholder(group.name, R.drawable.album_icon_white_24dp),
                        listOf(group.songs.firstOrNull()?.artUri), resources.getDimension(R.dimen.phi_radius_m)
                    )
                    root.setOnClickListener { openDetail(group.key) }
                }
            }
        }
    }

    /** The album grid's φ spacing: 21 dp at the edges, 13 dp between columns and rows, every cell equally wide. */
    private inner class GridSpacing : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
            val position = parent.getChildAdapterPosition(view).takeIf { it >= 0 } ?: return
            val columns = layoutManager.spanCount
            val column = position % columns
            val outer = resources.getDimension(R.dimen.phi_21)
            val gutter = resources.getDimension(R.dimen.phi_13)
            val perCell = (2 * outer + (columns - 1) * gutter) / columns
            val left = outer + column * (gutter - perCell)
            outRect.set(
                left.toInt(), if (position < columns) resources.getDimensionPixelSize(R.dimen.phi_8) else 0,
                (perCell - left).toInt(), gutter.toInt()
            )
        }
    }

    private class Holder<T : androidx.viewbinding.ViewBinding>(val binding: T) : RecyclerView.ViewHolder(binding.root)

    // The folder below the storage root ("Music/Rock"); other volumes keep their full path.
    private fun displayFolder(folder: String): String {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().path
        return folder.removePrefix("$root/").takeIf { it != root } ?: "/"
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        return if (hours > 0) "%d:%02d:%02d".format(hours, totalSeconds % 3_600L / 60L, totalSeconds % 60L)
        else "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    companion object {
        private const val STATE_TAB = "tab"
        private const val STATE_DETAIL = "detail"
        private const val STATE_QUERY = "query"
        private const val TAB_SONGS = 0
        private const val TAB_ALBUMS = 1
        private const val TAB_ARTISTS = 2
        private const val TAB_FOLDERS = 3
        private const val TYPE_HEADER = 3
        private const val TYPE_ACTIONS = 0
        private const val TYPE_ROW = 1
        private const val TYPE_ALBUM = 2
        private const val PROGRESS_MAX = 1_000
        // Once a second, like the player: a screen that redraws more often never goes idle for accessibility tools.
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        private const val MINI_PLAYER_FADE_MS = 200L
        private const val DISABLED_ALPHA = 0.38f
    }
}
