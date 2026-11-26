/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import dev.chrisbanes.insetter.applySystemWindowInsetsToPadding
import java8.nio.file.Path
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ImageViewerFragmentBinding
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.ui.DepthPageTransformer
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableListParceler
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createSendImageIntent
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.getStateOrNull
import me.zhanghai.android.files.util.mediumAnimTime
import me.zhanghai.android.files.util.putState
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.vault.VaultProvider
import me.zhanghai.android.systemuihelper.SystemUiHelper

class ImageViewerFragment : Fragment(), ConfirmDeleteDialogFragment.Listener {
    private val args by args<Args>()
    private val argsPaths by lazy { args.intent.extraPathList }
    private val pathsModel by viewModels<ImageViewerPathsViewModel>()

    private lateinit var paths: MutableList<Path>

    /** Elapsed realtime when a vault image left the screen, or 0 while it is visible. */
    private var vaultStoppedAt = 0L

    /** True after the window was focused. A background recreate never sets this, so its onStop keeps [vaultStoppedAt]. */
    private var vaultInFront = false

    private val vaultFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) markVaultInFront()
    }

    private lateinit var binding: ImageViewerFragmentBinding

    private lateinit var systemUiHelper: SystemUiHelper

    private lateinit var adapter: ImageViewerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vaultStoppedAt = savedInstanceState?.getLong(STATE_VAULT_STOPPED_AT) ?: 0L
        // The model outlives a rotation. Its list wins over the state saved before the delete finished.
        // State is saved only after a delete, so a restore after process death often has none.
        pathsModel.ensure(savedInstanceState?.getStateOrNull<State>()?.paths ?: argsPaths)
        paths = pathsModel.paths.value.orEmpty().toMutableList()

        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        ImageViewerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (paths.isEmpty()) {
            // TODO: Show a toast.
            finish()
            return
        }

        val activity = activity as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        // Our app bar will draw the status bar background.
        activity.window.statusBarColor = Color.TRANSPARENT
        binding.appBarLayout.applySystemWindowInsetsToPadding(left = true, top = true, right = true)
        systemUiHelper = SystemUiHelper(
            activity, SystemUiHelper.LEVEL_IMMERSIVE, SystemUiHelper.FLAG_IMMERSIVE_STICKY
        ) { visible: Boolean ->
            binding.appBarLayout.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else -binding.appBarLayout.bottom.toFloat())
                .setDuration(mediumAnimTime.toLong())
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
        // This will set up window flags.
        systemUiHelper.show()
        adapter = ImageViewerAdapter(viewLifecycleOwner) { systemUiHelper.toggle() }.apply {
            replace(paths)
        }
        binding.viewPager.apply {
            // 1 is the default for the old androidx.viewpager.widget.ViewPager.
            offscreenPageLimit = 1
            adapter = this@ImageViewerFragment.adapter
            // ViewPager saves its position and will restore it later.
            setCurrentItem(args.position, false)
            setPageTransformer(DepthPageTransformer)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateTitle()
                }
            })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            pathsModel.paths.filterNotNull().collect { updated ->
                if (updated == paths) return@collect
                onPathsChanged(updated)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            pathsModel.deleteError.collect { message -> showToast(message) }
        }
    }

    override fun onStart() {
        super.onStart()

        // Do not clear the leave time here. A recreate in the background also runs onStart, and
        // clearing it would let the following onStop start the two minutes over.
        if (!::paths.isInitialized || paths.none { VaultProvider.isVault(it) }) {
            vaultStoppedAt = 0L
            return
        }
        val timeout = vaultViewerTimeoutMillis(BuildConfig.DEBUG, debugVaultTimeoutOverride())
        if (vaultViewerTimedOut(vaultStoppedAt, SystemClock.elapsedRealtime(), timeout)) {
            vaultStoppedAt = 0L
            finish()
        }
    }

    override fun onResume() {
        super.onResume()

        if (!::binding.isInitialized) return
        val observer = binding.root.viewTreeObserver
        observer.removeOnWindowFocusChangeListener(vaultFocusListener)
        observer.addOnWindowFocusChangeListener(vaultFocusListener)
        if (binding.root.hasWindowFocus()) markVaultInFront()
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            val observer = binding.root.viewTreeObserver
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(vaultFocusListener)
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()

        val host = activity ?: return
        // A rotation must not count as leaving. A debug timeout of 0 would otherwise close on rotate.
        vaultStoppedAt = vaultLeaveOnStop(
            isVault = ::paths.isInitialized && paths.any { VaultProvider.isVault(it) },
            finishing = host.isFinishing,
            changingConfigurations = host.isChangingConfigurations,
            inFront = vaultInFront,
            stoppedAt = vaultStoppedAt,
            now = SystemClock.elapsedRealtime()
        )
        vaultInFront = false
    }

    private fun markVaultInFront() {
        if (activity?.isFinishing == true) return
        vaultInFront = true
        vaultStoppedAt = 0L
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        if (paths.isEmpty()) {
            // We did finish the activity in onActivityCreated(), however we will still be called
            // here before the activity is actually finished.
            return
        }

        updateTitle()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putLong(STATE_VAULT_STOPPED_AT, vaultStoppedAt)
        // The intent already carries the opened list. A second copy of a long list overflows the
        // binder transaction. Keep a copy only after a delete changed it.
        if (::paths.isInitialized && shouldKeepImageViewerState(argsPaths, paths)) {
            outState.putState(State(paths))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        // A Tresor image is not deleted or shared from here: restoring it is the way out of the vault.
        if (argsPaths.none { VaultProvider.isVault(it) }) {
            inflater.inflate(R.menu.image_viewer, menu)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            R.id.action_share -> {
                share()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun confirmDelete() {
        ConfirmDeleteDialogFragment.show(currentPath, this)
    }

    override fun delete(path: Path) {
        // A network file deleted on the main thread throws NetworkOnMainThreadException, which is
        // not an IOException, and the viewer died. The model deletes off the main thread and keeps
        // the list across a rotation that would cancel this view.
        pathsModel.delete(path)
    }

    private fun onPathsChanged(updated: List<Path>) {
        paths = updated.toMutableList()
        if (!::adapter.isInitialized || view == null) return
        if (paths.isEmpty()) {
            finish()
            return
        }
        adapter.replace(paths)
        // ViewPager only asynchronously sets current item to 0, which isn't a desirable behavior
        // for us and will make updateTitle() crash for index out of bounds.
        if (binding.viewPager.currentItem > paths.lastIndex) {
            binding.viewPager.currentItem = paths.lastIndex
        }
        updateTitle()
        // Work around blank screen due to ViewPager2.PageTransformer not being called (and thus the
        // next item keeps its 0 alpha) when we have offscreenPageLimit = 1.
        binding.viewPager.doOnPreDraw { binding.viewPager.requestTransform() }
    }

    private fun updateTitle() {
        val path = currentPath
        requireActivity().title = path.fileName.toString()
        val size = paths.size
        binding.toolbar.subtitle = if (size > 1) {
            getString(
                R.string.image_viewer_subtitle_format, binding.viewPager.currentItem + 1, size
            )
        } else {
            null
        }
    }

    private fun share() {
        val path = currentPath
        val intent = path.fileProviderUri.createSendImageIntent().withChooser()
        startActivitySafe(intent)
    }

    private val currentPath: Path
        get() = paths[binding.viewPager.currentItem]

    @Parcelize
    class Args(val intent: Intent, val position: Int) : ParcelableArgs

    @Parcelize
    private class State(val paths: @WriteWith<ParcelableListParceler> List<Path>) : ParcelableState
}

/** True when the open list is no longer the one the intent carried, so the saved state must keep it. */
internal fun shouldKeepImageViewerState(openedPaths: List<*>, currentPaths: List<*>): Boolean =
    currentPaths != openedPaths
