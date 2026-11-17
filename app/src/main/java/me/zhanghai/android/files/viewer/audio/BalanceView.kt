/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.Motion

/**
 * Two level bars, left and right, under a beam that tilts towards the louder side: moving the balance lowers the
 * other side. Purely a picture with nothing to grab; the − / + below it change the value.
 */
class BalanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    /** -100 (left) to 100 (right). */
    var balance: Int = 0
        set(value) {
            val clamped = value.coerceIn(-100, 100)
            if (clamped == field) return
            field = clamped
            val target = KlangMath.balanceFraction(clamped)
            if (Motion.any && isLaidOut) {
                spring.animateToFinalPosition(target)
            } else {
                spring.cancel()
                fraction = target
                invalidate()
            }
        }

    private var fraction = 0.5f
    private val fractionProperty = object : FloatPropertyCompat<BalanceView>("balanceFraction") {
        override fun getValue(view: BalanceView) = view.fraction
        override fun setValue(view: BalanceView, value: Float) {
            view.fraction = value
            view.invalidate()
        }
    }
    private val spring = SpringAnimation(this, fractionProperty).setSpring(
        SpringForce(0.5f).setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
    )
    private val speaker = ContextCompat.getDrawable(context, R.drawable.audio_speaker_icon_24dp)!!.mutate()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tops = FloatArray(2)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var primary = 0
    private var trackColor = 0
    private var iconColor = 0
    private var ringColor = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        resolveColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    override fun onDetachedFromWindow() {
        spring.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resolveSize(dp(55f).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(resolveSize(dp(200f).roundToInt(), widthMeasureSpec), height)
    }

    private fun resolveColors() {
        primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        trackColor = ColorUtils.setAlphaComponent(onSurface, 0x33)
        iconColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface)
        ringColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
    }

    /** The picture sits on a card: [color] is that card's colour, used for the ring around the dot. */
    fun setBackgroundSurface(color: Int) {
        ringColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = if (isEnabled) 255 else 97
        val icon = dp(24f).roundToInt()
        val top = paddingTop + dp(4f)
        val bottom = height - paddingBottom - dp(2f)
        val cx = width / 2f
        val barWidth = dp(13f)
        val spread = minOf(width / 4f, dp(89f))
        // fraction 0.5 = centre; towards 1 the left side gets quieter, towards 0 the right side.
        val leftLevel = 1f - maxOf(0f, fraction - 0.5f) * 2f * MAX_DROP
        val rightLevel = 1f - maxOf(0f, 0.5f - fraction) * 2f * MAX_DROP
        val span = bottom - top - dp(8f)
        for (index in SIDES.indices) {
            val side = SIDES[index]
            val level = if (index == 0) leftLevel else rightLevel
            val x = cx + side * spread
            trackPaint.color = trackColor
            trackPaint.alpha = alpha * (trackColor ushr 24) / 255
            trackPaint.strokeWidth = barWidth
            canvas.drawLine(x, bottom - barWidth / 2, x, top + dp(8f) + barWidth / 2, trackPaint)
            trackPaint.color = primary
            trackPaint.alpha = alpha
            val barTop = bottom - barWidth / 2 - (span - barWidth) * level
            canvas.drawLine(x, bottom - barWidth / 2, x, barTop, trackPaint)
            tops[index] = barTop
            // Speaker beside its bar, the right one mirrored.
            val iconX = (x + side * (barWidth / 2 + dp(10f)) + (if (side < 0) -icon.toFloat() else 0f)).roundToInt()
            val iconTop = ((top + bottom) / 2 - icon / 2f).roundToInt()
            speaker.setTint(iconColor)
            speaker.alpha = alpha
            canvas.save()
            if (side > 0) canvas.scale(-1f, 1f, iconX + icon / 2f, 0f)
            speaker.setBounds(iconX, iconTop, iconX + icon, iconTop + icon)
            speaker.draw(canvas)
            canvas.restore()
        }
        // The beam over both bars tilts with the balance, on a small pivot in the middle.
        trackPaint.strokeWidth = dp(3f)
        trackPaint.color = primary
        trackPaint.alpha = alpha
        val beamLeft = tops[0] - dp(6f)
        val beamRight = tops[1] - dp(6f)
        canvas.drawLine(cx - spread, beamLeft, cx + spread, beamRight, trackPaint)
        val pivotY = (beamLeft + beamRight) / 2
        ringPaint.color = ringColor
        canvas.drawCircle(cx, pivotY, dp(7f), ringPaint)
        dotPaint.color = primary
        dotPaint.alpha = alpha
        canvas.drawCircle(cx, pivotY, dp(5f), dotPaint)
        trackPaint.strokeWidth = dp(4f)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        val SIDES = floatArrayOf(-1f, 1f)
        /** At full left or right the other bar drops to a fifth: the side is quieter, not silent in the picture. */
        const val MAX_DROP = 0.8f
    }
}
