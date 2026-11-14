/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.os.AsyncTask
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class CategoryFileListLiveData(
    private val category: HomeCategory,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            loadValue()
        }
    }

    init {
        application.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"), true, observer
        )
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val result = try {
                val files = HomeRepository(application).loadCategoryPaths(category, query).mapNotNull {
                    try { it.loadFileItem() } catch (_: Exception) { null }
                }
                Success(files)
            } catch (exception: Exception) {
                Failure(valueCompat.value, exception)
            }
            postValue(result)
        }
    }

    override fun close() {
        application.contentResolver.unregisterContentObserver(observer)
        future?.cancel(true)
    }
}
