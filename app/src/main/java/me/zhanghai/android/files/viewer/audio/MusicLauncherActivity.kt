/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.MusicLauncherActivityBinding
import me.zhanghai.android.files.databinding.MusicLauncherItemBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.home.HomeRepository
import me.zhanghai.android.files.player.PlaybackStateStore
import me.zhanghai.android.files.util.startActivitySafe

/**
 * What the "Ordo Musik" launcher icon opens. It takes no input from the intent except a private flag: with a saved
 * queue it hands over to the player (paused, see [AudioPlayerActivity.createResumeIntent]); with nothing to resume it
 * shows the recent audio files and a way to pick a folder.
 */
class MusicLauncherActivity : AppActivity() {
    private lateinit var binding: MusicLauncherActivityBinding
    private val adapter = Adapter { paths, index -> play(paths, index) }

    private val chooseFolderLauncher =
        registerForActivityResult(FileListActivity.OpenDirectoryContract()) { path ->
            if (path != null) startActivitySafe(FileListActivity.createViewIntent(path))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_SHOW_START, false) &&
            PlaybackStateStore(this).read() != null) {
            startActivity(AudioPlayerActivity.createResumeIntent(this))
            finish()
            return
        }
        binding = MusicLauncherActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.chooseFolder.setOnClickListener { chooseFolderLauncher.launch(null) }
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        val context = applicationContext
        lifecycleScope.launch {
            val paths = withContext(Dispatchers.IO) {
                try {
                    HomeRepository(context).loadRecentAudio(LIST_SIZE_MAX)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
            adapter.submit(paths)
            binding.emptyText.isVisible = paths.isEmpty()
            binding.recyclerView.isVisible = paths.isNotEmpty()
        }
    }

    private fun play(paths: List<Path>, index: Int) {
        val intent = Intent(this, AudioPlayerActivity::class.java)
        AudioPlayerActivity.putExtras(intent, paths, index)
        startActivitySafe(intent)
    }

    private class Adapter(private val onClick: (List<Path>, Int) -> Unit) :
        RecyclerView.Adapter<Adapter.ViewHolder>() {
        private var paths = emptyList<Path>()

        fun submit(newPaths: List<Path>) {
            paths = newPaths
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = paths.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(MusicLauncherItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val path = paths[position]
            holder.binding.nameText.text = path.fileName?.toString().orEmpty()
            holder.binding.folderText.text = path.parent?.toString().orEmpty()
            holder.itemView.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(paths, index)
            }
        }

        class ViewHolder(val binding: MusicLauncherItemBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val EXTRA_SHOW_START =
            "me.zhanghai.android.files.viewer.audio.extra.SHOW_START"
        private const val LIST_SIZE_MAX = 200

        /** The start screen even when a saved queue exists, e.g. when none of its files is left. */
        fun createStartIntent(context: Context): Intent =
            Intent(context, MusicLauncherActivity::class.java).putExtra(EXTRA_SHOW_START, true)
    }
}
