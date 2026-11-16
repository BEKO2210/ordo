/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.getColorByAttr
import com.google.android.material.R as MaterialR

/** The black night mode switch with a 24 dp icon: a moon on a dark-grey disc that turns pure black with the switch. */
class BlackNightModePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchPreferenceCompat(context, attrs) {
    private val disc = BlackDiscDrawable(context)

    init {
        // A preference without an icon hides the icon view.
        icon = disc
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.icon) as? ImageView)?.setImageDrawable(disc)
        disc.setBlack(isChecked, Motion.any)
    }

    private class BlackDiscDrawable(context: Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val ring = ColorUtils.setAlphaComponent(context.getColorByAttr(MaterialR.attr.colorOutline), 0x99)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        private val moon = Path().apply {
            val outer = Path().apply { addCircle(0f, 0f, 1f, Path.Direction.CW) }
            val cut = Path().apply { addCircle(0.62f, -0.5f, 0.82f, Path.Direction.CW) }
            op(outer, cut, Path.Op.DIFFERENCE)
        }

        // 0 is the dark-grey disc, 1 the pure black one. The colours depict the real ones, so they are not themed.
        private var amount = -1f
        private var animator: ValueAnimator? = null

        fun setBlack(black: Boolean, animate: Boolean) {
            val target = if (black) 1f else 0f
            if (amount == target) return
            animator?.cancel()
            if (!animate || amount < 0f) {
                amount = target
                invalidateSelf()
                return
            }
            animator = ValueAnimator.ofFloat(amount, target).apply {
                duration = 150
                addUpdateListener {
                    amount = it.animatedValue as Float
                    invalidateSelf()
                }
                start()
            }
        }

        override fun getIntrinsicWidth(): Int = (24 * density).toInt()

        override fun getIntrinsicHeight(): Int = (24 * density).toInt()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val radius = b.width() * 0.42f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            fill.color = ColorUtils.blendARGB(GREY, Color.BLACK, amount.coerceAtLeast(0f))
            canvas.drawCircle(cx, cy, radius, fill)
            stroke.color = ring
            stroke.strokeWidth = density
            canvas.drawCircle(cx, cy, radius, stroke)
            fill.color = MOON
            canvas.save()
            canvas.translate(cx - radius * 0.08f, cy + radius * 0.05f)
            canvas.scale(radius * 0.52f, radius * 0.52f)
            canvas.drawPath(moon, fill)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            const val GREY = 0xFF3C4043.toInt()
            const val MOON = 0xFFE8EAED.toInt()
        }
    }
}
