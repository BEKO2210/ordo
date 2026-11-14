/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import coil.dispose
import coil.load
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.HomeCategoryItemBinding
import me.zhanghai.android.files.databinding.HomeRecentItemBinding
import me.zhanghai.android.files.databinding.HomeStorageItemBinding
import me.zhanghai.android.files.databinding.HomeQuickAccessItemBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.util.getQuantityString

class RecentFileAdapter(private val onClick: (RecentFile) -> Unit) :
    RecyclerView.Adapter<RecentFileAdapter.ViewHolder>() {
    var items: List<RecentFile> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        HomeRecentItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    inner class ViewHolder(private val binding: HomeRecentItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecentFile) {
            val iconRes = (item.mimeType?.asMimeTypeOrNull() ?: MimeType.GENERIC).iconRes
            binding.thumbnailImage.apply {
                dispose()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(iconRes)
                val attributes = item.attributes ?: return@apply
                load(item.path to attributes) {
                    placeholder(iconRes)
                    error(iconRes)
                    listener(onSuccess = { _, _ -> scaleType = ImageView.ScaleType.CENTER_CROP })
                }
            }
            binding.nameText.text = item.name
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

class TileAdapter : RecyclerView.Adapter<TileAdapter.ViewHolder>() {
    var items: List<HomeTile> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        HomeCategoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(private val binding: HomeCategoryItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeTile) {
            binding.iconImage.setTintedCircleIcon(item.iconRes, item.colorRes)
            binding.nameText.setText(item.titleRes)
            binding.descriptionText.text = item.summary
            binding.descriptionText.isInvisible = item.summary == null
            binding.root.setOnClickListener { item.onClick() }
        }
    }
}

class StorageAdapter(private val onClick: (StorageSummary) -> Unit) :
    RecyclerView.Adapter<StorageAdapter.ViewHolder>() {
    var items: List<StorageSummary> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        HomeStorageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    inner class ViewHolder(private val binding: HomeStorageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StorageSummary) {
            val context = binding.root.context
            binding.iconImage.setImageResource(
                if (item.removable) R.drawable.sd_card_icon_white_24dp else R.drawable.device_icon_white_24dp
            )
            binding.nameText.text = item.name
            binding.descriptionText.text = context.getString(
                R.string.home_storage_summary,
                item.used.asFileSize().formatHumanReadable(context),
                item.total.asFileSize().formatHumanReadable(context)
            )
            binding.usageProgress.max = 1000
            binding.usageProgress.progress = if (item.total > 0) {
                (item.used.toDouble() / item.total * 1000).toInt().coerceIn(0, 1000)
            } else 0
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

class QuickAccessAdapter(private val onClick: (QuickAccessItem) -> Unit) :
    RecyclerView.Adapter<QuickAccessAdapter.ViewHolder>() {
    var items: List<QuickAccessItem> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        HomeQuickAccessItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    inner class ViewHolder(private val binding: HomeQuickAccessItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: QuickAccessItem) {
            binding.iconImage.setImageResource(item.iconRes)
            binding.nameText.text = item.name
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

// Icon in its category color on a 15 % tinted circle, the tonal look of Material 3.
fun ImageView.setTintedCircleIcon(@DrawableRes iconRes: Int, @ColorRes colorRes: Int) {
    val color = ContextCompat.getColor(context, colorRes)
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(color)
    backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x26))
}
