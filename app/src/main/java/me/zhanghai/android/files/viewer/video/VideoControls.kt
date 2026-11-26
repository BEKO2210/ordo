/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.isVisible
import kotlin.math.hypot
import kotlin.math.max
import me.zhanghai.android.files.ui.Motion

/**
 * The controls over the video: they show on a tap and hide after [HIDE_DELAY_MS] (never while [stayVisible], i.e.
 * paused or with TalkBack on), the lock that hides everything but one "hold to unlock" button, and the tap zones of
 * the picture: a double tap in the left or right [SIDE_ZONE] jumps 10 s (every further quick tap 10 s more), in the
 * middle it plays or pauses. Swipes ([VideoGestures]) go to [gestureHandler]: vertical left brightness, right volume,
 * horizontal seek; a finger held still for [HOLD_MS] plays at double speed until it lifts. A touch that starts inside
 * the system's gesture areas or the status bar strip is never taken as a gesture, so back and home swipes stay the
 * system's ([isInGestureArea]); locked or with TalkBack ([gesturesAllowed] false) there are no swipes either.
 */
@SuppressLint("ClickableViewAccessibility")
internal class VideoControls(
    private val controls: View,
    private val unlockButton: View,
    private val stayVisible: () -> Boolean,
    private val onShownChanged: (Boolean) -> Unit,
    private val onSeekTap: (forward: Boolean, totalSeconds: Int) -> Unit,
    private val onPlayPauseTap: () -> Unit,
    private val onLockedChanged: (Boolean) -> Unit,
    private val gesturesAllowed: () -> Boolean,
    private val gestureHandler: GestureHandler,
    /** Ordo Video's settings: each gesture can be switched off, and the jump has its own size. */
    private val gestureEnabled: (VideoGestures.Mode) -> Boolean = { true },
    private val doubleTapEnabled: () -> Boolean = { true },
    private val holdEnabled: () -> Boolean = { true },
    private val seekStepSeconds: () -> Int = { SEEK_STEP_S }
) {
    interface GestureHandler {
        fun begin(mode: VideoGestures.Mode)
        fun update(mode: VideoGestures.Mode, dx: Float, dy: Float)
        /** [dx] and [dy] of the whole swipe; a seek back near its start is cancelled by the handler. */
        fun end(mode: VideoGestures.Mode, dx: Float, dy: Float)
        fun hold(held: Boolean)
    }

    var isShown = true
        private set
    var isLocked = false
        private set

    /** left, top, right, bottom in px: the system's gesture areas and the status bar strip. */
    var gestureInsets = IntArray(4)

    private val touchSlop = ViewConfiguration.get(controls.context).scaledTouchSlop
    private val gestures = VideoGestures(touchSlop.toFloat())
    private var held = false
    private var touchView: View? = null
    private val hold = Runnable {
        held = true
        tapCancelled = true
        gestures.cancel()
        gestureHandler.hold(true)
    }
    private val hide = Runnable { if (!stayVisible()) setShown(false) }
    private val hideUnlock = Runnable { if (!stayVisible()) fade(unlockButton, false) }
    private val singleTap = Runnable { toggle() }
    private val unlock = Runnable {
        unlockButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setLocked(false)
    }

    private var downX = 0f
    private var downY = 0f
    private var tapCancelled = false
    private var inGestureArea = false
    private var lastTapUpMs = 0L
    private var seriesZone = 0
    private var seriesTaps = 0

    init {
        unlockButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.postDelayed(unlock, UNLOCK_HOLD_MS)
                    view.removeCallbacks(hideUnlock)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.removeCallbacks(unlock)
                    if (isLocked) view.postDelayed(hideUnlock, HIDE_DELAY_MS)
                }
            }
            true
        }
        // TalkBack cannot hold: its double tap (a click) unlocks.
        unlockButton.setOnClickListener { setLocked(false) }
    }

    fun toggle() = setShown(!isShown)

    fun setShown(shown: Boolean) {
        controls.removeCallbacks(hide)
        if (isLocked) {
            showUnlockButton()
            return
        }
        if (shown != isShown) {
            isShown = shown
            fade(controls, shown)
            onShownChanged(shown)
        }
        if (shown) scheduleHide()
    }

    /** A finger is on a control: keep them up until it lifts. */
    fun holdOpen() = controls.removeCallbacks(hide)

    fun scheduleHide() {
        controls.removeCallbacks(hide)
        if (isShown && !isLocked) controls.postDelayed(hide, HIDE_DELAY_MS)
    }

    fun setLocked(locked: Boolean) {
        if (locked == isLocked) return
        isLocked = locked
        onLockedChanged(locked)
        if (locked) {
            controls.removeCallbacks(hide)
            isShown = false
            fade(controls, false)
            onShownChanged(false)
            showUnlockButton()
        } else {
            unlockButton.removeCallbacks(hideUnlock)
            fade(unlockButton, false)
            setShown(true)
        }
    }

    private fun showUnlockButton() {
        fade(unlockButton, true)
        unlockButton.removeCallbacks(hideUnlock)
        unlockButton.postDelayed(hideUnlock, HIDE_DELAY_MS)
    }

    /** Touches on the picture (the root, below every control), in the root's coordinates. */
    fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tapCancelled = false
                held = false
                touchView = view
                // A new contact settles the last tap: it becomes part of a double tap or, if this one is a swipe or a
                // hold, nothing (both actions at once would be worse).
                view.removeCallbacks(singleTap)
                val (left, top, right, bottom) = gestureInsets
                inGestureArea = isInGestureArea(event.x, event.y, view.width, view.height, left, top, right, bottom)
                val allowed = !inGestureArea && !isLocked && gesturesAllowed()
                gestures.down(event.x, event.y, allowed)
                if (allowed && holdEnabled()) view.postDelayed(hold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlop) {
                    tapCancelled = true
                    view.removeCallbacks(hold)
                }
                val before = gestures.mode
                var mode = gestures.move(event.x, event.y, view.width)
                // A swipe switched off in the settings does nothing at all (and is no tap either).
                if (isSwipe(mode) && !gestureEnabled(mode)) {
                    gestures.cancel()
                    mode = VideoGestures.Mode.NONE
                }
                if (mode != before && mode != VideoGestures.Mode.PENDING && mode != VideoGestures.Mode.NONE) {
                    gestureHandler.begin(mode)
                }
                if (isSwipe(mode)) gestureHandler.update(mode, gestures.dx(event.x), gestures.dy(event.y))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(hold)
                val mode = gestures.mode
                gestures.cancel()
                when {
                    held -> {
                        held = false
                        gestureHandler.hold(false)
                    }
                    isSwipe(mode) -> gestureHandler.end(
                        mode, if (event.actionMasked == MotionEvent.ACTION_UP) gestures.dx(event.x) else 0f,
                        gestures.dy(event.y)
                    )
                    event.actionMasked == MotionEvent.ACTION_UP && !tapCancelled -> onTap(view, event.x)
                }
            }
        }
        return true
    }

    /**
     * The screen is destroyed: drops every pending runnable. They hold this (and with it the activity) in the window's
     * queue up to [HIDE_DELAY_MS] after onDestroy; a second player destroyed meanwhile counted three instances
     * (StrictMode InstanceCountViolation, A15, rc1b; heap dump: Message.callback -> VideoControls -> activity).
     */
    fun release() {
        // From every view that posts: one posted before the window was attached waits in that view's own queue.
        for (view in listOfNotNull(controls, unlockButton, touchView)) {
            for (runnable in listOf(hide, hideUnlock, singleTap, unlock, hold)) view.removeCallbacks(runnable)
        }
        controls.animate().cancel() // a fade's end action is not run on cancel
        unlockButton.animate().cancel()
    }

    /** The screen goes away mid-gesture: no 2× left behind, no hold firing later. */
    fun cancelGestures() {
        touchView?.removeCallbacks(hold)
        gestures.cancel()
        if (held) {
            held = false
            gestureHandler.hold(false)
        }
    }

    private fun isSwipe(mode: VideoGestures.Mode) =
        mode == VideoGestures.Mode.BRIGHTNESS || mode == VideoGestures.Mode.VOLUME || mode == VideoGestures.Mode.SEEK

    private fun onTap(view: View, x: Float) {
        if (isLocked || inGestureArea || !doubleTapEnabled()) {
            // No double taps here: a plain tap only shows or hides.
            seriesTaps = 0
            toggle()
            return
        }
        val now = SystemClock.uptimeMillis()
        val zone = tapZone(x, view.width, view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        if (seriesTaps > 0 && now - lastTapUpMs <= DOUBLE_TAP_MS && zone == seriesZone) {
            view.removeCallbacks(singleTap)
            seriesTaps++
            if (zone != 0) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onSeekTap(zone > 0, (seriesTaps - 1) * seekStepSeconds())
            } else if (seriesTaps == 2) {
                onPlayPauseTap()
            }
        } else {
            seriesZone = zone
            seriesTaps = 1
            view.removeCallbacks(singleTap)
            view.postDelayed(singleTap, DOUBLE_TAP_MS)
        }
        lastTapUpMs = now
    }

    private fun fade(view: View, visible: Boolean) {
        view.animate().cancel()
        if (!Motion.any) {
            view.alpha = 1f
            view.isVisible = visible
            return
        }
        if (visible) {
            if (!view.isVisible) view.alpha = 0f
            view.isVisible = true
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        } else if (view.isVisible) {
            view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { view.isVisible = false }.start()
        }
    }

    companion object {
        const val HIDE_DELAY_MS = 4_000L
        const val DOUBLE_TAP_MS = 300L
        const val UNLOCK_HOLD_MS = 600L
        // YouTube's and VLC's "hold for 2×": still for this long (plan P4: 400 ms).
        const val HOLD_MS = 400L
        const val SEEK_STEP_S = 10
        private const val FADE_MS = 200L
        // mpv-android's tap zones: left 28 %, middle, right 28 %.
        internal const val SIDE_ZONE = 0.28f
        // mpv-android's DEADZONE: the top 5 % belong to the status bar even when it is hidden.
        internal const val TOP_DEAD_FRACTION = 0.05f
    }
}

/** -1 back, 1 ahead, 0 middle; mirrored for right-to-left like the seek bar. */
internal fun tapZone(x: Float, width: Int, rtl: Boolean): Int {
    if (width <= 0) return 0
    val fraction = x / width
    val zone = when {
        fraction <= VideoControls.SIDE_ZONE -> -1
        fraction >= 1 - VideoControls.SIDE_ZONE -> 1
        else -> 0
    }
    return if (rtl) -zone else zone
}

/**
 * Whether a touch starting at ([x], [y]) belongs to the system: inside the left, right or bottom gesture insets, or
 * above max(status bar inset, 5 % of the height).
 */
internal fun isInGestureArea(
    x: Float, y: Float, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int
): Boolean =
    x < left || x >= width - right || y >= height - bottom ||
        y < max(top.toFloat(), height * VideoControls.TOP_DEAD_FRACTION)
