/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.WindowInsets
import androidx.annotation.AttrRes
import androidx.core.view.GravityCompat
import androidx.core.view.children
import androidx.drawerlayout.widget.DrawerLayout
import com.drakeet.drawer.FullDraggableContainer

class IgnoreFitsSystemWindowsFullyDraggableDrawerContentLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0
) : FullDraggableContainer(context, attrs, defStyleAttr) {
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets = insets

    override fun smoothOpenDrawer(gravity: Int) {
        settleDrawer(gravity, true)
    }

    override fun smoothCloseDrawer(gravity: Int) {
        settleDrawer(gravity, false)
    }

    // Ordo: a drawer dragged all the way has nothing left to slide. DrawerLayout then starts no animation and never
    // marks it as open or closed, so a tap beside it and the back key stopped working. Without animation it does.
    private fun settleDrawer(gravity: Int, open: Boolean) {
        val drawerLayout = parent as DrawerLayout
        val drawer = drawerLayout.children.first {
            val drawerGravity = (it.layoutParams as DrawerLayout.LayoutParams).gravity
            GravityCompat.getAbsoluteGravity(drawerGravity, drawerLayout.layoutDirection) and
                Gravity.HORIZONTAL_GRAVITY_MASK == gravity
        }
        val animate = drawerHasToSlide(drawer.left, drawer.right, drawerLayout.width, open)
        if (open) drawerLayout.openDrawer(drawer, animate) else drawerLayout.closeDrawer(drawer, animate)
    }
}

/** Whether a drawer between [left] and [right] in a parent of [parentWidth] still has a way to go. */
internal fun drawerHasToSlide(left: Int, right: Int, parentWidth: Int, open: Boolean): Boolean =
    if (open) left < 0 || right > parentWidth else right > 0 && left < parentWidth
