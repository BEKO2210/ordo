/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.theme.night

import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_AUTO_TIME
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
import org.junit.Assert.assertEquals
import org.junit.Test

class NightModeHelperTest {
    private val settings =
        listOf(MODE_NIGHT_FOLLOW_SYSTEM, MODE_NIGHT_NO, MODE_NIGHT_YES, MODE_NIGHT_AUTO_TIME, MODE_NIGHT_AUTO_BATTERY)

    @Test
    fun expandedDarkThemeOnADarkSystemMakesOrdoDark() {
        // Pixel 11, 20.11.2026: Ordo "Off" (light) under the expanded dark theme inverted text but not the backdrop.
        for (setting in settings) {
            assertEquals(MODE_NIGHT_YES, NightModeHelper.effectiveNightMode(setting, systemNight = true, forceInvert = true))
        }
    }

    @Test
    fun otherwiseTheUsersChoiceStays() {
        for (setting in settings) {
            for ((systemNight, forceInvert) in listOf(false to false, true to false, false to true)) {
                assertEquals(setting, NightModeHelper.effectiveNightMode(setting, systemNight, forceInvert))
            }
        }
    }
}
