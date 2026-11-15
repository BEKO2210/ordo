/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearSnapHelper
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ai.CommandSheetFragment
import me.zhanghai.android.files.analysis.AnalysisActivity
import me.zhanghai.android.files.apps.AppsActivity
import me.zhanghai.android.files.backup.BackupActivity
import me.zhanghai.android.files.databinding.HomeFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.filelist.ShowRequestAllFilesAccessRationaleDialogFragment
import me.zhanghai.android.files.filelist.ShowRequestStoragePermissionRationaleDialogFragment
import me.zhanghai.android.files.legal.LegalActivity
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.trash.TrashActivity
import me.zhanghai.android.files.ui.DrawerLayoutOnBackPressedCallback
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.ui.setGlassAmount
import me.zhanghai.android.files.ui.setUpGlass
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.vault.VaultActivity
import me.zhanghai.android.files.util.viewModels

class HomeFragment : Fragment(), ShowRequestAllFilesAccessRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionRationaleDialogFragment.Listener, NavigationFragment.Listener {
    private lateinit var recentAdapter: RecentFileAdapter
    private lateinit var tileAdapter: TileAdapter
    private lateinit var storageAdapter: StorageAdapter
    private lateinit var quickAccessAdapter: QuickAccessAdapter

    private val viewModel by viewModels { { HomeViewModel(requireActivity().application) } }
    private var _binding: HomeFragmentBinding? = null
    private val binding get() = _binding!!
    private lateinit var menu: Menu

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
        tileAdapter = TileAdapter()
        binding.categoryList.apply {
            adapter = tileAdapter
            isNestedScrollingEnabled = false
        }
        binding.vaultIcon.setTintedCircleIcon(R.drawable.lock_icon_white_24dp, R.color.home_vault)
        binding.vaultCard.setOnClickListener {
            startActivity(Intent(requireContext(), VaultActivity::class.java))
        }
        binding.backupIcon.setTintedCircleIcon(
            R.drawable.backup_icon_white_24dp, R.color.home_backup
        )
        binding.backupCard.setOnClickListener {
            startActivity(Intent(requireContext(), BackupActivity::class.java))
        }
        storageAdapter = StorageAdapter(
            { startActivity(AnalysisActivity.createIntent(requireContext(), it.path.toString())) }
        ) { startActivity(FileListActivity.createViewIntent(it.path)) }
        binding.storageList.apply { adapter = storageAdapter; isNestedScrollingEnabled = false }
        quickAccessAdapter = QuickAccessAdapter { startActivity(FileListActivity.createViewIntent(it.path)) }
        binding.quickAccessList.apply { adapter = quickAccessAdapter; isNestedScrollingEnabled = false }
        applyHomeLayout()
        binding.searchCard.setOnClickListener { startActivity(FileListActivity.createSearchIntent()) }
        // Opt-in by design: nothing is downloaded or loaded until the user starts the setup in the sheet.
        binding.assistantButton.isVisible = true
        binding.assistantButton.setOnClickListener {
            CommandSheetFragment().show(childFragmentManager, CommandSheetFragment.TAG)
        }
        setUpGlassToolbar()
        // The same side menu as in the file list; its storages and folders open there.
        val navigationFragment = childFragmentManager.findFragmentById(R.id.navigationFragment)
            as NavigationFragment? ?: NavigationFragment().also {
                childFragmentManager.commit { add(R.id.navigationFragment, it) }
            }
        navigationFragment.listener = this
        addOnBackPressedCallback(DrawerLayoutOnBackPressedCallback(binding.drawerLayout))
        binding.menuButton.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        val popupMenu = PopupMenu(
            requireContext(), binding.moreButton, Gravity.END,
            androidx.appcompat.R.attr.actionOverflowMenuStyle, 0
        )
        popupMenu.inflate(R.menu.home)
        menu = popupMenu.menu
        binding.moreButton.setOnClickListener { popupMenu.show() }
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
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
                R.id.action_legal -> startActivity(LegalActivity::class.createIntent())
                else -> return@setOnMenuItemClickListener false
            }
            updateMenu()
            true
        }
        Settings.HOME_RECENT_VIEW.observe(viewLifecycleOwner) { view ->
            val isRow = view == RECENT_VIEW_ROW
            if (recentAdapter.isRow != isRow || binding.recentList.layoutManager == null) {
                val recentColumns = homeRecentColumns(homeWidthDp())
                recentAdapter.columns = recentColumns
                binding.recentList.layoutManager = if (isRow) {
                    LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                } else {
                    GridLayoutManager(requireContext(), recentColumns)
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
        binding.swipeRefreshLayout.setOnRefreshListener {
            replayBars = true
            viewModel.reload()
        }
        // USB/SD attach or detach while Home is visible; the first emission is the current state (onResume loads).
        var seenVolumes = false
        StorageVolumeListLiveData.observe(viewLifecycleOwner) {
            if (seenVolumes && hasStorageAccess()) viewModel.reload() else seenVolumes = true
        }
        viewModel.state.observe(viewLifecycleOwner) { homeState ->
            updateRecentItems()
            tileAdapter.items = createTiles(homeState)
            storageAdapter.items = homeState.storages
            quickAccessAdapter.items = homeState.quickAccess
            applyHomeLayout()
            updateRecentEmpty()
            binding.swipeRefreshLayout.isRefreshing = homeState.isLoading
            // After a pull to refresh the storage bars grow in again, also when the numbers did not change.
            if (replayBars && !homeState.isLoading) {
                replayBars = false
                binding.storageList.post { replayStorageBars() }
            }
        }
    }

    private var replayBars = false
    private var resumedBefore = false

    private fun holdStorageBars() {
        val binding = _binding ?: return
        binding.storageList.children.forEach {
            it.findViewById<me.zhanghai.android.files.analysis.StackedBarView>(R.id.usageBar)?.hold()
        }
    }

    private fun replayStorageBars() {
        val binding = _binding ?: return
        binding.storageList.children.forEach {
            it.findViewById<me.zhanghai.android.files.analysis.StackedBarView>(R.id.usageBar)?.replay()
        }
    }

    // The content scrolls under the top bar. Android 12+ blurs it; older versions get a solid bar.
    private fun setUpGlassToolbar() {
        val context = requireContext()
        // Exactly the page colour: at rest there is no seam under the bar; glass and hairline show once it scrolls.
        val barColor = context.getColorByAttr(android.R.attr.colorBackground)
        binding.toolbarBlur.setUpGlass(binding.blurTarget, barColor, requireActivity())
        val hairline = ColorUtils.setAlphaComponent(context.getColorByAttr(android.R.attr.textColorPrimary), 0x1F)
        binding.toolbarEdge.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                // Almost the page colour right under the bar: content dissolves before it reaches the search field.
                intArrayOf(
                    ColorUtils.setAlphaComponent(barColor, 0xE6), ColorUtils.setAlphaComponent(barColor, 0x80),
                    android.graphics.Color.TRANSPARENT
                )
            ),
            android.graphics.drawable.GradientDrawable().apply { setColor(hairline) }
        )).apply { setLayerHeight(1, (resources.displayMetrics.density).toInt().coerceAtLeast(1)) }
        binding.toolbarBlur.addOnLayoutChangeListener { bar, _, _, _, _, _, _, _, _ ->
            binding.toolbarEdge.translationY = bar.height.toFloat()
        }
        // Glass, edge and hairline appear over the first 24 dp of scrolling: flat at rest, layered in motion.
        val edgeDistance = 24 * resources.displayMetrics.density
        binding.toolbarBlur.setGlassAmount(barColor, 0f)
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val amount = (scrollY / edgeDistance).coerceIn(0f, 1f)
            binding.toolbarEdge.alpha = amount
            binding.toolbarBlur.setGlassAmount(barColor, amount)
        }
        val scrollPaddingBottom = binding.scrollView.paddingBottom
        val refreshDistance = binding.swipeRefreshLayout.progressViewEndOffset
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { root, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.updatePadding(left = insets.left, right = insets.right)
            binding.toolbarBlur.updatePadding(top = insets.top)
            val searchLayoutParams = binding.searchCard.layoutParams as ViewGroup.MarginLayoutParams
            val top = insets.top + searchLayoutParams.height + searchLayoutParams.topMargin +
                searchLayoutParams.bottomMargin
            binding.scrollView.updatePadding(top = top, bottom = scrollPaddingBottom + insets.bottom)
            binding.swipeRefreshLayout.setProgressViewOffset(true, top, top + refreshDistance)
            windowInsets
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) applyHomeLayout()
    }

    private fun homeWidthDp(): Int =
        (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()

    // Runs on every state update, so it only touches a list whose column count really changed: a new layout
    // manager rebuilds all item views, which would restart the storage bars and flicker.
    private fun applyHomeLayout() {
        val widthDp = homeWidthDp()
        binding.categoryList.setColumns(homeCategoryColumns(widthDp))
        val recentColumns = homeRecentColumns(widthDp)
        if (recentAdapter.columns != recentColumns) {
            recentAdapter.columns = recentColumns
            if (!recentAdapter.isRow) {
                binding.recentList.layoutManager = GridLayoutManager(requireContext(), recentColumns)
                updateRecentItems()
            }
        }
        binding.storageList.setColumns(homeStorageColumns(widthDp, storageAdapter.itemCount))
        binding.quickAccessList.setColumns(homeQuickAccessColumns(widthDp))
    }

    private fun RecyclerView.setColumns(columns: Int) {
        val manager = layoutManager
        val current = (manager as? GridLayoutManager)?.spanCount ?: if (manager != null) 1 else 0
        if (current == columns) return
        layoutManager = if (columns == 1) LinearLayoutManager(context) else GridLayoutManager(context, columns)
    }

    private fun updateRecentItems() {
        val files = viewModel.state.value?.recentFiles ?: return
        recentAdapter.items = if (recentAdapter.isRow) {
            files
        } else {
            files.take(2 * recentAdapter.columns)
        }
    }

    private fun updateRecentEmpty() {
        val state = viewModel.state.value ?: return
        binding.recentEmpty.isVisible = Settings.HOME_RECENT_VIEW.valueCompat != RECENT_VIEW_HIDDEN &&
            !state.isLoading && state.recentFiles.isEmpty()
    }

    private fun updateMenu() {
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
        val compact = homeCategoryCompact(homeWidthDp())
        fun summary(count: Int, size: Long): String {
            val sizeText = size.asFileSize().formatHumanReadable(context)
            return if (compact) sizeText else {
                context.getQuantityString(R.plurals.home_category_summary, count, count, sizeText)
            }
        }
        val categoryTiles = state.categories.map { summary ->
            HomeTile(
                summary.category.iconRes, summary.category.titleRes, summary.category.colorRes,
                summary(summary.count.toInt(), summary.size)
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
                state.trashCount?.let { summary(it, state.trashSize) }
            ) { startActivity(Intent(context, TrashActivity::class.java)) }
        )
    }

    override fun onResume() {
        super.onResume()
        binding.permissionCard.isVisible = !hasStorageAccess()
        // Coming back to the home screen (from another screen or from outside the app) grows the storage bars in
        // again. They are emptied right here, before the first frame, and start together once the reload below
        // is done and the screen is visible. Starting them here would run the animation behind the old screen: a
        // bar whose numbers did not change (the SD card) would already be full when the home screen appears.
        // The first resume needs no reload: the view model loads when it is created, and twice in parallel would
        // fight over MediaStore and FUSE. A permission just granted reloads in onStorageAccessResult().
        if (resumedBefore && hasStorageAccess()) {
            holdStorageBars()
            replayBars = true
            viewModel.reload()
        }
        resumedBefore = true
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

    // Nothing in the side menu is the current place: the home screen is not a folder.
    override val currentPath: Path?
        get() = null

    override fun navigateTo(path: Path) {
        startActivity(FileListActivity.createViewIntent(path))
    }

    override fun navigateToRoot(path: Path) {
        navigateTo(path)
    }

    override fun navigateToDefaultRoot() {}

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {}

    override fun closeNavigationDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
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
        private const val RECENT_VIEW_HIDDEN = "hidden"
    }
}
