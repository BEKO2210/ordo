/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.llm.LlamaEngine
import java.io.File
import java.security.MessageDigest

/** The on-device model: device check, download through DownloadManager, hash check. */
object CommandModel {
    // Qwen3.5-4B (Apache-2.0), Q4_0 by unsloth, pinned to one revision and checked by hash. Q4_0, not
    // Q4_K_M: Arm's KleidiAI kernels speed it up 3x reading and 1.5x writing (Tab S9 Ultra) at the
    // same accuracy (docs/KI-BEFEHLE.md).
    private const val URL = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/" +
        "e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_0.gguf"
    const val SIZE = 2_583_221_408L
    private const val SHA256 = "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb"
    const val FILE_NAME = "qwen3.5-4b-q4_0.gguf"
    private const val PREFS = "command_model"
    private const val KEY_DOWNLOAD = "download_id"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_STAMP = "stamp"
    private const val KEY_DOWNLOAD_FOR = "download_for"

    // 3.3 GB peak measured on a Pixel 11 Pro XL; phones sold as "6 GB" report about 5.5 GB.
    const val MIN_RAM = 5_500_000_000L
    const val SPARE_SPACE = 500_000_000L

    data class DeviceCheck(val ramBytes: Long, val freeBytes: Long, val is64Bit: Boolean) {
        val ramOk get() = ramBytes >= MIN_RAM
        val spaceOk get() = freeBytes >= SIZE + SPARE_SPACE
        val ok get() = ModelChoice.pick(ramBytes, is64Bit) != ModelChoice.TOO_WEAK && spaceOk
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long, val paused: Boolean, val waitingForWifi: Boolean) : State
        data class Failed(val noSpace: Boolean) : State
        data object Verifying : State
        data object Ready : State
    }

    // Null when shared storage is not mounted; then the download fails cleanly instead of crashing.
    fun file(context: Context): File =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), FILE_NAME)

    fun check(context: Context): DeviceCheck {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val dir = file(context).parentFile!!.apply { mkdirs() }
        val alreadyThere = file(context).takeIf { it.exists() }?.length() ?: 0L
        return DeviceCheck(
            ramBytes = memory.totalMem,
            freeBytes = try {
                StatFs(dir.path).availableBytes + alreadyThere
            } catch (e: IllegalArgumentException) {
                0L
            },
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && LlamaEngine.isSupported
        )
    }

    fun state(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = file(context)
        // A model of an earlier app version would only take up gigabytes.
        file.parentFile?.listFiles { other -> other.name.endsWith(".gguf") && other.name != FILE_NAME }
            ?.forEach { it.delete() }
        if (prefs.getBoolean(KEY_VERIFIED, false)) {
            // The hash only counts for the exact file it was computed on; any change means hash again.
            if (file.length() == SIZE && prefs.getString(KEY_STAMP, null) == stamp(file)) {
                return State.Ready
            }
            prefs.edit().putBoolean(KEY_VERIFIED, false).apply()
        }
        var id = prefs.getLong(KEY_DOWNLOAD, -1)
        // A download started by an older app version was for another model file: forget it, so its old
        // result (e.g. "not enough space") is not shown for the current model.
        if (id != -1L && prefs.getString(KEY_DOWNLOAD_FOR, null) != FILE_NAME) {
            context.getSystemService(DownloadManager::class.java).remove(id)
            prefs.edit().remove(KEY_DOWNLOAD).commit()
            id = -1L
        }
        if (id == -1L) {
            return if (file.length() == SIZE) State.Verifying else State.Missing
        }
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                return when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> State.Verifying
                    DownloadManager.STATUS_FAILED ->
                        State.Failed(reason == DownloadManager.ERROR_INSUFFICIENT_SPACE)
                    else -> State.Downloading(
                        bytes, SIZE, status == DownloadManager.STATUS_PAUSED,
                        reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                    )
                }
            }
        }
        // The download entry is gone (cleared, cancelled): a complete file is still worth a hash check.
        return if (file.length() == SIZE) State.Verifying else State.Missing
    }

    /** False when the system download service refused the job. Wi-Fi only unless [mobileData]. */
    fun startDownload(context: Context, mobileData: Boolean): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getLong(KEY_DOWNLOAD, -1).takeIf { it != -1L }?.let { manager.remove(it) }
        prefs.edit().remove(KEY_DOWNLOAD).putBoolean(KEY_VERIFIED, false).commit()
        if (file(context).length() == SIZE) {
            return true // complete already; state() sends it to the hash check
        }
        file(context).delete()
        return try {
            val request = DownloadManager.Request(Uri.parse(URL))
                .setTitle("Ordo KI")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(mobileData)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, "models", FILE_NAME)
            // commit, not apply: if the app dies right after enqueue, the id must already be stored.
            prefs.edit().putLong(KEY_DOWNLOAD, manager.enqueue(request)).putString(KEY_DOWNLOAD_FOR, FILE_NAME).commit()
            true
        } catch (e: RuntimeException) {
            e.printStackTrace()
            false
        }
    }

    /** Hashes the downloaded file; a wrong file is deleted so the next attempt starts clean. */
    suspend fun verify(context: Context): Boolean = verifyLock.withLock {
        withContext(Dispatchers.IO) { verifyLocked(context) }
    }

    // One hash at a time: reopening the sheet mid-check must not start a second one on the same file.
    private val verifyLock = Mutex()

    private fun verifyLocked(context: Context): Boolean {
        val file = file(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_VERIFIED, false) && prefs.getString(KEY_STAMP, null) == stamp(file)) {
            return true
        }
        val ok = try {
            file.length() == SIZE && sha256(file) == SHA256
        } catch (e: java.io.IOException) {
            false
        }
        prefs.edit().remove(KEY_DOWNLOAD).putBoolean(KEY_VERIFIED, ok).putString(KEY_STAMP, stamp(file)).apply()
        if (!ok) {
            file.delete()
        }
        return ok
    }

    /** Frees the model from memory first, then deletes it. */
    suspend fun remove(context: Context) {
        CommandEngine.release()
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getLong(KEY_DOWNLOAD, -1).takeIf { it != -1L }?.let {
                context.getSystemService(DownloadManager::class.java).remove(it)
            }
            prefs.edit().clear().commit()
            file(context).delete()
        }
    }

    private fun stamp(file: File): String = "${file.length()}:${file.lastModified()}"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
