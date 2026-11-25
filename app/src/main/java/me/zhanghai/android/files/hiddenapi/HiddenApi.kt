/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hiddenapi

import android.os.Build
import android.os.StrictMode

object HiddenApi {
    fun disableHiddenApiChecks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The linker's lookup of our own .so is a disk read that cannot move off the main thread: hidden APIs must
            // be open before any UI code runs. Allowed only for this call (StrictMode stays on for everything else).
            val policy = StrictMode.allowThreadDiskReads()
            try {
                System.loadLibrary("hiddenapi")
            } finally {
                StrictMode.setThreadPolicy(policy)
            }
        }
    }
}
