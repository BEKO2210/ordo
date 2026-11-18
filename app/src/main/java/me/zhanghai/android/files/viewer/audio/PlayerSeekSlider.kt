/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.android.material.slider.WavySlider
import kotlin.math.hypot

/**
 * The player's wavy seek bar, with Ordo's house rule against sliders that move by accident: a tap or a scroll never
 * seeks. Only a finger held still for [HOLD_MS] grabs it (haptic tick, the time bubble appears at the finger), and from
 * then on it drags until the finger lifts. Accessibility actions and keys still step it as usual.
 */
class PlayerSeekSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle
) : WavySlider(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var waiting = false
    private var grabbed = false

    private val grab = Runnable {
        if (!waiting) return@Runnable
        waiting = false
        grabbed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // The slider waits for a touch-slop move inside a scrolling parent; a synthetic press just beside the finger
        // and a move onto it start the drag at once, with the value under the finger.
        val now = SystemClock.uptimeMillis()
        val startX = if (lastX - 2 * touchSlop >= 0) lastX - 2 * touchSlop else lastX + 2 * touchSlop
        forward(now, MotionEvent.ACTION_DOWN, startX)
        forward(now, MotionEvent.ACTION_MOVE, lastX)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                grabbed = false
                waiting = true
                postDelayed(grab, HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (grabbed) return super.onTouchEvent(event)
                // Moving before the hold is over is a scroll or a swipe, never a seek.
                if (waiting && hypot(event.x - downX, event.y - downY) > touchSlop) cancelHold()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                if (!grabbed) return true
                grabbed = false
                // A finger that never moved after the grab leaves where it was grabbed; that was deliberate too.
                return super.onTouchEvent(event)
            }
        }
        return if (grabbed) super.onTouchEvent(event) else true
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        super.onDetachedFromWindow()
    }

    private fun cancelHold() {
        waiting = false
        removeCallbacks(grab)
    }

    private fun forward(time: Long, action: Int, x: Float) {
        val event = MotionEvent.obtain(time, time, action, x, lastY, 0)
        super.onTouchEvent(event)
        event.recycle()
    }

    /** Whether a finger currently holds the bar (the player then stops moving it with the music). */
    val isGrabbed: Boolean
        get() = grabbed

    companion object {
        const val HOLD_MS = 150L
    }
}
