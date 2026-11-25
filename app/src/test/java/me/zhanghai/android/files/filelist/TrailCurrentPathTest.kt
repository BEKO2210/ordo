/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.map
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.player.installTestFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Back from a subfolder of a quick-access folder left the list: the back stop is checked in a breadcrumb observer,
 * which runs before the mapped current path is updated. The trail itself is current there, so the view model reads it.
 */
class TrailCurrentPathTest {
    @Before
    fun mainThread() {
        installTestFileSystem()
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
    }

    @After
    fun reset() = ArchTaskExecutor.getInstance().setDelegate(null)

    @Test
    fun breadcrumbObserverSeesTheNewPathOnlyOnTheTrail() {
        val pictures = Paths.get("/storage/emulated/0/Pictures")
        val sub = pictures.resolve("e2e-sub")
        val trail = TrailLiveData()
        // Same order as FileListViewModel/FileListFragment: breadcrumb observed first, current path after.
        val breadcrumb = MediatorLiveData<TrailData>().apply { addSource(trail) { value = it } }
        val mapped = trail.map { it.currentPath }
        val seen = mutableListOf<Pair<Path, Path?>>()
        breadcrumb.observeForever { seen += it.currentPath to mapped.value }
        mapped.observeForever {}

        trail.resetTo(pictures)
        trail.navigateTo(object : Parcelable {
            override fun describeContents() = 0
            override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
        }, sub)
        trail.navigateUp()

        assertEquals(listOf(pictures, sub, pictures), seen.map { it.first })
        // The mapped path lags one step behind: reading it here is the bug this test pins.
        assertEquals(listOf(null, pictures, sub), seen.map { it.second })
    }
}
