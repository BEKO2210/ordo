/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.text.TextPaint
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton

enum class ActionRowMode { SIDE_BY_SIDE, SIDE_BY_SIDE_WITHOUT_ICONS, STACKED }

/** Label of one button: its text width at the smallest size, the room around it and the icon with its gap. */
class ActionLabel(val textWidth: Float, val chrome: Float, val icon: Float)

/** Smallest text size first; if a word still does not fit beside the other button, drop the icons, then stack. */
fun chooseActionRowMode(labels: List<ActionLabel>, available: Float): ActionRowMode {
    fun fits(withIcon: Boolean) = labels.all {
        it.textWidth <= available - it.chrome - (if (withIcon) it.icon else 0f)
    }
    return when {
        fits(true) -> ActionRowMode.SIDE_BY_SIDE
        fits(false) -> ActionRowMode.SIDE_BY_SIDE_WITHOUT_ICONS
        else -> ActionRowMode.STACKED
    }
}

/** Lays out the visible [buttons] of [row] by [chooseActionRowMode]; [icons] are the buttons' own icons, same order. */
fun fitActionRow(row: LinearLayout, buttons: List<MaterialButton>, icons: List<android.graphics.drawable.Drawable?>) {
    val visible = buttons.filter { it.visibility != android.view.View.GONE }
    if (visible.isEmpty() || row.width == 0) return
    val gap = row.resources.getDimensionPixelSize(me.zhanghai.android.files.R.dimen.phi_8)
    val inner = row.width - row.paddingStart - row.paddingEnd
    val available = (inner - gap * (visible.size - 1)).toFloat() / visible.size
    val paint = TextPaint(visible[0].paint).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, row.resources.displayMetrics)
    }
    val labels = visible.map {
        ActionLabel(
            paint.measureText(it.text.toString()), (it.paddingStart + it.paddingEnd).toFloat(),
            (it.iconSize + it.iconPadding).toFloat()
        )
    }
    val mode = chooseActionRowMode(labels, available)
    val stacked = mode == ActionRowMode.STACKED
    row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    var first = true
    buttons.forEachIndexed { index, button ->
        button.icon = if (mode == ActionRowMode.SIDE_BY_SIDE_WITHOUT_ICONS) null else icons[index]
        val isFirstVisible = first && button in visible
        if (button in visible) first = false
        val last = button === visible.last()
        button.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
            weight = if (stacked) 0f else 1f
            marginEnd = if (!stacked && button in visible && !last) gap else 0
            topMargin = if (stacked && button in visible && !isFirstVisible) gap else 0
        }
    }
}
