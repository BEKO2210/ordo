/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.player.isPlayableAudio
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.trash.Trash
import me.zhanghai.android.files.util.createInstallPackageIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.audio.AudioPlayerActivity
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.pdf.PdfViewerActivity
import me.zhanghai.android.files.viewer.video.VideoPlayerActivity

class OpenFileActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val path = intent.extraPath
        val mimeType = intent.type?.asMimeTypeOrNull()
        if (path != null && mimeType != null) {
            if (!File(path.toString()).isFile) {
                showToast(R.string.file_open_unavailable)
            } else if (isSharedFile(path)) {
                openFile(path, mimeType)
            }
        }
        if (!isFinishing && !isApkDialogVisible) finish()
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
        if (mimeType.isApk) {
            openApk(path)
            return
        }
        val intent = path.fileProviderUri.createViewIntent(mimeType)
            .apply {
                extraPath = path
                if (mimeType.isImage) {
                    setClass(this@OpenFileActivity, ImageViewerActivity::class.java)
                    ImageViewerActivity.putExtras(this, listOf(path), 0)
                } else if (mimeType == MimeType.PDF) {
                    setClass(this@OpenFileActivity, PdfViewerActivity::class.java)
                } else if (mimeType.isPlayableAudio) {
                    setClass(this@OpenFileActivity, AudioPlayerActivity::class.java)
                    AudioPlayerActivity.putExtras(this, listOf(path), 0)
                } else if (mimeType.isVideo) {
                    setClass(this@OpenFileActivity, VideoPlayerActivity::class.java)
                    VideoPlayerActivity.putExtras(this, listOf(path), 0)
                }
            }
        startActivitySafe(intent)
    }

    private var isApkDialogVisible = false

    private fun openApk(path: Path) {
        when (Settings.OPEN_APK_DEFAULT_ACTION.valueCompat) {
            OpenApkDefaultAction.INSTALL -> installApk(path)
            OpenApkDefaultAction.VIEW -> viewApk(path)
            OpenApkDefaultAction.ASK -> {
                isApkDialogVisible = true
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.file_open_apk_message)
                    .setPositiveButton(R.string.install) { _, _ -> installApk(path) }
                    .setNegativeButton(R.string.view) { _, _ -> viewApk(path) }
                    .setNeutralButton(android.R.string.cancel, null)
                    .setOnDismissListener { finish() }
                    .show()
            }
        }
    }

    private fun installApk(path: Path) {
        startActivitySafe(path.fileProviderUri.createInstallPackageIntent())
    }

    private fun viewApk(path: Path) {
        startActivitySafe(FileListActivity.createViewIntent(path.createArchiveRootPath()))
    }

    companion object {
        private const val ACTION_OPEN_FILE = "me.zhanghai.android.files.intent.action.OPEN_FILE"

        fun createIntent(path: Path, mimeType: MimeType): Intent =
            Intent(application, OpenFileActivity::class.java)
                .setAction(ACTION_OPEN_FILE)
                .setType(mimeType.value)
                .apply { extraPath = path }
    }
}
