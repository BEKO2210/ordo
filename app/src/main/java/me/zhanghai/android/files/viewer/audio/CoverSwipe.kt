/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import me.zhanghai.android.files.ui.Motion

/**
 * Swiping the player's cover sideways changes the song: the cover follows the finger with a little depth (it turns
 * a few degrees and shrinks), and past a third of its width or with a clear fling it leaves and the next one comes
 * in from the other side. Only a clearly horizontal drag counts (twice as far sideways as up or down, past the touch
 * slop), so scrolling the player never changes the song; without a song in that direction the cover only gives a
 * little and springs back.
 */
class CoverSwipe(
    private val view: View,
    private val canGo: (forward: Boolean) -> Boolean,
    private val go: (forward: Boolean) -> Unit
) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val minFling = view.resources.displayMetrics.density * MIN_FLING_DP_PER_S
    private var downX = 0f
    private var downY = 0f
    private var decided = false
    private var dragging = false
    private var tracker: VelocityTracker? = null

    /** Feed every touch of the cover; true while a swipe owns the gesture. */
    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                decided = false
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!decided && hypot(dx, dy) > slop) {
                    decided = true
                    dragging = abs(dx) > 2 * abs(dy)
                    if (dragging) view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val forward = dx < 0
                    // No song that way: it only gives a quarter of the way, as a hint.
                    show(if (canGo(forward)) dx else dx * RESIST)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val velocity = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                if (!dragging) return false
                dragging = false
                val dx = event.rawX - downX
                val forward = dx < 0
                val flung = abs(velocity) > minFling && sign(velocity) == sign(dx)
                val commit = event.actionMasked == MotionEvent.ACTION_UP && canGo(forward) &&
                    (abs(dx) > view.width * COMMIT_FRACTION || flung)
                if (commit) leave(forward) else settle()
                return true
            }
        }
        return dragging
    }

    private fun show(dx: Float) {
        val fraction = (dx / view.width.coerceAtLeast(1)).coerceIn(-1f, 1f)
        view.translationX = dx
        view.rotationY = -fraction * MAX_TURN_DEGREES
        val scale = 1f - abs(fraction) * MAX_SHRINK
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = 1f - abs(fraction) * MAX_FADE
    }

    private fun settle() {
        if (!Motion.any) {
            show(0f)
            return
        }
        view.animate().translationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(EMPHASIZED).setDuration(SETTLE_MS).start()
    }

    private fun leave(forward: Boolean) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        val out = if (forward) -view.width * 1.1f else view.width * 1.1f
        if (!Motion.any) {
            go(forward)
            show(0f)
            return
        }
        view.animate().translationX(out).alpha(0f).setDuration(LEAVE_MS).setInterpolator(EMPHASIZED).withEndAction {
            go(forward)
            // The next cover comes in from the other side.
            show(-out * ENTER_OFFSET)
            view.alpha = 0f
            view.animate().translationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(EMPHASIZED).setDuration(ENTER_MS).start()
        }.start()
    }

    companion object {
        private const val COMMIT_FRACTION = 0.3f
        private const val MIN_FLING_DP_PER_S = 1_000f
        private const val RESIST = 0.25f
        private const val MAX_TURN_DEGREES = 8f
        private const val MAX_SHRINK = 0.08f
        private const val MAX_FADE = 0.35f
        private const val ENTER_OFFSET = 0.4f
        private const val SETTLE_MS = 300L
        private const val LEAVE_MS = 180L
        private const val ENTER_MS = 320L
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
