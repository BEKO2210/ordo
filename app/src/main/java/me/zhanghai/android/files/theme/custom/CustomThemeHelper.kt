/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme.custom

import com.google.android.material.color.DynamicColors

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.recreateCompat
import me.zhanghai.android.files.compat.setThemeCompat
import me.zhanghai.android.files.compat.themeResIdCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks
import me.zhanghai.android.files.util.valueCompat

object CustomThemeHelper {
    private val activityBaseThemes = mutableMapOf<Activity, Int>()
    // Accent and wallpaper colours are overlays, not theme names: remember what each activity got.
    private val activityOverlays = mutableMapOf<Activity, String>()

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                check(activityBaseThemes.containsKey(activity)) {
                    "Activity must extend AppActivity: $activity"
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                activityBaseThemes.remove(activity)
                activityOverlays.remove(activity)
            }
        })
    }

    fun apply(activity: Activity) {
        val baseThemeRes = activity.themeResIdCompat
        activityBaseThemes[activity] = baseThemeRes
        val customThemeRes = getCustomThemeRes(baseThemeRes, activity)
        activity.setThemeCompat(customThemeRes)
        applyOverlays(activity)
        if (!Motion.any) {
            val noMotionOverlay = if (Settings.MATERIAL_DESIGN_3.valueCompat) {
                R.style.ThemeOverlay_Ordo_Material3_NoMotion
            } else {
                R.style.ThemeOverlay_Ordo_MaterialComponents_NoMotion
            }
            activity.theme.applyStyle(noMotionOverlay, true)
        }
    }

    /** Ordo Musik's or Ordo Video's own accent for this activity, or null when it shows Ordo's colours. */
    private fun ownAccent(activity: Activity): String? =
        (activity as? AppAccentActivity)?.appAccent?.let { setting ->
            AppAccent.own(
                setting.valueCompat, activity.resources.getStringArray(R.array.pref_entry_values_accent).toList()
            )
        }

    private fun overlaySignature(activity: Activity): String =
        "${Settings.ACCENT.valueCompat}/${Settings.WALLPAPER_COLORS.valueCompat}/${ownAccent(activity)}"

    private fun applyOverlays(activity: Activity) {
        activityOverlays[activity] = overlaySignature(activity)
        // An app's own accent is an explicit choice: it wins over the wallpaper's colours as well.
        val own = ownAccent(activity)
        if (own == null && Settings.WALLPAPER_COLORS.valueCompat && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(activity)
            return
        }
        val accent = when (own ?: Settings.ACCENT.valueCompat) {
            "blue" -> R.style.ThemeOverlay_Ordo_Accent_Blue
            "mint" -> R.style.ThemeOverlay_Ordo_Accent_Mint
            "violet" -> R.style.ThemeOverlay_Ordo_Accent_Violet
            "amber" -> R.style.ThemeOverlay_Ordo_Accent_Amber
            else -> R.style.ThemeOverlay_Ordo_Accent_Aurora
        }
        activity.theme.applyStyle(accent, true)
    }

    fun sync() {
        for ((activity, baseThemeRes) in activityBaseThemes) {
            val currentThemeRes = activity.themeResIdCompat
            val customThemeRes = getCustomThemeRes(baseThemeRes, activity)
            if (currentThemeRes != customThemeRes || activityOverlays[activity] != overlaySignature(activity)) {
                // Ignore ".Black" theme changes when not in night mode, unless the accent changed as well.
                if (!NightModeHelper.isInNightMode(activity as AppCompatActivity)
                    && isBlackThemeChange(currentThemeRes, customThemeRes, activity)
                    && activityOverlays[activity] == overlaySignature(activity)) {
                    continue
                }
                if (activity is OnThemeChangedListener) {
                    (activity as OnThemeChangedListener).onThemeChanged(customThemeRes)
                } else {
                    activity.recreateCompat()
                }
            }
        }
    }

    private fun getCustomThemeRes(@StyleRes baseThemeRes: Int, context: Context): Int {
        val resources = context.resources
        val baseThemeName = resources.getResourceName(baseThemeRes)
        val customThemeName = if (Settings.MATERIAL_DESIGN_3.valueCompat) {
            val defaultThemeName = resources.getResourceEntryName(R.style.Theme_MaterialFiles)
            val material3ThemeName =
                resources.getResourceEntryName(R.style.Theme_MaterialFiles_Material3)
            baseThemeName.replace(defaultThemeName, material3ThemeName)
        } else {
            val themeColorName =
                resources.getResourceEntryName(Settings.THEME_COLOR.valueCompat.resourceId)
            "$baseThemeName.$themeColorName"
        } + if (Settings.BLACK_NIGHT_MODE.valueCompat) ".Black" else ""
        return resources.getIdentifier(customThemeName, null, null)
    }

    private fun isBlackThemeChange(
        @StyleRes themeRes1: Int,
        @StyleRes themeRes2: Int,
        context: Context
    ): Boolean {
        val resources = context.resources
        val themeName1 = resources.getResourceName(themeRes1)
        val themeName2 = resources.getResourceName(themeRes2)
        return themeName1 == "$themeName2.Black" || themeName2 == "$themeName1.Black"
    }

    interface OnThemeChangedListener {
        fun onThemeChanged(@StyleRes theme: Int)
    }
}
