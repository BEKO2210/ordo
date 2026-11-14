/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import me.zhanghai.android.files.analysis.AnalysisActivity
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.apps.AppsActivity
import me.zhanghai.android.files.trash.TrashActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.HomeFragmentBinding
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.filelist.ShowRequestAllFilesAccessRationaleDialogFragment
import me.zhanghai.android.files.filelist.ShowRequestStoragePermissionRationaleDialogFragment
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.viewModels

class HomeFragment : Fragment(), ShowRequestAllFilesAccessRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionRationaleDialogFragment.Listener {
    private val viewModel by viewModels { { HomeViewModel(requireActivity().application) } }
    private var _binding: HomeFragmentBinding? = null
    private val binding get() = _binding!!

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStorageAccessResult() }
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onStorageAccessResult() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = HomeFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val recentAdapter = RecentFileAdapter { file ->
            startActivitySafe(
                OpenFileActivity.createIntent(
                    file.path, file.mimeType?.asMimeTypeOrNull() ?: MimeType.ANY
                )
            )
        }
        binding.recentList.apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = recentAdapter
            isNestedScrollingEnabled = false
        }
        val tileAdapter = TileAdapter()
        binding.categoryList.apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = tileAdapter
            isNestedScrollingEnabled = false
        }
        val storageAdapter = StorageAdapter { startActivity(FileListActivity.createViewIntent(it.path)) }
        binding.storageList.apply { layoutManager = LinearLayoutManager(context); adapter = storageAdapter; isNestedScrollingEnabled = false }
        val quickAccessAdapter = QuickAccessAdapter { startActivity(FileListActivity.createViewIntent(it.path)) }
        binding.quickAccessList.apply { layoutManager = LinearLayoutManager(context); adapter = quickAccessAdapter; isNestedScrollingEnabled = false }
        binding.analyzeButton.setOnClickListener {
            startActivity(Intent(requireContext(), AnalysisActivity::class.java))
        }
        binding.toolbar.setTitle(R.string.app_name)
        binding.toolbar.inflateMenu(R.menu.home)
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_browse -> startActivity(Intent(requireContext(), FileListActivity::class.java))
                R.id.action_settings -> startActivity(SettingsActivity::class.createIntent())
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.permissionButton.setOnClickListener { requestStorageAccess() }
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.reload() }
        viewModel.state.observe(viewLifecycleOwner) { homeState ->
            recentAdapter.items = homeState.recentFiles
            tileAdapter.items = createTiles(homeState)
            storageAdapter.items = homeState.storages
            quickAccessAdapter.items = homeState.quickAccess
            binding.recentEmpty.isVisible = !homeState.isLoading && homeState.recentFiles.isEmpty()
            binding.swipeRefreshLayout.isRefreshing = homeState.isLoading
        }
    }

    private fun createTiles(state: HomeState): List<HomeTile> {
        val context = requireContext()
        val categoryTiles = state.categories.map { summary ->
            HomeTile(
                summary.category.iconRes, summary.category.titleRes, summary.category.colorRes,
                context.getQuantityString(
                    R.plurals.home_category_summary, summary.count.toInt(), summary.count,
                    summary.size.asFileSize().formatHumanReadable(context)
                )
            ) { startActivity(FileListActivity.createCategoryIntent(summary.category)) }
        }
        return categoryTiles + listOf(
            HomeTile(
                R.drawable.apps_icon_white_24dp, R.string.navigation_apps, R.color.home_apps,
                state.appCount?.let {
                    context.getQuantityString(R.plurals.home_apps_summary, it, it)
                }
            ) { startActivity(Intent(context, AppsActivity::class.java)) },
            HomeTile(
                R.drawable.delete_icon_white_24dp, R.string.navigation_trash, R.color.home_trash,
                state.trashCount?.let {
                    context.getQuantityString(
                        R.plurals.home_category_summary, it, it,
                        state.trashSize.asFileSize().formatHumanReadable(context)
                    )
                }
            ) { startActivity(Intent(context, TrashActivity::class.java)) }
        )
    }

    override fun onResume() {
        super.onResume()
        binding.permissionCard.isVisible = !hasStorageAccess()
        if (hasStorageAccess()) viewModel.reload()
    }

    private fun hasStorageAccess(): Boolean =
        if (Environment::class.supportsExternalStorageManager()) Environment.isExternalStorageManager()
        else requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        if (Environment::class.supportsExternalStorageManager()) {
            ShowRequestAllFilesAccessRationaleDialogFragment.show(this)
        } else {
            ShowRequestStoragePermissionRationaleDialogFragment.show(this)
        }
    }

    override fun onShowRequestAllFilesAccessRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesAccessLauncher.launch(
                Environment::class.createManageAppAllFilesAccessPermissionIntent(requireContext().packageName)
            )
        }
    }

    override fun onShowRequestStoragePermissionRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun onStorageAccessResult() {
        binding.permissionCard.isVisible = !hasStorageAccess()
        if (hasStorageAccess()) viewModel.reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
