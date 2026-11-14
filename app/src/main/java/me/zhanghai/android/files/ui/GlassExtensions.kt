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

// Maximum radius and a lighter tint: clear glass rather than a grey film.
private const val GLASS_BLUR_RADIUS = 25f
private const val GLASS_OVERLAY_ALPHA = 0x99

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
