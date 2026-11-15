/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    fun reload() {
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
}
