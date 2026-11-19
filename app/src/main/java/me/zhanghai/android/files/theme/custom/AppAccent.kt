/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.theme.custom

import me.zhanghai.android.files.settings.SettingLiveData

/**
 * Ordo Musik and Ordo Video may have their own accent colour: one of Ordo's accents, or [FOLLOW] ("Same as Ordo",
 * the default), which means whatever Ordo shows, its accent or the wallpaper's colours. Pure, so it is unit-tested.
 */
object AppAccent {
    const val FOLLOW = "ordo"

    /** The app's own accent, or null when it follows Ordo (also for an unknown value from an older version). */
    fun own(appSetting: String?, accents: Collection<String>): String? =
        appSetting?.takeIf { it != FOLLOW && it in accents }

    /** The accent overlay the app's screens get when wallpaper colours are off. */
    fun effective(appSetting: String?, ordoAccent: String, accents: Collection<String>): String =
        own(appSetting, accents) ?: ordoAccent
}

/** An activity of a built-in app with its own colour choice; [appAccent] null means it simply follows Ordo. */
interface AppAccentActivity {
    val appAccent: SettingLiveData<String>?
}
