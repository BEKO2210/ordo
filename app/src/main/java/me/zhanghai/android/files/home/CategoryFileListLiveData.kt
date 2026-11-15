/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

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
import java.util.concurrent.Executors
import java.util.concurrent.Future

class CategoryFileListLiveData(
    private val category: HomeCategory,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reload = Runnable { loadValue() }

    // A delete of thousands of files changes MediaStore thousands of times: wait for a quiet moment and reload once,
    // instead of starting a full reload per change (that ran them in parallel until memory ran out).
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, RELOAD_DELAY_MILLIS)
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
        future = executor.submit<Unit> {
            val result = try {
                val files = HomeRepository(application).loadCategoryPaths(category, query).mapNotNull {
                    // A newer load replaced this one: stop instead of finishing work nobody will see.
                    if (Thread.currentThread().isInterrupted) return@submit
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
        handler.removeCallbacks(reload)
        future?.cancel(true)
    }

    private companion object {
        const val RELOAD_DELAY_MILLIS = 800L

        // One load at a time for all category lists.
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
