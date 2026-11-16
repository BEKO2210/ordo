/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.os.Environment
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ai.CommandFiles
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.ai.CommandPlan
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The files an AI "show" plan finds, searched again on every load, so the list follows deletes and renames. */
class ShowFileListLiveData(
    private val request: Request,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    /**
     * What the command sheet understood: the plan, the folders it searches, and in words a title and the filters.
     * It never travels in the intent: FileListActivity is exported, so the intent only carries a random token for a
     * request kept in this process. Another app cannot forge one, and after the process is gone the token is void.
     */
    class Request(val plan: CommandPlan, val sources: List<File>, val title: String, val detail: String) {
        companion object {
            private val pending = ConcurrentHashMap<String, Request>()

            /** Keeps [request] for the list about to open and returns its token. */
            fun register(request: Request): String =
                UUID.randomUUID().toString().also { pending[it] = request }

            fun from(intent: Intent): Request? =
                intent.getStringExtra(FileListActivity.EXTRA_SHOW_TOKEN)?.let { pending[it] }
                    ?.takeIf { it.plan.action == "show" }
        }
    }

    private var future: Future<Unit>? = null
    private val handler = Handler(Looper.getMainLooper())

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = executor.submit<Unit> {
            val result = try {
                @Suppress("DEPRECATION")
                val root = Environment.getExternalStorageDirectory()
                val found = CommandFiles.show(request.plan, request.sources, root)
                if (found.capped) {
                    // Honest about a partial list: the walk stopped at the result or text limit.
                    handler.post { Toast.makeText(application, R.string.ai_show_capped, Toast.LENGTH_LONG).show() }
                }
                val files = found.files
                    .filter { query.isEmpty() || it.name.contains(query, true) }
                    .mapNotNull {
                        if (Thread.currentThread().isInterrupted) return@submit
                        try { Paths.get(it.path).loadFileItem() } catch (_: Exception) { null }
                    }
                Success(files)
            } catch (exception: Exception) {
                Failure(valueCompat.value, exception)
            }
            postValue(result)
        }
    }

    override fun close() {
        future?.cancel(true)
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
