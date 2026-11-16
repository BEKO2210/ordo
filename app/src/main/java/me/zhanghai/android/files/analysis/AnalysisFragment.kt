/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.os.Bundle
import android.os.storage.StorageManager
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import kotlinx.coroutines.launch
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.getDescriptionCompat
import me.zhanghai.android.files.compat.storageVolumesCompat
import me.zhanghai.android.files.databinding.AnalysisFragmentBinding
import me.zhanghai.android.files.databinding.AnalysisHeaderItemBinding
import me.zhanghai.android.files.databinding.AnalysisFileItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isMedia
import me.zhanghai.android.files.file.isPdf
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.trash.Trash
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.startActivitySafe

class AnalysisFragment : Fragment() {
    private sealed class Row {
        class Header(val text: String) : Row()
        class FileRow(val file: ScannedFile) : Row()
        /** [whole] is what the share bar is measured against: the page's total. */
        class FolderRow(val folder: FolderSize, val whole: Long) : Row()
    }

    private val viewModel by viewModels<AnalysisViewModel>()

    private lateinit var binding: AnalysisFragmentBinding

    private val adapter = Adapter()

    private var result: AnalysisResult? = null

    private var boundResult: AnalysisResult? = null

    /** The open page, or null for the overview. */
    private val page: AnalysisPage?
        get() = viewModel.pages.lastOrNull()

    // Back first clears a selection, then goes down one page, so the user lands where they were.
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                viewModel.selectedPaths.isNotEmpty() -> {
                    viewModel.selectedPaths.clear()
                    adapter.notifyDataSetChanged()
                    updateSelection()
                }
                page != null -> closePage()
                // On the overview a lifted slice or a marked tile comes down before the analysis is left.
                // Nothing to clear after all (the pick went with a rebind): back leaves as usual.
                else -> if (dashboard?.clearPick() != true) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private var dashboard: AnalysisDashboard? = null

    // Waits out the scan picture's shortest time, so a scan of a split second does not flash by.
    private var reveal: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened by an AI command on one cleanup list ("large", "duplicates"): that page sits on the overview, so
        // back leads to the overview. Only on first creation; afterwards the view model keeps the pages.
        val startPage = arguments?.getString(AnalysisActivity.EXTRA_PAGE)
        if (savedInstanceState == null && startPage in setOf("large", "duplicates") && viewModel.pages.isEmpty()) {
            viewModel.pages += AnalysisPage.Cleanup(startPage!!)
        }

