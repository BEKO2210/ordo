/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.trash

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrashItem(val entry: TrashEntry, val isDirectory: Boolean, val size: Long)

class TrashViewModel : ViewModel() {
    private val _items = MutableLiveData<List<TrashItem>>()
    val items: LiveData<List<TrashItem>> = _items

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    val selectedIds = mutableSetOf<String>()

    init {
        reload()
    }

    fun reload() {
        _isLoading.value = true
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                Trash.entries().map {
                    val file = it.file
                    TrashItem(it, file.isDirectory, file.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                }
            }
            selectedIds.retainAll(items.map { it.entry.id }.toSet())
            _items.value = items
            _isLoading.value = false
        }
    }
}
