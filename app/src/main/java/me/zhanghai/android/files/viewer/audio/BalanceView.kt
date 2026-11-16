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

/** Left speaker, right speaker and a dot between them that moves with the balance. Purely a picture. */
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
        val height = resolveSize(dp(48f).roundToInt(), heightMeasureSpec)
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
        val cy = height / 2f
        val top = (cy - icon / 2f).roundToInt()
        speaker.setTint(iconColor)
        speaker.alpha = alpha
        speaker.setBounds(paddingLeft, top, paddingLeft + icon, top + icon)
        speaker.draw(canvas)
        val rightEdge = width - paddingRight
        canvas.save()
        canvas.scale(-1f, 1f, rightEdge - icon / 2f, cy)
        speaker.setBounds(rightEdge - icon, top, rightEdge, top + icon)
        speaker.draw(canvas)
        canvas.restore()

        val left = paddingLeft + icon + dp(14f)
        val right = rightEdge - icon - dp(14f)
        val centerX = (left + right) / 2
        val dotX = left + (right - left) * fraction
        trackPaint.color = trackColor
        trackPaint.alpha = alpha * (trackColor ushr 24) / 255
        canvas.drawLine(left, cy, right, cy, trackPaint)
        trackPaint.color = primary
        trackPaint.alpha = alpha
        canvas.drawLine(centerX, cy, dotX, cy, trackPaint)
        ringPaint.color = ringColor
        canvas.drawCircle(dotX, cy, dp(11f), ringPaint)
        dotPaint.color = primary
        dotPaint.alpha = alpha
        canvas.drawCircle(dotX, cy, dp(8f), dotPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
