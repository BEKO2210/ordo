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
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.Fill
import com.github.mikephil.charting.utils.MPPointF
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
    /** What is picked in the charts; kept by the caller, so it survives a rotation. */
    private val chart: ChartState,
    /** The picks in [chart] changed, e.g. so "back" can drop them first. */
    private val onChartChanged: () -> Unit,
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

    private lateinit var insights: StorageInsights
    private var root = ""
    private var parts: List<AnalysisChart.Part> = emptyList()
    private val kindRows = HashMap<String, View>()
    private var mapItems: List<TreemapView.Item> = emptyList()

    /** [fromScan] is true when the scan view is about to turn into the ring, which then needs no entrance of its own. */
    fun bind(insights: StorageInsights, folders: List<FolderSize>, root: String, fromScan: Boolean = false) {
        this.insights = insights
        this.root = root
        arrange(folders.count { it.size > 0 })
        val cards = listOf(
            binding.usageCard to { bindUsage(insights) }, binding.filesCard to { bindFiles(fromScan) },
            binding.cleanupCard to { bindCleanup(insights) }, binding.suggestionCard to { bindSuggestions(insights) },
            binding.folderCard to { bindFolders(folders, root, insights.own) },
            binding.growthCard to { bindGrowth(insights) }
        )
        val pass = ++passes
        if (!animate) return cards.forEach { it.second() }
        // One card per frame after the first two: all six at once is a frame the eye can see, just when the scan's
        // ring takes off. The first two go together because that ring needs to know where it lands.
        cards.forEach { it.first.alpha = 0f }
        fun step(index: Int) {
            if (pass != passes || index >= cards.size) return
            val (card, fill) = cards[index]
            fill()
            if (card.visibility == View.VISIBLE) card.rise(60L * index)
            if (index == 0) step(1) else binding.root.postOnAnimation { step(index + 1) }
        }
        step(0)
    }

    // A newer bind takes over from one that is still filling its cards.
    private var passes = 0

    /**
     * Wide screens use their whole width like the home screen: two columns of cards, 16 dp to the edge and 8 dp
     * between. Where a card is wide enough, its chart stands next to its rows instead of above them.
     */
    private fun arrange(folderCount: Int) {
        val width = context.resources.displayMetrics.widthPixels / density
        if (width < WIDE_DP) return
        binding.columnB.isVisible(true)
        val beside = (width - 2 * 16 - 8) / 2 - 2 * 20 >= BESIDE_DP
        // Roughly how tall each column gets, in dp, to give the last card to the shorter one.
        val kinds = insights.usage.count { it.first != StorageInsights.KIND_SYSTEM && it.second > 0 }
        val rows = minOf(folderCount, 7)
        val map = insights.tiles.size > 1
        val left = 300 + 60 + if (beside) maxOf(280, kinds * 56) else 268 + kinds * 56
        val right = 110 + insights.cleanup.count { it.second > 0 } * 48 +
            (if (insights.suggestions.isEmpty()) 0 else 60 + insights.suggestions.size * 64) +
            60 + if (beside) maxOf(if (map) 360 else 0, rows * 56) else (if (map) 284 else 0) + rows * 56
        val second = listOf(binding.cleanupCard, binding.suggestionCard, binding.folderCard) +
            listOfNotNull(binding.growthCard.takeIf { right < left })
        for (card in second) {
            if (card.parent === binding.columnB) continue
            (card.parent as ViewGroup).removeView(card)
            binding.columnB.addView(card)
        }
        if (right >= left && binding.growthCard.parent !== binding.columnA) {
            binding.columnB.removeView(binding.growthCard)
            binding.columnA.addView(binding.growthCard)
        }
        for (card in listOf(binding.usageCard, binding.filesCard, binding.cleanupCard, binding.suggestionCard,
            binding.folderCard, binding.growthCard)) {
            (card.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = dp(8)
        }
        binding.filesChart.layoutParams = if (beside) {
            binding.filesBody.orientation = LinearLayout.HORIZONTAL
            binding.filesList.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(20) }
            LinearLayout.LayoutParams(dp(280), dp(280))
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)).apply { topMargin = dp(8) }
        }
        if (beside) {
            binding.folderBody.orientation = LinearLayout.HORIZONTAL
            binding.folderMapBlock.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            binding.folderMap.layoutParams.height = dp(300)
            binding.folderList.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(20); topMargin = dp(4) }
        }
    }

    private fun kindLabel(kind: String) = kindLabel(context, kind)


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

    private fun bindFiles(fromScan: Boolean) {
        val own = insights.own
        val kinds = insights.usage.filter { it.first != StorageInsights.KIND_SYSTEM && it.second > 0 }
        binding.filesCard.isVisible(kinds.isNotEmpty())
        binding.filesList.removeAllViews()
        kindRows.clear()
        // The rows are the ring's legend: same colour, the name and the real numbers, largest first.
        kinds.sortedByDescending { it.second }.forEachIndexed { i, (kind, bytes) ->
            val row = meterRow(kindIcon(kind), kindColor(kind),
                kindLabel(kind), bytes, own)
            binding.filesList.addView(row)
            row.clickable { onOpen(AnalysisPage.Kind(kind)) }
            row.rise(200L + 40L * i)
            kindRows[kind] = row
        }
        binding.filesChart.onSelect = { slice ->
            chart.kind = slice?.key
            showPick()
            onChartChanged()
        }
        binding.filesChart.onOpen = { slice ->
            if (slice.key == AnalysisChart.OTHERS) drill(true) else onOpen(AnalysisPage.Kind(slice.key))
        }
        binding.filesChart.onCenterClick = { if (chart.drilled) drill(false) }
        showRing(entrance = !fromScan)
        // The scan's ring is on its way here; this one waits for it.
        if (fromScan) binding.filesChart.await()
    }

    // The merged small types open up into a ring of their own, and the centre leads back.
    private fun drill(into: Boolean) {
        chart.drilled = into
        chart.kind = null
        showRing(entrance = true)
        onChartChanged()
    }

    private fun showRing(entrance: Boolean) {
        val kinds = insights.usage.filter { it.first != StorageInsights.KIND_SYSTEM }
        val all = AnalysisChart.slices(kinds)
        val small = all.firstOrNull { it.key == AnalysisChart.OTHERS }
        if (small == null) chart.drilled = false
        parts = if (chart.drilled) AnalysisChart.slices(kinds.filter { it.first in small!!.members }, minShare = 0.0) else all
        if (parts.none { it.key == chart.kind }) chart.kind = null
        binding.filesChart.setSlices(parts.map { part ->
            DonutView.Slice(part.key, part.value,
                (part.members.ifEmpty { listOf(part.key) }).map { color(kindColor(it)) }.toIntArray(),
                "${partLabel(part)}, ${size(part.value)}, ${StorageInsights.share(part.value, insights.own)}")
        }, entrance)
        binding.filesChart.select(chart.kind)
        showPick()
    }

    private fun partLabel(part: AnalysisChart.Part) =
        if (part.key == AnalysisChart.OTHERS) context.resources.getQuantityString(R.plurals.analysis_chart_small_types,
            part.members.size, part.members.size)
        else kindLabel(part.key)

    // The centre names what is picked, and its rows stay bright while the others step back.
    private fun showPick() {
        val part = parts.firstOrNull { it.key == chart.kind }
        val share = part?.let { StorageInsights.share(it.value, insights.own) }
        binding.filesChart.setCenter(when {
            part != null -> DonutView.Center(partLabel(part), size(part.value), "$share · " + context.getString(
                if (part.key == AnalysisChart.OTHERS) R.string.analysis_chart_show_each else R.string.analysis_view) + " ›")
            chart.drilled -> DonutView.Center(
                context.resources.getQuantityString(R.plurals.analysis_chart_small_types, parts.size, parts.size),
                size(parts.sumOf { it.value }), "‹ " + context.getString(R.string.analysis_chart_all_types))
            else -> DonutView.Center(context.resources.getQuantityString(R.plurals.analysis_file_count,
                insights.fileCount, insights.fileCount), size(insights.own))
        })
        val bright = when {
            part != null -> part.members.ifEmpty { listOf(part.key) }
            chart.drilled -> parts.map { it.key }
            else -> null
        }
        for ((kind, row) in kindRows) {
            row.animate().cancel()
            row.translationY = 0f   // the cancel may stop a rise halfway
            val alpha = if (bright == null || kind in bright) 1f else 0.4f
            if (animate && row.isShown) row.animate().alpha(alpha).setStartDelay(0).setDuration(160).start() else row.alpha = alpha
        }
    }

    /**
     * The ring as the scan view's slots: degrees and colours per file type in [StorageInsights.KINDS] order, then
     * the merged small types. With this the scan's ring can end as exactly this one.
     */
    fun ringSlots(): Pair<FloatArray, Array<IntArray>> {
        val keys = StorageInsights.KINDS + AnalysisChart.OTHERS
        val sweeps = FloatArray(keys.size)
        val colors = Array(keys.size) { intArrayOf(color(kindColor(keys[it]))) }
        parts.forEachIndexed { i, part ->
            val slot = keys.indexOf(part.key)
            sweeps[slot] = binding.filesChart.drawnSweeps[i]
            colors[slot] = binding.filesChart.drawnColors[i]
        }
        return sweeps to colors
    }

    /** Drops what is picked in the charts, the newest pick first; false when there was nothing. */
    fun clearPick(): Boolean {
        when {
            chart.tile != null -> {
                chart.tile = null
                binding.folderMap.select(null)
                showTile()
            }
            chart.kind != null -> {
                chart.kind = null
                binding.filesChart.select(null)
                showPick()
            }
            chart.drilled -> drill(false)
            else -> return false
        }
        onChartChanged()
        return true
    }

    // ---- Cleanup -----------------------------------------------------------------------------

    private fun bindCleanup(insights: StorageInsights) {
        val colors = mapOf("duplicates" to R.color.chart_cleanup_duplicates, "apks" to R.color.chart_cleanup_apks,
            "thumbnails" to R.color.chart_cleanup_thumbnails, "trash" to R.color.chart_cleanup_trash)
        // Empty folders free no space: their row counts them instead.
        val empty = StorageInsights.EMPTY_FOLDERS
        val items = insights.cleanup.filter { it.second > 0 || it.first == empty && insights.emptyFolders > 0 }
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
            val value = if (type == empty) context.resources.getQuantityString(R.plurals.analysis_folder_count,
                insights.emptyFolders, insights.emptyFolders) else size(bytes)
            val hint = if (type == "thumbnails") context.getString(R.string.analysis_cleanup_thumbnails_hint) else null
            val row = legendRow(colors[type]?.let(::color), name, value, "", hint)
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
            // All folders are one tap away on their own page.
            row.clickable { onOpen(AnalysisPage.Cleanup("folders")) }
            row.rise(200L + 40L * top.size)
        }
        bindMap(own)
    }

    // The map goes deeper than the rows: it shows where inside the big folders the space is.
    private fun bindMap(own: Long) {
        val tiles = insights.tiles
        // One tile alone is no picture; the rows say it better.
        val show = tiles.size > 1
        binding.folderMap.isVisible(show)
        binding.folderPick.isVisible(show)
        if (!show) {
            chart.tile = null
            return
        }
        val areas = AnalysisChart.extents(tiles.map { it.size }, 1f, AnalysisChart.MIN_TILE_AREA)
        mapItems = tiles.mapIndexed { i, tile ->
            val share = StorageInsights.share(tile.size, own)
            TreemapView.Item(tile.path, tileLabel(tile, short = true), size(tile.size), areas[i],
                tile.size.toFloat() / own.coerceAtLeast(1), "${tileLabel(tile, short = false)}, ${size(tile.size)}, $share")
        }
        if (mapItems.none { it.key == chart.tile }) chart.tile = null
        binding.folderMap.setItems(mapItems, entrance = true)
        binding.folderMap.onSelect = { item ->
            chart.tile = item?.key
            showTile()
            onChartChanged()
        }
        binding.folderMap.onOpen = { openTile(it.key) }
        binding.folderMap.select(chart.tile)
        showTile()
    }

    /** A tile's name: the folder alone on the tile ([short]), the way to it below the map and for TalkBack. */
    private fun tileLabel(tile: AnalysisChart.Tile, short: Boolean): String {
        if (tile.path.isEmpty()) {
            return context.resources.getQuantityString(R.plurals.analysis_treemap_more, tile.count, tile.count)
        }
        val path = tile.path.removePrefix(root).trim('/')
        if (path.isEmpty()) return context.getString(R.string.analysis_tile_loose)
        val name = if ('/' in path) path.substringAfterLast('/') else folderLabel(context, path)
        val label = if (short) name else path.replace("/", " › ")
        return if (tile.direct) context.getString(R.string.analysis_tile_direct, label) else label
    }

    private fun openTile(key: String) =
        onOpen(if (key.isEmpty()) AnalysisPage.Cleanup("folders") else AnalysisPage.Folder(key))

    // Below the map: what the marked tile is, as a row that opens it; a hint while nothing is marked.
    private fun showTile() {
        val index = mapItems.indexOfFirst { it.key == chart.tile }
        binding.folderPick.removeAllViews()
        if (index < 0) {
            binding.folderPick.addView(label(context.getString(R.string.analysis_map_hint), primary = false).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            })
            return
        }
        val tile = insights.tiles[index]
        val row = legendRow(color(R.color.ai_accent), tileLabel(tile, short = false), size(tile.size),
            StorageInsights.share(tile.size, insights.own))
        binding.folderPick.addView(row)
        row.clickable { openTile(tile.path) }
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
            // A tapped month dims a little under its marker.
            highlightAlpha = 70
            highlightColor = text
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
            isHighlightPerTapEnabled = true
            isHighlightPerDragEnabled = false
            marker = GrowthMarker(context) { index ->
                val (year, month) = months[index].first.split("-")
                "${DateFormatSymbols.getInstance(Locale.getDefault()).months[month.toInt() - 1]} $year · ${size(months[index].second)}"
            }.also { it.chartView = this }
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

    /** A month's name and size above the bar that was tapped. */
    private class GrowthMarker(context: Context, private val text: (Int) -> String) :
        MarkerView(context, R.layout.analysis_growth_marker) {
        private val label = findViewById<TextView>(R.id.markerText)

        override fun refreshContent(e: Entry<*>, highlight: Highlight) {
            label.text = text(e.x.toInt())
            super.refreshContent(e, highlight)
        }

        // Centred over the bar, a little above its top.
        override var offset: MPPointF
            get() = MPPointF(-width / 2f, -height - 6 * resources.displayMetrics.density)
            set(_) {}
    }

    companion object {
        /** From this width in dp on, the overview has two columns. */
        const val WIDE_DP = 840

        /** A chart stands next to its rows when the inside of its card is at least this wide, in dp. */
        const val BESIDE_DP = 560

        /** The icon of a file type: the one of the matching home category, so a type has one sign everywhere. */
        fun kindIcon(kind: String): Int = when (kind) {
            "image" -> HomeCategory.IMAGES
            "audio" -> HomeCategory.AUDIO
            "document" -> HomeCategory.DOCUMENTS
            "video" -> HomeCategory.VIDEOS
            "apps" -> HomeCategory.APKS
            else -> null
        }?.iconRes ?: R.drawable.file_icon_white_24dp

        /** A type's icon in its colour on a tinted circle, for the top bar of its page. */
        fun kindBadge(context: Context, kind: String): android.graphics.drawable.Drawable {
            val density = context.resources.displayMetrics.density
            val color = ContextCompat.getColor(context, kindColor(kind))
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x26))
            }
            val icon = ContextCompat.getDrawable(context, kindIcon(kind))!!.mutate().apply { setTint(color) }
            val pad = (8 * density).toInt()
            return android.graphics.drawable.LayerDrawable(arrayOf(circle, icon)).apply {
                setLayerInset(1, pad, pad, pad, pad)
                setLayerSize(0, (36 * density).toInt(), (36 * density).toInt())
                setLayerSize(1, (20 * density).toInt(), (20 * density).toInt())
            }
        }

        /** The colour of a file type in the ring, its legend rows and the scan view. */
        fun kindColor(kind: String): Int = when (kind) {
            "image" -> R.color.chart_kind_image
            "audio" -> R.color.chart_kind_audio
            "document" -> R.color.chart_kind_document
            "video" -> R.color.chart_kind_video
            "apps" -> R.color.chart_kind_apps
            else -> R.color.chart_kind_other
        }

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
            "thumbnails" -> R.string.analysis_cleanup_thumbnails
            StorageInsights.EMPTY_FOLDERS -> R.string.analysis_cleanup_empty_folders
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
