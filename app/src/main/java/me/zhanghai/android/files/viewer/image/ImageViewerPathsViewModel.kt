/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.trash.Trash

/**
 * The open image list. A rotation destroys the view before a delete on that view can update it;
 * this scope outlives the view, so the new fragment reads the list after the file is gone.
 */
class ImageViewerPathsViewModel : ViewModel() {
    private val _paths = MutableStateFlow<List<Path>?>(null)
    val paths: StateFlow<List<Path>?> = _paths

    private val _deleteError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val deleteError: SharedFlow<String> = _deleteError

    fun ensure(initial: List<Path>) {
        if (_paths.value == null) _paths.value = initial
    }

    fun delete(path: Path) {
        viewModelScope.launch {
            try {
                // NonCancellable: a rotation used to cancel withContext after the file was already
                // gone, and the list update never ran.
                withContext(NonCancellable + Dispatchers.IO) {
                    if (Trash.isEnabled && Trash.canTrash(path)) {
                        Trash.moveToTrash(path)
                    } else {
                        path.delete()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _deleteError.tryEmit(e.toString())
                return@launch
            }
            withContext(NonCancellable) {
                _paths.value = _paths.value?.filter { it != path }
            }
        }
    }
}
