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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HomeRepository(application)
    private val _state = MutableLiveData(HomeState())
    val state: LiveData<HomeState> = _state

    init { reload() }

    fun reload() {
        _state.value = _state.value?.copy(isLoading = true)
        val quickAccess = repository.loadQuickAccess()
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { repository.load(quickAccess) }
        }
    }
}
