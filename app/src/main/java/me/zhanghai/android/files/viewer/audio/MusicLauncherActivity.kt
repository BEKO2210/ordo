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
import android.view.ViewGroup
import android.widget.ImageView
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
import me.zhanghai.android.files.databinding.MusicLibraryAlbumBinding
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
        listOf(R.string.music_tab_songs, R.string.music_tab_albums, R.string.music_tab_artists, R.string.music_tab_folders)
            .forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
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
        binding.appBar.setExpanded(true, Motion.any)
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
                if (shownSongs.isNotEmpty()) rows += Row.Actions(shuffleOnly = false)
                shownSongs.forEachIndexed { index, song -> rows += Row.Song(song, index) }
            }
            tab == TAB_SONGS -> {
                shownSongs = library.songs.filter { MusicLibrary.matches(it, query) }
                if (shownSongs.isNotEmpty()) rows += Row.Actions(shuffleOnly = true)
                shownSongs.forEachIndexed { index, song -> rows += Row.Song(song, index) }
            }
            else -> groupsOf(library, tab).filter { MusicLibrary.matches(it, query) }
                .mapTo(rows) { if (tab == TAB_ALBUMS) Row.Album(it) else Row.Group(it, tab == TAB_FOLDERS) }
        }
        val grid = group == null && tab == TAB_ALBUMS
        val layoutManager = binding.recyclerView.layoutManager
        if (layoutManager == null || (layoutManager is GridLayoutManager) != grid) {
            binding.recyclerView.layoutManager = if (grid) {
                val columnWidth = resources.getDimension(R.dimen.music_album_min_width)
                val width = resources.configuration.screenWidthDp * resources.displayMetrics.density
                GridLayoutManager(this, (width / columnWidth).toInt().coerceAtLeast(2))
            } else {
                LinearLayoutManager(this)
            }
        }
        // The grid keeps the screen's gutter: 13 dp here plus 8 dp inside each cell.
        val gridPadding = if (grid) resources.getDimensionPixelSize(R.dimen.phi_13) else 0
        binding.recyclerView.setPaddingRelative(
            gridPadding, 0, gridPadding, binding.recyclerView.paddingBottom
        )
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
        val showPlay: Boolean
        if (item != null) {
            val metadata = player.mediaMetadata
            val song = songAt(item.mediaId)
            title = metadata.title?.takeIf { it.isNotBlank() } ?: song?.title ?: metadata.displayTitle
                ?: getString(R.string.audio_player_unknown_title)
            artist = metadata.artist?.takeIf { it.isNotBlank() } ?: song?.artist
            art = metadata.artworkData ?: song?.artUri
            showPlay = Util.shouldShowPlayButton(player)
        } else if (savedState != null && player != null) {
            val pathUri = savedState.paths[savedState.currentIndex]
            val song = songAt(pathUri)
            title = song?.title ?: pathUri.substringAfterLast('/')
            artist = song?.artist
            art = song?.artUri
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
        val artKey = (art as? ByteArray)?.contentHashCode() ?: art
        if (artKey != miniArtKey || !mini.root.isVisible) {
            miniArtKey = artKey
            mini.art.cover.loadCover(art)
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

    private fun ImageView.loadCover(data: Any?) {
        if (data == null) {
            dispose()
            setImageDrawable(null)
            return
        }
        // Without a cover the error leaves the view empty and the symbol behind it shows.
        load(data) { crossfade(Motion.any) }
    }

    private sealed class Row {
        class Actions(val shuffleOnly: Boolean) : Row()
        class Song(val song: MusicSong, val index: Int) : Row()
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
                is Row.Actions -> TYPE_ACTIONS
                is Row.Album -> TYPE_ALBUM
                else -> TYPE_ROW
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
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
                is Row.Song -> (binding as MusicLibraryRowBinding).apply {
                    val song = row.song
                    title.text = song.title
                    info.text = getString(
                        R.string.audio_player_artist_album_format,
                        song.artist ?: getString(R.string.audio_player_unknown_artist),
                        song.album ?: getString(R.string.music_unknown_album)
                    )
                    duration.isVisible = true
                    duration.text = formatDuration(song.durationMs)
                    art.icon.setImageResource(R.drawable.audio_icon_white_24dp)
                    art.cover.loadCover(song.artUri)
                    root.setOnClickListener { play(shownSongs, row.index) }
                }
                is Row.Group -> (binding as MusicLibraryRowBinding).apply {
                    val group = row.group
                    val count = resources.getQuantityString(R.plurals.music_song_count, group.songs.size, group.songs.size)
                    title.text = groupName(group, tab)
                    info.text = if (row.isFolder) {
                        getString(R.string.audio_player_artist_album_format, count, displayFolder(group.key))
                    } else {
                        count
                    }
                    duration.isVisible = false
                    art.icon.setImageResource(
                        if (row.isFolder) R.drawable.directory_icon_white_24dp else R.drawable.person_icon_white_24dp
                    )
                    art.cover.loadCover(if (row.isFolder) null else group.songs.firstOrNull()?.artUri)
                    root.setOnClickListener { openDetail(group.key) }
                }
                is Row.Album -> (binding as MusicLibraryAlbumBinding).apply {
                    val group = row.group
                    title.text = groupName(group, TAB_ALBUMS)
                    info.text = group.artist ?: getString(R.string.audio_player_unknown_artist)
                    cover.loadCover(group.songs.firstOrNull()?.artUri)
                    root.setOnClickListener { openDetail(group.key) }
                }
            }
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
        private const val TYPE_ACTIONS = 0
        private const val TYPE_ROW = 1
        private const val TYPE_ALBUM = 2
        private const val PROGRESS_MAX = 1_000
        // Once a second, like the player: a screen that redraws more often never goes idle for accessibility tools.
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        private const val MINI_PLAYER_FADE_MS = 200L
    }
}
