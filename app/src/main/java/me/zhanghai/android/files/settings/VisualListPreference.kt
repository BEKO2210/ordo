/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.ChoicePictogramDrawable.Kind
import me.zhanghai.android.files.ui.Motion

/**
 * A [ListPreference] whose dialog shows a pictogram per option (see [VisualListPreferenceDialogFragment]). The night
 * mode row also carries a 24 dp icon of the current value that cross-fades when the value changes.
 */
class VisualListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ListPreference(context, attrs) {
    enum class Style { NIGHT_MODE, MOTION, HOME_HEADER, AI_SPEED, TRASH_RETENTION, FILE_NAME_ELLIPSIZE, APK_ACTION }

    val style: Style = when (key) {
        context.getString(R.string.pref_key_night_mode) -> Style.NIGHT_MODE
        context.getString(R.string.pref_key_motion) -> Style.MOTION
        context.getString(R.string.pref_key_ai_speed) -> Style.AI_SPEED
        context.getString(R.string.pref_key_trash_retention_days) -> Style.TRASH_RETENTION
        context.getString(R.string.pref_key_file_name_ellipsize) -> Style.FILE_NAME_ELLIPSIZE
        context.getString(R.string.pref_key_open_apk_default_action) -> Style.APK_ACTION
        else -> Style.HOME_HEADER
    }

    private var shownKind: Kind? = null

    init {
        // A preference without an icon hides the icon view; the real icon is set when the row is bound.
        if (style == Style.NIGHT_MODE) icon = ChoicePictogramDrawable(context, Kind.NIGHT_OFF, true)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        if (style != Style.NIGHT_MODE) return
        val view = holder.findViewById(android.R.id.icon) as? ImageView ?: return
        val kind = kindFor(style, value ?: context.getString(R.string.pref_default_value_night_mode), true)
        val previous = shownKind
        shownKind = kind
        val drawable: Drawable = if (previous != null && previous != kind && Motion.any) {
            TransitionDrawable(
                arrayOf(ChoicePictogramDrawable(context, previous, true), ChoicePictogramDrawable(context, kind, true))
            ).apply {
                isCrossFadeEnabled = true
                startTransition(CROSS_FADE)
            }
        } else {
            ChoicePictogramDrawable(context, kind, true)
        }
        view.setImageDrawable(drawable)
    }

    companion object {
        private const val CROSS_FADE = 150

        /** The picture for an option; with [icon] the three that read at 24 dp (sun, moon, half and half). */
        fun kindFor(style: Style, value: String, icon: Boolean): Kind = when (style) {
            Style.NIGHT_MODE -> when (value) {
                "1" -> Kind.NIGHT_OFF
                "2" -> Kind.NIGHT_ON
                "3" -> if (icon) Kind.NIGHT_FOLLOW else Kind.NIGHT_TIME
                "4" -> if (icon) Kind.NIGHT_ON else Kind.NIGHT_BATTERY
                else -> Kind.NIGHT_FOLLOW
            }
            Style.MOTION -> when (value) {
                "subtle" -> Kind.MOTION_SUBTLE
                "off" -> Kind.MOTION_OFF
                else -> Kind.MOTION_FULL
            }
            Style.AI_SPEED -> when (value) {
                "turbo" -> Kind.AI_TURBO
                "eco" -> Kind.AI_ECO
                else -> Kind.AI_BALANCED
            }
            Style.TRASH_RETENTION -> when (value) {
                "7" -> Kind.TRASH_SHORT
                "30" -> Kind.TRASH_MEDIUM
                "90" -> Kind.TRASH_LONG
                else -> Kind.TRASH_NEVER
            }
            Style.FILE_NAME_ELLIPSIZE -> when (value) {
                "0" -> Kind.ELLIPSIZE_START
                "1" -> Kind.ELLIPSIZE_MIDDLE
                "3" -> Kind.ELLIPSIZE_MARQUEE
                else -> Kind.ELLIPSIZE_END
            }
            Style.APK_ACTION -> when (value) {
                "1" -> Kind.APK_VIEW
                "2" -> Kind.APK_ASK
                else -> Kind.APK_INSTALL
            }
            Style.HOME_HEADER -> if (value == "bottom") Kind.HEADER_BOTTOM else Kind.HEADER_TOP
        }
    }
}
