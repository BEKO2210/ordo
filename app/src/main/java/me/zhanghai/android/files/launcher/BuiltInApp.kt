/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.defaultSharedPreferences

/**
 * Ordo's built-in apps that can get their own launcher icon. An app has one `<activity-alias>` per [IconColor] in the
 * manifest (all disabled there) once it is good enough; [aliasPrefix] + [IconColor.aliasSuffix] names it. An app
 * without [aliasPrefix] is announced in the settings but has no icon to switch on. Nothing here is ever enabled by
 * Ordo itself: only the user's switch does it.
 */
enum class BuiltInApp(val aliasPrefix: String?, @StringRes val labelRes: Int, @DrawableRes val iconRes: Int) {
    MUSIC("me.zhanghai.android.files.launcher.Music", R.string.launcher_music_label, R.drawable.audio_icon_white_24dp),
    VIDEO("me.zhanghai.android.files.launcher.Video", R.string.launcher_video_label, R.drawable.video_icon_white_24dp),
    IMAGES(null, R.string.launcher_images_label, R.drawable.image_icon_white_24dp),
    PDF(null, R.string.launcher_pdf_label, R.drawable.document_icon_white_24dp),
    NOTES(null, R.string.launcher_notes_label, R.drawable.text_file_icon_white_24dp);

    val isAvailable: Boolean
        get() = aliasPrefix != null

    companion object {
        /** The manifest declares every alias disabled; the icon only appears when the user switches it on. */
        const val ENABLED_BY_DEFAULT = false

        val available: List<BuiltInApp>
            get() = entries.filter { it.isAvailable }

        /** [PackageManager.getComponentEnabledSetting] answers DEFAULT as long as nobody changed the alias. */
        fun isEnabled(state: Int, byDefault: Boolean = ENABLED_BY_DEFAULT): Boolean =
            when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> byDefault
                else -> false
            }
    }
}

/**
 * The background colours an icon can have, in the order of utils/launcher-icon-colors.py, which writes the icons and
 * aliases. Blue is the original icon and the default; its aliases are the hand-written `HomeAlias`, `MusicAlias` and
 * `VideoAlias`.
 */
enum class IconColor(val aliasSuffix: String, @ColorRes val colorRes: Int, @StringRes val labelRes: Int) {
    BLUE("Alias", R.color.launcher_icon_blue, R.string.launcher_color_blue),
    TEAL("Teal", R.color.launcher_icon_teal, R.string.launcher_color_teal),
    GREEN("Green", R.color.launcher_icon_green, R.string.launcher_color_green),
    AMBER("Amber", R.color.launcher_icon_amber, R.string.launcher_color_amber),
    RED("Red", R.color.launcher_icon_red, R.string.launcher_color_red),
    PINK("Pink", R.color.launcher_icon_pink, R.string.launcher_color_pink),
    VIOLET("Violet", R.color.launcher_icon_violet, R.string.launcher_color_violet),
    GRAPHITE("Graphite", R.color.launcher_icon_graphite, R.string.launcher_color_graphite)
}

/** Which alias belongs to which colour, and how to get from one to another. Pure, so it is tested without a device. */
object IconAliases {
    /** Ordo's own icon: always there, only its colour changes. */
    const val HOME = "me.zhanghai.android.files.launcher.Home"

    fun name(prefix: String, color: IconColor): String = prefix + color.aliasSuffix

    /** Only Ordo's blue icon is enabled in the manifest. */
    fun enabledByDefault(alias: String): Boolean = alias == name(HOME, IconColor.BLUE)

    /** The colour whose alias is on, or null when none is (a built-in app switched off). */
    fun current(prefix: String, isEnabled: (String) -> Boolean): IconColor? =
        IconColor.entries.firstOrNull { isEnabled(name(prefix, it)) }

    /**
     * The switches that leave exactly [color] on, in order: the new alias goes on before the others go off, so there
     * is never a moment without a launcher entry. Aliases already in the wanted state are left alone.
     */
    fun switchesFor(prefix: String, color: IconColor, isEnabled: (String) -> Boolean): List<Pair<String, Boolean>> =
        (listOf(name(prefix, color) to true) +
            IconColor.entries.filter { it != color }.map { name(prefix, it) to false })
            .filter { (alias, on) -> isEnabled(alias) != on }

    /** A saved colour differs from the one on the home screen (an icon switched off has none and is left alone). */
    fun needsReconcile(saved: IconColor?, current: IconColor?): Boolean =
        saved != null && current != null && saved != current

    /** Switching a built-in app off: every colour of it goes off. */
    fun switchesOff(prefix: String, isEnabled: (String) -> Boolean): List<Pair<String, Boolean>> =
        IconColor.entries.map { name(prefix, it) }.filter(isEnabled).map { it to false }
}

