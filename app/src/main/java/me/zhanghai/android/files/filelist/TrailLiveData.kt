/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import java8.nio.file.Path
import me.zhanghai.android.files.util.valueCompat

class TrailLiveData : LiveData<TrailData>() {
    val currentPathLiveData: LiveData<Path> = map { it.currentPath }

    // Read from the trail, not currentPathLiveData: observers of the breadcrumb (the back stop) run before the
    // mapped value is updated, and a stale path there turned back in a subfolder into leaving the list.
    val currentPath: Path
        get() = valueCompat.currentPath

    fun navigateTo(lastState: Parcelable, path: Path) {
        val oldTrailData = value
        if (oldTrailData == null) {
            resetTo(path)
            return
        }
        value = oldTrailData.navigateTo(lastState, path)
    }

    fun resetTo(path: Path) {
        value = TrailData.of(path)
    }

    fun navigateUp(): Boolean {
        val oldTrailData = value ?: return false
        val trailData = oldTrailData.navigateUp() ?: return false
        value = trailData
        return true
    }

    fun reload() {
        value = value
    }
}
