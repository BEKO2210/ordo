/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.app

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.StrictMode

/**
 * Debug builds only: StrictMode with detectAll and penaltyLog on top of the platform's own policy, so
 * penaltyDeathOnNetwork (network on the main thread) stays. Violations go to logcat; e2e/lib.sh fails a run that finds one. A
 * ContentProvider starts it because providers are created with the process, before any activity.
 */
class StrictModeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).detectAll().penaltyLog().build()
        )
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy()).detectAll().penaltyLog().build())
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ) = null

    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
