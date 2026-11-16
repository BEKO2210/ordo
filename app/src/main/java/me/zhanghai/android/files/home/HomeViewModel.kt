/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HomeRepository(application)
    private val _state = MutableLiveData(HomeState())
    val state: LiveData<HomeState> = _state

    // A reload replaces the one still running, so an older result can never overwrite a newer one.
    // Declared before init: its reload() sets it.
    private var job: Job? = null

    init { reload() }

    private val debounce = ReloadDebounce(QUIET_MS, MAX_WAIT_MS)
    private var pending: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reloadSoon()
    }
    private var watching = false

    /**
     * Follows MediaStore while the home screen is visible: new, changed or deleted files reload it on their own.
     * Off while hidden, so nothing runs in the background; coming back reloads anyway (HomeFragment.onResume).
     */
    fun watchChanges(watch: Boolean) {
        if (watch == watching) return
        watching = watch
        val resolver = getApplication<Application>().contentResolver
        if (watch) {
            resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObserver)
        } else {
            resolver.unregisterContentObserver(mediaObserver)
            pending?.cancel()
            debounce.reset()
        }
    }

    /** A burst of changes (50 files copied at once) becomes one reload, not fifty. */
    fun reloadSoon() {
        pending?.cancel()
        val wait = debounce.delayFor(SystemClock.elapsedRealtime())
        pending = viewModelScope.launch {
            delay(wait)
            debounce.reset()
            reload()
        }
    }

    override fun onCleared() {
        watchChanges(false)
    }

    fun reload() {
        Log.d(TAG, "reload")
        _state.value = _state.value?.copy(isLoading = true)
        val quickAccess = repository.loadQuickAccess()
        job?.cancel()
        job = viewModelScope.launch {
            val storages = withContext(Dispatchers.IO) { repository.loadStorages() }
            // The storage card needs only statfs: on a cold start it shows at once, not after the media scan.
            // Later reloads keep the old card until the end, so held bars grow in together (HomeFragment.onResume).
            if (_state.value?.storages.isNullOrEmpty()) _state.value = _state.value?.copy(storages = storages)
            val partial = withContext(Dispatchers.IO) { repository.load(quickAccess, storages) }
            _state.value = _state.value?.copy(
                recentFiles = partial.recentFiles, categories = partial.categories, quickAccess = partial.quickAccess
            )
            _state.value = withContext(Dispatchers.IO) { repository.loadCounts(partial) }
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val QUIET_MS = 800L
        private const val MAX_WAIT_MS = 3000L
    }
}
