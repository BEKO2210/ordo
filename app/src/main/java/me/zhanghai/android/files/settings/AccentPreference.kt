/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.colorpicker.ColorPreviewView
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.ParcelableState
import me.zhanghai.android.files.util.getColorByAttr

/** Ordo's accent colours as five round swatches with a live miniature of the resulting theme. */
class AccentPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {
    private val values = context.resources.getStringArray(R.array.pref_entry_values_accent)
    private val names = context.resources.getStringArray(R.array.settings_accent_entries)
    private val colors = intArrayOf(
        R.color.accent_aurora_primary, R.color.accent_blue_primary, R.color.accent_mint_primary,
        R.color.accent_violet_primary, R.color.accent_amber_primary
    )
    private val boundSwatches = mutableListOf<BoundSwatch>()
    private var boundRoot: View? = null
    private var boundPreview: ColorPreviewView? = null
    private var pendingValue: String? = null
    private var pendingSaveUptime = 0L
    private var storageRevealPlayed = false
    private val savePending = Runnable { commitPendingValue() }
    private val rootAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            commitPendingValue()
            cancelBoundAnimations()
        }
    }

    init {
        layoutResource = R.layout.accent_preference
        isSelectable = false
    }

    private val selected: String
        get() = getPersistedString(context.getString(R.string.pref_default_value_accent))

    override fun onAttached() {
        super.onAttached()
        summaryProvider = SummaryProvider<AccentPreference> {
            names[values.indexOf(selected).coerceAtLeast(0)]
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        boundRoot?.removeOnAttachStateChangeListener(rootAttachListener)
        cancelBoundAnimations()

        val root = holder.itemView
        boundRoot = root
        root.addOnAttachStateChangeListener(rootAttachListener)

        // Preference greys the title of rows that are not selectable; this one is, through its swatches.
        context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use { it.getColorStateList(0) }
            ?.let { (holder.findViewById(android.R.id.title) as TextView).setTextColor(it) }

        val visualValue = pendingValue ?: selected
        val preview = holder.findViewById(R.id.accent_preview) as ColorPreviewView
        boundPreview = preview
        val previewColor = if (isEnabled) {
            colorForValue(visualValue)
        } else {
            // The active theme already contains the wallpaper-derived accent.
            context.getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
        }
        preview.setAccent(previewColor, false)
        preview.alpha = if (isEnabled) 1f else DISABLED_ALPHA
        preview.revealStorage(!storageRevealPlayed) { storageRevealPlayed = true }

        val row = holder.findViewById(R.id.swatches) as LinearLayout
        row.removeAllViews()
        val cell = context.resources.getDimensionPixelSize(R.dimen.phi_55)
        val ringColor = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
        )
        values.forEachIndexed { index, value ->
            val chosen = value == visualValue
            val swatch = AccentSwatchView(context, ContextCompat.getColor(context, colors[index]), ringColor).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(cell, cell, Gravity.CENTER)
                setSelectedImmediately(chosen)
            }
            val touchTarget = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(cell, cell)
                addView(swatch)
                contentDescription = names[index]
                isSelected = chosen
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = RadioButton::class.java.name
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                })
                isEnabled = this@AccentPreference.isEnabled
                alpha = if (this@AccentPreference.isEnabled) 1f else DISABLED_ALPHA
                background = TypedValue().let {
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                    ContextCompat.getDrawable(context, it.resourceId)
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> animatePress(view, true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(view, false)
                    }
                    false
                }
                setOnClickListener { choose(value, this) }
            }
            row.addView(touchTarget)
            boundSwatches += BoundSwatch(value, touchTarget, swatch)
        }
        schedulePendingSave()
    }

    private fun choose(value: String, hapticView: View) {
        val currentValue = pendingValue ?: selected
        if (value == currentValue || !callChangeListener(value)) return

        hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        pendingValue = value
        pendingSaveUptime = SystemClock.uptimeMillis() + SAVE_DELAY_MS
        boundPreview?.setAccent(colorForValue(value))
        boundSwatches.forEach { bound ->
            val chosen = bound.value == value
            if (bound.target.isSelected != chosen) {
                bound.target.isSelected = chosen
                bound.swatch.setSelected(chosen, Motion.any)
            }
        }

        schedulePendingSave()
    }

    private fun schedulePendingSave() {
        if (pendingValue == null) return
        boundRoot?.removeCallbacks(savePending)
        val remaining = pendingSaveUptime - SystemClock.uptimeMillis()
        if (!Motion.any || remaining <= 0L) {
            commitPendingValue()
        } else {
            // The preview is the longest transition at 200 ms; save only after it and both swatches settle.
            boundRoot?.postDelayed(savePending, remaining)
        }
    }

    private fun commitPendingValue() {
        boundRoot?.removeCallbacks(savePending)
        val value = pendingValue ?: return
        pendingValue = null
        pendingSaveUptime = 0L
        persistString(value)
        notifyChanged()
    }

    private fun cancelBoundAnimations() {
        boundRoot?.removeCallbacks(savePending)
        boundSwatches.forEach {
            it.target.animate().cancel()
            it.target.scaleX = 1f
            it.target.scaleY = 1f
            it.swatch.cancelAnimation()
        }
        boundSwatches.clear()
        boundPreview = null
    }

    @ColorInt
    private fun colorForValue(value: String): Int {
        val index = values.indexOf(value).coerceAtLeast(0)
        return ContextCompat.getColor(context, colors[index])
    }

    override fun onSaveInstanceState(): Parcelable? {
        commitPendingValue()
        return State(super.onSaveInstanceState(), storageRevealPlayed)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is State) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        storageRevealPlayed = state.storageRevealPlayed
    }

    override fun onDetached() {
        commitPendingValue()
        boundRoot?.removeOnAttachStateChangeListener(rootAttachListener)
        cancelBoundAnimations()
        boundRoot = null
        super.onDetached()
    }

    private data class BoundSwatch(
        val value: String,
        val target: View,
        val swatch: AccentSwatchView
    )

    @Parcelize
    private class State(val superState: Parcelable?, val storageRevealPlayed: Boolean) : ParcelableState

    companion object {
        private const val SAVE_DELAY_MS = 200L
        private const val DISABLED_ALPHA = 0.38f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

        private fun animatePress(view: View, pressed: Boolean) {
            view.animate().cancel()
            if (!Motion.any) {
                view.scaleX = 1f
                view.scaleY = 1f
                return
            }
            view.animate()
                .scaleX(if (pressed) 0.96f else 1f)
                .scaleY(if (pressed) 0.96f else 1f)
                .setDuration(if (pressed) 70 else 120)
                .setInterpolator(if (pressed) EMPHASIZED else AccelerateInterpolator())
                .start()
        }
    }
}

