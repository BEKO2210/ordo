/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.onFirstSight

/** A row of the settings overview: white pictogram on a coloured circle, title, summary and a chevron. */
class SettingsCategoryPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {
    private val circle: GradientDrawable
    private var iconView: ImageView? = null
    private var entrancePlayed = false

    init {
        layoutResource = R.layout.settings_category_preference
        val (start, end) = context.obtainStyledAttributes(attrs, R.styleable.SettingsCategoryPreference).use {
            val start = it.getColor(R.styleable.SettingsCategoryPreference_ordoCircleColor, Color.GRAY)
            // Without an end colour, a slightly deeper shade gives the circle some depth.
            start to it.getColor(R.styleable.SettingsCategoryPreference_ordoCircleColorEnd,
                ColorUtils.blendARGB(start, Color.BLACK, 0.18f))
        }
        circle = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            shape = GradientDrawable.OVAL
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val image = (holder.findViewById(android.R.id.icon) as ImageView).also { iconView = it }
        val drawable = image.drawable?.constantState?.newDrawable()?.mutate() ?: image.drawable?.mutate()
        image.setImageDrawable(drawable)
        image.background = circle
        image.imageTintList = ColorStateList.valueOf(Color.WHITE)
        if (!Motion.any) {
            entrancePlayed = true
            return
        }
        if (!entrancePlayed) {
            image.onFirstSight {
                if (entrancePlayed) return@onFirstSight
                entrancePlayed = true
                val delay = ROW_STAGGER_MS * (ROW_INDEX[key.orEmpty()] ?: 0)
                if (delay == 0L) {
                    playPictogram(image)
                } else {
                    image.postDelayed({
                        if (image.isAttachedToWindow) playPictogram(image)
                    }, delay)
                }
            }
        }
    }

    override fun performClick() {
        iconView?.let { playPictogram(it) }
        super.performClick()
    }

    private fun playPictogram(image: ImageView) {
        if (!Motion.any) return
        when (val d = image.drawable) {
            is AnimatedVectorDrawable -> {
                d.stop()
                d.reset()
                d.start()
            }
            is Animatable -> {
                d.stop()
                d.start()
            }
        }
    }

    companion object {
        private const val ROW_STAGGER_MS = 40L

        /** Top-to-bottom order on the main settings screen. */
        private val ROW_INDEX = mapOf(
            "section_appearance" to 0,
            "section_ai" to 1,
            "section_launcher_icons" to 2,
            "section_files" to 3,
            "section_storage" to 4,
            "section_trash" to 5,
            "link_backup" to 6,
            "link_ftp_server" to 7,
            "link_legal" to 8,
            "link_about" to 9
        )
    }
}
