/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sheet can open on a download already waiting for Wi-Fi: then "use mobile data" is shown by the download
 * follower without the setup page having run. Its click (and the setup button's) must therefore be set once in
 * onViewCreated, not by the setup page. The fragment needs a device to run, so this reads its source.
 */
class CommandSheetButtonsTest {
    private val repo = File(System.getProperty("user.dir")!!).let { if (File(it, "README.md").exists()) it else it.parentFile!! }
    private val source = File(repo, "app/src/main/java/me/zhanghai/android/files/ai/CommandSheetFragment.kt").readText()

    @Test
    fun downloadButtonsAreWiredOnceWhenTheSheetIsCreated() {
        val created = source.indexOf("override fun onViewCreated")
        val end = source.indexOf("\n    override fun ", created + 1)
        for (button in listOf("setupButton", "mobileDataButton")) {
            val wiring = Regex("""binding\.$button\.setOnClickListener""").findAll(source).map { it.range.first }.toList()
            assertEquals("$button gets its click in one place", 1, wiring.size)
            assertTrue("$button is wired in onViewCreated", wiring.single() in created until end)
        }
    }
}
