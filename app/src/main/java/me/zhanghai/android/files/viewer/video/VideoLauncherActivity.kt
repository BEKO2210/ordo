/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

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
class VideoLauncherActivity : AppActivity() {
    private lateinit var binding: VideoLauncherActivityBinding
    private var library: VideoLibrary? = null
    private var continueItems = emptyList<ContinueItem>()
    private var folder: String? = null
    private val adapter = Adapter()
    private val timeBuilder = StringBuilder()
    private val timeFormatter = Formatter(timeBuilder)
    private var columns = 2
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
        val recycler = binding.recyclerView
        recycler.adapter = adapter
        recycler.layoutManager = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.getItemViewType(position) == TYPE_VIDEO) 1 else spanCount
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
        lifecycleScope.launch {
            val (loaded, resume) = withContext(Dispatchers.IO) {
                val loaded = VideoLibrary(queryVideos(context), showHiddenFiles, Collator.getInstance())
                loaded to loaded.continueWatching(VideoResumeStore(context).readAll())
            }
            library = loaded
            continueItems = resume
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
        binding.emptyText.setText(if (hasAccess) R.string.video_library_empty else R.string.video_library_access_message)
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
        columns = (content / (CELL_DP * density)).toInt().coerceAtLeast(2)
        val recycler = binding.recyclerView
        recycler.setPaddingRelative(side, recycler.paddingTop, side, recycler.paddingBottom)
        (recycler.layoutManager as GridLayoutManager).spanCount = columns
        binding.folderScroller.setPaddingRelative(side, 0, side, 0)
        recycler.invalidateItemDecorations()
    }

    private fun play(queue: List<VideoItem>, video: VideoItem, resume: Boolean) {
        val index = queue.indexOf(video).coerceAtLeast(0)
        val intent = Intent(this, VideoPlayerActivity::class.java)
        VideoPlayerActivity.putExtras(intent, queue.map { Paths.get(it.path) }, index, resume)
        startActivitySafe(intent)
    }

    private fun formatTime(milliseconds: Long): String =
        Util.getStringForTime(timeBuilder, timeFormatter, milliseconds.coerceAtLeast(0L))

    private fun bind(item: VideoLibraryItemBinding, video: VideoItem, positionMs: Long?) {
        item.title.text = video.title
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
        item.root.contentDescription = listOfNotNull(video.title, duration, item.info.text).joinToString(", ")
        item.root.setOnClickListener {
            val library = library ?: return@setOnClickListener
            play(library.queueOf(video), video, resume = positionMs != null)
        }
        loadThumbnail(item, video)
    }

    private fun loadThumbnail(item: VideoLibraryItemBinding, video: VideoItem) {
        val view = item.thumbnail
        (view.getTag(R.id.thumbnail) as? Job)?.cancel()
        val key = video.contentUri ?: video.path
        val cached = thumbnails.get(key)
        view.setImageBitmap(cached)
        if (cached != null) return
        val context = applicationContext
        val (width, height) = THUMBNAIL_WIDTH to THUMBNAIL_WIDTH * 9 / 16
        view.setTag(R.id.thumbnail, lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadVideoThumbnail(context, video, width, height) } ?: return@launch
            thumbnails.put(key, bitmap)
            view.setImageBitmap(bitmap)
        })
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
            is Row.Video -> TYPE_VIDEO
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
                is Row.Video -> bind(VideoLibraryItemBinding.bind(holder.itemView), row.video, null)
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
            bind(VideoLibraryItemBinding.bind(holder.itemView), entry.video, entry.positionMs)
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
        private const val CELL_DP = 180
        private const val THUMBNAIL_WIDTH = 512
    }
}
