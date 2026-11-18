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
import me.zhanghai.android.files.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HomeRepository(application)
    private val _state = MutableLiveData(HomeState())
    val state: LiveData<HomeState> = _state

    // Declared before init: its reload() uses it.
    private val auto = AutoReload(viewModelScope, QUIET_MS, MAX_WAIT_MS, MIN_PAUSE_MS, SystemClock::elapsedRealtime) { load() }

    init { reload() }

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = auto.signal()

        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            // Rows of missing files Ordo asked MediaStore to drop come back as a change: the numbers already left
            // them out, so a reload would only repeat itself.
            val own = StaleMedia.isForgotten(uri?.lastPathSegment?.toLongOrNull())
            if (BuildConfig.DEBUG) Log.d(TAG, "change $uri" + if (own) " (forgotten row, ignored)" else "")
            if (!own) auto.signal()
        }
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
            auto.stop()
        }
    }

    override fun onCleared() {
        watchChanges(false)
    }

    /** Loads now and replaces a load still running, so an older result can never overwrite a newer one. */
    fun reload() = auto.reloadNow()

    private suspend fun load() {
        // Device tests count reloads in the log; release builds stay quiet.
        if (BuildConfig.DEBUG) Log.d(TAG, "reload")
        _state.value = _state.value?.copy(isLoading = true)
        val quickAccess = repository.loadQuickAccess()
        val storages = withContext(Dispatchers.IO) { repository.loadStorages() }
        // The storage card needs only statfs: on a cold start it shows at once, not after the media scan.
        // Later reloads keep the old card until the sections arrive, so held bars grow in together (HomeFragment.onResume).
        if (_state.value?.storages.isNullOrEmpty()) _state.value = _state.value?.copy(storages = storages)
        val partial = withContext(Dispatchers.IO) { repository.load(quickAccess, storages) }
        _state.value = _state.value?.withSections(partial)
        val counts = withContext(Dispatchers.IO) { repository.loadCounts(partial) }
        _state.value = _state.value?.withCounts(counts)
        if (BuildConfig.DEBUG) Log.d(TAG, "reload done")
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val QUIET_MS = 800L
        private const val MAX_WAIT_MS = 3000L
        // The rhythm MAX_WAIT_MS already gives fast loads during a long copy; slow loads (large libraries) get it too.
        private const val MIN_PAUSE_MS = 3000L
    }
}
