/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.Fill
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.onFirstSight
import me.zhanghai.android.files.databinding.AnalysisOverviewBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.home.HomeCategory
import me.zhanghai.android.files.home.setTintedCircleIcon
import java.text.DateFormatSymbols
import java.util.Locale

/** Draws [StorageInsights]: fixed layout, real numbers only, motion that settles quickly. */
class AnalysisDashboard(
    private val binding: AnalysisOverviewBinding,
    /** A page inside the analysis: a cleanup list, a file kind or a folder. */
    private val onOpen: (AnalysisPage) -> Unit,
    /** Another screen: "trash" or "system" (the app list). */
    private val onShow: (String) -> Unit,
    private val onPreview: (StorageInsights.Suggestion) -> Unit
) {
    private val context: Context = binding.root.context
    private val density = context.resources.displayMetrics.density
    private val text = MaterialColors.getColor(binding.root, android.R.attr.textColorPrimary)
    private val muted = MaterialColors.getColor(binding.root, android.R.attr.textColorSecondary)
    private val animate = me.zhanghai.android.files.ui.Motion.any

    private fun color(res: Int) = ContextCompat.getColor(context, res)
    private fun size(bytes: Long) = bytes.asFileSize().formatHumanReadable(context)
    private fun dp(value: Int) = (value * density).toInt()

    fun bind(insights: StorageInsights, folders: List<FolderSize>, root: String) {
        // Tablets: a centred column of comfortable width instead of cards stretched edge to edge.
        val column = binding.overviewScroll.getChildAt(0)
        val side = ((context.resources.displayMetrics.widthPixels - 720 * density) / 2).coerceAtLeast(16 * density).toInt()
        column.setPadding(side, column.paddingTop, side, column.paddingBottom)
        bindUsage(insights)
        bindFiles(insights)
        bindCleanup(insights)
        bindSuggestions(insights)
        bindFolders(folders, root, insights.own)
        bindGrowth(insights)
        listOf(binding.usageCard, binding.filesCard, binding.cleanupCard, binding.suggestionCard, binding.folderCard,
            binding.growthCard)
            .filter { it.visibility == View.VISIBLE }
            .forEachIndexed { i, card -> card.rise(70L * i) }
    }

    private fun kindLabel(kind: String) = kindLabel(context, kind)

    // Icon and colour of the matching home category, so a type looks the same everywhere.
    private fun categoryOf(kind: String) = when (kind) {
        "image" -> HomeCategory.IMAGES
        "audio" -> HomeCategory.AUDIO
        "document" -> HomeCategory.DOCUMENTS
        "video" -> HomeCategory.VIDEOS
        "apps" -> HomeCategory.APKS
        else -> null
    }

    // ---- Usage: your files, system and free as one bar -----------------------------------------

    private fun bindUsage(insights: StorageInsights) {
        val total = insights.total
        val system = insights.usage.toMap()[StorageInsights.KIND_SYSTEM] ?: 0L
        val mine = color(R.color.ai_accent)
        val gray = color(R.color.chart_system)
        val label = context.getString(R.string.analysis_usage_center_label)
        val final = StorageInsights.share(insights.used, total)
        val percent = java.text.NumberFormat.getPercentInstance()
        val ratio = if (total > 0) insights.used.toDouble() / total else 0.0
        // The number counts up with the bar, which waits until it is on screen.
        binding.usageBar.onProgress = { progress ->
            binding.usageHero.text = hero(if (progress >= 1f) final else percent.format(ratio * progress), label)
        }
        binding.usageHero.contentDescription = "$final $label"
        binding.usageBar.setParts(listOf(mine to insights.own, gray to system), total)
        binding.usageSubtitle.text = context.getString(R.string.analysis_usage_subtitle, size(insights.used), size(total))
        binding.usageLegend.removeAllViews()
        // Each part says in plain words what it is; the first two lead to where it can be looked at.
        listOf(
            Triple(mine, R.string.analysis_kind_mine, insights.own) to R.string.analysis_usage_mine_hint,
            Triple(gray, R.string.analysis_kind_system, system) to R.string.analysis_usage_system_hint,
            Triple(null, R.string.analysis_kind_free, insights.free) to R.string.analysis_usage_free_hint
        ).forEachIndexed { i, (part, hint) ->
            val (dot, name, bytes) = part
            val row = legendRow(dot, context.getString(name), size(bytes), StorageInsights.share(bytes, total),
                context.getString(hint))
            binding.usageLegend.addView(row)
            when (name) {
                R.string.analysis_kind_mine -> row.clickable {
                    binding.overviewScroll.smoothScrollTo(0, binding.filesCard.top - dp(8))
                }
                // What the app cannot read is mostly apps and their data: the app list is where that can shrink.
                R.string.analysis_kind_system -> row.clickable { onShow("system") }
                else -> row.chevronSpace()
            }
            row.rise(250L + 45L * i)
        }
    }

    private fun hero(value: String, label: String): SpannableString =
        SpannableString("$value $label").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(text), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.42f), value.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(muted), value.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** A row with a swatch (a ring when [dot] is null, e.g. free space), name, value, share and an optional hint. */
    private fun legendRow(dot: Int?, name: String, value: String, share: String, hint: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (hint != null) 56 else 40)
            if (hint != null) setPadding(0, dp(8), 0, dp(8))
            contentDescription = listOfNotNull(name, value, share, hint).filter { it.isNotEmpty() }.joinToString()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // The swatch sits in the name's line, so it stays beside the name however long the hint is.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            if (dot != null) setColor(dot) else setStroke((1.5f * density).toInt(), muted)
                        }
                    }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
                    addView(label(name, primary = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label(value, primary = true))
                    if (share.isNotEmpty()) addView(label(share, primary = false).apply {
                        gravity = Gravity.END
                        minWidth = dp(56)
                    })
                })
                if (hint != null) addView(label(hint, primary = false).apply {
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                    setTextColor(muted)
                    setPaddingRelative(dp(22), dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun label(value: String, primary: Boolean) = TextView(context).apply {
        text = value
        TextViewCompat.setTextAppearance(
            this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        )
        setTextColor(if (primary) this@AnalysisDashboard.text else muted)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // Tappable rows: rounded accent ripple, a slight give under the finger and a chevron at the end. Call after
    // the row is added to its parent.
    private fun LinearLayout.clickable(onClick: () -> Unit) {
        addView(ImageView(context).apply {
            setImageResource(R.drawable.arrow_end_icon_white_24dp)
            imageTintList = android.content.res.ColorStateList.valueOf(muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
        background = ContextCompat.getDrawable(context, R.drawable.ordo_row_ripple)
        stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.ordo_row_press)
        // The ripple is inset by 8 dp; the row reaches that far past the content so text stays aligned.
        // Left and right, not start and end: the row is already attached, and relative margins would not resolve.
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin = -dp(8); it.rightMargin = -dp(8) }
        setPaddingRelative(dp(8), paddingTop, dp(8), paddingBottom)
        minimumHeight = maxOf(minimumHeight, dp(48))
        setOnClickListener { onClick() }
    }

    // Keeps the figures of a row without a chevron in line with the tappable rows around it.
    private fun LinearLayout.chevronSpace() = addView(android.widget.Space(context), LinearLayout.LayoutParams(dp(26), 0))

    /**
     * Name, size and share with a thin bar of that share below: the bar is exactly as long as the percentage says,
     * so small parts stay small and nothing is scaled up to look bigger.
     */
    private fun meterRow(@DrawableRes icon: Int, @ColorRes tint: Int, name: String, bytes: Long, whole: Long): LinearLayout {
        val share = StorageInsights.share(bytes, whole)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(8), 0, dp(8))
            contentDescription = "$name, ${size(bytes)}, $share"
            addView(ImageView(context).apply {
                setBackgroundResource(R.drawable.home_icon_circle)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setTintedCircleIcon(icon, tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(name, primary = true).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label(size(bytes), primary = true).apply { setPaddingRelative(dp(8), 0, 0, 0) })
                    addView(label(share, primary = false).apply {
                        gravity = Gravity.END
                        minWidth = dp(56)
                    })
                })
                addView(StackedBarView(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setParts(listOf(color(R.color.ai_accent) to bytes), whole)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ---- Your files by type --------------------------------------------------------------------

    private fun bindFiles(insights: StorageInsights) {
        val own = insights.own
        val kinds = insights.usage.filter { it.first != StorageInsights.KIND_SYSTEM && it.second > 0 }
            .sortedByDescending { it.second }
        binding.filesCard.isVisible(kinds.isNotEmpty())
        binding.filesSubtitle.text = context.getString(R.string.analysis_files_subtitle, size(own))
        binding.filesList.removeAllViews()
        kinds.forEachIndexed { i, (kind, bytes) ->
            val category = categoryOf(kind)
            val row = meterRow(category?.iconRes ?: R.drawable.file_icon_white_24dp,
                category?.colorRes ?: R.color.chart_system, kindLabel(kind), bytes, own)
            binding.filesList.addView(row)
            row.clickable { onOpen(AnalysisPage.Kind(kind)) }
            row.rise(200L + 40L * i)
        }
    }

    // ---- Cleanup -----------------------------------------------------------------------------

    private fun bindCleanup(insights: StorageInsights) {
        val colors = mapOf("duplicates" to R.color.chart_image, "apks" to R.color.chart_apps,
            "trash" to R.color.chart_document)
        val items = insights.cleanup.filter { it.second > 0 }
        val (safe, review) = items.partition { it.first in StorageInsights.SAFE }
        binding.cleanupHeadline.text = context.getString(
            when {
                items.isEmpty() -> R.string.analysis_cleanup_none
                safe.isEmpty() -> R.string.analysis_cleanup_safe_none
                else -> R.string.analysis_cleanup_headline
            }, size(insights.cleanupTotal)
        )
        // Only the safe parts make up the bar; a duplicate APK counts in two rows, so it is a guide, not a sum.
        binding.cleanupBar.isVisible(safe.isNotEmpty())
        binding.cleanupBar.setParts(safe.map { color(colors.getValue(it.first)) to it.second }, insights.cleanupTotal)
        binding.cleanupList.removeAllViews()
        var delay = 300L
        fun addRow(type: String, bytes: Long) {
            val name = context.getString(cleanupLabel(type))
            val row = legendRow(colors[type]?.let(::color), name, size(bytes), "")
            row.addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = context.getString(R.string.analysis_view)
                isAllCaps = false
                setTextColor(color(R.color.ai_accent))
                minimumHeight = dp(48)
                contentDescription = "${context.getString(R.string.analysis_view)}: $name"
                setOnClickListener { if (type == "trash") onShow(type) else onOpen(AnalysisPage.Cleanup(type)) }
            })
            binding.cleanupList.addView(row.apply { rise(delay) })
            delay += 50L
        }
        safe.forEach { (type, bytes) -> addRow(type, bytes) }
        if (review.isNotEmpty()) {
            binding.cleanupList.addView(label(context.getString(R.string.analysis_cleanup_review), primary = false).apply {
                setPadding(0, dp(12), 0, dp(2))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                rise(delay)
            })
            review.forEach { (type, bytes) -> addRow(type, bytes) }
        }
    }

    private fun View.isVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ---- Suggestions -------------------------------------------------------------------------

    private fun bindSuggestions(insights: StorageInsights) {
        binding.suggestionCard.isVisible(insights.suggestions.isNotEmpty())
        binding.suggestionList.removeAllViews()
        insights.suggestions.forEach { suggestion ->
            val title = context.getString(
                if (suggestion.id == "old_downloads") R.string.analysis_suggestion_old_downloads
                else R.string.analysis_suggestion_download_apks
            )
            val detail = context.resources.getQuantityString(R.plurals.analysis_suggestion_detail, suggestion.count,
                suggestion.count, size(suggestion.bytes))
            binding.suggestionList.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, primary = true).apply { setTypeface(typeface, Typeface.BOLD) })
                    addView(label(detail, primary = false))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialButton(context).apply {
                    text = context.getString(R.string.analysis_preview)
                    isAllCaps = false
                    cornerRadius = dp(20)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.ai_accent))
                    setTextColor(color(R.color.ai_on_accent))
                    minimumHeight = dp(48)
                    setOnClickListener { onPreview(suggestion) }
                })
            })
        }
    }

    // ---- Biggest folders ---------------------------------------------------------------------

    private fun bindFolders(folders: List<FolderSize>, root: String, own: Long) {
        // Ranked bars read on a phone even when one folder holds most of the data; a treemap did not.
        val sorted = folders.filter { it.size > 0 }.sortedByDescending { it.size }
        val top = sorted.take(6)
        val rest = sorted.drop(6)
        binding.folderCard.isVisible(top.isNotEmpty())
        binding.folderList.removeAllViews()
        top.forEachIndexed { i, folder ->
            val name = folder.path.removePrefix("$root/")
            val kind = StorageInsights.folderKind(name)
            val row = meterRow(if (kind == "apps") R.drawable.android_icon_white_24dp else R.drawable.directory_icon_white_24dp,
                if (kind == "hidden") R.color.chart_system else R.color.home_analysis, folderLabel(context, name), folder.size, own)
            binding.folderList.addView(row)
            row.clickable { onOpen(AnalysisPage.Folder(folder.path)) }
            row.rise(200L + 40L * i)
        }
        if (rest.isNotEmpty()) {
            val row = meterRow(R.drawable.directory_icon_white_24dp, R.color.chart_system,
                context.resources.getQuantityString(R.plurals.analysis_treemap_more, rest.size, rest.size),
                rest.sumOf { it.size }, own)
            binding.folderList.addView(row)
            // All folders are one tap away in their own tab.
            row.clickable { onOpen(AnalysisPage.Cleanup("folders")) }
            row.rise(200L + 40L * top.size)
        }
    }


    // ---- Growth ------------------------------------------------------------------------------

    private fun bindGrowth(insights: StorageInsights) {
        val chart = binding.growthChart
        val months = insights.months
        val names = DateFormatSymbols.getInstance(Locale.getDefault()).shortMonths
        val labels = months.map { names[it.first.substring(5).toInt() - 1].take(3) }
        val accent = color(R.color.ai_accent)
        val glow = color(R.color.ai_glow_1)
        val set = BarDataSet(months.mapIndexed { i, (_, bytes) -> BarEntry(i.toFloat(), bytes / 1e6f) }, "").apply {
            barCornerRadius = 7f
            isDrawValuesEnabled = false
            highlightAlpha = 0
            // Older months recede, the current one carries the accent: one hue, change over time.
            fills = months.indices.map { i ->
                if (i == months.lastIndex) Fill(glow, accent)
                else Fill(withAlpha(accent, 140), withAlpha(accent, 40))
            }.toMutableList()
        }
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            isScaleEnabled = false
            isDragEnabled = false
            isDoubleTapToZoomEnabled = false
            isHighlightPerTapEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                isDrawGridLinesEnabled = false
                isDrawAxisLineEnabled = false
                textColor = muted
                textSize = 10f
                granularity = 1f
                labelCount = months.size
                valueFormatter = IndexAxisValueFormatter(labels)
            }
            axisLeft.apply {
                isDrawAxisLineEnabled = false
                gridColor = withAlpha(muted, 40)
                textColor = muted
                textSize = 10f
                axisMinimum = 0f
                labelCount = 4
                valueFormatter = com.github.mikephil.charting.formatter.IAxisValueFormatter { value, _ ->
                    (value * 1e6).toLong().asFileSize().formatHumanReadable(context)
                }
            }
            axisRight.isEnabled = false
            data = BarData(set).apply { barWidth = 0.55f }
            contentDescription = months.zip(labels).joinToString { (m, l) -> "$l ${size(m.second)}" }
        }
        chart.invalidate()
        if (animate) chart.onFirstSight { chart.animateY(1000, Easing.EaseOutCubic) }
        // The average of the full months gives this month something to be compared with.
        binding.growthSubtitle.text = context.getString(R.string.analysis_growth_subtitle,
            size(months.dropLast(1).sumOf { it.second } / (months.size - 1).coerceAtLeast(1)), size(months.last().second))
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    // ---- Motion ------------------------------------------------------------------------------

    private fun View.rise(delay: Long) {
        if (!animate) return
        alpha = 0f
        translationY = 18 * density
        animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(420)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    companion object {
        fun kindLabel(context: Context, kind: String): String = context.getString(
            when (kind) {
                "image" -> R.string.analysis_kind_image
                "audio" -> R.string.analysis_kind_audio
                "document" -> R.string.analysis_kind_document
                "video" -> R.string.analysis_kind_video
                "apps" -> R.string.analysis_kind_apps
                else -> R.string.analysis_kind_other
            }
        )

        /** The name of a cleanup list; the same words on the overview row and as the title of its page. */
        fun cleanupLabel(type: String): Int = when (type) {
            "duplicates" -> R.string.analysis_cleanup_duplicates
            "large" -> R.string.analysis_cleanup_large
            "old" -> R.string.analysis_cleanup_old
            "apks" -> R.string.analysis_cleanup_apks
            "trash" -> R.string.analysis_cleanup_trash
            else -> R.string.analysis_tab_folders
        }

        /** A top-level folder's name, with what it holds when the name alone says little ("Android", ".cache"). */
        fun folderLabel(context: Context, name: String): String = when (StorageInsights.folderKind(name)) {
            "apps" -> context.getString(R.string.analysis_folder_apps, name)
            "hidden" -> context.getString(R.string.analysis_folder_hidden, name)
            else -> name
        }
    }
}
