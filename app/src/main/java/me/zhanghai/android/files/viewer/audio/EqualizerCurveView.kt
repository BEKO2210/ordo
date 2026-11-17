/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt
import me.zhanghai.android.files.R
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.ui.Motion

/**
 * The equalizer stage: a spline through the ten band gains with a soft fill, a dB grid and the frequencies below.
 * A handle is moved only after holding it for 150 ms and then dragging vertically, so scrolling the sheet never
 * changes the sound by accident. Nothing is allocated while drawing.
 */
class EqualizerCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var gainsDb: List<Float> = List(BANDS) { 0f }
        set(value) {
            val normalized = value.take(BANDS).let { it + List(BANDS - it.size) { 0f } }
            if (normalized == field) return
            field = normalized
            accessibilityHelper.invalidateRoot()
            retarget(animate = !dragArmed)
        }
    var onGainChanged: ((index: Int, gainDb: Float) -> Unit)? = null

    /** Shown in the middle while the equalizer is off or unavailable. */
    var offText: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The colour of the card behind the view: it forms the ring around every handle. */
    var ringColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val handler = Handler(Looper.getMainLooper())
    private var activeBand = -1
    private var dragArmed = false
    private var handleScale = 1f
    private var lastDragGain = 0f
    private var bubbleText = ""
    private val armDrag = Runnable {
        if (activeBand < 0) return@Runnable
        dragArmed = true
        lastDragGain = gainsDb[activeBand]
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        updateBubble()
        animateScale(HANDLE_ACTIVE_SCALE)
    }

    private val shown = FloatArray(BANDS)
    private val from = FloatArray(BANDS)
    private val to = FloatArray(BANDS)
    private val xs = FloatArray(BANDS)
    private val ys = FloatArray(BANDS)
    private val morph = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = MORPH_MS
        interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        addUpdateListener {
            val t = it.animatedValue as Float
            for (i in 0 until BANDS) shown[i] = from[i] + (to[i] - from[i]) * t
            invalidate()
        }
    }
    private val scaleProperty = object : FloatPropertyCompat<EqualizerCurveView>("handleScale") {
        override fun getValue(view: EqualizerCurveView) = view.handleScale
        override fun setValue(view: EqualizerCurveView, value: Float) {
            view.handleScale = value
            view.invalidate()
        }
    }
    private val scaleSpring = SpringAnimation(this, scaleProperty).setSpring(
        SpringForce(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
    )

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(11f) }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val curvePath = Path()
    private val fillPath = Path()
    private val box = RectF()

    private var primary = 0
    private var gridColor = 0
    private var labelColor = 0
    private var bubbleColor = 0
    private var onBubbleColor = 0
    private var onSurfaceColor = 0

    private var gridLeft = 0f
    private var gridRight = 0f
    private var bandLeft = 0f
    private var bandRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f
    private var labelBaseline = 0f

    private val dbLabels = GRID_DB.map {
        when {
            it > 0 -> "+${it.toInt()}"
            it < 0 -> "\u2212${(-it).toInt()}"
            else -> "0"
        }
    }
    private val frequencyLabels = AudioSettings.BAND_FREQUENCIES_HZ.map {
        if (it >= 1_000) "${it / 1_000}k" else it.toString()
    }

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            if (width == 0 || height == 0) INVALID_ID else nearestBand(x)

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            repeat(BANDS) { virtualViewIds += it }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            node.contentDescription = spokenBandLabel(virtualViewId)
            node.stateDescription = context.getString(R.string.audio_sound_db_spoken, gainsDb[virtualViewId])
            node.className = android.widget.SeekBar::class.java.name
            node.isEnabled = isEnabled
            node.isFocusable = true
            node.isScrollable = true
            node.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_FLOAT,
                AudioSettings.MIN_GAIN_DB,
                AudioSettings.MAX_GAIN_DB,
                gainsDb[virtualViewId]
            )
            val label = bandLabel(virtualViewId)
            node.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,
                    context.getString(R.string.audio_sound_decrease, label)
                )
            )
            node.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
                    context.getString(R.string.audio_sound_increase, label)
                )
            )
            val x = bandX(virtualViewId).toInt()
            val y = gainToY(gainsDb[virtualViewId]).toInt()
            val radius = dp(24f).toInt()
            node.setBoundsInParent(Rect(x - radius, y - radius, x + radius, y + radius))
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            if (!isEnabled) return false
            val delta = when (action) {
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> -ACCESSIBILITY_STEP_DB
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> ACCESSIBILITY_STEP_DB
                else -> return false
            }
            setGain(virtualViewId, (gainsDb[virtualViewId] + delta).coerceIn(
                AudioSettings.MIN_GAIN_DB, AudioSettings.MAX_GAIN_DB
            ))
            invalidateVirtualView(virtualViewId)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_SELECTED)
            return true
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
        resolveColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(armDrag)
        morph.cancel()
        scaleSpring.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gridLeft = paddingLeft + dp(34f)
        gridRight = w - paddingRight.toFloat()
        bandLeft = gridLeft + dp(14f)
        bandRight = gridRight - dp(14f)
        plotTop = paddingTop + dp(14f)
        plotBottom = h - paddingBottom - dp(26f)
        labelBaseline = h - paddingBottom - dp(6f)
        rebuildShader()
    }

    private fun resolveColors() {
        primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
        onSurfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        labelColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt()
        )
        gridColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOutlineVariant,
            ColorUtils.setAlphaComponent(labelColor, 0x40)
        )
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        bubbleColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceInverse, onSurfaceColor
        )
        onBubbleColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceInverse, surface
        )
        if (ringColor == 0) ringColor = surface
        rebuildShader()
    }

    private fun rebuildShader() {
        if (plotBottom <= plotTop) return
        fillPaint.shader = LinearGradient(
            0f, plotTop, 0f, plotBottom,
            ColorUtils.setAlphaComponent(primary, FILL_ALPHA), ColorUtils.setAlphaComponent(primary, 0),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val factor = if (isEnabled) 1f else DISABLED_ALPHA
        labelPaint.color = labelColor
        gridPaint.color = gridColor
        labelPaint.textAlign = Paint.Align.RIGHT
        GRID_DB.forEachIndexed { index, gain ->
            val y = gainToY(gain)
            canvas.drawLine(gridLeft, y, gridRight, y, gridPaint)
            canvas.drawText(dbLabels[index], gridLeft - dp(8f), y + dp(4f), labelPaint)
        }
        labelPaint.textAlign = Paint.Align.CENTER
        for (i in 0 until BANDS) {
            canvas.drawText(frequencyLabels[i], bandX(i), labelBaseline, labelPaint)
            xs[i] = bandX(i)
            ys[i] = gainToY(shown[i])
        }
        KlangMath.spline(curvePath, xs, ys, BANDS)
        fillPath.set(curvePath)
        fillPath.lineTo(xs[BANDS - 1], plotBottom)
        fillPath.lineTo(xs[0], plotBottom)
        fillPath.close()
        fillPaint.alpha = (255 * factor).roundToInt()
        canvas.drawPath(fillPath, fillPaint)
        curvePaint.color = primary
        curvePaint.alpha = (255 * factor).roundToInt()
        canvas.drawPath(curvePath, curvePaint)
        handlePaint.color = primary
        handlePaint.alpha = (255 * factor).roundToInt()
        ringPaint.color = ringColor
        for (i in 0 until BANDS) {
            val scale = if (i == activeBand && dragArmed) handleScale else 1f
            canvas.drawCircle(xs[i], ys[i], dp(HANDLE_RING_DP) * scale, ringPaint)
            canvas.drawCircle(xs[i], ys[i], dp(HANDLE_DP) * scale, handlePaint)
        }
        if (dragArmed && activeBand >= 0) drawBubble(canvas, xs[activeBand], ys[activeBand])
        val off = offText
        if (!isEnabled && off != null) drawOffText(canvas, off)
    }

    private fun drawBubble(canvas: Canvas, x: Float, y: Float) {
        val width = bubbleTextPaint.measureText(bubbleText) + dp(20f)
        val height = dp(30f)
        val lift = dp(HANDLE_RING_DP) * HANDLE_ACTIVE_SCALE + dp(8f)
        val left = (x - width / 2).coerceIn(paddingLeft.toFloat(), (this.width - paddingRight - width).coerceAtLeast(0f))
        val above = y - lift - height >= 0f
        val top = if (above) y - lift - height else y + lift
        box.set(left, top, left + width, top + height)
        bubblePaint.color = bubbleColor
        canvas.drawRoundRect(box, height / 2, height / 2, bubblePaint)
        bubbleTextPaint.color = onBubbleColor
        canvas.drawText(bubbleText, box.centerX(), box.centerY() + dp(4.5f), bubbleTextPaint)
    }

    private fun drawOffText(canvas: Canvas, text: String) {
        val width = offPaint.measureText(text) + dp(32f)
        val height = dp(36f)
        val cx = (gridLeft + gridRight) / 2
        val cy = (plotTop + plotBottom) / 2
        box.set(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
        ringPaint.color = ringColor
        canvas.drawRoundRect(box, height / 2, height / 2, ringPaint)
        offPaint.color = onSurfaceColor
        canvas.drawText(text, cx, cy + dp(5f), offPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeBand = nearestBand(event.x)
                dragArmed = false
                handler.postDelayed(armDrag, HOLD_TO_DRAG_MS)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragArmed) updateGain(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragArmed) updateGain(event.y) else performClick()
                finishGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun updateGain(y: Float) {
        if (plotBottom <= plotTop) return
        val gain = KlangMath.gainAtFraction((y - plotTop) / (plotBottom - plotTop))
        if (gain == lastDragGain) return
        if (gain == 0f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastDragGain = gain
        setGain(activeBand, gain)
        updateBubble()
    }

    private fun setGain(index: Int, gain: Float) {
        gainsDb = gainsDb.toMutableList().apply { this[index] = gain }
        onGainChanged?.invoke(index, gain)
    }

    private fun updateBubble() {
        if (activeBand < 0) return
        bubbleText = context.getString(R.string.audio_sound_bubble, gainsDb[activeBand], bandLabel(activeBand))
    }

    private fun finishGesture() {
        handler.removeCallbacks(armDrag)
        parent?.requestDisallowInterceptTouchEvent(false)
        if (dragArmed) animateScale(1f)
        dragArmed = false
        activeBand = -1
        invalidate()
    }

    private fun animateScale(target: Float) {
        if (Motion.any) {
            scaleSpring.animateToFinalPosition(target)
        } else {
            scaleSpring.cancel()
            handleScale = target
            invalidate()
        }
    }

    private fun retarget(animate: Boolean) {
        morph.cancel()
        for (i in 0 until BANDS) {
            from[i] = shown[i]
            to[i] = gainsDb[i]
        }
        if (animate && Motion.any && isLaidOut && width > 0 && isShown) {
            morph.start()
        } else {
            to.copyInto(shown)
            invalidate()
        }
    }

    private fun nearestBand(x: Float): Int {
        if (bandRight <= bandLeft) return 0
        return (((x - bandLeft) / (bandRight - bandLeft)) * (BANDS - 1)).roundToInt().coerceIn(0, BANDS - 1)
    }

    private fun bandX(index: Int): Float = bandLeft + (bandRight - bandLeft) * index / (BANDS - 1)

    private fun gainToY(gain: Float): Float =
        plotTop + (AudioSettings.MAX_GAIN_DB - gain) /
            (AudioSettings.MAX_GAIN_DB - AudioSettings.MIN_GAIN_DB) * (plotBottom - plotTop)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun bandLabel(index: Int): String {
        val frequency = AudioSettings.BAND_FREQUENCIES_HZ[index]
        return if (frequency >= 1_000) {
            context.getString(R.string.audio_frequency_khz, frequency / 1_000)
        } else {
            context.getString(R.string.audio_frequency_hz, frequency)
        }
    }

    private fun spokenBandLabel(index: Int): String {
        val frequency = AudioSettings.BAND_FREQUENCIES_HZ[index]
        return if (frequency >= 1_000) {
            context.getString(R.string.audio_frequency_khz_spoken, frequency / 1_000)
        } else {
            context.getString(R.string.audio_frequency_hz_spoken, frequency)
        }
    }

    companion object {
        private const val BANDS = AudioSettings.BAND_COUNT
        private val GRID_DB = floatArrayOf(12f, 6f, 0f, -6f, -12f)
        private const val HOLD_TO_DRAG_MS = 150L
        private const val MORPH_MS = 320L
        private const val ACCESSIBILITY_STEP_DB = 0.5f
        private const val HANDLE_DP = 9f
        private const val HANDLE_RING_DP = 11f
        private const val HANDLE_ACTIVE_SCALE = 1.3f
        private const val FILL_ALPHA = 0x47
        private const val DISABLED_ALPHA = 0.38f
    }
}
