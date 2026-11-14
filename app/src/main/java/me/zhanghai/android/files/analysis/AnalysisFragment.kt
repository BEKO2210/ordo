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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.getDescriptionCompat
import me.zhanghai.android.files.compat.storageVolumesCompat
import me.zhanghai.android.files.databinding.AnalysisFragmentBinding
import me.zhanghai.android.files.databinding.AnalysisHeaderItemBinding
import me.zhanghai.android.files.databinding.CheckableFileItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.trash.Trash
import me.zhanghai.android.files.util.getQuantityString

class AnalysisFragment : Fragment() {
    private enum class Tab(val titleRes: Int) {
        OVERVIEW(R.string.analysis_tab_overview),
        LARGE(R.string.analysis_tab_large),
        DUPLICATES(R.string.analysis_tab_duplicates),
        OLD(R.string.analysis_tab_old),
        APKS(R.string.analysis_tab_apks),
        FOLDERS(R.string.analysis_tab_folders)
    }

    private sealed class Row {
        class Header(val text: String) : Row()
        class FileRow(val file: ScannedFile) : Row()
        class FolderRow(val folder: FolderSize) : Row()
    }

    private val viewModel by viewModels<AnalysisViewModel>()

    private lateinit var binding: AnalysisFragmentBinding

    private val adapter = Adapter()

    private var result: AnalysisResult? = null

    private var tab = Tab.OVERVIEW
    private var boundResult: AnalysisResult? = null

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
        for (tab in Tab.entries) {
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(tab.titleRes))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selected: TabLayout.Tab) {
                tab = Tab.entries[selected.position]
                updateRows()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.actionBar.applySystemWindowInsetsToPadding(bottom = true)
        binding.deleteButton.setOnClickListener { confirmDelete() }
        viewModel.state.observe(viewLifecycleOwner) { onStateChanged(it) }
    }

    private fun onStateChanged(state: AnalysisState) {
        when (state) {
            is AnalysisState.Scanning -> {
                result = null
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
                updateTabTitles(state.result)
            }
        }
        updateRows()
    }

    private fun dashboard() = AnalysisDashboard(
        binding.overview,
        onViewCleanup = { type ->
            when (type) {
                "trash" -> startActivity(android.content.Intent(requireContext(), me.zhanghai.android.files.trash.TrashActivity::class.java))
                else -> binding.tabLayout.getTabAt(
                    Tab.entries.indexOf(
                        when (type) {
                            "duplicates" -> Tab.DUPLICATES
                            "large" -> Tab.LARGE
                            "old" -> Tab.OLD
                            else -> Tab.APKS
                        }
                    )
                )!!.select()
            }
        },
        onOpenFolder = { path -> startActivity(FileListActivity.createViewIntent(java8.nio.file.Paths.get(path))) },
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

    private fun updateTabTitles(result: AnalysisResult) {
        val counts = listOf(
            -1, result.large.size, result.duplicates.size, result.old.size, result.apks.size,
            result.folders.size
        )
        for ((index, tab) in Tab.entries.withIndex()) {
            binding.tabLayout.getTabAt(index)!!.text = if (counts[index] < 0) {
                getString(tab.titleRes)
            } else {
                getString(R.string.analysis_tab_title_format, getString(tab.titleRes), counts[index])
            }
        }
    }

    private fun updateRows() {
        val result = result
        val context = requireContext()
        val overview = tab == Tab.OVERVIEW
        binding.overview.root.isVisible = overview && result != null
        binding.recyclerView.isVisible = !overview
        if (overview && result != null && result !== boundResult && result.insights != null) {
            boundResult = result
            dashboard().bind(result.insights, result.folders, result.root)
        }
        adapter.rows = when {
            result == null || overview -> emptyList()
            tab == Tab.LARGE -> result.large.map { Row.FileRow(it) }
            tab == Tab.OLD -> result.old.map { Row.FileRow(it) }
            tab == Tab.APKS -> result.apks.map { Row.FileRow(it) }
            tab == Tab.FOLDERS -> result.folders.map { Row.FolderRow(it) }
            else -> result.duplicates.flatMap { group ->
                listOf<Row>(
                    Row.Header(
                        context.getQuantityString(
                            R.plurals.analysis_duplicate_header_format, group.files.size,
                            group.files.size, group.wastedSize.asFileSize().formatHumanReadable(context)
                        )
                    )
                ) + group.files.map { Row.FileRow(it) }
            }
        }
        binding.emptyText.isVisible = result != null && !overview && adapter.rows.isEmpty()
        updateSelection()
    }

    private fun selectedFiles(): List<ScannedFile> {
        val result = result ?: return emptyList()
        return (result.large + result.old + result.apks + result.duplicates.flatMap { it.files })
            .filter { it.path in viewModel.selectedPaths }.distinctBy { it.path }
    }

    private fun updateSelection() {
        val files = selectedFiles()
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
        val isDone = result != null
        menu.findItem(R.id.action_select_all_but_newest).isVisible =
            isDone && tab == Tab.DUPLICATES
        menu.findItem(R.id.action_select_all).isVisible =
            isDone && tab != Tab.DUPLICATES && tab != Tab.FOLDERS && tab != Tab.OVERVIEW
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                requireActivity().finish()
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
                FileHolder(CheckableFileItemBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).binding.headerText.text = row.text
                is Row.FileRow -> bindFile(holder as FileHolder, row.file)
                is Row.FolderRow -> bindFolder(holder as FileHolder, row.folder)
            }
        }

        private fun bindFile(holder: FileHolder, file: ScannedFile) {
            val binding = holder.binding
            val context = binding.root.context
            binding.iconImage.setImageResource(MimeType.guessFromPath(file.path).iconRes)
            binding.iconImage.imageTintList = null
            binding.nameText.text = file.name
            val modified = DateUtils.formatDateTime(context, file.lastModified, DateUtils.FORMAT_SHOW_DATE)
            binding.descriptionText.text = getString(
                R.string.analysis_file_description_format,
                file.size.asFileSize().formatHumanReadable(context), modified,
                file.path.substringBeforeLast('/')
            )
            binding.checkBox.isVisible = true
            binding.checkBox.isChecked = file.path in viewModel.selectedPaths
            binding.root.setOnClickListener {
                if (!viewModel.selectedPaths.remove(file.path)) {
                    viewModel.selectedPaths += file.path
                }
                notifyItemChanged(holder.bindingAdapterPosition)
                updateSelection()
            }
        }

        private fun bindFolder(holder: FileHolder, folder: FolderSize) {
            val binding = holder.binding
            val context = binding.root.context
            binding.iconImage.setImageResource(MimeType.DIRECTORY.iconRes)
            binding.iconImage.imageTintList = null
            binding.nameText.text = folder.path.substringAfterLast('/')
            binding.descriptionText.text = folder.size.asFileSize().formatHumanReadable(context)
            binding.checkBox.isVisible = false
            binding.root.setOnClickListener {
                startActivity(FileListActivity.createViewIntent(Paths.get(folder.path)))
            }
        }
    }

    private class HeaderHolder(val binding: AnalysisHeaderItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class FileHolder(val binding: CheckableFileItemBinding) :
        RecyclerView.ViewHolder(binding.root)
}
