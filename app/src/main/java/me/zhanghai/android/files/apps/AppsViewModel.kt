/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppEntry(
    val info: ApplicationInfo,
    val label: String,
    val versionName: String?,
    val apkFiles: List<File>
) {
    val packageName: String
        get() = info.packageName

    val size: Long
        get() = apkFiles.sumOf { it.length() }

    val isSystem: Boolean
        get() = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
}

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    private val _apps = MutableLiveData<List<AppEntry>>()
    val apps: LiveData<List<AppEntry>> = _apps

    var showSystemApps = false
        private set

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun setShowSystemApps(show: Boolean) {
        showSystemApps = show
        reload()
    }

    fun reload() {
        val packageManager = getApplication<Application>().packageManager
        val showSystemApps = showSystemApps
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                val collator = Collator.getInstance()
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
                    .filter { showSystemApps || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { info ->
                        val versionName = try {
                            @Suppress("DEPRECATION")
                            packageManager.getPackageInfo(info.packageName, 0).versionName
                        } catch (e: PackageManager.NameNotFoundException) {
                            null
                        }
                        AppEntry(
                            info, info.loadLabel(packageManager).toString(), versionName,
                            listOf(info.sourceDir).plus(info.splitSourceDirs.orEmpty())
                                .map { File(it) }
                        )
                    }
                    .sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
        }
    }

    // Writes the APK(s) to Download/Ordo APKs; split apps get a folder with every part.
    fun backup(app: AppEntry, onDone: (File?) -> Unit) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    val directory = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ), BACKUP_DIRECTORY_NAME
                    )
                    val baseName = backupFileName(app.label, app.versionName)
                    if (app.apkFiles.size == 1) {
                        File(directory, "$baseName.apk").also { app.apkFiles[0].copyTo(it, true) }
                    } else {
                        File(directory, baseName).also { folder ->
                            app.apkFiles.forEach { it.copyTo(File(folder, it.name), true) }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            onDone(target)
        }
    }

    companion object {
        const val BACKUP_DIRECTORY_NAME = "Ordo APKs"

        fun backupFileName(label: String, versionName: String?): String {
            val name = if (versionName.isNullOrBlank()) label else "$label $versionName"
            return name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
                .ifEmpty { "app" }
        }
    }
}
