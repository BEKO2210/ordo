/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.KlangSheetBinding
import me.zhanghai.android.files.ui.MasonryLayout
import me.zhanghai.android.files.ui.Motion

/**
 * The frame Ordo Video's sheets share with Ordo Musik's Klang sheet: the handle, the header (accent symbol, title,
 * close), an info line, then cards with the Klang colours, spacing and type; on wide sheets a [MasonryLayout] puts the
 * cards side by side (two columns once each gets 320 dp, as in the Klang sheet). Rows are 55 dp, every target 48 dp,
 * words never break, and without motion the sheet appears without its window animation.
 */
class KlangSheet(val context: Context, @DrawableRes icon: Int, title: CharSequence) {
    val dialog = BottomSheetDialog(context)
    private val binding = KlangSheetBinding.inflate(LayoutInflater.from(context))
    /** Where the cards go. */
    val body: LinearLayout get() = binding.body
    private val res = context.resources
    private val cardColor: Int
    private val outlineColor: Int

    init {
        binding.icon.setImageResource(icon)
        binding.sheetTitle.text = title
        binding.close.setOnClickListener { dialog.dismiss() }
        val view = binding.root
        val surface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        val onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        cardColor = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorSurfaceContainerHigh, MaterialColors.layer(surface, onSurface, 0.08f)
        )
        outlineColor = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOutlineVariant, MaterialColors.layer(cardColor, onSurface, 0.16f)
        )
    }

    constructor(context: Context, @DrawableRes icon: Int, title: Int) : this(context, icon, context.getString(title))

    /** The line under the title (null hides it). */
    fun info(text: CharSequence?) {
        binding.info.text = text
        binding.info.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Cards side by side on wide sheets; add cards with [card] (parent = this grid). */
    fun grid(parent: ViewGroup = body): MasonryLayout = MasonryLayout(context).apply {
        val half = res.getDimensionPixelSize(R.dimen.klang_card_gap_half)
        columnCount = columns()
        parent.addView(this, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(-half, -half, -half, -half) })
    }

    /** Columns for [grid]: as the Klang sheet's lower cards ([KlangMath.columns]). */
    fun columns(): Int {
        val density = res.displayMetrics.density
        val sheetDp = minOf(
            res.configuration.screenWidthDp.toFloat(), res.getDimension(R.dimen.klang_sheet_max_width) / density
        )
        val contentDp = sheetDp - 2 * res.getDimension(R.dimen.klang_space_lg) / density
        return KlangMath.columns(
            contentDp, res.getDimension(R.dimen.klang_card_gap) / density,
            res.getDimension(R.dimen.klang_column_min_width) / density
        )
    }

    /**
     * A Klang card with its header row (symbol, title, optional explanation) and returns the column inside it, where
     * rows and choices go. In a [grid] it carries the grid's half gap; in the body the cards stand 13 dp apart.
     */
    fun card(
        @DrawableRes icon: Int,
        title: CharSequence?,
        summary: CharSequence? = null,
        parent: ViewGroup = body
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lg = res.getDimensionPixelSize(R.dimen.klang_space_lg)
            val md = res.getDimensionPixelSize(R.dimen.klang_space_md)
            setPadding(lg, md, lg, md)
        }
        if (title != null) column.addView(headerRow(icon, title, summary))
        val card = MaterialCardView(context, null, 0).apply {
            setCardBackgroundColor(cardColor)
            cardElevation = 0f
            radius = res.getDimension(R.dimen.klang_space_lg)
            strokeColor = outlineColor
            strokeWidth = (res.displayMetrics.density).toInt().coerceAtLeast(1)
            stateListAnimator = null
            addView(column)
        }
        val params = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (parent is MasonryLayout) {
            val half = res.getDimensionPixelSize(R.dimen.klang_card_gap_half)
            params.setMargins(half, half, half, half)
            parent.addView(card, params)
        } else {
            if (parent.childCount > 0) params.topMargin = res.getDimensionPixelSize(R.dimen.klang_space_md)
            parent.addView(card, LinearLayout.LayoutParams(params))
        }
        return column
    }

    /** A section heading in the accent colour with its symbol, as the Klang sheet's "Effects" and "Playback". */
    fun section(parent: ViewGroup, @DrawableRes icon: Int, title: CharSequence): TextView =
        TextView(context, null, 0, R.style.Widget_Ordo_Klang_Section).apply {
            text = title
            gravity = Gravity.CENTER_VERTICAL
            minHeight = res.getDimensionPixelSize(R.dimen.klang_touch_target)
            compoundDrawablePadding = res.getDimensionPixelSize(R.dimen.klang_space_sm)
            setCompoundDrawablesRelativeWithIntrinsicBounds(AppCompatResources.getDrawable(context, icon)?.mutate(), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(primary())
            ViewCompat.setAccessibilityHeading(this, true)
            parent.addView(this)
        }

    private fun headerRow(@DrawableRes icon: Int, title: CharSequence, summary: CharSequence?): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.klang_row_min_height)
            addView(symbol(icon))
            addView(texts(title, summary, heading = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun symbol(@DrawableRes icon: Int) = symbol(AppCompatResources.getDrawable(context, icon))

    private fun symbol(icon: Drawable?) = ImageView(context).apply {
        setImageDrawable(icon?.mutate())
        scaleType = ImageView.ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(primary())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(
            res.getDimensionPixelSize(R.dimen.klang_icon_column), res.getDimensionPixelSize(R.dimen.klang_icon_size)
        )
    }

    private fun texts(title: CharSequence, summary: CharSequence?, heading: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val sm = res.getDimensionPixelSize(R.dimen.klang_space_sm)
        setPaddingRelative(sm, 0, sm, 0)
        addView(TextView(context, null, 0, R.style.Widget_Ordo_Klang_Title).apply {
            text = title
            if (heading) ViewCompat.setAccessibilityHeading(this, true)
        })
        // Ids, not positions, find the summary later (a live state line, as the signal path's "Measurements").
        if (!summary.isNullOrEmpty()) addView(info(summary).apply { id = android.R.id.summary })
    }

    /** An explanation line in Klang's info style. */
    fun info(text: CharSequence, parent: ViewGroup? = null): TextView =
        TextView(context, null, 0, R.style.Widget_Ordo_Klang_Info).apply {
            this.text = text
            parent?.addView(this)
        }

    /**
     * A tappable 55 dp row (null [onClick]: only focusable): symbol (0 keeps the column empty, so texts stay on one
     * edge), title, optional line under it and an optional view at the end (a check, a switch, a time).
     */
    fun row(
        parent: ViewGroup,
        @DrawableRes icon: Int,
        title: CharSequence,
        summary: CharSequence? = null,
        end: View? = null,
        onClick: (() -> Unit)?
    ): LinearLayout =
        row(parent, if (icon != 0) AppCompatResources.getDrawable(context, icon) else null, title, summary, end, onClick)

    /** [row] with the symbol as a drawable (a menu item's); null keeps the column empty. */
    fun row(
        parent: ViewGroup,
        icon: Drawable?,
        title: CharSequence,
        summary: CharSequence?,
        end: View?,
        onClick: (() -> Unit)?
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = res.getDimensionPixelSize(R.dimen.klang_row_min_height)
        if (onClick != null) {
            setBackgroundResource(selectableBackground())
            setOnClickListener { onClick() }
        } else {
            isFocusable = true
        }
        addView(if (icon != null) symbol(icon) else View(context).apply {
            layoutParams = LinearLayout.LayoutParams(res.getDimensionPixelSize(R.dimen.klang_icon_column), 1)
        })
        addView(texts(title, summary, heading = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        end?.let { addView(it) }
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The accent check at the end of the chosen row. */
    fun check(shown: Boolean): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.check_icon_control_normal_24dp)
        imageTintList = ColorStateList.valueOf(primary())
        visibility = if (shown) View.VISIBLE else View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = res.getDimensionPixelSize(R.dimen.klang_touch_target)
        layoutParams = LinearLayout.LayoutParams(size, size)
        scaleType = ImageView.ScaleType.CENTER
    }

    /**
     * One choice in equal-width segments, rows balanced as in the Klang sheet ("three and two", [rowSizes]); the
     * checked one is [checked]. Returns the buttons so the caller can re-check them.
     */
    fun segments(
        parent: ViewGroup,
        labels: List<CharSequence>,
        checked: Int,
        perRow: Int,
        tall: Boolean = false,
        onPick: (Int) -> Unit
    ): List<MaterialButton> {
        val buttons = ArrayList<MaterialButton>(labels.size)
        val gap = res.getDimensionPixelSize(R.dimen.klang_space_xs)
        var index = 0
        for ((rowIndex, size) in rowSizes(labels.size, perRow).withIndex()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(size) { cell ->
                val position = index++
                val button = inflate(if (tall) R.layout.klang_segment_tall else R.layout.klang_segment, row).apply {
                    text = labels[position]
                    isCheckable = true
                    isChecked = position == checked
                    setOnClickListener {
                        buttons.forEachIndexed { i, other -> other.isChecked = i == position }
                        onPick(position)
                    }
                }
                buttons += button
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (cell > 0) marginStart = gap
                })
            }
            parent.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (rowIndex == 0) res.getDimensionPixelSize(R.dimen.klang_space_sm) else gap })
        }
        return buttons
    }

    /** A Klang text button (sentence case, 48 dp), e.g. "Reset" or "Load subtitles…". */
    fun textButton(parent: ViewGroup, text: CharSequence, @DrawableRes icon: Int = 0, onClick: () -> Unit): MaterialButton =
        inflate(R.layout.klang_text_button, parent).apply {
            this.text = text
            if (icon != 0) setIconResource(icon)
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = res.getDimensionPixelSize(R.dimen.klang_space_xs) })
        }

    private fun inflate(layout: Int, parent: ViewGroup): MaterialButton =
        LayoutInflater.from(context).inflate(layout, parent, false) as MaterialButton

    fun primary(): Int = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())

    private fun selectableBackground(): Int = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId

    fun show() {
        // The player screen owns the sheet: it goes when the screen does.
        (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = dialog.dismiss()
            }
        )
        if (!Motion.any) dialog.window?.setWindowAnimations(0)
        dialog.setContentView(binding.root)
        dialog.showExpanded()
    }

    companion object {
        /** [count] choices in rows of at most [perRow], balanced: 5 by 3 is 3 + 2, 7 by 4 is 4 + 3, 6 by 4 is 3 + 3. */
        fun rowSizes(count: Int, perRow: Int): List<Int> {
            if (count <= 0) return emptyList()
            val rows = (count + perRow - 1) / perRow.coerceAtLeast(1)
            return List(rows) { count / rows + if (it < count % rows) 1 else 0 }
        }
    }
}

/**
 * Opens a sheet fully and readable everywhere: expanded at once (no peek, the W90 in landscape showed only 60 px
 * under the navigation bar), at most 90 % of the window high with its own scrolling, above the navigation bar, and
 * on wide windows centred at the Klang sheet's width.
 */
internal fun BottomSheetDialog.showExpanded() {
    setOnShowListener {
        val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
        val metrics = context.resources.displayMetrics
        val width = minOf(metrics.widthPixels, context.resources.getDimensionPixelSize(R.dimen.klang_sheet_max_width))
        sheet.layoutParams = sheet.layoutParams.apply { this.width = width }
        (sheet.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER_HORIZONTAL
        behavior.maxWidth = width
        behavior.maxHeight = (metrics.heightPixels * 0.9f).toInt()
        behavior.skipCollapsed = true
        behavior.isFitToContents = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        // The last row and the button stay above the navigation bar (gesture strip or three buttons).
        val content = (sheet as? ViewGroup)?.getChildAt(0) ?: return@setOnShowListener
        val basePadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
    show()
}
