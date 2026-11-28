/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.MusicQueueRowBinding

/**
 * The play queue as a sheet over the player: the playing song is tinted and marked with the bars, a tap plays a
 * song, its handle drags it to another place, and a swipe sideways takes it out. TalkBack gets "move up", "move down"
 * and "remove" as actions of every row. Every change goes straight to the media session.
 */
class PlayerQueueSheet : BottomSheetDialogFragment() {
    private val player: Player?
        get() = (activity as? AudioPlayerActivity)?.queuePlayer()

    private val adapter = QueueAdapter()
    private var songsLoadJob: Job? = null
    private lateinit var touchHelper: ItemTouchHelper

    private val listener = object : Player.Listener {
        @SuppressLint("NotifyDataSetChanged")
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED) && !dragging) {
                adapter.notifyDataSetChanged()
            }
        }
    }
    private var dragging = false
    // Queue items carry only their file; titles and artists come from the music library, read once per sheet.
    private var songs: Map<String, MusicSong> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PlayerQueueSheet.adapter
            clipToPadding = false
            val vertical = resources.getDimensionPixelSize(R.dimen.phi_21)
            setPadding(0, 0, 0, vertical)
            touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END
            ) {
                override fun isLongPressDragEnabled() = false

                override fun onMove(
                    recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from < 0 || to < 0) return false
                    player?.moveMediaItem(from, to) ?: return false
                    this@PlayerQueueSheet.adapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val position = viewHolder.bindingAdapterPosition
                    val player = player
                    // The playing song stays; it is stopped with the player's own controls.
                    if (player == null || position < 0 || position == player.currentMediaItemIndex) {
                        this@PlayerQueueSheet.adapter.notifyItemChanged(position)
                        return
                    }
                    player.removeMediaItem(position)
                    this@PlayerQueueSheet.adapter.notifyItemRemoved(position)
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    dragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG
                    val lift = if (dragging) LIFT_DP * resources.displayMetrics.density else 0f
                    viewHolder?.itemView?.animate()?.translationZ(lift)
                }

                @SuppressLint("NotifyDataSetChanged")
                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.animate().translationZ(0f)
                    this@PlayerQueueSheet.adapter.notifyDataSetChanged()
                }
            })
            touchHelper.attachToRecyclerView(this)
        }
        // The sheet's grip on top, so it reads as something to pull.
        return android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(com.google.android.material.bottomsheet.BottomSheetDragHandleView(context))
            addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private var list: RecyclerView? = null

    @SuppressLint("NotifyDataSetChanged")
    override fun onStart() {
        super.onStart()
        // Open in full as the cast sheet (#119): a long queue opened at half height and needed a swipe up.
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        if (songs.isEmpty()) {
            songsLoadJob?.cancel()
            val context = requireContext().applicationContext
            songsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                songs = withContext(Dispatchers.IO) {
                    try {
                        queryMusic(context).associateBy { it.path }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyMap()
                    }
                }
                adapter.notifyDataSetChanged()
            }
        }
        player?.addListener(listener)
        val index = player?.currentMediaItemIndex ?: 0
        list?.scrollToPosition(index)
    }

    override fun onStop() {
        songsLoadJob?.cancel()
        songsLoadJob = null
        player?.removeListener(listener)
        super.onStop()
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {
        inner class Holder(val binding: MusicQueueRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = player?.mediaItemCount ?: 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(MusicQueueRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.binding.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
            holder.binding.root.setOnClickListener {
                val position = holder.bindingAdapterPosition
                player?.takeIf { position >= 0 }?.let {
                    it.seekToDefaultPosition(position)
                    it.play()
                }
            }
            ViewCompat.setAccessibilityDelegate(holder.binding.root, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val position = holder.bindingAdapterPosition
                    if (position > 0) info.addAction(action(R.id.queue_move_up, R.string.music_queue_move_up))
                    if (position in 0 until itemCount - 1) {
                        info.addAction(action(R.id.queue_move_down, R.string.music_queue_move_down))
                    }
                    if (position != player?.currentMediaItemIndex) {
                        info.addAction(action(R.id.queue_remove, R.string.music_queue_remove))
                    }
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val position = holder.bindingAdapterPosition
                    val player = player ?: return false
                    if (position !in 0 until player.mediaItemCount) return false
                    when (action) {
                        R.id.queue_move_up -> player.moveMediaItem(position, position - 1)
                        R.id.queue_move_down -> player.moveMediaItem(position, position + 1)
                        R.id.queue_remove -> player.removeMediaItem(position)
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    return true
                }
            })
            return holder
        }

        private fun action(id: Int, label: Int) =
            AccessibilityNodeInfoCompat.AccessibilityActionCompat(id, getString(label))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val player = player ?: return
            val item = player.getMediaItemAt(position)
            val playing = position == player.currentMediaItemIndex
            val song = runCatching { java.net.URI.create(item.mediaId).path }.getOrNull()?.let { songs[it] }
            holder.binding.title.text = song?.title ?: title(item)
            val artist = song?.artist ?: item.mediaMetadata.artist
            holder.binding.subtitle.text = artist ?: ""
            holder.binding.subtitle.isVisible = !artist.isNullOrBlank()
            holder.binding.bars.isVisible = playing
            holder.binding.bars.color = MaterialColors.getColor(holder.itemView, androidx.appcompat.R.attr.colorPrimary)
            holder.binding.bars.setPlaying(playing && player.isPlaying)
            holder.binding.root.background = if (playing) {
                androidx.core.content.ContextCompat.getDrawable(holder.itemView.context, R.drawable.music_queue_current)
            } else {
                android.util.TypedValue().let { value ->
                    holder.itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                    androidx.core.content.ContextCompat.getDrawable(holder.itemView.context, value.resourceId)
                }
            }
            holder.binding.root.isSelected = playing
        }
    }

    private fun title(item: MediaItem): CharSequence =
        item.mediaMetadata.title?.takeIf { it.isNotBlank() }
            ?: item.mediaMetadata.displayTitle?.takeIf { it.isNotBlank() }
            ?: android.net.Uri.decode(item.mediaId.substringAfterLast('/')).substringBeforeLast('.')

    companion object {
        const val TAG = "PlayerQueueSheet"
        private const val LIFT_DP = 8f
    }
}
