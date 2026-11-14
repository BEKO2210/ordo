/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.apps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.CheckableFileItemBinding
import me.zhanghai.android.files.databinding.TrashFragmentBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe

class AppsFragment : Fragment() {
    private val viewModel by viewModels<AppsViewModel>()

    // The trash screen layout is a plain toolbar + refreshable list, which is all we need here.
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
        activity.setTitle(R.string.apps_title)
        binding.emptyView.setText(R.string.apps_empty)
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = adapter
        binding.swipeRefreshLayout.isRefreshing = true
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.reload() }
        viewModel.apps.observe(viewLifecycleOwner) {
            adapter.apps = it
            binding.swipeRefreshLayout.isRefreshing = false
            binding.emptyView.isVisible = it.isEmpty()
        }
    }

    override fun onResume() {
        super.onResume()

        // Picks up apps uninstalled from the system dialog.
        viewModel.reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.apps, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_show_system_apps).isChecked = viewModel.showSystemApps
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                requireActivity().finish()
                true
            }
            R.id.action_show_system_apps -> {
                viewModel.setShowSystemApps(!item.isChecked)
                requireActivity().invalidateOptionsMenu()
                true
            }
            else -> @Suppress("DEPRECATION") super.onOptionsItemSelected(item)
        }

    private fun showActions(app: AppEntry, anchor: View) {
        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        PopupMenu(context, anchor).apply {
            inflate(R.menu.apps_item)
            menu.findItem(R.id.action_open).isVisible = launchIntent != null
            menu.findItem(R.id.action_uninstall).isVisible = !app.isSystem
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_open -> startActivitySafe(launchIntent!!)
                    R.id.action_app_info -> startActivitySafe(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", app.packageName, null)
                        )
                    )
                    R.id.action_backup -> viewModel.backup(app) { file ->
                        if (file != null) {
                            showToast(getString(R.string.apps_backup_done_format, file.path))
                        } else {
                            showToast(R.string.apps_backup_failed)
                        }
                    }
                    R.id.action_uninstall -> startActivitySafe(
                        @Suppress("DEPRECATION")
                        Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", app.packageName, null))
                    )
                }
                true
            }
            show()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.ViewHolder>() {
        var apps: List<AppEntry> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                CheckableFileItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            val binding = holder.binding
            val context = binding.root.context
            binding.iconImage.apply {
                imageTintList = null
                dispose()
                load(app.info)
            }
            binding.nameText.text = app.label
            val size = app.size.asFileSize().formatHumanReadable(context)
            val versionAndSize = app.versionName?.let { "$it · $size" } ?: size
            binding.descriptionText.text = getString(
                R.string.apps_item_description_format, versionAndSize, app.packageName
            )
            binding.checkBox.isVisible = false
            binding.root.setOnClickListener { showActions(app, binding.nameText) }
        }

        inner class ViewHolder(val binding: CheckableFileItemBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
