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
    val insights: StorageInsights? = null,
    /** Empty folders worth a look, with size 0 (see [StorageAnalysis.isJunkFolder]). */
    val emptyFolders: List<ScannedFile> = emptyList()
)

/** A page opened on top of the overview. Back goes down one page, so the user lands where they were. */
sealed class AnalysisPage {
    /** A list from the cleanup card ([StorageInsights.CLEANUP], except "trash"), or "folders" for all top folders. */
    data class Cleanup(val type: String) : AnalysisPage()

    /** The user's files of one kind (see [StorageInsights.KINDS]), largest first. */
    data class Kind(val kind: String) : AnalysisPage()

    /** A folder's subfolders and files, largest first. */
    data class Folder(val path: String) : AnalysisPage()
}

/** What is picked in the overview's charts: a file type, the opened-up small types, a tile of the folder map. */
class ChartState(var kind: String? = null, var drilled: Boolean = false, var tile: String? = null) {
    val isSet: Boolean get() = kind != null || drilled || tile != null

    fun clear() {
        kind = null
        drilled = false
        tile = null
    }
}

sealed class AnalysisState {
    class Scanning(val progress: ScanProgress) : AnalysisState()
    class Done(val result: AnalysisResult) : AnalysisState()
}

// The root comes from the intent: internal storage by default, or an SD card / USB drive.
class AnalysisViewModel(handle: SavedStateHandle) : ViewModel() {
    private val _state = MutableLiveData<AnalysisState>()
    val state: LiveData<AnalysisState> = _state

    val selectedPaths = mutableSetOf<String>()

    /** Pages on top of the overview, and the scroll position of each page below the top one. Both survive rotation. */
    val pages = mutableListOf<AnalysisPage>()
    val scrollStates = mutableListOf<android.os.Parcelable?>()

    /** Survives rotation like the pages, so a lifted slice is still lifted afterwards. */
    val chart = ChartState()

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
        pages.clear()
        scrollStates.clear()
        chart.clear()
        _state.value = AnalysisState.Scanning(ScanProgress(0, 0, LongArray(StorageInsights.KINDS.size), ""))
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var last: ScanProgress? = null
                val emptyFolders = mutableListOf<ScannedFile>()
                val files = StorageAnalysis.scan(root, { !isActive }, emptyFolders) {
                    last = it
                    _state.postValue(AnalysisState.Scanning(it))
                }
                val walked = last!!
                withInsights(AnalysisResult(
                    root.path, StorageAnalysis.largeFiles(files),
                    StorageAnalysis.duplicates(files, isCancelled = { !isActive }) {
                        _state.postValue(AnalysisState.Scanning(
                            ScanProgress(walked.files, walked.bytes, walked.kinds, "", it)))
                    },
                    StorageAnalysis.oldFiles(files, System.currentTimeMillis()),
                    StorageAnalysis.apkFiles(files), StorageAnalysis.folderSizes(root.path, files), files,
                    emptyFolders = emptyFolders
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
            result.files, insights, result.emptyFolders)
    }

    // Deleted files leave every list at once, so sizes and duplicate groups stay correct.
    fun removeDeleted(paths: Set<String>) {
        val result = (_state.value as? AnalysisState.Done)?.result ?: return
        fun List<ScannedFile>.keep() = filter { it.path !in paths }
        val deletedFiles = result.files.filter { it.path in paths }
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
                result.files.keep(), emptyFolders = result.emptyFolders.keep()
            ))
        )
        selectedPaths.removeAll(paths)
    }
}
