/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.theme.night

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegateCompat
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks
import me.zhanghai.android.files.util.valueCompat

// We take over the activity creation when setting the default night mode from AppCompat so that:
// 1. We can recreate all activities upon change, instead of only started activities.
// 2. We can have custom handling of the change, instead of being forced to either recreate or
//    update resources configuration which is shared among activities.
object NightModeHelper {
    private val activities = mutableSetOf<AppCompatActivity>()

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity !is AppActivity) return
                    check(activity in activities) { "Activity must extend AppActivity: $activity" }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    (activity as? AppCompatActivity)?.let { activities -= it }
                }
            })
        // The user can switch the expanded dark theme on and off without a configuration change.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            runCatching {
                application.contentResolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor(FORCE_INVERT_SETTING), false,
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) = sync()
                    }
                )
            }
        }
    }

    fun apply(activity: AppCompatActivity) {
        activities += activity
        activity.delegate.localNightMode = nightMode
    }

    fun sync() {
        for (activity in activities) {
            val nightMode = nightMode
            if (activity is OnNightModeChangedListener) {
                if (getUiModeNight(activity.delegate.localNightMode, activity)
                    != getUiModeNight(nightMode, activity)) {
                    activity.onNightModeChangedFromHelper(nightMode)
                }
            } else {
                activity.delegate.localNightMode = nightMode
            }
        }
    }

    private val nightMode: Int
        get() = effectiveNightMode(
            Settings.NIGHT_MODE.valueCompat.value,
            Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES,
            isForceInvertEnabled()
        )

    /**
     * Android 16 QPR2's expanded dark theme (Pixel: Settings › Display › Dark theme › Expanded) inverts a light app's
     * colours while the system is dark, and it ignores every opt-out. It inverts text and icons but not the player's
     * cover backdrop (a bitmap through a shader), so light text landed on a light background (owner's Pixel,
     * 20.11.2026). The user asked for dark: then Ordo shows its own dark theme, which the system leaves alone.
     */
    fun effectiveNightMode(setting: Int, systemNight: Boolean, forceInvert: Boolean): Int =
        if (forceInvert && systemNight) AppCompatDelegate.MODE_NIGHT_YES else setting

    // Hidden setting: unreadable on some builds, then nothing changes.
    private fun isForceInvertEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && runCatching {
            android.provider.Settings.Secure.getInt(application.contentResolver, FORCE_INVERT_SETTING, 0) == 1
        }.getOrDefault(false)

    private const val FORCE_INVERT_SETTING = "accessibility_force_invert_color_enabled"

    /*
     * @see androidx.appcompat.app.AppCompatDelegateImpl#updateForNightMode(int, boolean)
     */
    private fun getUiModeNight(nightMode: Int, activity: AppCompatActivity): Int =
        when (AppCompatDelegateCompat.mapNightMode(activity.delegate, application, nightMode)) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            else ->
                (activity.applicationContext.resources.configuration.uiMode
                    and Configuration.UI_MODE_NIGHT_MASK)
        }

    fun isInNightMode(activity: AppCompatActivity): Boolean =
        (getUiModeNight(activity.delegate.localNightMode, activity)
            == Configuration.UI_MODE_NIGHT_YES)

    interface OnNightModeChangedListener {
        fun onNightModeChangedFromHelper(nightMode: Int)
    }
}