/** Reads and switches the launcher icons of Ordo and its built-in apps through the real component state. */
object LauncherIcons {
    private fun component(context: Context, alias: String) = ComponentName(context.packageName, alias)

    private fun isAliasEnabled(context: Context, alias: String): Boolean =
        BuiltInApp.isEnabled(
            context.packageManager.getComponentEnabledSetting(component(context, alias)),
            IconAliases.enabledByDefault(alias)
        )

    fun isEnabled(context: Context, app: BuiltInApp): Boolean =
        app.aliasPrefix?.let { color(context, it) } != null

    /** The colour on the home screen, or null while the icon is switched off. */
    fun color(context: Context, prefix: String): IconColor? =
        IconAliases.current(prefix) { isAliasEnabled(context, it) }

    /**
     * The colour the user chose: one still waiting for Ordo to go to the background, else the one on the home screen,
     * else for an icon switched off the one it will get (remembered, blue at first).
     */
    fun chosenColor(context: Context, prefix: String): IconColor =
        storedColor(prefix).takeIf { prefix in pending } ?: color(context, prefix) ?: storedColor(prefix)
            ?: IconColor.BLUE

    private fun storedColor(prefix: String): IconColor? =
        defaultSharedPreferences.getString(colorKey(prefix), null)
            ?.let { name -> IconColor.entries.firstOrNull { it.name == name } }

    /** Icons whose new colour is applied once Ordo is in the background; see [setColor]. */
    private val pending = mutableSetOf<String>()

    /** The icon exactly as the launcher gets it, read from the alias itself (also while it is disabled). */
    fun icon(context: Context, prefix: String, color: IconColor): Drawable? =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(
                component(context, IconAliases.name(prefix, color)), PackageManager.MATCH_DISABLED_COMPONENTS
            ).loadIcon(context.packageManager)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            null
        }

    /**
     * Remembers [color] for the icon with [prefix] and, while the icon is on (Ordo's always is), switches to it.
     * Returns false when the system refused; the caller then shows the real state again.
     */
    fun setColor(context: Context, prefix: String, color: IconColor): Boolean {
        defaultSharedPreferences.edit { putString(colorKey(prefix), color.name) }
        if (color(context, prefix) == null) return true
        if (prefix == IconAliases.HOME) {
            // One UI (Galaxy Tab S9, measured 17.11.2026) closes Ordo's task when the alias that started it is
            // disabled, DONT_KILL_APP or not: Ordo's own icon changes only once Ordo is in the background.
            applyInBackground(context, prefix)
            return true
        }
        return apply(context, IconAliases.switchesFor(prefix, color) { isAliasEnabled(context, it) })
    }

    /**
     * At process start: a colour saved for Ordo's own icon but never applied (Ordo was killed before it went to the
     * background) is applied at this session's next ON_STOP, so a choice never gets lost.
     */
    fun reconcile(context: Context) {
        val prefix = IconAliases.HOME
        if (IconAliases.needsReconcile(storedColor(prefix), color(context, prefix))) applyInBackground(context, prefix)
    }

    private fun applyInBackground(context: Context, prefix: String) {
        if (!pending.add(prefix)) return
        val appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                pending.remove(prefix)
                val chosen = storedColor(prefix) ?: return
                apply(appContext, IconAliases.switchesFor(prefix, chosen) { isAliasEnabled(appContext, it) })
                // The long-press entries belonged to the alias that just went off.
                LauncherShortcuts.publish(appContext)
            }
        })
    }

    /** True while Ordo's own new icon colour waits for Ordo to go to the background. */
    fun isPending(prefix: String): Boolean = prefix in pending

    /** Returns false when the system refused; the caller then shows the real state again. */
    fun setEnabled(context: Context, app: BuiltInApp, enabled: Boolean): Boolean {
        val prefix = app.aliasPrefix ?: return false
        val isEnabled = { alias: String -> isAliasEnabled(context, alias) }
        return apply(
            context,
            if (enabled) {
                IconAliases.switchesFor(prefix, chosenColor(context, prefix), isEnabled)
            } else {
                IconAliases.switchesOff(prefix, isEnabled)
            }
        )
    }

    private fun apply(context: Context, switches: List<Pair<String, Boolean>>): Boolean =
        try {
            for ((alias, on) in switches) {
                context.packageManager.setComponentEnabledSetting(
                    component(context, alias),
                    if (on) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    PackageManager.DONT_KILL_APP
                )
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

    private fun colorKey(prefix: String) = "launcher_color_${prefix.substringAfterLast('.')}"
}
