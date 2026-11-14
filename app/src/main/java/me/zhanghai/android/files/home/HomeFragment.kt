/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

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
import androidx.recyclerview.widget.LinearSnapHelper
import me.zhanghai.android.files.R
import me.zhanghai.android.files.analysis.AnalysisActivity
import me.zhanghai.android.files.apps.AppsActivity
import me.zhanghai.android.files.databinding.HomeFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.filelist.ShowRequestAllFilesAccessRationaleDialogFragment
import me.zhanghai.android.files.filelist.ShowRequestStoragePermissionRationaleDialogFragment
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.trash.TrashActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.viewModels

class HomeFragment : Fragment(), ShowRequestAllFilesAccessRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionRationaleDialogFragment.Listener {
    private lateinit var recentAdapter: RecentFileAdapter

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
        recentAdapter = RecentFileAdapter { file ->
            startActivitySafe(
                OpenFileActivity.createIntent(
                    file.path, file.mimeType?.asMimeTypeOrNull() ?: MimeType.ANY
                )
            )
        }
        binding.recentList.apply {
            adapter = recentAdapter
            isNestedScrollingEnabled = false
            LinearSnapHelper().attachToRecyclerView(this)
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
                R.id.action_browse ->
                    startActivity(Intent(requireContext(), FileListActivity::class.java))
                R.id.action_recent_grid -> Settings.HOME_RECENT_VIEW.putValue(RECENT_VIEW_GRID)
                R.id.action_recent_row -> Settings.HOME_RECENT_VIEW.putValue(RECENT_VIEW_ROW)
                R.id.action_recent_hidden -> Settings.HOME_RECENT_VIEW.putValue(RECENT_VIEW_HIDDEN)
                R.id.action_show_hidden_files -> {
                    Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(!it.isChecked)
                    viewModel.reload()
                }
                R.id.action_show_quick_access ->
                    Settings.HOME_SHOW_QUICK_ACCESS.putValue(!it.isChecked)
                R.id.action_settings -> startActivity(SettingsActivity::class.createIntent())
                else -> return@setOnMenuItemClickListener false
            }
            updateMenu()
            true
        }
        Settings.HOME_RECENT_VIEW.observe(viewLifecycleOwner) { view ->
            val isRow = view == RECENT_VIEW_ROW
            if (recentAdapter.isRow != isRow || binding.recentList.layoutManager == null) {
                binding.recentList.layoutManager = if (isRow) {
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                } else {
                    GridLayoutManager(requireContext(), RecentFileAdapter.COLUMNS)
                }
                recentAdapter.isRow = isRow
                updateRecentItems()
            }
            val isVisible = view != RECENT_VIEW_HIDDEN
            binding.recentTitle.isVisible = isVisible
            binding.recentList.isVisible = isVisible
            updateRecentEmpty()
            updateMenu()
        }
        Settings.HOME_SHOW_QUICK_ACCESS.observe(viewLifecycleOwner) {
            binding.quickAccessTitle.isVisible = it
            binding.quickAccessList.isVisible = it
            updateMenu()
        }
        binding.permissionButton.setOnClickListener { requestStorageAccess() }
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.reload() }
        viewModel.state.observe(viewLifecycleOwner) { homeState ->
            updateRecentItems()
            tileAdapter.items = createTiles(homeState)
            storageAdapter.items = homeState.storages
            quickAccessAdapter.items = homeState.quickAccess
            updateRecentEmpty()
            binding.swipeRefreshLayout.isRefreshing = homeState.isLoading
        }
    }

    private fun updateRecentItems() {
        val files = viewModel.state.value?.recentFiles ?: return
        recentAdapter.items = if (recentAdapter.isRow) files else files.take(RECENT_GRID_COUNT)
    }

    private fun updateRecentEmpty() {
        val state = viewModel.state.value ?: return
        binding.recentEmpty.isVisible = Settings.HOME_RECENT_VIEW.valueCompat != RECENT_VIEW_HIDDEN &&
            !state.isLoading && state.recentFiles.isEmpty()
    }

    private fun updateMenu() {
        val menu = binding.toolbar.menu
        menu.findItem(
            when (Settings.HOME_RECENT_VIEW.valueCompat) {
                RECENT_VIEW_ROW -> R.id.action_recent_row
                RECENT_VIEW_HIDDEN -> R.id.action_recent_hidden
                else -> R.id.action_recent_grid
            }
        ).isChecked = true
        menu.findItem(R.id.action_show_hidden_files).isChecked =
            Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        menu.findItem(R.id.action_show_quick_access).isChecked =
            Settings.HOME_SHOW_QUICK_ACCESS.valueCompat
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

    companion object {
        private const val RECENT_VIEW_GRID = "grid"
        private const val RECENT_VIEW_ROW = "row"
        private const val RECENT_GRID_COUNT = 2 * RecentFileAdapter.COLUMNS
        private const val RECENT_VIEW_HIDDEN = "hidden"
    }
}
