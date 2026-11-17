/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R

/**
 * Undo after the tag editor: the screen that opened it shows "Info saved · Undo" for 10 s. Undo puts each original
 * back from its backup; otherwise the backups are deleted when the bar goes away (and any left over after a day).
 */
object TagEditUndo {
    private const val EXTRA_FILES = "me.zhanghai.android.files.viewer.audio.extra.TAG_FILES"
    private const val EXTRA_BACKUPS = "me.zhanghai.android.files.viewer.audio.extra.TAG_BACKUPS"
    private const val UNDO_MS = 10_000
    private const val STALE_MS = 24L * 60 * 60 * 1000

    fun backupDir(context: Context): File = File(context.cacheDir, "tag-backups")

    fun resultIntent(done: List<Pair<File, File>>): Intent =
        Intent()
            .putStringArrayListExtra(EXTRA_FILES, ArrayList(done.map { it.first.path }))
            .putStringArrayListExtra(EXTRA_BACKUPS, ArrayList(done.map { it.second.path }))

    fun cleanUp(context: Context) {
        val now = System.currentTimeMillis()
        backupDir(context).listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach { it.delete() }
    }

    /** Shows the bar for a result of [MusicTagEditorActivity]; [onChanged] runs after the save and after an undo. */
    fun offer(
        context: Context,
        anchor: View,
        scope: LifecycleCoroutineScope,
        data: Intent?,
        onChanged: () -> Unit
    ) {
        val files = data?.getStringArrayListExtra(EXTRA_FILES)?.map(::File) ?: return
        val backups = data.getStringArrayListExtra(EXTRA_BACKUPS)?.map(::File) ?: return
        onChanged()
        var undone = false
        Snackbar.make(anchor, R.string.music_tag_saved, UNDO_MS)
            .setAction(R.string.music_tag_undo) {
                undone = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        files.zip(backups).forEach { (file, backup) ->
                            runCatching { SongTagIo.restore(file, backup) }.onFailure { it.printStackTrace() }
                            backup.delete()
                        }
                    }
                    MusicTagEditorActivity.scan(context, files)
                    Snackbar.make(anchor, R.string.music_tag_undone, Snackbar.LENGTH_SHORT).show()
                    onChanged()
                }
            }
            .addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (!undone) backups.forEach { it.delete() }
                }
            })
            .show()
    }
}
