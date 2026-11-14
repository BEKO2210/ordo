/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.commit
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

class HomeActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.SHOW_HOME_ON_START.valueCompat) {
            startActivity(Intent(this, FileListActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            supportFragmentManager.commit { add(android.R.id.content, HomeFragment()) }
            if (!OnboardingActivity.isDone(this)) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
        }
    }
}
