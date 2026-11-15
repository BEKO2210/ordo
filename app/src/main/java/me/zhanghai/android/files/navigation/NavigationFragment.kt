/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.NavigationFragmentBinding
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

        val viewLifecycleOwner = viewLifecycleOwner
        NavigationItemListLiveData.observe(viewLifecycleOwner) { onNavigationItemsChanged(it) }
        listener.observeCurrentPath(viewLifecycleOwner) { onCurrentPathChanged(it) }
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
