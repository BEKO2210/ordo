/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import android.os.Parcel
import android.os.Parcelable
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.MediatorLiveData
import java8.nio.file.Paths
import me.zhanghai.android.files.player.installTestFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression (0.18.1): Pictures from quick access, a subfolder, back: the list closed. FileListFragment decides in a
 * breadcrumb observer, which runs before TrailLiveData.currentPathLiveData has the new path; currentPath has to be
 * current there.
 */
class BackFromEntryFolderTest {
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
    fun backWalksUpToTheEntryFolderThenLeaves() {
        val pictures = Paths.get("/storage/emulated/0/Pictures")
        val sub = pictures.resolve("e2e-back")
        val sub2 = sub.resolve("e2e-sub")
        val trail = TrailLiveData()
        // Same order as FileListFragment: the breadcrumb (back stop) observer first, the current path observer after.
        val breadcrumb = MediatorLiveData<TrailData>().apply { addSource(trail) { value = it } }
        var backStop: java8.nio.file.Path? = null
        var walksUp = false
        breadcrumb.observeForever {
            if (backStop == null) backStop = trail.currentPath
            walksUp = shouldBackNavigateUp(it.currentIndex > 0, trail.currentPath, backStop)
        }
        trail.currentPathLiveData.observeForever {}
        val state = object : Parcelable {
            override fun describeContents() = 0
            override fun writeToParcel(dest: Parcel, flags: Int) {}
        }

        trail.resetTo(pictures)
        assertEquals("on the entry folder back leaves", false, walksUp)
        trail.navigateTo(state, sub)
        assertEquals("one level down back walks up", true, walksUp)
        trail.navigateTo(state, sub2)
        assertEquals("two levels down back walks up", true, walksUp)
        trail.navigateUp()
        assertEquals("back on the first subfolder still walks up", true, walksUp)
        trail.navigateUp()
        assertEquals("back on the entry folder again leaves", false, walksUp)
    }
}
