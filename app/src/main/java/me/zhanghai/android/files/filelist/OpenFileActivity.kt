/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Bundle
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.player.isPlayableAudio
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.trash.Trash
import java.io.File
import java.io.IOException
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.audio.AudioPlayerActivity

class OpenFileActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val path = intent.extraPath
        val mimeType = intent.type?.asMimeTypeOrNull()
        if (path != null && mimeType != null && isSharedFile(path)) {
            openFile(path, mimeType)
        }
        finish()
    }

    // This activity is exported for home-screen shortcuts, so any app can call it: only files on shared
    // storage (never Ordo's own data, Android/data or the trash), and the viewer only gets to read.
    private fun isSharedFile(path: Path): Boolean {
        if (path.isArchivePath || !path.isLinuxPath) {
            return false
        }
        val canonical = try {
            File(path.toString()).canonicalPath
        } catch (e: IOException) {
            return false
        }
        return Trash.rootOf(canonical) != null
    }

    private fun openFile(path: Path, mimeType: MimeType) {
        val intent = path.fileProviderUri.createViewIntent(mimeType)
            .apply {
                extraPath = path
                if (mimeType.isPlayableAudio) {
                    setClass(this@OpenFileActivity, AudioPlayerActivity::class.java)
                    AudioPlayerActivity.putExtras(this, listOf(path), 0)
                }
            }
        startActivitySafe(intent)
    }

    companion object {
        private const val ACTION_OPEN_FILE = "me.zhanghai.android.files.intent.action.OPEN_FILE"

        fun createIntent(path: Path, mimeType: MimeType): Intent =
            Intent(ACTION_OPEN_FILE)
                .setPackage(application.packageName)
                .setType(mimeType.value)
                .apply { extraPath = path }
    }
}
