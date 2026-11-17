/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import me.zhanghai.android.files.R

/**
 * Ordo's built-in apps that can get their own launcher icon. An app has an `<activity-alias>` in the manifest
 * (always disabled there) once it is good enough; an app without [aliasName] is announced in the settings but has
 * no icon to switch on. Nothing here is ever enabled by Ordo itself: only the user's switch does it.
 */
enum class BuiltInApp(val aliasName: String?, @StringRes val labelRes: Int) {
    MUSIC("me.zhanghai.android.files.launcher.MusicAlias", R.string.launcher_music_label),
    VIDEO(null, R.string.launcher_video_label),
    IMAGES(null, R.string.launcher_images_label),
    PDF(null, R.string.launcher_pdf_label),
    NOTES(null, R.string.launcher_notes_label);

    val isAvailable: Boolean
        get() = aliasName != null

    companion object {
        /** The manifest declares every alias disabled; the icon only appears when the user switches it on. */
        const val ENABLED_BY_DEFAULT = false

        val available: List<BuiltInApp>
            get() = entries.filter { it.isAvailable }

        /** [PackageManager.getComponentEnabledSetting] answers DEFAULT as long as nobody changed the alias. */
        fun isEnabled(state: Int): Boolean =
            when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> ENABLED_BY_DEFAULT
                else -> false
            }
    }
}

/** Reads and switches the launcher icon of a built-in app through the real component state. */
object LauncherIcons {
    private fun component(context: Context, app: BuiltInApp): ComponentName? =
        app.aliasName?.let { ComponentName(context.packageName, it) }

    fun isEnabled(context: Context, app: BuiltInApp): Boolean {
        val component = component(context, app) ?: return false
        return BuiltInApp.isEnabled(context.packageManager.getComponentEnabledSetting(component))
    }

    /** Returns false when the system refused; the caller then shows the real state again. */
    fun setEnabled(context: Context, app: BuiltInApp, enabled: Boolean): Boolean {
        val component = component(context, app) ?: return false
        return try {
            context.packageManager.setComponentEnabledSetting(
                component,
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