        @Suppress("DEPRECATION")
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        AnalysisFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        activity.setTitle(R.string.analysis_title)
        // Wide screens use their width: rows side by side, so name and size are not a screen apart.
        val metrics = resources.displayMetrics
        val columns = (metrics.widthPixels / metrics.density / ROW_MIN_WIDTH_DP).toInt().coerceIn(1, 3)
        binding.recyclerView.layoutManager = GridLayoutManager(activity, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                // The heading of a group of duplicates runs across all columns.
                override fun getSpanSize(position: Int) = if (adapter.rows[position] is Row.Header) columns else 1
            }
        }
        binding.recyclerView.adapter = adapter
        binding.scanView.setKindColors(StorageInsights.KINDS.map {
            ContextCompat.getColor(activity, AnalysisDashboard.kindColor(it))
        }.toIntArray())
        dashboard = dashboard()
        activity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        binding.actionBar.applySystemWindowInsetsToPadding(bottom = true)
        binding.deleteButton.setOnClickListener { confirmDelete() }
        viewModel.state.observe(viewLifecycleOwner) { onStateChanged(it) }
    }

    private fun onStateChanged(state: AnalysisState) {
        when (state) {
            is AnalysisState.Scanning -> {
                reveal?.cancel()
                val fresh = state.progress.files == 0 && state.progress.compared < 0
                if (result != null || !binding.scanView.isVisible || fresh) {
                    // A new scan (another storage, or again) starts at the top of its overview.
                    binding.overview.overviewScroll.scrollTo(0, 0)
                    binding.scanView.reset()
                    binding.scanView.isVisible = true
                }
                result = null
                binding.scanView.update(state.progress)
                updateRows()
            }
            is AnalysisState.Done -> {
                // Straight to the result when the scan was not on screen (after a rotation) or motion is off.
                val wait = if (binding.scanView.isVisible && page == null) binding.scanView.remaining else -1
                if (wait <= 0) return showResult(state.result, fromScan = wait == 0L && Motion.any)
                reveal?.cancel()
                reveal = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(wait)
                    showResult(state.result, fromScan = true)
                }
            }
        }
    }

    private fun showResult(result: AnalysisResult, fromScan: Boolean) {
        this.result = result
        val insights = result.insights
        if (!fromScan || insights == null || insights.own <= 0) {
            binding.scanView.isVisible = false
            return updateRows()
        }
        // The scan's ring becomes the ring of "your files": it flies to where that one is and stays as it.
        val ring = binding.overview.filesChart
        updateRows(fromScan = true)
        binding.overview.root.doOnPreDraw {
            val place = android.graphics.Rect()
            ring.getDrawingRect(place)
            binding.overview.overviewScroll.offsetDescendantRectToMyCoords(ring, place)
            val (sweeps, colors) = dashboard!!.ringSlots()
            val land = {
                ring.land()
                binding.scanView.isVisible = false
            }
            // Off screen (a small window) there is nowhere to fly to: the picture just makes room.
            if (place.top < 0 || place.bottom > binding.scanView.height || !ring.isShown) land()
            else binding.scanView.finishInto(place.left + ring.ringCenterX, place.top + ring.ringCenterY,
                ring.ringRadius, ring.ringStroke, sweeps, colors, land)
        }
    }

    private fun dashboard() = AnalysisDashboard(
        binding.overview,
        viewModel.chart,
        onChartChanged = { updateSelection() },
        onOpen = ::openPage,
        onShow = { target ->
            val activity = if (target == "trash") me.zhanghai.android.files.trash.TrashActivity::class.java
            else me.zhanghai.android.files.apps.AppsActivity::class.java
            startActivity(android.content.Intent(requireContext(), activity))
        },
        onPreview = { suggestion ->
            val textRes = if (suggestion.id == "old_downloads") R.string.analysis_suggestion_old_downloads_command
            else R.string.analysis_suggestion_download_apks_command
            val plan = me.zhanghai.android.files.ai.CommandPlan(
                "delete", "${boundResult?.root ?: viewModel.root.path}/${suggestion.folder}",
                extensions = suggestion.extensions,
                olderThan = suggestion.olderThanDays
            )
            me.zhanghai.android.files.ai.CommandSheetFragment.withPlan(getString(textRes), plan)
                .show(childFragmentManager, me.zhanghai.android.files.ai.CommandSheetFragment.TAG)
        }
    )

    private fun openPage(page: AnalysisPage) {
        if (this.page != null) viewModel.scrollStates += binding.recyclerView.layoutManager!!.onSaveInstanceState()
        viewModel.pages += page
        updateRows()
        binding.recyclerView.scrollToPosition(0)
    }

    private fun closePage() {
        viewModel.pages.removeLastOrNull() ?: return
        updateRows()
        if (page != null) {
            binding.recyclerView.layoutManager!!.onRestoreInstanceState(viewModel.scrollStates.removeLastOrNull())
        }
    }

    private fun updateRows(fromScan: Boolean = false) {
        val result = result
        val page = page
        binding.overview.root.isVisible = page == null && result != null
        binding.recyclerView.isVisible = page != null
        if (page == null && result != null && result !== boundResult && result.insights != null) {
            boundResult = result
            dashboard!!.bind(result.insights, result.folders, result.root, fromScan)
        }
        adapter.rows = if (result == null || page == null) emptyList() else rows(result, page)
        updateTitle(result, page)
        binding.emptyText.isVisible = result != null && page != null && adapter.rows.isEmpty()
        updateSelection()
    }

    private fun rows(result: AnalysisResult, page: AnalysisPage): List<Row> {
        val context = requireContext()
        return when (page) {
            is AnalysisPage.Kind -> files(result, page).map { Row.FileRow(it) }
            is AnalysisPage.Folder -> {
                val (folders, files) = StorageAnalysis.children(page.path, result.files)
                val whole = folders.sumOf { it.size } + files.sumOf { it.size }
                (folders.map { Row.FolderRow(it, whole) to it.size } + files.map { Row.FileRow(it) to it.size })
                    .sortedByDescending { it.second }.map { it.first }
            }
            is AnalysisPage.Cleanup -> when (page.type) {
                "folders" -> result.folders.map { Row.FolderRow(it, result.insights?.own ?: result.folders.sumOf { f -> f.size }) }
                "duplicates" -> result.duplicates.flatMap { group ->
                    listOf<Row>(
                        Row.Header(
                            context.getQuantityString(
                                R.plurals.analysis_duplicate_header_format, group.files.size,
                                group.files.size, group.wastedSize.asFileSize().formatHumanReadable(context)
                            )
                        )
                    ) + group.files.map { Row.FileRow(it) }
                }
                else -> files(result, page).map { Row.FileRow(it) }
            }
        }
    }

    /** The files a file page lists; its title counts the same ones, so the numbers match the overview. */
    private fun files(result: AnalysisResult, page: AnalysisPage): List<ScannedFile> = when (page) {
        is AnalysisPage.Kind -> result.files.filter { StorageInsights.kindOf(it.path) == page.kind }
            .sortedByDescending { it.size }
        is AnalysisPage.Folder -> StorageAnalysis.children(page.path, result.files).second
        is AnalysisPage.Cleanup -> when (page.type) {
            "large" -> result.large
            "old" -> result.old
            "apks" -> result.apks
            "thumbnails" -> StorageAnalysis.thumbnailFiles(result.root, result.files)
            StorageInsights.EMPTY_FOLDERS -> result.emptyFolders
            "duplicates" -> StorageAnalysis.allButNewest(result.duplicates)
            else -> emptyList()
        }
    }

    private fun updateTitle(result: AnalysisResult?, page: AnalysisPage?) {
        val context = requireContext()
        val bar = (requireActivity() as AppCompatActivity).supportActionBar!!
        bar.title = when (page) {
            null -> getString(R.string.analysis_title)
            is AnalysisPage.Kind -> AnalysisDashboard.kindLabel(context, page.kind)
            // The storage itself, from the map's tile of loose files: all of it, by folder.
            is AnalysisPage.Folder -> if (page.path == result?.root) getString(R.string.analysis_treemap_title)
            else AnalysisDashboard.folderLabel(context, page.path.substringAfterLast('/'))
            is AnalysisPage.Cleanup -> getString(AnalysisDashboard.cleanupLabel(page.type))
        }
        // A type's page wears the type's icon and colour, the same as its slice and its row on the overview.
        binding.toolbar.logo = (page as? AnalysisPage.Kind)?.let { AnalysisDashboard.kindBadge(context, it.kind) }
        bar.subtitle = when {
            result == null || page == null -> null
            // A top folder: its size and what is in it ("195 MB · 1 folder"); deeper: where it is ("DCIM › Camera · 195 MB").
            page is AnalysisPage.Folder -> {
                val path = page.path.removePrefix(result.root).trim('/')
                val (folders, files) = StorageAnalysis.children(page.path, result.files)
                val size = (folders.sumOf { it.size } + files.sumOf { it.size }).asFileSize().formatHumanReadable(context)
                if ('/' in path) "${path.replace("/", " › ")} · $size"
                else listOfNotNull(
                    size,
                    folders.size.takeIf { it > 0 }?.let { context.getQuantityString(R.plurals.analysis_folder_count, it, it) },
                    files.size.takeIf { it > 0 }?.let { context.getQuantityString(R.plurals.analysis_file_count, it, it) }
                ).joinToString(" · ")
            }
            page is AnalysisPage.Cleanup && page.type == "folders" ->
                context.getQuantityString(R.plurals.analysis_folder_count, result.folders.size, result.folders.size)
            page == AnalysisPage.Cleanup(StorageInsights.EMPTY_FOLDERS) -> context.getQuantityString(
                R.plurals.analysis_folder_count, result.emptyFolders.size, result.emptyFolders.size)
            else -> files(result, page).let {
                context.getQuantityString(R.plurals.analysis_suggestion_detail, it.size, it.size,
                    it.sumOf { f -> f.size }.asFileSize().formatHumanReadable(context))
            }
        }
    }

    private fun selectedFiles(): List<ScannedFile> {
        val result = result ?: return emptyList()
        return (result.files + result.emptyFolders).filter { it.path in viewModel.selectedPaths }
    }

    private fun updateSelection() {
        val files = selectedFiles()
        back.isEnabled = page != null || files.isNotEmpty() || (result != null && viewModel.chart.isSet)
        binding.actionBar.isVisible = files.isNotEmpty()
        binding.selectionText.text = getString(
            R.string.analysis_selection_format, files.size,
            files.sumOf { it.size }.asFileSize().formatHumanReadable(requireContext())
        )
        requireActivity().invalidateOptionsMenu()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.analysis, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        val page = if (result != null) page else null
        val duplicates = page == AnalysisPage.Cleanup("duplicates")
        menu.findItem(R.id.action_select_all_but_newest).isVisible = duplicates
        menu.findItem(R.id.action_select_all).isVisible =
            page != null && !duplicates && page != AnalysisPage.Cleanup("folders")
        menu.findItem(R.id.action_open_folder).isVisible = page is AnalysisPage.Folder
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                if (page != null) closePage() else requireActivity().finish()
                true
            }
            R.id.action_open_folder -> {
                (page as? AnalysisPage.Folder)?.let { startActivity(FileListActivity.createViewIntent(Paths.get(it.path))) }
                true
            }
            R.id.action_select_all_but_newest -> {
                viewModel.selectedPaths += StorageAnalysis.allButNewest(result!!.duplicates)
                    .map { it.path }
                adapter.notifyDataSetChanged()
                updateSelection()
                true
            }
            R.id.action_select_all -> {
                viewModel.selectedPaths += adapter.rows.filterIsInstance<Row.FileRow>()
                    .map { it.file.path }
                adapter.notifyDataSetChanged()
                updateSelection()
                true
            }
            R.id.action_storage -> {
                chooseStorage()
                true
            }
            R.id.action_rescan -> {
                viewModel.scan(viewModel.root)
                true
            }
            else -> @Suppress("DEPRECATION") super.onOptionsItemSelected(item)
        }

    private fun chooseStorage() {
        val context = requireContext()
        val volumes = context.getSystemService<StorageManager>()!!.storageVolumesCompat
            .mapNotNull { volume -> volume.directoryCompat?.let { volume.getDescriptionCompat(context) to it } }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.analysis_choose_storage)
            .setItems(volumes.map { it.first }.toTypedArray()) { _, which ->
                viewModel.scan(volumes[which].second)
            }
            .show()
    }

    private fun confirmDelete() {
        val emptyFolders = result?.emptyFolders.orEmpty()
        // A folder found empty at scan time may have filled since; never delete what is now inside.
        val (files, filled) = selectedFiles()
            .partition { it !in emptyFolders || StorageAnalysis.isStillEmpty(it.path) }
        if (filled.isNotEmpty()) {
            viewModel.removeDeleted(filled.map { it.path }.toSet())
        }
        if (files.isEmpty()) {
            return
        }
        val context = requireContext()
        val size = files.sumOf { it.size }.asFileSize().formatHumanReadable(context)
        val paths = files.map { Paths.get(it.path) }
        val toTrash = Trash.isEnabled && paths.all { Trash.canTrash(it) }
        // Empty folders have no size to tell; the message names them as folders.
        val folders = files.all { it in emptyFolders }
        MaterialAlertDialogBuilder(context)
            .setMessage(
                context.getQuantityString(
                    when {
                        folders && toTrash -> R.plurals.analysis_trash_folders_message_format
                        folders -> R.plurals.analysis_delete_folders_message_format
                        toTrash -> R.plurals.analysis_trash_message_format
                        else -> R.plurals.analysis_delete_message_format
                    }, files.size, files.size, size
                )
            )
            .setPositiveButton(
                if (toTrash) R.string.trash_move_action else R.string.trash_delete_permanently_action
            ) { _, _ ->
                FileJobService.delete(paths, context)
                viewModel.removeDeleted(files.map { it.path }.toSet())
                // The lists follow once the view model has updated them; the selection bar goes at once.
                updateSelection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(AnalysisHeaderItemBinding.inflate(inflater, parent, false))
            } else {
                FileHolder(AnalysisFileItemBinding.inflate(inflater, parent, false).apply {
                    // The rounded tile clips the thumbnail.
                    iconImage.clipToOutline = true
                })
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).binding.headerText.text = row.text
                is Row.FileRow -> bindFile(holder as FileHolder, row.file)
                is Row.FolderRow -> bindFolder(holder as FileHolder, row.folder, row.whole)
            }
        }

        private fun bindFile(holder: FileHolder, file: ScannedFile) {
            val binding = holder.binding
            val context = binding.root.context
            val mimeType = MimeType.guessFromPath(file.path)
            // The empty folders page lists folders: a folder icon, and a tap opens it in the file list.
            val isFolder = page == AnalysisPage.Cleanup(StorageInsights.EMPTY_FOLDERS)
            if (isFolder) binding.iconImage.showFolderIcon() else binding.iconImage.showThumbnail(file, mimeType)
            binding.nameText.text = file.name
            binding.sizeText.text = file.size.asFileSize().formatHumanReadable(context)
            val modified = DateUtils.formatDateTime(context, file.lastModified, DateUtils.FORMAT_SHOW_DATE)
            // On a folder page every file is in that folder; elsewhere it says where, short: "DCIM › Camera".
            val folder = result?.root?.let { file.path.substringBeforeLast('/').removePrefix(it).trim('/') }
            binding.descriptionText.text = if (page is AnalysisPage.Folder || folder.isNullOrEmpty()) modified
            else "$modified · ${folder.replace("/", " › ")}"
            binding.shareBar.isVisible = false
            binding.chevronImage.isVisible = false
            val selecting = viewModel.selectedPaths.isNotEmpty()
            binding.checkBox.isVisible = selecting
            binding.checkBox.isChecked = file.path in viewModel.selectedPaths
            // Like the file list: a tap opens the file, a long press starts choosing files to delete.
            binding.root.setOnClickListener {
                if (viewModel.selectedPaths.isNotEmpty()) {
                    toggle(file, holder)
                } else {
                    startActivitySafe(if (isFolder) FileListActivity.createViewIntent(Paths.get(file.path))
                    else OpenFileActivity.createIntent(Paths.get(file.path), mimeType))
                }
            }
            binding.root.setOnLongClickListener {
                toggle(file, holder)
                true
            }
        }

        private fun toggle(file: ScannedFile, holder: FileHolder) {
            val wasSelecting = viewModel.selectedPaths.isNotEmpty()
            if (!viewModel.selectedPaths.remove(file.path)) {
                viewModel.selectedPaths += file.path
            }
            // Entering or leaving selection shows or hides every checkbox.
            if (wasSelecting != viewModel.selectedPaths.isNotEmpty()) notifyDataSetChanged()
            else notifyItemChanged(holder.bindingAdapterPosition)
            updateSelection()
        }

        private fun bindFolder(holder: FileHolder, folder: FolderSize, whole: Long) {
            val binding = holder.binding
            val context = binding.root.context
            binding.iconImage.showFolderIcon()
            val name = folder.path.substringAfterLast('/')
            binding.nameText.text = AnalysisDashboard.folderLabel(context, name)
            binding.sizeText.text = folder.size.asFileSize().formatHumanReadable(context)
            // Its share of what the page shows: all your files, or the folder it is in.
            val share = StorageInsights.share(folder.size, whole)
            val parent = (page as? AnalysisPage.Folder)?.path?.substringAfterLast('/')
            binding.descriptionText.text = if (parent != null) getString(R.string.analysis_share_of_folder, share, parent)
            else getString(R.string.analysis_share_of_files, share)
            binding.checkBox.isVisible = false
            binding.chevronImage.isVisible = true
            binding.shareBar.isVisible = true
            binding.shareBar.setParts(listOf(ContextCompat.getColor(context, R.color.ai_accent) to folder.size), whole)
            // Into the folder, inside the analysis; the toolbar opens it in the file list.
            binding.root.setOnClickListener { openPage(AnalysisPage.Folder(folder.path)) }
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false
        }
    }

    private fun ImageView.showFolderIcon() {
        dispose()
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(R.drawable.directory_icon_white_24dp)
        imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ai_accent))
    }

    // The real picture for photos, videos, music covers, APKs and PDFs, as in the file list; the type icon otherwise.
    private fun ImageView.showThumbnail(file: ScannedFile, mimeType: MimeType) {
        dispose()
        scaleType = ImageView.ScaleType.CENTER
        imageTintList = null
        setImageResource(mimeType.iconRes)
        if (mimeType.isImage || mimeType.isMedia || mimeType.isApk || mimeType.isPdf) {
            load(Paths.get(file.path) to ScannedAttributes(file)) {
                placeholder(mimeType.iconRes)
                error(mimeType.iconRes)
                listener(onSuccess = { _, _ -> scaleType = ImageView.ScaleType.CENTER_CROP })
            }
        }
    }

    /** What the thumbnail loader needs about a file, from the scan instead of a second read per row. */
    private class ScannedAttributes(private val file: ScannedFile) : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(file.lastModified)
        override fun lastAccessTime(): FileTime = lastModifiedTime()
        override fun creationTime(): FileTime = lastModifiedTime()
        override fun isRegularFile() = true
        override fun isDirectory() = false
        override fun isSymbolicLink() = false
        override fun isOther() = false
        override fun size() = file.size
        override fun fileKey(): Any? = null
    }

    private companion object {
        /** A row needs about this much to show name, date, folder and size without a gap in the middle. */
        const val ROW_MIN_WIDTH_DP = 440
    }

    private class HeaderHolder(val binding: AnalysisHeaderItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class FileHolder(val binding: AnalysisFileItemBinding) :
        RecyclerView.ViewHolder(binding.root)
}
