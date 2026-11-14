/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.animation.ValueAnimator
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.Fill
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AnalysisOverviewBinding
import me.zhanghai.android.files.file.asFileSize
import java.text.DateFormatSymbols
import java.util.Locale

/** Draws [StorageInsights]: fixed layout, real numbers only, motion that settles quickly. */
class AnalysisDashboard(
    private val binding: AnalysisOverviewBinding,
    private val onViewCleanup: (String) -> Unit,
    private val onOpenFolder: (String) -> Unit,
    private val onPreview: (StorageInsights.Suggestion) -> Unit
) {
    private val context: Context = binding.root.context
    private val density = context.resources.displayMetrics.density
    private val text = MaterialColors.getColor(binding.root, android.R.attr.textColorPrimary)
    private val muted = MaterialColors.getColor(binding.root, android.R.attr.textColorSecondary)
    private val animate = ValueAnimator.areAnimatorsEnabled()

    private fun color(res: Int) = ContextCompat.getColor(context, res)
    private fun size(bytes: Long) = bytes.asFileSize().formatHumanReadable(context)

    fun bind(insights: StorageInsights, folders: List<FolderSize>, root: String) {
        bindUsage(insights)
        bindCleanup(insights)
        bindSuggestions(insights)
        bindTreemap(folders, root)
        bindGrowth(insights)
        listOf(binding.usageCard, binding.cleanupCard, binding.suggestionCard, binding.treemapCard, binding.growthCard)
            .filter { it.visibility == View.VISIBLE }
            .forEachIndexed { i, card -> card.rise(70L * i) }
    }

    private fun kindLabel(kind: String) = context.getString(
        when (kind) {
            "image" -> R.string.analysis_kind_image
            "audio" -> R.string.analysis_kind_audio
            "document" -> R.string.analysis_kind_document
            "video" -> R.string.analysis_kind_video
            "apps" -> R.string.analysis_kind_apps
            "other" -> R.string.analysis_kind_other
            "system" -> R.string.analysis_kind_system
            else -> R.string.analysis_kind_free
        }
    )

    private fun kindColor(kind: String) = color(
        when (kind) {
            "image" -> R.color.chart_image
            "audio" -> R.color.chart_audio
            "document" -> R.color.chart_document
            "video" -> R.color.chart_video
            "apps" -> R.color.chart_apps
            "other" -> R.color.chart_other
            "system" -> R.color.chart_system
            else -> R.color.chart_track
        }
    )

    // ---- Usage donut -------------------------------------------------------------------------

    private fun bindUsage(insights: StorageInsights) {
        val chart = binding.usageChart
        val parts = (insights.usage + ("free" to insights.free)).filter { it.second > 0 }
        val total = insights.total.coerceAtLeast(1)
        val set = PieDataSet(parts.map { (kind, bytes) -> PieEntry(bytes.toFloat(), kindLabel(kind)) }, "").apply {
            colors = parts.map { kindColor(it.first) }.toMutableList()
            sliceSpace = 2.5f
            isDrawValuesEnabled = false
            selectionShift = 6f
        }
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            isDrawEntryLabelsEnabled = false
            isRotationEnabled = false
            isDrawRoundedSlicesEnabled = true
            holeRadius = 74f
            transparentCircleRadius = 0f
            holeColor = android.graphics.Color.TRANSPARENT
            data = PieData(set)
            contentDescription = parts.joinToString { "${kindLabel(it.first)} ${size(it.second)}" }
        }
        val percent = (insights.used * 100 / total).toInt()
        countUp(percent) { value ->
            chart.centerText = center("$value %", context.getString(R.string.analysis_usage_center_label))
            chart.invalidate()
        }
        if (animate) chart.animateY(1100, Easing.EaseOutCubic) else chart.invalidate()
        binding.usageSubtitle.text = context.getString(R.string.analysis_usage_subtitle, size(insights.used), size(insights.total))

        // Legend with name, size and share: identity never rests on colour alone.
        binding.usageLegend.removeAllViews()
        parts.forEachIndexed { i, (kind, bytes) ->
            binding.usageLegend.addView(legendRow(kindColor(kind), kindLabel(kind), size(bytes),
                "${(bytes * 100 / total).coerceAtLeast(if (bytes > 0) 1 else 0)} %").apply { rise(250L + 45L * i) })
        }
    }

    private fun center(value: String, label: String): SpannableString =
        SpannableString("$value\n$label").apply {
            setSpan(RelativeSizeSpan(2.4f), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(text), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(muted), value.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun legendRow(dot: Int, name: String, value: String, share: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (36 * density).toInt()
            contentDescription = "$name, $value, $share"
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
            }, LinearLayout.LayoutParams((10 * density).toInt(), (10 * density).toInt()).apply {
                marginEnd = (12 * density).toInt()
            })
            addView(label(name, primary = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(value, primary = true))
            addView(label(share, primary = false).apply {
                gravity = Gravity.END
                minWidth = (52 * density).toInt()
            })
        }

    private fun label(value: String, primary: Boolean) = TextView(context).apply {
        text = value
        TextViewCompat.setTextAppearance(
            this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
        )
        setTextColor(if (primary) this@AnalysisDashboard.text else muted)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---- Cleanup -----------------------------------------------------------------------------

    private fun bindCleanup(insights: StorageInsights) {
        val colors = mapOf("duplicates" to R.color.chart_image, "large" to R.color.chart_video,
            "old" to R.color.chart_audio, "apks" to R.color.chart_apps, "trash" to R.color.chart_document)
        val names = mapOf("duplicates" to R.string.analysis_cleanup_duplicates, "large" to R.string.analysis_cleanup_large,
            "old" to R.string.analysis_cleanup_old, "apks" to R.string.analysis_cleanup_apks,
            "trash" to R.string.analysis_cleanup_trash)
        val items = insights.cleanup.filter { it.second > 0 }
        binding.cleanupHeadline.text = if (items.isEmpty()) {
            context.getString(R.string.analysis_cleanup_none)
        } else {
            context.getString(R.string.analysis_cleanup_headline, size(insights.cleanupTotal))
        }
        // The bar shows each list's own size against the headline; overlaps make it a guide, not a sum.
        binding.cleanupBar.isVisible(items.isNotEmpty())
        binding.cleanupBar.setParts(items.map { color(colors.getValue(it.first)) to it.second },
            items.sumOf { it.second })
        binding.cleanupList.removeAllViews()
        items.forEachIndexed { i, (type, bytes) ->
            val row = legendRow(color(colors.getValue(type)), context.getString(names.getValue(type)), size(bytes), "")
            (row as LinearLayout).removeViewAt(row.childCount - 1)
            row.addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = context.getString(R.string.analysis_view)
                isAllCaps = false
                setTextColor(color(R.color.ai_accent))
                minimumHeight = (48 * density).toInt()
                setOnClickListener { onViewCleanup(type) }
            })
            binding.cleanupList.addView(row.apply { rise(300L + 50L * i) })
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
                setPadding(0, (10 * density).toInt(), 0, 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, primary = true).apply { setTypeface(typeface, Typeface.BOLD) })
                    addView(label(detail, primary = false))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialButton(context).apply {
                    text = context.getString(R.string.analysis_preview)
                    isAllCaps = false
                    cornerRadius = (20 * density).toInt()
                    backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.ai_accent))
                    setTextColor(color(R.color.ai_on_accent))
                    minimumHeight = (48 * density).toInt()
                    setOnClickListener { onPreview(suggestion) }
                })
            })
        }
    }

    // ---- Treemap -----------------------------------------------------------------------------

    private fun bindTreemap(folders: List<FolderSize>, root: String) {
        val top = folders.sortedByDescending { it.size }.take(9)
        val rest = folders.drop(9).sumOf { it.size }
        val items = top.map { TreemapView.Item(it.path.removePrefix("$root/"), size(it.size), it.size, it.path) } +
            listOfNotNull(rest.takeIf { it > 0 }?.let { TreemapView.Item("…", size(it), it, null) })
        binding.treemapCard.isVisible(items.isNotEmpty())
        binding.treemap.setItems(items)
        binding.treemap.onItemClick = { item -> (item.key as? String)?.let(onOpenFolder) }
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
        if (animate) chart.animateY(1000, Easing.EaseOutCubic) else chart.invalidate()
        binding.growthSubtitle.text = context.getString(R.string.analysis_growth_subtitle, size(months.last().second))
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    // ---- Motion ------------------------------------------------------------------------------

    private fun countUp(target: Int, update: (Int) -> Unit) {
        if (!animate) return update(target)
        ValueAnimator.ofInt(0, target).apply {
            duration = 1100
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { update(it.animatedValue as Int) }
        }.start()
    }

    private fun View.rise(delay: Long) {
        if (!animate) return
        alpha = 0f
        translationY = 18 * density
        animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(420)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }
}
