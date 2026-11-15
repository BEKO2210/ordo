/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import androidx.core.content.edit
import me.zhanghai.android.files.BuildConfig

private const val KEY_VERSION_CODE = "key_version_code"

private const val VERSION_CODE_BELOW_1_1_0 = 17
private const val VERSION_CODE_LATEST = BuildConfig.VERSION_CODE

private var lastVersionCode: Int
    get() {
        if (defaultSharedPreferences.all.isEmpty()) {
            // This is a new install.
            lastVersionCode = VERSION_CODE_LATEST
            return VERSION_CODE_LATEST
        }
        return defaultSharedPreferences.getInt(KEY_VERSION_CODE, VERSION_CODE_BELOW_1_1_0)
    }
    set(value) {
        defaultSharedPreferences.edit { putInt(KEY_VERSION_CODE, value) }
    }

fun upgradeApp() {
    upgradeAppFrom(lastVersionCode)
    lastVersionCode = VERSION_CODE_LATEST
}

// Ordo (com.beko2210.ordo) never shipped Material Files' old settings formats, and Ordo's own version codes
// (14, 15, …) lie below Material Files' 18–37. Its migrations (upgradeAppTo1_1_0 … 1_7_2) therefore ran on every
// Ordo update and rewrote settings already in the current format, which crashed the start on a Pixel 11
// (0.7.0, "bad base-64"). They must never run for Ordo. Ordo's own migrations go here, keyed on Ordo's codes.
@Suppress("UNUSED_PARAMETER")
private fun upgradeAppFrom(lastVersionCode: Int) {}
