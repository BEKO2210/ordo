/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.add
import androidx.fragment.app.commit
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.AppActivity

class VaultActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keeps vault content out of screenshots and the recent apps overview. Debug builds
        // leave it off so the screens can be checked over adb.
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { add<VaultFragment>(android.R.id.content) }
        }
    }
}
