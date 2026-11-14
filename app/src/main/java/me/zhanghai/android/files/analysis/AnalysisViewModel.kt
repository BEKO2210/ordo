/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.os.Environment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalysisResult(
    val root: String,
    val large: List<ScannedFile>,
    val duplicates: List<DuplicateGroup>,
    val old: List<ScannedFile>,
    val apks: List<ScannedFile>,
    val folders: List<FolderSize>,
    val files: List<ScannedFile> = emptyList(),
    val insights: StorageInsights? = null
)

sealed class AnalysisState {
    class Scanning(val fileCount: Int, val isHashing: Boolean) : AnalysisState()
    class Done(val result: AnalysisResult) : AnalysisState()
}

// The root comes from the intent: internal storage by default, or an SD card / USB drive.
class AnalysisViewModel(handle: SavedStateHandle) : ViewModel() {
    private val _state = MutableLiveData<AnalysisState>()
    val state: LiveData<AnalysisState> = _state

    val selectedPaths = mutableSetOf<String>()

    @Suppress("DEPRECATION")
    var root: File = handle.get<String>(AnalysisActivity.EXTRA_ROOT)?.let(::File)
        ?: Environment.getExternalStorageDirectory()
        private set

    private var job: Job? = null

    init {
        scan(root)
    }

    fun scan(root: File) {
        this.root = root
        job?.cancel()
        selectedPaths.clear()
        _state.value = AnalysisState.Scanning(0, false)
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val files = StorageAnalysis.scan(root, { !isActive }) {
                    _state.postValue(AnalysisState.Scanning(it, false))
                }
                _state.postValue(AnalysisState.Scanning(files.size, true))
                withInsights(AnalysisResult(
                    root.path, StorageAnalysis.largeFiles(files),
                    StorageAnalysis.duplicates(files, isCancelled = { !isActive }),
                    StorageAnalysis.oldFiles(files, System.currentTimeMillis()),
                    StorageAnalysis.apkFiles(files), StorageAnalysis.folderSizes(root.path, files), files
                ))
            }
            _state.value = AnalysisState.Done(result)
        }
    }

    private fun withInsights(result: AnalysisResult): AnalysisResult {
        val stat = try {
            android.os.StatFs(result.root)
        } catch (e: IllegalArgumentException) {
            null
        }
        val trashBytes = me.zhanghai.android.files.trash.Trash.entries()
            .filter { it.root == result.root }.sumOf { it.file.length() }
        val insights = StorageInsights.compute(
            result.root, result.files, result, stat?.totalBytes ?: 0L, stat?.availableBytes ?: 0L, trashBytes,
            System.currentTimeMillis()
        )
        return AnalysisResult(result.root, result.large, result.duplicates, result.old, result.apks, result.folders,
            result.files, insights)
    }

    // Deleted files leave every list at once, so sizes and duplicate groups stay correct.
    fun removeDeleted(paths: Set<String>) {
        val result = (_state.value as? AnalysisState.Done)?.result ?: return
        fun List<ScannedFile>.keep() = filter { it.path !in paths }
        val deletedFiles = (result.large + result.old + result.apks + result.duplicates.flatMap { it.files })
            .filter { it.path in paths }.distinctBy { it.path }
        _state.value = AnalysisState.Done(
            withInsights(AnalysisResult(
                result.root, result.large.keep(),
                result.duplicates.map { DuplicateGroup(it.files.keep()) }.filter { it.files.size > 1 },
                result.old.keep(), result.apks.keep(),
                result.folders.map { folder ->
                    FolderSize(
                        folder.path,
                        folder.size - deletedFiles.filter { it.path.startsWith(folder.path + "/") }
                            .sumOf { it.size }
                    )
                }.sortedByDescending { it.size },
                result.files.keep()
            ))
        )
        selectedPaths.removeAll(paths)
    }
}
