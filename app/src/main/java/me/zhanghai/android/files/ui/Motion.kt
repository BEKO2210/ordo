/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * One switch for all of Ordo's motion. The system setting "remove animations" always wins; on top of it the
 * user picks full (everything), subtle (transitions only, no decorative loops) or off.
 */
object Motion {
    /** Transitions, reveals, counting numbers. */
    val any: Boolean
        // areAnimatorsEnabled() exists from Android 8; before that the system switch is not readable here.
        get() = (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) &&
            Settings.MOTION.valueCompat != "off"

    /** Decoration on top: particles, ripples, light sweeps, the moving aurora backdrop. */
    val full: Boolean
        get() = any && Settings.MOTION.valueCompat == "full"
}

/**
 * Runs [action] once any part of this view is on screen. An entrance animation below the fold (a storage bar, a chart)
 * would otherwise be over before anyone scrolls there. A newer call replaces a pending one.
 */
fun View.onFirstSight(action: () -> Unit) {
    (getTag(R.id.on_first_sight) as? FirstSight)?.cancel()
    FirstSight(this, action).also { setTag(R.id.on_first_sight, it) }.check()
}

private class FirstSight(private val view: View, private val action: () -> Unit) :
    ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
    private val rect = Rect()
    private var done = false

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    override fun onViewAttachedToWindow(v: View) {
        v.viewTreeObserver.addOnScrollChangedListener(this)
        v.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onViewDetachedFromWindow(v: View) {
        val observer = v.viewTreeObserver
        // A dead observer throws on removal; its listeners went with it.
        if (!observer.isAlive) return
        observer.removeOnScrollChangedListener(this)
        observer.removeOnGlobalLayoutListener(this)
    }

    override fun onScrollChanged() = check()

    override fun onGlobalLayout() = check()

    fun check() {
        if (done || !view.isAttachedToWindow || !view.isShown || view.height == 0 || !view.getGlobalVisibleRect(rect)) {
            return
        }
        cancel()
        action()
    }

    fun cancel() {
        done = true
        view.removeOnAttachStateChangeListener(this)
        // ViewTreeObserver keeps these listeners in copy-on-write lists, so removing them while it dispatches is safe.
        onViewDetachedFromWindow(view)
    }
}