/** Draws selection separately from the colour so the ring and check can follow their own short choreography. */
internal class AccentSwatchView(
    context: Context,
    @ColorInt private val color: Int,
    @ColorInt private val ringColor: Int,
    private val radiusDp: Float = 17f,
    // A thin edge for a swatch that would melt into the page (graphite on the dark theme).
    @ColorInt private val outlineColor: Int? = null
) : View(context) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_DP * density
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = RING_DP * density
    }
    private val checkPath = Path()
    private val drawnCheck = Path()
    private val pathMeasure = PathMeasure()
    private var ringAmount = 0f
    private var checkAmount = 0f
    private var animator: ValueAnimator? = null

    fun setSelected(selected: Boolean, animate: Boolean) {
        animator?.cancel()
        if (!animate) {
            setSelectedImmediately(selected)
            return
        }
        val startRing = ringAmount
        val startCheck = checkAmount
        val end = if (selected) 1f else 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (selected) 160 else 100
            interpolator = if (selected) EMPHASIZED else AccelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                ringAmount = lerp(startRing, end, fraction)
                checkAmount = lerp(startCheck, end, fraction)
                invalidate()
            }
            start()
        }
    }

    fun setSelectedImmediately(selected: Boolean) {
        animator?.cancel()
        animator = null
        ringAmount = if (selected) 1f else 0f
        checkAmount = ringAmount
        invalidate()
    }

    fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = radiusDp * density
        fillPaint.color = color
        canvas.drawCircle(cx, cy, radius, fillPaint)
        if (outlineColor != null) {
            fillPaint.color = outlineColor
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 2 * density
            canvas.drawCircle(cx, cy, radius - density, fillPaint)
            fillPaint.style = Paint.Style.FILL
        }

        if (ringAmount > 0f) {
            ringPaint.color = ringColor
            ringPaint.alpha = (255 * ringAmount).toInt()
            val ringRadius = radius + (RING_GAP_DP + RING_DP / 2f + 1.5f * (1f - ringAmount)) * density
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }
        if (checkAmount <= 0f) return
        checkPaint.color = if (ColorUtils.calculateContrast(Color.WHITE, color) >= 3.0) {
            Color.WHITE
        } else {
            Color.BLACK
        }
        checkPaint.alpha = (255 * checkAmount).toInt()
        checkPath.reset()
        checkPath.moveTo(cx - 7f * density, cy)
        checkPath.lineTo(cx - 2f * density, cy + 5f * density)
        checkPath.lineTo(cx + 8f * density, cy - 6f * density)
        pathMeasure.setPath(checkPath, false)
        drawnCheck.reset()
        pathMeasure.getSegment(0f, pathMeasure.length * checkAmount, drawnCheck, true)
        canvas.drawPath(drawnCheck, checkPaint)
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    companion object {
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

        // φ scale: a 34 dp circle in a 55 dp cell, a 3 dp ring 3 dp away from it (reaches 24.5 dp, cell edge is 27.5).
        private const val RING_DP = 3f
        private const val RING_GAP_DP = 3f

        private fun lerp(start: Float, end: Float, fraction: Float): Float =
            start + (end - start) * fraction
    }
}
