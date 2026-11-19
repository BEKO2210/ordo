/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.valueCompat
import kotlin.math.sin

/**
 * Ordo Musik's appearance page starts with a picture of the mini player as it will look: a sample cover, title lines,
 * the progress wave and the play button, in the cover's teal when "Colours from the cover" is on, otherwise in the
 * chosen accent; light or dark as the app is. It redraws whenever one of the choices below it changes.
 */
class MusicLookPreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {
    init {
        // A layout of its own: the list must never hand this row's view to another preference.
        layoutResource = R.layout.music_look_preview_preference
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val frame = holder.itemView as ViewGroup
        frame.removeAllViews()
        val height = context.resources.getDimensionPixelSize(R.dimen.phi_89) +
            context.resources.getDimensionPixelSize(R.dimen.phi_21) * 2
        frame.addView(PreviewView(context), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        frame.contentDescription = context.getString(
            if (Settings.MUSIC_COVER_COLORS.valueCompat) R.string.settings_music_preview_cover
            else R.string.settings_music_preview_accent
        )
    }

    private class PreviewView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private val density = resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            val surfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
            val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
            val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
            val isLight = ColorUtils.calculateLuminance(surfaceVariant) > 0.5
            // The sample cover's teal through the same colour roles as the player; the theme's accent otherwise.
            val accent = if (Settings.MUSIC_COVER_COLORS.valueCompat) {
                MaterialColors.getColorRoles(COVER_TEAL, isLight).accent
            } else primary
            val gutter = 21 * density
            val h = height - 2 * gutter
            // As wide as a real mini player gets (610 dp, centred) instead of the whole tablet.
            val inset = ((width - 2 * gutter - MAX_WIDTH_DP * density) / 2).coerceAtLeast(0f)
            canvas.save()
            canvas.translate(inset, 0f)
            val width = width - 2 * inset
            // The mini player card, tinted towards the accent as the real one is.
            paint.shader = null
            paint.color = ColorUtils.blendARGB(surfaceVariant, accent, 0.12f)
            rect.set(gutter, gutter, width - gutter, gutter + h)
            canvas.drawRoundRect(rect, 21 * density, 21 * density, paint)
            // A sample cover: the aurora.
            val cover = h - 26 * density
            rect.set(gutter + 13 * density, gutter + 13 * density, gutter + 13 * density + cover, gutter + 13 * density + cover)
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, COVER_NIGHT, COVER_TEAL,
                Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)
            paint.shader = null
            // Title and artist as calm lines.
            val textLeft = rect.right + 13 * density
            val playR = 21 * density
            val textRight = width - gutter - 13 * density - 2 * playR - 13 * density
            paint.color = onSurface
            rect.set(textLeft, gutter + h * 0.28f, textLeft + (textRight - textLeft) * 0.7f, gutter + h * 0.28f + 8 * density)
            canvas.drawRoundRect(rect, 4 * density, 4 * density, paint)
            paint.color = ColorUtils.setAlphaComponent(onSurface, 0x8a)
            rect.set(textLeft, gutter + h * 0.46f, textLeft + (textRight - textLeft) * 0.45f, gutter + h * 0.46f + 6 * density)
            canvas.drawRoundRect(rect, 3 * density, 3 * density, paint)
            // The progress wave and its track.
            val waveY = gutter + h * 0.74f
            val played = textLeft + (textRight - textLeft) * 0.55f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = ColorUtils.setAlphaComponent(accent, 0x66)
            canvas.drawLine(played + 6 * density, waveY, textRight, waveY, paint)
            paint.color = accent
            path.reset()
            var x = textLeft
            path.moveTo(x, waveY)
            while (x < played) {
                x += density
                path.lineTo(x, waveY + 2.5f * density * sin((x - textLeft) / (13 * density) * Math.PI.toFloat()))
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            // The play button in the accent, its triangle in the role drawn on it.
            val cx = width - gutter - 13 * density - playR
            val cy = gutter + h / 2
            canvas.drawCircle(cx, cy, playR, paint)
            paint.color = MaterialColors.getColorRoles(accent, isLight).onAccent.let {
                if (Settings.MUSIC_COVER_COLORS.valueCompat) it
                else MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
            }
            path.reset()
            path.moveTo(cx - 6 * density, cy - 8 * density)
            path.lineTo(cx + 9 * density, cy)
            path.lineTo(cx - 6 * density, cy + 8 * density)
            path.close()
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    companion object {
        private const val MAX_WIDTH_DP = 610f
        private const val COVER_TEAL = 0xff2ac8a6.toInt()
        private const val COVER_NIGHT = 0xff1c1650.toInt()
    }
}
