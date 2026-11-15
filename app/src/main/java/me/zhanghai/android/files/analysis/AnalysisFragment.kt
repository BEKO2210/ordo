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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
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
            if (viewModel.selectedPaths.isNotEmpty()) {
                viewModel.selectedPaths.clear()
                adapter.notifyDataSetChanged()
                updateSelection()
            } else {
                closePage()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = adapter
        // Tablets: the lists keep the overview's comfortable column instead of rows stretched edge to edge.
        val metrics = resources.displayMetrics
        val side = ((metrics.widthPixels - 720 * metrics.density) / 2).coerceAtLeast(0f).toInt()
        binding.recyclerView.setPaddingRelative(side, binding.recyclerView.paddingTop, side,
            binding.recyclerView.paddingBottom)
        activity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        binding.actionBar.applySystemWindowInsetsToPadding(bottom = true)
        binding.deleteButton.setOnClickListener { confirmDelete() }
        viewModel.state.observe(viewLifecycleOwner) { onStateChanged(it) }
    }

    private fun onStateChanged(state: AnalysisState) {
        when (state) {
            is AnalysisState.Scanning -> {
                result = null
                // A new scan (another storage, or again) starts at the top of its overview.
                binding.overview.overviewScroll.scrollTo(0, 0)
                binding.progressLayout.isVisible = true
                binding.progressText.text = if (state.isHashing) {
                    getString(R.string.analysis_progress_duplicates)
                } else {
                    requireContext().getQuantityString(
                        R.plurals.analysis_progress_scanning, state.fileCount, state.fileCount
                    )
                }
            }
            is AnalysisState.Done -> {
                result = state.result
                binding.progressLayout.isVisible = false
            }
        }
        updateRows()
    }

    private fun dashboard() = AnalysisDashboard(
        binding.overview,
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

    private fun updateRows() {
        val result = result
        val page = page
        binding.overview.root.isVisible = page == null && result != null
        binding.recyclerView.isVisible = page != null
        if (page == null && result != null && result !== boundResult && result.insights != null) {
            boundResult = result
            dashboard().bind(result.insights, result.folders, result.root)
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
            is AnalysisPage.Folder -> AnalysisDashboard.folderLabel(context, page.path.substringAfterLast('/'))
            is AnalysisPage.Cleanup -> getString(AnalysisDashboard.cleanupLabel(page.type))
        }
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
            else -> files(result, page).let {
                context.getQuantityString(R.plurals.analysis_suggestion_detail, it.size, it.size,
                    it.sumOf { f -> f.size }.asFileSize().formatHumanReadable(context))
            }
        }
    }

    private fun selectedFiles(): List<ScannedFile> {
        val result = result ?: return emptyList()
        return result.files.filter { it.path in viewModel.selectedPaths }
    }

    private fun updateSelection() {
        val files = selectedFiles()
        back.isEnabled = page != null || files.isNotEmpty()
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
        val files = selectedFiles()
        if (files.isEmpty()) {
            return
        }
        val context = requireContext()
        val size = files.sumOf { it.size }.asFileSize().formatHumanReadable(context)
        val paths = files.map { Paths.get(it.path) }
        val toTrash = Trash.isEnabled && paths.all { Trash.canTrash(it) }
        MaterialAlertDialogBuilder(context)
            .setMessage(
                context.getQuantityString(
                    if (toTrash) {
                        R.plurals.analysis_trash_message_format
                    } else {
                        R.plurals.analysis_delete_message_format
                    }, files.size, files.size, size
                )
            )
            .setPositiveButton(
                if (toTrash) R.string.trash_move_action else R.string.trash_delete_permanently_action
            ) { _, _ ->
                FileJobService.delete(paths, context)
                viewModel.removeDeleted(files.map { it.path }.toSet())
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
            binding.iconImage.showThumbnail(file, mimeType)
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
                    startActivitySafe(OpenFileActivity.createIntent(Paths.get(file.path), mimeType))
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
            binding.iconImage.apply {
                dispose()
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.directory_icon_white_24dp)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.ai_accent))
            }
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

    private class HeaderHolder(val binding: AnalysisHeaderItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class FileHolder(val binding: AnalysisFileItemBinding) :
        RecyclerView.ViewHolder(binding.root)
}
