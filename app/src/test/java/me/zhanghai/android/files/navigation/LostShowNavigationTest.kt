/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The drawer fragment needs a device to run. Fragment.performActivityCreated (androidx 1.9)
 * dispatches children only after the parent returns, so a lost-show finish() still reaches
 * onActivityCreated without a listener. This locks the guard that has to sit in front of that use.
 */
class LostShowNavigationTest {
    @Test
    fun listenerIsCheckedBeforeTheDrawerBinds() {
        val repo = File(System.getProperty("user.dir")!!).let {
            if (File(it, "README.md").exists()) it else it.parentFile!!
        }
        val source = File(
            repo, "app/src/main/java/me/zhanghai/android/files/navigation/NavigationFragment.kt"
        ).readText()
        val start = source.indexOf("override fun onActivityCreated")
        val use = source.indexOf("listener.observeCurrentPath", start)
        val guard = source.indexOf("!::listener.isInitialized", start)
        assertTrue(start >= 0 && guard > start && use > guard)
    }
}
