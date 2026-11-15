/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.app.Activity
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import kotlin.math.roundToInt

// Maximum radius and a lighter tint: clear glass rather than a grey film.
private const val GLASS_BLUR_RADIUS = 25f
private const val GLASS_OVERLAY_ALPHA = 0x99

/**
 * Overlay alpha of a glass bar: solid while nothing has scrolled under it ([amount] 0), so the bar is exactly the
 * page and shows neither blur grain nor a seam; glass once content is under it (1).
 */
fun glassOverlayAlpha(amount: Float): Int =
    (0xFF + (GLASS_OVERLAY_ALPHA - 0xFF) * amount.coerceIn(0f, 1f)).roundToInt()

/** Fades this bar between the solid [color] and glass, see [glassOverlayAlpha]. */
fun BlurView.setGlassAmount(@ColorInt color: Int, amount: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setOverlayColor(ColorUtils.setAlphaComponent(color, glassOverlayAlpha(amount)))
    }
}

/** Blurs [target] behind this bar on Android 12+; older versions get the solid [color]. */
fun BlurView.setUpGlass(target: BlurTarget, @ColorInt color: Int, activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // BlurView draws its background over the blur.
        background = null
        setupWith(target)
            .setFrameClearDrawable(activity.window.decorView.background)
            .setBlurRadius(GLASS_BLUR_RADIUS)
            .setOverlayColor(ColorUtils.setAlphaComponent(color, GLASS_OVERLAY_ALPHA))
    } else {
        setBackgroundColor(color)
    }
}
