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

/**
 * What the "Ordo Video" icon opens: "Continue watching" from the player's saved places, then every video on the
 * device (MediaStore, newest first), filterable by folder. A tap plays the video with its folder as the queue.
 */
class VideoLauncherActivity : AppActivity(), AppAccentActivity {
    override val appAccent: SettingLiveData<String>
        get() = Settings.VIDEO_ACCENT

    private lateinit var binding: VideoLauncherActivityBinding
    private var library: VideoLibrary? = null
    private var continueItems = emptyList<ContinueItem>()
    private var folder: String? = null
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
        binding.accessButton.setOnClickListener { requestAccess() }
        // Ordo Video's own settings: its colour ("Same as Ordo" or one of Ordo's accents).
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId != R.id.actionSettings) return@setOnMenuItemClickListener false
            startActivitySafe(
                me.zhanghai.android.files.settings.SettingsActivity.createIntent(
                    me.zhanghai.android.files.settings.SettingsActivity.SECTION_VIDEO
                )
            )
            true
        }
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
            val (loaded, resume) = withContext(Dispatchers.IO) {
                val loaded =
                    VideoLibrary(queryVideos(context), showHiddenFiles, Collator.getInstance(), sort, hiddenFolders)
                loaded to loaded.continueWatching(VideoResumeStore(context).readAll())
            }
            library = loaded
            continueItems = resume
            // The view (grid or list) may have changed on the settings page.
            if (listMode != (Settings.VIDEO_VIEW.valueCompat == "list")) updateWidths()
            render()
        }
    }

    private fun render() {
        val library = library
        val folders = library?.folders.orEmpty()
        if (folder != null && folders.none { it.path == folder }) folder = null
        renderFolders(folders)
        val rows = ArrayList<Row>()
        if (library != null) {
            val shownContinue = continueItems.filter { folder == null || it.video.folder == folder }
                .takeIf { Settings.VIDEO_SHOW_CONTINUE.valueCompat }.orEmpty()
            if (shownContinue.isNotEmpty()) {
                rows += Row.Section(getString(R.string.video_library_continue))
                rows += Row.Continue(shownContinue)
            }
            val videos = folders.find { it.path == folder }?.videos ?: library.videos
            if (videos.isNotEmpty()) {
                rows += Row.Section(
                    folders.find { it.path == folder }?.name ?: getString(R.string.video_library_all_videos)
                )
                videos.mapTo(rows) { Row.Video(it) }
            }
        }
        adapter.submit(rows)
        val hasAccess = library != null || hasStorageAccess()
        binding.emptyText.setText(
            if (hasAccess) R.string.video_library_empty else R.string.video_library_access_message
        )
        binding.accessButton.isVisible = !hasAccess
        binding.emptyGroup.isVisible = rows.isEmpty() && (library != null || !hasAccess)
    }

    /** "All" and one chip per folder (newest video first); hidden with fewer than two folders. */
    private fun renderFolders(folders: List<VideoFolder>) {
        val group = binding.folders
        binding.folderScroller.isVisible = folders.size > 1
        val wanted = listOf<String?>(null) + folders.map { it.path }
        if (group.childCount != wanted.size || wanted.indices.any { group.getChildAt(it).tag != wanted[it] }) {
            group.removeAllViews()
            for (path in wanted) {
                group.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                    id = View.generateViewId()
                    tag = path
                    text = folders.find { it.path == path }?.name ?: getString(R.string.video_library_all)
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

    private fun play(queue: List<VideoItem>, video: VideoItem, resume: Boolean) {
        val index = queue.indexOf(video).coerceAtLeast(0)
        val intent = Intent(this, VideoPlayerActivity::class.java)
        VideoPlayerActivity.putExtras(intent, queue.map { Paths.get(it.path) }, index, resume)
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
        val info: TextView
    )

    private fun cardViews(view: View, row: Boolean): CardViews =
        if (row) {
            VideoLibraryRowBinding.bind(view).let {
                CardViews(it.root, it.thumbnail, it.duration, it.progress, it.title, it.info)
            }
        } else {
            VideoLibraryItemBinding.bind(view).let {
                CardViews(it.root, it.thumbnail, it.duration, it.progress, it.title, it.info)
            }
        }

    private fun bind(item: CardViews, video: VideoItem, positionMs: Long?) {
        val title = displayTitle(video)
        item.title.text = title
        val duration = video.durationMs.takeIf { it > 0 }?.let(::formatTime)
        item.duration.text = duration
        item.duration.isVisible = duration != null
        val left = positionMs?.let { getString(R.string.video_library_left_format, formatTime(video.durationMs - it)) }
        item.info.text = left ?: if (folder == null) {
            video.folder.substringAfterLast('/')
        } else {
            DateUtils.formatDateTime(this, video.dateAddedMs, DateUtils.FORMAT_SHOW_DATE)
        }
        item.progress.isVisible = positionMs != null && video.durationMs > 0
        if (positionMs != null && video.durationMs > 0) {
            item.progress.progress = (positionMs * 1000 / video.durationMs).toInt()
        }
        item.root.contentDescription =
            listOfNotNull(title, video.title.takeIf { it != title }, duration, item.info.text).joinToString(", ")
        item.root.setOnClickListener {
            val library = library ?: return@setOnClickListener
            play(library.queueOf(video), video, resume = positionMs != null)
        }
        // A long press shows "Video info" (TalkBack names the action).
        item.root.setOnLongClickListener {
            lifecycleScope.launch {
                // The same tracks the player shows (Media3's extractors), the file's own size, folder and date.
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
            true
        }
        ViewCompat.replaceAccessibilityAction(
            item.root, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
                .ACTION_LONG_CLICK, getString(R.string.video_info_title), null
        )
        loadThumbnail(item.thumbnail, video)
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
        class Continue(val items: List<ContinueItem>) : Row()
        class Video(val video: VideoItem) : Row()
    }

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
            is Row.Continue -> TYPE_CONTINUE
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
                is Row.Continue -> {
                    val list = holder.itemView as RecyclerView
                    (list.layoutParams as ViewGroup.MarginLayoutParams).apply {
                        marginStart = -side
                        marginEnd = -side
                    }
                    list.setPaddingRelative(side, 0, side, 0)
                    list.adapter = ContinueAdapter(row.items)
                }
                is Row.Video -> bind(cardViews(holder.itemView, holder.itemViewType == TYPE_ROW), row.video, null)
            }
        }
    }

    private inner class ContinueAdapter(private val items: List<ContinueItem>) :
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
            val entry = items[position]
            bind(cardViews(holder.itemView, row = false), entry.video, entry.positionMs)
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
        private const val TYPE_SECTION = 0
        private const val TYPE_CONTINUE = 1
        private const val TYPE_VIDEO = 2
        private const val TYPE_ROW = 3
        private const val CELL_DP = 180
        private const val THUMBNAIL_WIDTH = 512
    }
}
