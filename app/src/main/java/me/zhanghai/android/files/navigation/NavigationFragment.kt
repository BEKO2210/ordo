/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.content.Intent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.NavigationFragmentBinding
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.startActivitySafe

class NavigationFragment : Fragment(), NavigationItem.Listener {
    private lateinit var binding: NavigationFragmentBinding

    private lateinit var adapter: NavigationListAdapter

    lateinit var listener: Listener

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        NavigationFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        binding.recyclerView.setHasFixedSize(true)
        // TODO: Needed?
        //binding.recyclerView.setItemAnimator(new NoChangeAnimationItemAnimator())
        val context = requireContext()
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = NavigationListAdapter(this, context)
        binding.recyclerView.adapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                .build(),
            HeaderAdapter(), adapter
        )

        binding.recyclerView.doOnAttach { setUpOpenStagger(it) }

        val viewLifecycleOwner = viewLifecycleOwner
        NavigationItemListLiveData.observe(viewLifecycleOwner) { onNavigationItemsChanged(it) }
        listener.observeCurrentPath(viewLifecycleOwner) { onCurrentPathChanged(it) }
    }

    // Each time the side menu starts to open its rows rise in one after the other, 20 ms apart, the first eight only.
    private fun setUpOpenStagger(view: View) {
        var drawer: View? = view
        while (drawer != null && drawer !is DrawerLayout) {
            drawer = drawer.parent as? View
        }
        val drawerLayout = drawer as? DrawerLayout ?: return
        val listener = object : DrawerLayout.SimpleDrawerListener() {
            private var closed = true

            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (!closed || slideOffset <= 0f) {
                    return
                }
                closed = false
                if (view.isAttachedToWindow && Motion.any) {
                    staggerRows()
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                closed = true
            }
        }
        drawerLayout.addDrawerListener(listener)
        // The drawer outlives this view: without this every recreated view would leave a listener behind.
        view.doOnDetach { drawerLayout.removeDrawerListener(listener) }
    }

    private fun staggerRows() {
        val recyclerView = binding.recyclerView
        val rise = 8 * recyclerView.resources.displayMetrics.density
        val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        for (index in 0 until recyclerView.childCount) {
            val row = recyclerView.getChildAt(index)
            row.animate().cancel()
            row.alpha = 0f
            row.translationY = rise
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(minOf(index, 8) * 20L)
                .setDuration(200)
                .setInterpolator(interpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        row.alpha = 1f
                        row.translationY = 0f
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        row.animate().setListener(null)
                    }
                })
        }
    }

    private fun onNavigationItemsChanged(navigationItems: List<NavigationItem?>) {
        adapter.replace(navigationItems)
    }

    private fun onCurrentPathChanged(path: Path) {
        adapter.notifyCheckedChanged()
    }

    override val currentPath: Path?
        get() = listener.currentPath

    override fun navigateTo(path: Path) {
        listener.navigateTo(path)
    }

    override fun navigateToRoot(path: Path) {
        listener.navigateToRoot(path)
    }

    override fun launchIntent(intent: Intent) {
        startActivitySafe(intent)
    }

    override fun closeNavigationDrawer() {
        listener.closeNavigationDrawer()
    }

    // App icon and name on top of the list; scrolls away with it.
    private class HeaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int = 1

        override fun getItemId(position: Int): Long = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(
                parent.context.layoutInflater.inflate(R.layout.navigation_header, parent, false)
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    interface Listener {
        // Null when the host shows no folder, as the home screen does.
        val currentPath: Path?
        fun navigateTo(path: Path)
        fun navigateToRoot(path: Path)
        fun navigateToDefaultRoot()
        fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit)
        fun closeNavigationDrawer()
    }
}
