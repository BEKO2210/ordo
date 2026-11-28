/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorInt
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
import me.zhanghai.android.files.ui.fitActionRow
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.getQuantityString
import java.util.Locale
import me.zhanghai.android.files.analysis.StorageInsights

class RecentFileAdapter(
    private val onClick: (RecentFile) -> Unit,
    private val onLongClick: (android.view.View, RecentFile) -> Unit
) :
    RecyclerView.Adapter<RecentFileAdapter.ViewHolder>() {
    var items: List<RecentFile> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // In the horizontal row one page of cards fits the width, like one row of the grid.
    var isRow = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var columns = COLUMNS
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        HomeRecentItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = if (isRow) {
                // The list spans the screen; its width may still be 0 on the first layout pass.
                val view = holder.itemView
                val listWidth = view.resources.displayMetrics.widthPixels
                val padding = view.resources.getDimensionPixelSize(R.dimen.home_list_padding)
                (listWidth - 2 * padding) / columns - leftMargin - rightMargin
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        holder.bind(items[position])
    }

    companion object {
        const val COLUMNS = 4
    }

    inner class ViewHolder(private val binding: HomeRecentItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
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
            // One line, cut at the end: the tile is narrow and a name never wraps inside a word.
            binding.nameText.text = item.name
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(it, item)
                true
            }
            ViewCompat.replaceAccessibilityAction(
                binding.root,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                binding.root.context.getString(R.string.home_recent_file_actions, item.name),
                null
            )
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

/**
 * The storage card's percentage, worded exactly as the analysis shows it ([StorageInsights.share]); [shown] is the
 * share of [used] drawn so far while the bar grows or moves, so the label never jumps to another wording on the way.
 */
internal fun storagePercentLabel(used: Long, total: Long, shown: Float = 1f, locale: Locale = Locale.getDefault()): String =
    StorageInsights.share(if (shown >= 1f) used else Math.round(used * shown.toDouble()), total, locale)

class StorageAdapter(
    private val onAnalyze: (StorageSummary) -> Unit,
    private val onClick: (StorageSummary) -> Unit
) :
    RecyclerView.Adapter<StorageAdapter.ViewHolder>() {
    var items: List<StorageSummary> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        HomeStorageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    inner class ViewHolder(private val binding: HomeStorageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private val icons = listOf(binding.openButton.icon, binding.analyzeButton.icon)
        fun bind(item: StorageSummary) {
            val context = binding.root.context
            // Storage belongs to no file type, so it wears the chosen accent like its bar.
            binding.iconImage.setTintedCircleIconColor(
                if (item.removable) R.drawable.sd_card_icon_white_24dp else R.drawable.device_icon_white_24dp,
                context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
            )
            binding.nameText.text = item.name
            binding.descriptionText.text = context.getString(
                R.string.home_storage_summary,
                item.used.asFileSize().formatHumanReadable(context),
                item.total.asFileSize().formatHumanReadable(context)
            )
            val progress = if (item.total > 0) {
                (item.used.toDouble() / item.total * 1000).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            // The number counts up with the bar, which waits until it is scrolled into view.
            // Only a changed number is set: each setText on the wrap_content label lays out the whole home screen.
            binding.usageBar.onProgress = {
                val text = storagePercentLabel(item.used, item.total, it)
                if (binding.percentText.text.toString() != text) binding.percentText.text = text
            }
            // TalkBack reads the real value even before the bar has counted up.
            binding.percentText.contentDescription = storagePercentLabel(item.used, item.total)
            // The chosen accent, a lighter tone flowing into the full one; amber to red once the storage is nearly full.
            val accent = context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
            binding.usageBar.gradient = if (progress >= 900) {
                intArrayOf(ContextCompat.getColor(context, R.color.chart_audio), ContextCompat.getColor(context, R.color.chart_video))
            } else {
                intArrayOf(ColorUtils.blendARGB(accent, Color.WHITE, 0.35f), accent)
            }
            // Keyed by the volume: a recycled card that showed another volume grows in instead of moving from its number.
            binding.usageBar.setParts(listOf(accent to item.used), item.total, item.path)
            binding.analyzeButton.setOnClickListener { onAnalyze(item) }
            binding.root.setOnClickListener { onClick(item) }
            binding.openButton.setOnClickListener { onClick(item) }
            binding.actionsRow.doOnLayout {
                fitActionRow(binding.actionsRow, listOf(binding.openButton, binding.analyzeButton), icons)
            }
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
            binding.iconImage.setTintedCircleIcon(item.iconRes, quickAccessColor(item.iconRes))
            binding.nameText.text = item.name
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

// Same colors as the matching categories; a folder without its own pictogram gets the neutral blue.
@ColorRes
internal fun quickAccessColor(@DrawableRes iconRes: Int): Int = when (iconRes) {
    R.drawable.camera_icon_white_24dp, R.drawable.image_icon_white_24dp -> R.color.home_images
    R.drawable.video_icon_white_24dp -> R.color.home_videos
    R.drawable.audio_icon_white_24dp, R.drawable.podcast_icon_white_24dp -> R.color.home_audio
    R.drawable.document_icon_white_24dp -> R.color.home_documents
    R.drawable.download_icon_white_24dp -> R.color.home_downloads
    else -> R.color.home_backup
}

// Icon in its category color on a 15 % tinted circle, the tonal look of Material 3.
fun ImageView.setTintedCircleIcon(@DrawableRes iconRes: Int, @ColorRes colorRes: Int) =
    setTintedCircleIconColor(iconRes, ContextCompat.getColor(context, colorRes))

fun ImageView.setTintedCircleIconColor(@DrawableRes iconRes: Int, @ColorInt color: Int) {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(color)
    backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x26))
}
