/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.viewer.video.vault.VaultVideos

class VaultViewModel : ViewModel() {
    private val _isUnlocked = MutableLiveData(false)
    val isUnlocked: LiveData<Boolean> = _isUnlocked

    private val _items = MutableLiveData<List<VaultEntry>>(emptyList())
    val items: LiveData<List<VaultEntry>> = _items

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Whether the vault itself could not be read, as opposed to being empty.
    private val _hasError = MutableLiveData(false)
    val hasError: LiveData<Boolean> = _hasError

    // One-shot results of open(); the fragment calls the matching consume function.
    private val _openedFile = MutableLiveData<File?>()
    val openedFile: LiveData<File?> = _openedFile

    private val _hasOpenError = MutableLiveData(false)
    val hasOpenError: LiveData<Boolean> = _hasOpenError

    val selectedIds = mutableSetOf<String>()

    var hasPrompted = false

    private var viewerStartedAt = 0L

    init {
        clearOpenedFiles()
    }

    fun unlock() {
        _isUnlocked.value = true
        reload()
    }

    fun lock() {
        if (_isUnlocked.value != true) {
            return
        }
        _isUnlocked.value = false
        _items.value = emptyList()
        selectedIds.clear()
        clearOpenedFiles()
        // A player still open loses its videos too.
        VaultVideos.revokeAll()
    }

    // A viewer on top of the vault must not lock it right away, or the file it shows would be
    // deleted under it. Coming back later than the timeout locks instead.
    fun onStop() {
        if (viewerStartedAt == 0L) {
            lock()
        }
    }

    fun onStart() {
        if (viewerStartedAt != 0L) {
            if (SystemClock.elapsedRealtime() - viewerStartedAt > VIEWER_TIMEOUT_MILLIS) {
                lock()
            }
            viewerStartedAt = 0
        }
    }

    fun reload() {
        if (_isUnlocked.value != true) {
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    Vault.store.entries()
                } catch (e: IOException) {
                    e.printStackTrace()
                    null
                }
            }
            _isLoading.value = false
            // Locked while loading.
            if (_isUnlocked.value != true) {
                return@launch
            }
            selectedIds.retainAll(items.orEmpty().map { it.id }.toSet())
            _hasError.value = items == null
            _items.value = items.orEmpty()
        }
    }

    fun open(entry: VaultEntry) {
        if (_isLoading.value == true) {
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    Vault.decryptToCache(entry)
                } catch (e: IOException) {
                    e.printStackTrace()
                    null
                }
            }
            _isLoading.value = false
            if (_isUnlocked.value != true) {
                clearOpenedFiles()
            } else if (file != null) {
                _openedFile.value = file
            } else {
                _hasOpenError.value = true
            }
        }
    }

    fun consumeOpenedFile() {
        _openedFile.value = null
        onViewerStarted()
    }

    /** A viewer is on top: stopping now must not lock the vault under it. */
    fun onViewerStarted() {
        viewerStartedAt = SystemClock.elapsedRealtime()
    }

    fun consumeOpenError() {
        _hasOpenError.value = false
    }

    fun delete(entries: List<VaultEntry>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    entries.forEach { Vault.store.delete(it) }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            reload()
        }
    }

    private fun clearOpenedFiles() {
        Thread({ Vault.clearOpenedFiles() }, "VaultClear").start()
    }

    override fun onCleared() {
        clearOpenedFiles()
        VaultVideos.revokeAll()
    }

    companion object {
        private const val VIEWER_TIMEOUT_MILLIS = 2 * 60 * 1000L
    }
}
