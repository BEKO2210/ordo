/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.trash

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TrashFragmentBinding
import me.zhanghai.android.files.databinding.CheckableFileItemBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.util.getQuantityString

class TrashFragment : Fragment() {
    private val viewModel by viewModels<TrashViewModel>()

    private lateinit var binding: TrashFragmentBinding

    private val adapter = Adapter()

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
        TrashFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = adapter
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.reload() }
        viewModel.isLoading.observe(viewLifecycleOwner) {
            binding.swipeRefreshLayout.isRefreshing = it
        }
        viewModel.items.observe(viewLifecycleOwner) {
            adapter.items = it
            binding.emptyView.isVisible = it.isEmpty()
            updateSelection()
        }
    }

    override fun onResume() {
        super.onResume()

        viewModel.reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.trash, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        val hasSelection = viewModel.selectedIds.isNotEmpty()
        menu.findItem(R.id.action_restore).isVisible = hasSelection
        menu.findItem(R.id.action_delete).isVisible = hasSelection
        val hasItems = adapter.items.isNotEmpty()
        menu.findItem(R.id.action_select_all).isVisible = hasItems
        menu.findItem(R.id.action_empty).isVisible = hasItems
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                requireActivity().finish()
                true
            }
            R.id.action_restore -> {
                FileJobService.restoreFromTrash(selectedEntries(), requireContext())
                clearSelectionAndReloadLater()
                true
            }
            R.id.action_delete -> {
                confirmDelete(selectedEntries(), R.plurals.trash_delete_message_format)
                true
            }
            R.id.action_select_all -> {
                viewModel.selectedIds += adapter.items.map { it.entry.id }
                adapter.notifyDataSetChanged()
                updateSelection()
                true
            }
            R.id.action_empty -> {
                confirmDelete(adapter.items.map { it.entry }, R.plurals.trash_empty_message_format)
                true
            }
            else -> @Suppress("DEPRECATION") super.onOptionsItemSelected(item)
        }

    private fun selectedEntries(): List<TrashEntry> =
        adapter.items.map { it.entry }.filter { it.id in viewModel.selectedIds }

    private fun confirmDelete(entries: List<TrashEntry>, messageRes: Int) {
        if (entries.isEmpty()) {
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(requireContext().getQuantityString(messageRes, entries.size, entries.size))
            .setPositiveButton(R.string.trash_delete_permanently_action) { _, _ ->
                val paths = entries.flatMap {
                    listOf(Paths.get(it.file.path), Paths.get(it.infoFile.path))
                }
                FileJobService.delete(paths, requireContext(), true)
                clearSelectionAndReloadLater()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // shortcut: jobs run in FileJobService without a callback, so we reload after a short delay
    // and again on resume; upgrade to job completion events if large trashes feel stale.
    private fun clearSelectionAndReloadLater() {
        viewModel.selectedIds.clear()
        updateSelection()
        binding.recyclerView.postDelayed({ if (isAdded) viewModel.reload() }, 800)
    }

    private fun updateSelection() {
        val count = viewModel.selectedIds.size
        requireActivity().title = if (count > 0) {
            getString(R.string.trash_selected_format, count)
        } else {
            getString(R.string.trash_title)
        }
        requireActivity().invalidateOptionsMenu()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.ViewHolder>() {
        var items: List<TrashItem> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(CheckableFileItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.binding.root.context
            val entry = item.entry
            holder.binding.iconImage.setImageResource(
                if (item.isDirectory) {
                    R.drawable.directory_icon_white_24dp
                } else {
                    R.drawable.file_icon_white_24dp
                }
            )
            holder.binding.nameText.text = entry.name
            val deletedAt = DateUtils.formatDateTime(
                context, entry.deletedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
            )
            holder.binding.descriptionText.text = getString(
                R.string.trash_item_description_format, deletedAt,
                item.size.asFileSize().formatHumanReadable(context),
                entry.originalPath.substringBeforeLast('/')
            )
            holder.binding.checkBox.isChecked = entry.id in viewModel.selectedIds
            holder.binding.root.setOnClickListener {
                if (!viewModel.selectedIds.remove(entry.id)) {
                    viewModel.selectedIds += entry.id
                }
                notifyItemChanged(holder.bindingAdapterPosition)
                updateSelection()
            }
        }

        inner class ViewHolder(val binding: CheckableFileItemBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
