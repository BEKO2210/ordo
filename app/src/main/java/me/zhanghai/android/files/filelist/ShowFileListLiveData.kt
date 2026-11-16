/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Environment
import java8.nio.file.Paths
import me.zhanghai.android.files.ai.CommandFiles
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
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The files an AI "show" plan finds, searched again on every load, so the list follows deletes and renames. */
class ShowFileListLiveData(
    private val request: Request,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    /** What the command sheet understood: the plan, the folders it searches and a title saying it in words. */
    class Request(val plan: CommandPlan, val sources: List<File>, val title: String) {
        companion object {
            fun from(intent: Intent): Request? {
                val plan = intent.getStringExtra(FileListActivity.EXTRA_SHOW_PLAN)?.let(CommandPlan::parse)
                    ?: return null
                // Read-only either way; a plan that is not "show" never gets a list here.
                if (plan.action != "show") return null
                val sources = intent.getStringArrayExtra(FileListActivity.EXTRA_SHOW_SOURCES)?.map(::File)
                    ?: return null
                return Request(plan, sources, intent.getStringExtra(FileListActivity.EXTRA_SHOW_TITLE).orEmpty())
            }
        }
    }

    private var future: Future<Unit>? = null

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
                val files = CommandFiles.show(request.plan, request.sources, root)
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
