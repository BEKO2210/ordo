/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback.DISMISS_EVENT_MANUAL
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.lang.ref.WeakReference
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
    private const val STATE_FILES = "tag_undo_files"
    private const val STATE_BACKUPS = "tag_undo_backups"
    private const val STATE_DEADLINE = "tag_undo_deadline"
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
        show(context, anchor, scope, files, backups, UNDO_MS, onChanged)
    }

    /** The open undo of this screen goes into its saved state (a rotation, a killed process) with its deadline. */
    fun save(context: Context, outState: Bundle) {
        val pending = pending?.takeIf { it.owner.get() === context } ?: return
        if (pending.deadlineMs <= System.currentTimeMillis()) return
        outState.putStringArrayList(STATE_FILES, ArrayList(pending.files.map { it.path }))
        outState.putStringArrayList(STATE_BACKUPS, ArrayList(pending.backups.map { it.path }))
        outState.putLong(STATE_DEADLINE, pending.deadlineMs)
    }

    /** A screen recreated within the 10 s shows the bar again for the time that is left. */
    fun reoffer(
        context: Context,
        anchor: View,
        scope: LifecycleCoroutineScope,
        saved: Bundle?,
        onChanged: () -> Unit
    ) {
        val files = saved?.getStringArrayList(STATE_FILES)?.map(::File) ?: return
        val backups = saved.getStringArrayList(STATE_BACKUPS)?.map(::File) ?: return
        val left = remainingMs(saved.getLong(STATE_DEADLINE), System.currentTimeMillis())
        if (left <= 0) {
            backups.forEach { it.delete() }
            return
        }
        show(context, anchor, scope, files, backups, left.toInt(), onChanged)
    }

    internal fun remainingMs(deadlineMs: Long, nowMs: Long): Long = deadlineMs - nowMs

    private class Pending(
        val owner: WeakReference<Context>,
        val files: List<File>,
        val backups: List<File>,
        val deadlineMs: Long
    )

    private var pending: Pending? = null

    private fun show(
        context: Context,
        anchor: View,
        scope: LifecycleCoroutineScope,
        files: List<File>,
        backups: List<File>,
        durationMs: Int,
        onChanged: () -> Unit
    ) {
        var undone = false
        pending = Pending(WeakReference(context), files, backups, System.currentTimeMillis() + durationMs)
        Snackbar.make(anchor, R.string.music_tag_saved, durationMs)
            .setAction(R.string.music_tag_undo) {
                undone = true
                scope.launch {
                    val failed = withContext(Dispatchers.IO) {
                        files.zip(backups).forEach { (file, backup) ->
                            runCatching { SongTagIo.restore(file, backup) }
                                .onSuccess { backup.delete() }
                                .onFailure { it.printStackTrace() }
                        }
                        backups.any { it.exists() }
                    }
                    if (failed) {
                        Snackbar.make(
                            anchor, context.getString(
                                R.string.music_tag_failed, context.getString(R.string.music_tag_restore_error)
                            ), Snackbar.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    MusicTagEditorActivity.scan(context, files)
                    Snackbar.make(anchor, R.string.music_tag_undone, Snackbar.LENGTH_SHORT).show()
                    onChanged()
                }
            }
            .addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    // The screen going away (a rotation) is no decision: the backups stay for the new screen.
                    if (event == DISMISS_EVENT_MANUAL) return
                    pending = null
                    if (!undone) backups.forEach { it.delete() }
                }
            })
            .show()
    }
}
