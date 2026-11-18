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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
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
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.databinding.MusicLibraryActionsBinding
import me.zhanghai.android.files.databinding.MusicArtBinding
import me.zhanghai.android.files.databinding.MusicLibraryAlbumBinding
import me.zhanghai.android.files.databinding.MusicLibraryHeaderBinding
import me.zhanghai.android.files.databinding.MusicLibraryRowBinding
import me.zhanghai.android.files.databinding.MusicNavigationHeaderBinding
import me.zhanghai.android.files.navigation.NavigationItem
import me.zhanghai.android.files.navigation.NavigationListAdapter
import me.zhanghai.android.files.navigation.setUpOpenStagger
import me.zhanghai.android.files.player.AudioPlaybackService
import me.zhanghai.android.files.player.PlayHistoryStore
import me.zhanghai.android.files.player.PlaybackStateStore
import me.zhanghai.android.files.player.pathUriExists
import me.zhanghai.android.files.player.queueWindow
import me.zhanghai.android.files.player.restorableState
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.ui.DrawerLayoutOnBackPressedCallback
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
class MusicLauncherActivity : AppActivity(), NavigationItem.Listener {
    private lateinit var binding: MusicLauncherActivityBinding
    private val adapter = Adapter()
    // The accent for text and bars: in the light theme darkened until it reads at 4.5:1 on the surface.
    private val accentColor by lazy {
        val accent = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
        val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        readableOn(accent, surface)
    }
    private val rowTitleColor by lazy {
        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
    }
    private var library: MusicLibrary? = null
    private var songsByPath = emptyMap<String, MusicSong>()
    private var tab = TAB_SONGS
    // Library (with its tabs), recently played or most played: the side menu's first three entries.
    private var section = SECTION_LIBRARY
    // Playlists and favourites: M3U8 files in Music/Playlists, read with the library (name → song paths).
    private val playlists by lazy {
        @Suppress("DEPRECATION")
        PlaylistStore(java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Playlists"))
    }
    private var playlistData: Map<String, List<String>> = emptyMap()
    // The playlist open in the Playlists section.
    private var playlistName: String? = null
    // Songs selected with a long press (paths, in the order they were picked).
    private val selection = linkedSetOf<String>()
    private var keepQueryOnCollapse = false
    // Ordo's play history (PlayHistoryStore), as paths.
    private var history = emptyList<PlayHistoryStore.Entry>()
    private lateinit var navigationAdapter: NavigationListAdapter
    private val navigationHeader = NavigationHeaderAdapter()
    // What the menu's header shows as playing: title, artist, cover data and album (for the placeholder).
    private var nowPlaying: List<Any?>? = null
    // The album, artist or folder (by MusicGroup.key) open inside the current tab.
    private var detailKey: String? = null
    private var query = ""
    private var shownSongs = emptyList<MusicSong>()
    private var shownGroup: MusicGroup? = null
    // A page on a wide window: header and buttons on the left, songs on the right.
    private var twoPane = false
    // The song in the session (by path) and whether it plays: its row shows the moving bars.
    private var playingPath: String? = null
    private var currentPlaying = false
    private var miniAccent: Int? = null
    private var miniCoverColors = true
    private var miniColorAnimator: android.animation.ValueAnimator? = null
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
            when {
                selection.isNotEmpty() -> clearSelection()
                searchItem.isActionViewExpanded -> searchItem.collapseActionView()
                detailKey != null -> openDetail(null)
                playlistName != null -> openPlaylist(null)
                else -> openSection(SECTION_LIBRARY)
            }
        }
    }
    // The side menu slides in (phones, tablets upright) or stays beside the library (tablets in landscape).
    private val drawerLayout: androidx.drawerlayout.widget.DrawerLayout?
        get() = binding.root as? androidx.drawerlayout.widget.DrawerLayout
    private val persistentDrawer: me.zhanghai.android.files.ui.PersistentDrawerLayout?
        get() = binding.root as? me.zhanghai.android.files.ui.PersistentDrawerLayout
    private val searchItem: MenuItem
        get() = binding.content.toolbar.menu.findItem(R.id.actionSearch)
    private val accessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }
    // The tag editor: after a save the library reloads and offers undo for 10 s.
    private val editTagsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                TagEditUndo.offer(this, binding.content.root, lifecycleScope, result.data) { load() }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MusicLauncherActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        savedInstanceState?.let {
            tab = it.getInt(STATE_TAB)
            detailKey = it.getString(STATE_DETAIL)
            query = it.getString(STATE_QUERY).orEmpty()
            section = it.getInt(STATE_SECTION)
            playlistName = it.getString(STATE_PLAYLIST)
        }
        onBackPressedDispatcher.addCallback(this, backInLibrary)
        binding.content.recyclerView.adapter = adapter
        ThemedFastScroller.create(binding.content.recyclerView)
        setUpTabs()
        binding.content.recyclerView.addItemDecoration(GridSpacing())
        // The list and the mini player keep a readable width on tablets: centred, at most 610 dp.
        binding.content.recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) binding.content.recyclerView.post { updateWidths() }
        }
        binding.content.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateTitle()
                parallaxHeader(recyclerView)
            }
        })
        binding.content.tabs.getTabAt(tab)?.select()
        binding.content.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selected: TabLayout.Tab) {
                // openSection selects the tab itself after it has set the tab and section.
                if (selected.position == tab) return
                tab = selected.position
                section = sectionOfTab(tab)
                playlistName = null
                render(scrollToTop = true)
                navigationAdapter.notifyCheckedChanged()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) = binding.content.recyclerView.scrollToPosition(0)
        })
        binding.content.toolbar.setNavigationOnClickListener {
            when {
                selection.isNotEmpty() -> clearSelection()
                detailKey != null -> openDetail(null)
                playlistName != null -> openPlaylist(null)
                else -> toggleNavigation()
            }
        }
        setUpSearch()
        setUpNavigation()
        binding.content.toolbar.setOnMenuItemClickListener { item -> onToolbarItem(item.itemId) }
        binding.content.accessButton.setOnClickListener { requestAccess() }
        binding.content.miniPlayer.root.setOnClickListener {
            startActivitySafe(AudioPlayerActivity.createResumeIntent(this))
        }
        binding.content.miniPlayer.playPause.setOnClickListener {
            // With nothing in the session, play() makes the service resume the saved queue.
            controller?.let { Util.handlePlayPauseButtonAction(it) }
        }
        binding.content.miniPlayer.next.setOnClickListener { controller?.seekToNextMediaItem() }
    }

    private fun setUpTabs() {
        listOf(
            R.string.music_tab_songs to R.drawable.audio_icon_white_24dp,
            R.string.music_tab_albums to R.drawable.album_icon_white_24dp,
            R.string.music_tab_artists to R.drawable.person_icon_white_24dp,
            R.string.music_nav_playlists to R.drawable.queue_music_icon_white_24dp,
            R.string.music_nav_favorites to R.drawable.favorite_icon_white_24dp,
            R.string.music_tab_folders to R.drawable.directory_icon_white_24dp
        ).forEach { (text, icon) -> binding.content.tabs.addTab(binding.content.tabs.newTab().setText(text).setIcon(icon)) }
        // The pill takes Material 3's secondary container; the Material 2 theme has none, so primary at 16 %.
        val tabs = binding.content.tabs
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
        // The pill glides (and stretches) to the new tab; with reduced motion it jumps.
        if (!Motion.any) tabs.tabIndicatorAnimationDuration = 0
        // TabLayout's own ripple fills the whole rectangular tab (owner: a square frame flashed around the pill), so
        // it is off in the layout and every tab gets a ripple masked to exactly the pill's shape instead.
        val highlight = MaterialColors.getColor(tabs, androidx.appcompat.R.attr.colorControlHighlight)
        for (i in 0 until tabs.tabCount) {
            tabs.getTabAt(i)?.view?.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(highlight), null,
                ContextCompat.getDrawable(this, R.drawable.music_tab_indicator)
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        outState.putString(STATE_DETAIL, detailKey)
        outState.putString(STATE_QUERY, query)
        outState.putInt(STATE_SECTION, section)
        outState.putString(STATE_PLAYLIST, playlistName)
    }

    override fun onStart() {
        super.onStart()
        // Back from the player the search stays, but the keyboard must not cover the list again.
        (searchItem.actionView as SearchView).clearFocus()
        load()
        connectController()
        // Back from Ordo Musik's settings with "Colours from the cover" switched: the mini player follows.
        if (miniCoverColors != Settings.MUSIC_COVER_COLORS.valueCompat) miniArtKey = null
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
                binding.content.appBar.setExpanded(false, Motion.any)
                backInLibrary.isEnabled = true
                // The item counts as expanded only after this returns.
                binding.root.post { updateTitle() }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                // A selection takes the toolbar over: the search field steps aside but its results stay.
                if (!keepQueryOnCollapse) query = ""
                keepQueryOnCollapse = false
                render()
                // The item still counts as expanded while this runs.
                backInLibrary.isEnabled = detailKey != null || playlistName != null || !inTabs()
                binding.root.post { updateTitle() }
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
                history = PlayHistoryStore(context).read()
                playlistData = readPlaylists()
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
        binding.content.appBar.setExpanded(key == null, Motion.any)
        render(scrollToTop = true)
    }

    private fun render(scrollToTop: Boolean = false) {
        val library = library
        val group = library?.takeIf { section == SECTION_LIBRARY }
            ?.let { lib -> groupsOf(lib, tab).find { it.key == detailKey } }
        // A page whose album, artist or folder is gone after a reload closes.
        if (library != null && group == null) detailKey = null
        val rows = ArrayList<Row>()
        shownSongs = emptyList()
        when {
            library == null -> {}
            group != null -> {
                shownSongs = group.songs.filter { MusicLibrary.matches(it, query) }
                if (!twoPaneFor(group)) {
                    rows += Row.Header(group)
                    if (shownSongs.isNotEmpty()) rows += Row.Actions(shuffleOnly = false)
                }
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
            section == SECTION_PLAYLISTS && playlistName == null ->
                playlistData.filter { (name, _) -> MusicLibrary.matches(MusicGroup(name, playlistTitle(name), null, emptyList()), query) }
                    .mapTo(rows) { (name, paths) -> Row.Playlist(name, paths.mapNotNull { songsByPath[it] }) }
            section != SECTION_LIBRARY -> {
                shownSongs = sectionSongs().filter { MusicLibrary.matches(it, query) }
                if (shownSongs.isNotEmpty()) rows += Row.Actions(shuffleOnly = false)
                shownSongs.forEachIndexed { index, song -> rows += Row.Song(song, index, artistAndAlbum(song)) }
            }
            tab == TAB_SONGS -> {
                shownSongs = library.songs.filter { MusicLibrary.matches(it, query) }
                if (shownSongs.isNotEmpty()) rows += Row.Actions(shuffleOnly = true)
                shownSongs.forEachIndexed { index, song -> rows += Row.Song(song, index, artistAndAlbum(song)) }
            }
            else -> groupsOf(library, tab).filter { MusicLibrary.matches(it, query) }
                .mapTo(rows) { if (tab == TAB_ALBUMS) Row.Album(it) else Row.Group(it, tab == TAB_FOLDERS) }
        }
        shownGroup = group
        twoPane = twoPaneFor(group)
        binding.content.detailPane.isVisible = twoPane
        if (twoPane && group != null) {
            bindHeader(binding.content.paneHeader, group)
            bindActions(binding.content.paneActions, shuffleOnly = false)
            stackPaneActions()
            binding.content.paneActions.root.isVisible = shownSongs.isNotEmpty()
        }
        // A page has no large title area: its own cover and name are the header.
        val headerHeight = if (group != null) {
            binding.content.toolbar.layoutParams.height
        } else {
            resources.getDimensionPixelSize(R.dimen.music_header_height)
        }
        if (binding.content.collapsingToolbar.layoutParams.height != headerHeight) {
            binding.content.collapsingToolbar.layoutParams = binding.content.collapsingToolbar.layoutParams.apply { height = headerHeight }
        }
        val grid = group == null && section == SECTION_LIBRARY && tab == TAB_ALBUMS
        val layoutManager = binding.content.recyclerView.layoutManager
        if (layoutManager == null || (layoutManager is GridLayoutManager) != grid) {
            binding.content.recyclerView.layoutManager =
                if (grid) GridLayoutManager(this, albumColumns()) else LinearLayoutManager(this)
        }
        updateWidths()
        adapter.submit(rows)
        if (scrollToTop) binding.content.recyclerView.scrollToPosition(0)

        updateTitle()
        binding.content.tabs.isVisible = group == null && playlistName == null && inTabs()
        backInLibrary.isEnabled = group != null || playlistName != null || !inTabs() || searchItem.isActionViewExpanded ||
            selection.isNotEmpty()
        updateToolbarMenu()
        // A page goes back; otherwise the button opens the side menu, as in Ordo's file list.
        if (selection.isNotEmpty()) {
            binding.content.toolbar.setNavigationIcon(R.drawable.close_icon_control_normal_24dp)
            binding.content.toolbar.setNavigationContentDescription(R.string.music_selection_close)
        } else if (group != null || playlistName != null) {
            binding.content.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.content.toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
        } else {
            binding.content.toolbar.setNavigationIcon(R.drawable.menu_icon_control_normal_24dp)
            binding.content.toolbar.setNavigationContentDescription(R.string.open_navigation_drawer)
        }
        val hasAccess = library != null || hasStorageAccess()
        binding.content.emptyText.text = when {
            !hasAccess -> getString(R.string.music_access_message)
            library == null -> null
            query.isNotBlank() -> getString(R.string.music_no_results, query.trim())
            section == SECTION_FAVORITES -> getString(R.string.music_favorites_empty)
            section == SECTION_PLAYLISTS && playlistName == null -> getString(R.string.music_playlists_empty)
            section == SECTION_PLAYLISTS -> null
            section != SECTION_LIBRARY -> getString(R.string.music_history_empty)
            else -> getString(R.string.music_empty)
        }
        binding.content.accessButton.isVisible = !hasAccess
        binding.content.emptyGroup.isVisible = rows.isEmpty() && (library != null || !hasAccess)
        binding.content.recyclerView.isVisible = rows.isNotEmpty()
    }

    private fun twoPaneFor(group: MusicGroup?): Boolean =
        group != null && resources.configuration.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP

    /**
     * The library shows its large "Music" title. A page shows its name in the toolbar only once its own header has
     * scrolled away (never on a wide window, where the header stays on the left); a search hides both.
     */
    private fun updateTitle() {
        val group = shownGroup
        val searching = searchItem.isActionViewExpanded
        val collapsing = binding.content.collapsingToolbar
        // The count is a view of its own in the toolbar: a title drawn by the collapsing bar is not reliably shown
        // (or read by TalkBack) while the search field steps aside.
        val count = binding.content.selectionCount
        count.isVisible = selection.isNotEmpty()
        if (selection.isNotEmpty()) {
            collapsing.isTitleEnabled = false
            binding.content.toolbar.title = null
            count.text = resources.getQuantityString(R.plurals.music_selected, selection.size, selection.size)
            return
        }
        if (group == null) {
            collapsing.isTitleEnabled = !searching
            collapsing.title = playlistName?.let(::playlistTitle) ?: getString(sectionTitle())
            binding.content.toolbar.title = null
            return
        }
        collapsing.isTitleEnabled = false
        val firstVisible = (binding.content.recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        val headerGone = !twoPane && firstVisible > 0
        binding.content.toolbar.title = if (headerGone && !searching) groupName(group, tab) else null
    }

    /**
     * A page's big cover drifts up at 40 % of the scroll and fades a little as the list moves over it (the hero's
     * parallax); without motion it simply scrolls with the list.
     */
    private fun parallaxHeader(list: RecyclerView) {
        val header = list.findViewHolderForAdapterPosition(0)?.itemView ?: return
        val art = header.findViewById<View>(R.id.art) ?: return
        if (!Motion.any || header.height == 0) {
            art.translationY = 0f
            art.alpha = 1f
            return
        }
        val scrolled = (-header.top).coerceAtLeast(0).toFloat()
        art.translationY = scrolled * HERO_PARALLAX
        art.alpha = 1f - (scrolled / header.height).coerceIn(0f, 1f) * HERO_FADE
    }

    private fun bindHeader(header: MusicLibraryHeaderBinding, group: MusicGroup) {
        header.art.root.translationY = 0f
        header.art.root.alpha = 1f
        // An album's cover about two thirds of a phone's width, 233 dp at most on a tablet.
        val size = minOf(
            resources.displayMetrics.widthPixels * 2 / 3,
            resources.getDimensionPixelSize(R.dimen.phi_233)
        )
        header.art.root.layoutParams = header.art.root.layoutParams.apply {
            width = size
            height = size
        }
        header.title.text = groupName(group, tab)
        header.subtitle.text = when (tab) {
            TAB_ALBUMS -> group.artist ?: getString(R.string.audio_player_unknown_artist)
            TAB_FOLDERS -> displayFolder(group.key)
            else -> null
        }
        header.subtitle.isVisible = !header.subtitle.text.isNullOrEmpty()
        header.info.text = formatTotal(group.songs)
        when (tab) {
            TAB_ALBUMS -> bindArt(
                header.art, placeholder(group.name, R.drawable.album_icon_white_24dp),
                listOf(group.songs.firstOrNull()?.artUri), resources.getDimension(R.dimen.phi_21)
            )
            // Artists are round, like a portrait.
            TAB_ARTISTS -> bindArt(
                header.art, placeholder(group.name, R.drawable.person_icon_white_24dp),
                albumCovers(group.songs), size / 2f
            )
            else -> bindArt(
                header.art, placeholder(group.name, R.drawable.directory_icon_white_24dp),
                albumCovers(group.songs), resources.getDimension(R.dimen.phi_21), size / 12
            )
        }
    }

    /**
     * In the narrow left pane (a tablet upright with the side menu beside it) "Abspielen" and "Zufällig" side by side
     * broke into two lines each: there they stand one above the other, each the full width.
     */
    private fun stackPaneActions() {
        val actions = binding.content.paneActions
        if (actions.root.orientation == android.widget.LinearLayout.VERTICAL) return
        actions.root.orientation = android.widget.LinearLayout.VERTICAL
        val gap = resources.getDimensionPixelSize(R.dimen.phi_8)
        for (button in listOf(actions.play, actions.shuffle)) {
            button.layoutParams = (button.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                marginEnd = 0
                topMargin = if (button === actions.shuffle) gap else 0
            }
        }
    }

    private fun bindActions(actions: MusicLibraryActionsBinding, shuffleOnly: Boolean) {
        actions.play.isVisible = !shuffleOnly
        actions.shuffle.text = if (shuffleOnly) {
            getString(R.string.music_shuffle_all, shownSongs.size)
        } else {
            getString(R.string.music_shuffle)
        }
        actions.play.setOnClickListener { play(shownSongs, 0) }
        actions.shuffle.setOnClickListener { play(MusicLibrary.shuffled(shownSongs), 0) }
    }

    /** ⋮ of a song: play next and add to the queue while there is one, and the file in its folder. */
    private fun showSongMenu(anchor: View, song: MusicSong) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.inflate(R.menu.music_song)
        popup.setForceShowIcon(true)
        val queued = (controller?.mediaItemCount ?: 0) > 0
        popup.menu.findItem(R.id.actionPlayNext).isVisible = queued
        popup.menu.findItem(R.id.actionAddToQueue).isVisible = queued
        val favorite = song.path in playlistData[PlaylistStore.FAVORITES_FILE].orEmpty()
        popup.menu.findItem(R.id.actionFavorite).apply {
            setTitle(if (favorite) R.string.music_favorite_remove else R.string.music_favorite_add)
            setIcon(if (favorite) R.drawable.favorite_border_icon_white_24dp else R.drawable.favorite_icon_white_24dp)
        }
        popup.menu.findItem(R.id.actionRemoveFromPlaylist).isVisible = section == SECTION_PLAYLISTS && playlistName != null
        val iconTint = ColorStateList.valueOf(
            MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurfaceVariant,
                MaterialColors.getColor(anchor, android.R.attr.textColorSecondary))
        )
        for (index in 0 until popup.menu.size()) MenuItemCompat.setIconTintList(popup.menu.getItem(index), iconTint)
        popup.setOnMenuItemClickListener { item ->
            val player = controller
            val mediaItem = AudioPlaybackService.mediaItem(Paths.get(song.path).toUri().toString())
            when (item.itemId) {
                R.id.actionPlayNext -> if (player != null) {
                    player.addMediaItem((player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount), mediaItem)
                    confirm(R.string.music_added_next)
                }
                R.id.actionAddToQueue -> if (player != null) {
                    player.addMediaItem(mediaItem)
                    confirm(R.string.music_added_to_queue)
                }
                R.id.actionFavorite -> changePlaylists(
                    if (favorite) R.string.music_favorite_removed else R.string.music_favorite_added
                ) { it.toggleFavorite(song.path) }
                R.id.actionAddToPlaylist -> pickPlaylist(listOf(song.path))
                R.id.actionRemoveFromPlaylist -> playlistName?.let { name ->
                    changePlaylists(null) { it.remove(name, listOf(song.path)) }
                }
                R.id.actionEditTags ->
                    editTagsLauncher.launch(MusicTagEditorActivity.createIntent(this, listOf(song.path)))
                R.id.actionShowFile -> Paths.get(song.path).parent?.let {
                    startActivitySafe(FileListActivity.createViewIntent(it))
                }
            }
            true
        }
        popup.show()
    }

    private fun confirm(text: Int) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).apply {
            if (binding.content.miniPlayer.root.isVisible) anchorView = binding.content.miniPlayer.root
        }.show()
    }

    @StringRes
    private fun sectionTitle(): Int = when (section) {
        SECTION_RECENT -> R.string.music_nav_recent
        SECTION_MOST -> R.string.music_nav_most
        // Favourites and playlists are tabs of the library: the tab names them, the title stays "Music".
        else -> R.string.launcher_music_start_title
    }

    /** The songs of the section that are still in the library, in its order. */
    private fun sectionSongs(): List<MusicSong> = when (section) {
        SECTION_FAVORITES -> playlistData[PlaylistStore.FAVORITES_FILE].orEmpty().mapNotNull { songsByPath[it] }
        SECTION_PLAYLISTS -> playlistData[playlistName].orEmpty().mapNotNull { songsByPath[it] }
        else -> historySongs()
    }

    /** The songs of the play history that are still in the library, newest or most played first. */
    private fun historySongs(): List<MusicSong> {
        val ordered = if (section == SECTION_MOST) {
            PlayHistoryStore.mostPlayed(history)
        } else {
            PlayHistoryStore.recent(history)
        }
        return ordered.mapNotNull { songAt(it.pathUri) }
    }

    // ---- Side menu: Ordo's own (NavigationListAdapter, its header style and the opening stagger) -------------------

    private fun setUpNavigation() {
        val list = binding.navigation.recyclerView
        list.layoutManager = LinearLayoutManager(this)
        navigationAdapter = NavigationListAdapter(this, this)
        list.adapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                .build(),
            navigationHeader, navigationAdapter
        )
        navigationAdapter.replace(navigationItems())
        list.doOnAttach { setUpOpenStagger(list) }
        // The open menu dims the library only a little (about a third), so it stays visible behind it.
        drawerLayout?.setScrimColor(0x52000000)
        // Registered after the library's own back handling, so an open menu closes first.
        drawerLayout?.let { onBackPressedDispatcher.addCallback(this, DrawerLayoutOnBackPressedCallback(it)) }
        persistentDrawer?.openDrawer(GravityCompat.START, false)
    }

    private fun navigationItems(): List<NavigationItem?> = listOf(
        MusicNavigationItem(MusicNavigation.IDS[0]!!, R.drawable.audio_icon_white_24dp, R.string.music_nav_library,
            { section == SECTION_LIBRARY }) { openSection(SECTION_LIBRARY) },
        MusicNavigationItem(MusicNavigation.IDS[1]!!, R.drawable.history_icon_white_24dp, R.string.music_nav_recent,
            { section == SECTION_RECENT }) { openSection(SECTION_RECENT) },
        MusicNavigationItem(MusicNavigation.IDS[2]!!, R.drawable.trending_icon_white_24dp, R.string.music_nav_most,
            { section == SECTION_MOST }) { openSection(SECTION_MOST) },
        MusicNavigationItem(MusicNavigation.IDS[3]!!, R.drawable.favorite_icon_white_24dp, R.string.music_nav_favorites,
            { section == SECTION_FAVORITES }) { openSection(SECTION_FAVORITES) },
        MusicNavigationItem(MusicNavigation.IDS[4]!!, R.drawable.queue_music_icon_white_24dp, R.string.music_nav_playlists,
            { section == SECTION_PLAYLISTS }) { openSection(SECTION_PLAYLISTS) },
        null,
        MusicNavigationItem(MusicNavigation.IDS[6]!!, R.drawable.audio_equalizer_icon_24dp, R.string.audio_sound_title) { openSound(false) },
        MusicNavigationItem(MusicNavigation.IDS[7]!!, R.drawable.audio_timer_icon_24dp, R.string.audio_sound_sleep_heading) { openSound(true) },
        null,
        MusicNavigationItem(MusicNavigation.IDS[9]!!, R.drawable.settings_icon_white_24dp, R.string.settings_title) {
            launchIntent(SettingsActivity.createIntent(SettingsActivity.SECTION_MUSIC))
        },
        MusicNavigationItem(MusicNavigation.IDS[10]!!, R.drawable.directory_icon_white_24dp, R.string.music_nav_files) {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent(it) }
        }
    )

    private fun toggleNavigation() {
        drawerLayout?.openDrawer(GravityCompat.START)
        persistentDrawer?.let {
            if (it.isDrawerOpen(GravityCompat.START)) it.closeDrawer(GravityCompat.START) else it.openDrawer(GravityCompat.START)
        }
    }

    private fun openSection(newSection: Int) {
        searchItem.collapseActionView()
        selection.clear()
        section = newSection
        detailKey = null
        playlistName = null
        tab = when (newSection) {
            SECTION_FAVORITES -> TAB_FAVORITES
            SECTION_PLAYLISTS -> TAB_PLAYLISTS
            else -> tab.takeIf { sectionOfTab(it) == SECTION_LIBRARY } ?: TAB_SONGS
        }
        binding.content.tabs.getTabAt(tab)?.select()
        binding.content.appBar.setExpanded(true, Motion.any)
        render(scrollToTop = true)
        navigationAdapter.notifyCheckedChanged()
        closeNavigationDrawer()
    }

    private fun sectionOfTab(tab: Int): Int = when (tab) {
        TAB_FAVORITES -> SECTION_FAVORITES
        TAB_PLAYLISTS -> SECTION_PLAYLISTS
        else -> SECTION_LIBRARY
    }

    /** The library's top level, where the tabs show: everything but the history lists. */
    private fun inTabs(): Boolean = section != SECTION_RECENT && section != SECTION_MOST

    /** Sound or the sleep timer, over the player: with the saved queue if there is one, so they apply to it. */
    private fun openSound(sleepTimer: Boolean) {
        val resumable = saved != null || (controller?.mediaItemCount ?: 0) > 0
        val intent = if (resumable) {
            AudioPlayerActivity.createResumeIntent(this)
        } else {
            AudioPlayerActivity.createSessionIntent(this)
        }
        startActivitySafe(AudioPlayerActivity.withSoundSheet(intent, sleepTimer))
    }

    override val currentPath: java8.nio.file.Path?
        get() = null

    override fun navigateTo(path: java8.nio.file.Path) {}

    override fun navigateToRoot(path: java8.nio.file.Path) {}

    override fun launchIntent(intent: Intent) {
        startActivitySafe(intent)
    }

    override fun closeNavigationDrawer() {
        drawerLayout?.closeDrawer(GravityCompat.START)
    }

    private class MusicNavigationItem(
        override val id: Long,
        @DrawableRes override val iconRes: Int,
        @StringRes private val titleRes: Int,
        private val checked: () -> Boolean = { false },
        private val action: () -> Unit
    ) : NavigationItem() {
        override fun getTitle(context: android.content.Context): String = context.getString(titleRes)

        override fun isChecked(listener: Listener): Boolean = checked()

        override fun onClick(listener: Listener) {
            action()
            listener.closeNavigationDrawer()
        }
    }

    /** Ordo Music's icon and name, and what plays: a small cover, title and artist that open the player. */
    private inner class NavigationHeaderAdapter : RecyclerView.Adapter<Holder<MusicNavigationHeaderBinding>>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int = 1

        override fun getItemId(position: Int): Long = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder<MusicNavigationHeaderBinding> =
            Holder(MusicNavigationHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder<MusicNavigationHeaderBinding>, position: Int) {
            val header = holder.binding
            val now = nowPlaying
            header.nowPlaying.isVisible = now != null
            if (now == null) return
            header.navTitle.text = now[0] as CharSequence
            header.navArtist.text = now[1] as CharSequence
            header.nowPlaying.contentDescription = getString(R.string.music_now_playing, now[0], now[1])
            header.art.collage.isVisible = false
            header.art.cover.loadCover(now[2], placeholder(now[3] as String?, R.drawable.audio_icon_white_24dp))
            header.nowPlaying.setOnClickListener {
                closeNavigationDrawer()
                startActivitySafe(AudioPlayerActivity.createResumeIntent(this@MusicLauncherActivity))
            }
        }
    }

    private fun updateNavigationHeader(now: List<Any?>?) {
        val key = now?.let { listOf(it[0].toString(), it[1].toString(), (it[2] as? ByteArray)?.contentHashCode() ?: it[2]) }
        val oldKey = nowPlaying?.let {
            listOf(it[0].toString(), it[1].toString(), (it[2] as? ByteArray)?.contentHashCode() ?: it[2])
        }
        nowPlaying = now
        if (key != oldKey) navigationHeader.notifyItemChanged(0)
    }

    // ---- Playlists, favourites and the selection ------------------------------------------------------------------

    private fun readPlaylists(): Map<String, List<String>> =
        runCatching { playlists.names().associateWith { playlists.read(it) } }.getOrDefault(emptyMap())

    private fun playlistTitle(name: String): String =
        if (name == PlaylistStore.FAVORITES_FILE) getString(R.string.music_nav_favorites) else PlaylistStore.title(name)

    private fun openPlaylist(name: String?) {
        searchItem.collapseActionView()
        playlistName = name
        binding.content.appBar.setExpanded(true, Motion.any)
        render(scrollToTop = true)
    }

    /** Changes the playlist files off the main thread, then reloads them and says what happened. */
    private fun changePlaylists(@StringRes done: Int?, doneArg: String? = null, change: (PlaylistStore) -> Unit) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { change(playlists) }.onFailure { it.printStackTrace() }.isSuccess.also {
                    playlistData = readPlaylists()
                }
            }
            render()
            when {
                !ok -> confirm(R.string.music_playlist_failed)
                done != null && doneArg != null ->
                    Snackbar.make(binding.content.root, getString(done, doneArg), Snackbar.LENGTH_SHORT).apply {
                        if (binding.content.miniPlayer.root.isVisible) anchorView = binding.content.miniPlayer.root
                    }.show()
                done != null -> confirm(done)
            }
        }
    }

    /** "Add to playlist": the existing lists (favourites aside) and a new one. */
    private fun pickPlaylist(paths: List<String>) {
        val names = playlistData.keys.filter { it != PlaylistStore.FAVORITES_FILE }
        val labels = (listOf(getString(R.string.music_playlist_new)) + names.map(PlaylistStore::title)).toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.music_playlist_add)
            .setItems(labels) { _, which ->
                if (which == 0) {
                    newPlaylist(paths)
                } else {
                    val name = names[which - 1]
                    changePlaylists(R.string.music_playlist_added, PlaylistStore.title(name)) { it.add(name, paths) }
                }
            }
            .show()
    }

    private fun newPlaylist(paths: List<String>) {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.music_playlist_name)
            setStartIconDrawable(R.drawable.queue_music_icon_white_24dp)
            addView(input)
            val gutter = resources.getDimensionPixelSize(R.dimen.space_gutter)
            setPadding(gutter, resources.getDimensionPixelSize(R.dimen.phi_8), gutter, 0)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.music_playlist_new)
            .setView(layout)
            .setPositiveButton(R.string.music_tag_save) { _, _ ->
                val name = PlaylistStore.fileName(input.text.toString()) ?: return@setPositiveButton
                changePlaylists(R.string.music_playlist_added, PlaylistStore.title(name)) { it.add(name, paths) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun toggleSelection(path: String) {
        if (!selection.remove(path)) selection += path
        // The toolbar shows "N selected" and the actions; an open search field would hide them.
        if (selection.isNotEmpty() && searchItem.isActionViewExpanded) {
            keepQueryOnCollapse = true
            searchItem.collapseActionView()
        }
        render()
        // The search field comes back only once the menu shows its item again (next frame).
        if (selection.isEmpty()) binding.root.post { restoreSearch() }
    }

    private fun clearSelection() {
        selection.clear()
        render()
        binding.root.post { restoreSearch() }
    }

    /** After a selection made in search results, the search field comes back with its words. */
    private fun restoreSearch() {
        if (query.isBlank() || searchItem.isActionViewExpanded) return
        val restored = query
        searchItem.isVisible = true
        searchItem.expandActionView()
        (searchItem.actionView as SearchView).apply {
            setQuery(restored, false)
            clearFocus()
        }
    }

    private fun updateToolbarMenu() {
        val menu = binding.content.toolbar.menu
        val selecting = selection.isNotEmpty()
        menu.setGroupVisible(R.id.groupSelection, selecting)
        searchItem.isVisible = !selecting
        menu.findItem(R.id.actionEditAll).isVisible = !selecting && shownGroup != null
        menu.findItem(R.id.actionDeletePlaylist).isVisible =
            !selecting && playlistName != null && playlistName != PlaylistStore.FAVORITES_FILE
        if (selecting) {
            val queued = (controller?.mediaItemCount ?: 0) > 0
            menu.findItem(R.id.actionSelectionPlayNext).isVisible = queued
            menu.findItem(R.id.actionSelectionQueue).isVisible = queued
        }
    }

    private fun onToolbarItem(itemId: Int): Boolean {
        val paths = selection.toList()
        when (itemId) {
            R.id.actionEditAll -> shownGroup?.let {
                editTagsLauncher.launch(MusicTagEditorActivity.createIntent(this, it.songs.map(MusicSong::path)))
            }
            R.id.actionDeletePlaylist -> playlistName?.let { name ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.music_playlist_delete_confirm, PlaylistStore.title(name)))
                    .setPositiveButton(R.string.music_playlist_delete) { _, _ ->
                        playlistName = null
                        changePlaylists(null) { it.delete(name) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.actionSelectionPlayNext, R.id.actionSelectionQueue -> controller?.let { player ->
                val items = paths.map { AudioPlaybackService.mediaItem(Paths.get(it).toUri().toString()) }
                if (itemId == R.id.actionSelectionPlayNext) {
                    player.addMediaItems((player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount), items)
                    confirm(R.string.music_added_next)
                } else {
                    player.addMediaItems(items)
                    confirm(R.string.music_added_to_queue)
                }
                clearSelection()
            }
            R.id.actionSelectionPlaylist -> {
                pickPlaylist(paths)
                clearSelection()
            }
            R.id.actionSelectionFavorite -> {
                changePlaylists(R.string.music_favorite_added) { it.add(PlaylistStore.FAVORITES_FILE, paths) }
                clearSelection()
            }
            R.id.actionSelectionEdit -> {
                editTagsLauncher.launch(MusicTagEditorActivity.createIntent(this, paths))
                clearSelection()
            }
            else -> return false
        }
        return true
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
        val width = binding.content.root.width.takeIf { it > 0 } ?: return
        val side = ((width - resources.getDimensionPixelSize(R.dimen.phi_content_max)) / 2).coerceAtLeast(0)
        val recycler = binding.content.recyclerView
        val listSide = if (recycler.layoutManager is GridLayoutManager || twoPane) 0 else side
        if (recycler.paddingStart != listSide) {
            recycler.setPaddingRelative(listSide, recycler.paddingTop, listSide, recycler.paddingBottom)
        }
        val mini = binding.content.miniPlayer.root
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
        // The song that is already in the session: the player opens and it plays on from where it is, in any list
        // (also in another list than the one it was started from); no new queue, no seek.
        if (MusicLibrary.isSameSong(songs[index].path, controller?.currentMediaItem?.mediaId)) {
            startActivitySafe(AudioPlayerActivity.createResumeIntent(this))
            return
        }
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
        val mini = binding.content.miniPlayer
        val player = controller
        val item = player?.currentMediaItem
        val savedState = saved
        val title: CharSequence
        val artist: CharSequence?
        val art: Any?
        val album: String?
        val showPlay: Boolean
        val path = item?.mediaId?.let { runCatching { URI.create(it).path }.getOrNull() }
        val isPlaying = player?.isPlaying == true
        if (path != playingPath || isPlaying != currentPlaying) {
            playingPath = path
            currentPlaying = isPlaying
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
        if (item != null) {
            val metadata = player.mediaMetadata
            val song = songAt(item.mediaId)
            title = metadata.title?.takeIf { it.isNotBlank() } ?: song?.title ?: metadata.displayTitle
                ?: getString(R.string.audio_player_unknown_title)
            artist = metadata.artist?.takeIf { it.isNotBlank() } ?: song?.artist
            art = metadata.artworkData ?: song?.let { me.zhanghai.android.files.coil.SongCover(it.path, it.artUri) }
            album = song?.album ?: metadata.albumTitle?.toString()
            showPlay = Util.shouldShowPlayButton(player)
        } else if (savedState != null && player != null) {
            val pathUri = savedState.paths[savedState.currentIndex]
            val song = songAt(pathUri)
            title = song?.title ?: pathUri.substringAfterLast('/')
            artist = song?.artist
            art = song?.let { me.zhanghai.android.files.coil.SongCover(it.path, it.artUri) }
            album = song?.album
            showPlay = true
        } else {
            mini.root.isVisible = false
            handler.removeCallbacks(progressUpdater)
            updateNavigationHeader(null)
            return
        }
        mini.miniTitle.text = title
        mini.miniArtist.text = artist ?: getString(R.string.audio_player_unknown_artist)
        updateNavigationHeader(listOf(title, mini.miniArtist.text, art, album))
        mini.root.contentDescription = getString(R.string.music_now_playing, title, mini.miniArtist.text)
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
            mini.art.cover.loadCover(art, placeholder(album, R.drawable.audio_icon_white_24dp)) {
                tintMiniPlayer(it, artKey)
            }
            if (art == null) tintMiniPlayer(null, artKey)
        }
        // A quiet, still wave while music plays, flat when paused. It does not flow: MDC moves it every display frame,
        // which cost about 9 % of a core on the Galaxy Tab S9 Ultra (18.11.2026) for a 2 dp line.
        mini.progress.waveAmplitude =
            if (isPlaying) resources.getDimensionPixelSize(R.dimen.music_mini_wave_amplitude) else 0
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
        binding.content.miniPlayer.progress.setProgressCompat(progress, false)
    }

    private fun ImageView.loadCover(data: Any?, placeholder: Drawable?, onLoaded: ((Drawable?) -> Unit)? = null) {
        if (data == null) {
            dispose()
            setImageDrawable(placeholder)
            return
        }
        load(data) {
            placeholder(placeholder)
            error(placeholder)
            crossfade(Motion.any)
            if (onLoaded != null) {
                // The tint reads the cover's pixels, which a hardware bitmap does not allow.
                allowHardware(false)
                listener(onSuccess = { _, result -> onLoaded(result.drawable) }, onError = { _, _ -> onLoaded(null) })
            }
        }
    }

    /**
     * The mini player takes on the playing cover's colour like the player: the card tinted towards it, the wave in its
     * accent, both WCAG-checked by [AudioPlayerColorWash.scheme] and computed off the main thread; no cover or a failed
     * check keeps the theme's colours.
     */
    private fun tintMiniPlayer(drawable: Drawable?, key: Any?) {
        val mini = binding.content.miniPlayer
        val surface = MaterialColors.getColor(mini.root, com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurface = MaterialColors.getColor(mini.root, com.google.android.material.R.attr.colorOnSurface)
        val primary = MaterialColors.getColor(mini.root, androidx.appcompat.R.attr.colorPrimary)
        miniCoverColors = Settings.MUSIC_COVER_COLORS.valueCompat
        // "Colours from the cover" off (Ordo Musik › Settings): the chosen accent.
        val bitmap = (drawable as? BitmapDrawable)?.bitmap?.takeIf { miniCoverColors }
        if (bitmap == null) {
            applyMiniColors(surface, primary)
            return
        }
        val isLight = androidx.core.graphics.ColorUtils.calculateLuminance(surface) > 0.5
        lifecycleScope.launch {
            val scheme = withContext(Dispatchers.Default) {
                val small = Bitmap.createScaledBitmap(bitmap, MINI_TINT_SAMPLE, MINI_TINT_SAMPLE, true)
                val pixels = IntArray(MINI_TINT_SAMPLE * MINI_TINT_SAMPLE)
                small.getPixels(pixels, 0, MINI_TINT_SAMPLE, 0, 0, MINI_TINT_SAMPLE, MINI_TINT_SAMPLE)
                if (small !== bitmap) small.recycle()
                AudioPlayerColorWash.seed(pixels)?.let { AudioPlayerColorWash.scheme(it, surface, onSurface, isLight) }
            }
            if (key != miniArtKey) return@launch
            applyMiniColors(scheme?.tint ?: surface, scheme?.accent ?: primary)
        }
    }

    private fun applyMiniColors(card: Int, accent: Int) {
        val mini = binding.content.miniPlayer
        val from = mini.root.cardBackgroundColor.defaultColor
        val fromAccent = miniAccent ?: accent
        miniAccent = accent
        miniColorAnimator?.cancel()
        val evaluator = android.animation.ArgbEvaluator()
        fun apply(f: Float) {
            mini.root.setCardBackgroundColor(evaluator.evaluate(f, from, card) as Int)
            val a = evaluator.evaluate(f, fromAccent, accent) as Int
            mini.progress.setIndicatorColor(a)
            mini.progress.trackColor = androidx.core.graphics.ColorUtils.setAlphaComponent(a, MINI_TRACK_ALPHA)
        }
        if (!Motion.any || !mini.root.isShown) {
            apply(1f)
            return
        }
        miniColorAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MINI_TINT_FADE_MS
            addUpdateListener { apply(it.animatedFraction) }
            start()
        }
    }

    private sealed class Row {
        class Header(val group: MusicGroup) : Row()
        class Actions(val shuffleOnly: Boolean) : Row()
        class Song(val song: MusicSong, val index: Int, val info: String?, val number: Int? = null) : Row()
        class Album(val group: MusicGroup) : Row()
        class Group(val group: MusicGroup, val isFolder: Boolean) : Row()
        class Playlist(val name: String, val songs: List<MusicSong>) : Row()
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
                is Row.Actions -> bindActions(binding as MusicLibraryActionsBinding, row.shuffleOnly)
                is Row.Header -> bindHeader(binding as MusicLibraryHeaderBinding, row.group)
                is Row.Song -> (binding as MusicLibraryRowBinding).apply {
                    val song = row.song
                    val current = song.path == playingPath
                    title.text = song.title
                    title.setTextColor(if (current) accentColor else rowTitleColor)
                    info.text = row.info
                    info.isVisible = row.info != null
                    duration.isVisible = true
                    duration.text = formatDuration(song.durationMs)
                    numberSlot.isVisible = row.number != null
                    art.root.isVisible = row.number == null
                    if (row.number != null) {
                        // The playing song's number gives way to the moving bars.
                        number.text = row.number.toString()
                        number.isVisible = !current
                        slotBars.isVisible = current
                        slotBars.color = accentColor
                        slotBars.setPlaying(current && currentPlaying)
                    } else {
                        // Each song its own cover (embedded picture first): an untagged folder is one MediaStore
                        // album, whose cover would otherwise repeat on every row.
                        art.root.radius = resources.getDimension(R.dimen.phi_radius_s)
                        art.collage.isVisible = false
                        art.cover.loadCover(
                            me.zhanghai.android.files.coil.SongCover(song.path, song.artUri),
                            placeholder(song.album, R.drawable.audio_icon_white_24dp)
                        )
                        art.bars.isVisible = current
                        art.bars.color = Color.WHITE
                        art.bars.setPlaying(current && currentPlaying)
                    }
                    // A long press selects; while songs are selected a tap adds or removes one.
                    val selected = song.path in selection
                    art.selected.isVisible = selected && row.number == null
                    if (row.number != null && selected) number.text = "✓"
                    root.isActivated = selected
                    root.background = if (selected) selectedBackground() else rowBackground()
                    root.setOnLongClickListener {
                        toggleSelection(song.path)
                        true
                    }
                    more.isVisible = selection.isEmpty()
                    more.contentDescription = getString(R.string.music_more_options, song.title)
                    more.setOnClickListener { showSongMenu(it, song) }
                    root.setPaddingRelative(
                        root.paddingStart, root.paddingTop, resources.getDimensionPixelSize(R.dimen.phi_5),
                        root.paddingBottom
                    )
                    root.setOnClickListener {
                        if (selection.isNotEmpty()) toggleSelection(song.path) else play(shownSongs, row.index)
                    }
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
                    numberSlot.isVisible = false
                    more.isVisible = false
                    art.root.isVisible = true
                    art.bars.isVisible = false
                    art.selected.isVisible = false
                    root.background = rowBackground()
                    root.setOnLongClickListener(null)
                    title.setTextColor(rowTitleColor)
                    root.setPaddingRelative(
                        root.paddingStart, root.paddingTop, resources.getDimensionPixelSize(R.dimen.space_gutter),
                        root.paddingBottom
                    )
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
                is Row.Playlist -> (binding as MusicLibraryRowBinding).apply {
                    val favorites = row.name == PlaylistStore.FAVORITES_FILE
                    title.text = playlistTitle(row.name)
                    title.setTextColor(rowTitleColor)
                    info.isVisible = true
                    info.text = resources.getQuantityString(R.plurals.music_song_count, row.songs.size, row.songs.size)
                    duration.isVisible = false
                    numberSlot.isVisible = false
                    more.isVisible = false
                    art.root.isVisible = true
                    art.bars.isVisible = false
                    art.selected.isVisible = false
                    root.background = rowBackground()
                    root.setOnLongClickListener(null)
                    val size = resources.getDimensionPixelSize(R.dimen.music_row_art_size)
                    bindArt(
                        art, placeholder(playlistTitle(row.name),
                            if (favorites) R.drawable.favorite_icon_white_24dp else R.drawable.queue_music_icon_white_24dp),
                        albumCovers(row.songs), resources.getDimension(R.dimen.phi_radius_s), size / 12
                    )
                    root.setPaddingRelative(
                        root.paddingStart, root.paddingTop, resources.getDimensionPixelSize(R.dimen.space_gutter),
                        root.paddingBottom
                    )
                    root.setOnClickListener { openPlaylist(row.name) }
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

    private fun readableOn(color: Int, background: Int): Int {
        var result = color
        var step = 0
        while (androidx.core.graphics.ColorUtils.calculateContrast(result, background) < 4.5 && step < 10) {
            result = androidx.core.graphics.ColorUtils.blendARGB(result, android.graphics.Color.BLACK, 0.1f)
            step++
        }
        return result
    }

    private fun rowBackground(): Drawable? {
        val value = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.selectableItemBackground, value, true)
        return ContextCompat.getDrawable(this, value.resourceId)
    }

    private fun selectedBackground(): Drawable =
        android.graphics.drawable.ColorDrawable(MaterialColors.compositeARGBWithAlpha(accentColor, 41))

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
        private const val STATE_SECTION = "section"
        private const val STATE_PLAYLIST = "playlist"
        private const val SECTION_FAVORITES = 3
        private const val SECTION_PLAYLISTS = 4
        private const val SECTION_LIBRARY = 0
        private const val SECTION_RECENT = 1
        private const val SECTION_MOST = 2
        private const val TAB_SONGS = 0
        private const val TAB_ALBUMS = 1
        private const val TAB_ARTISTS = 2
        // In the order of use (owner, 19.11.2026); favourites and playlists are the side menu's sections as tabs.
        private const val TAB_PLAYLISTS = 3
        private const val TAB_FAVORITES = 4
        private const val TAB_FOLDERS = 5
        private const val TYPE_HEADER = 3
        private const val TYPE_ACTIONS = 0
        private const val TYPE_ROW = 1
        private const val TYPE_ALBUM = 2
        private const val PROGRESS_MAX = 1_000
        // Once a second, like the player: a screen that redraws more often never goes idle for accessibility tools.
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        private const val MINI_PLAYER_FADE_MS = 200L
        private const val MINI_TINT_SAMPLE = 24
        private const val HERO_PARALLAX = 0.4f
        private const val HERO_FADE = 0.6f
        private const val MINI_TINT_FADE_MS = 300L
        private const val MINI_TRACK_ALPHA = 0x3d
        private const val DISABLED_ALPHA = 0.38f
        private const val TWO_PANE_MIN_WIDTH_DP = 600
    }
}
