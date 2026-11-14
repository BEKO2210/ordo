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
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.llm.LlamaEngine
import java.io.File
import java.security.MessageDigest

/** The on-device model: device check, download through DownloadManager, hash check. */
object CommandModel {
    // Qwen3.5-4B (Apache-2.0), Q4_K_M by unsloth, pinned to one revision and checked by hash.
    private const val URL = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/" +
        "e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_K_M.gguf"
    const val SIZE = 2_740_937_888L
    private const val SHA256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4"
    private const val NAME = "qwen3.5-4b-q4_k_m.gguf"
    private const val PREFS = "command_model"
    private const val KEY_DOWNLOAD = "download_id"
    private const val KEY_VERIFIED = "verified"

    // Measured on a Pixel 11 Pro XL: 3.3 GB peak with a 2048 token context.
    const val MIN_RAM = 6_000_000_000L
    private const val SPARE_SPACE = 500_000_000L

    data class DeviceCheck(val ramBytes: Long, val freeBytes: Long, val is64Bit: Boolean) {
        val ramOk get() = ramBytes >= MIN_RAM
        val spaceOk get() = freeBytes >= SIZE + SPARE_SPACE
        val ok get() = ramOk && spaceOk && is64Bit
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long, val paused: Boolean) : State
        data object Failed : State
        data object Verifying : State
        data object Ready : State
    }

    fun file(context: Context): File = File(context.getExternalFilesDir("models"), NAME)

    fun check(context: Context): DeviceCheck {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val dir = file(context).parentFile!!.apply { mkdirs() }
        val alreadyThere = file(context).takeIf { it.exists() }?.length() ?: 0L
        return DeviceCheck(
            ramBytes = memory.totalMem,
            freeBytes = StatFs(dir.path).availableBytes + alreadyThere,
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && LlamaEngine.isSupported
        )
    }

    fun state(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = file(context)
        if (prefs.getBoolean(KEY_VERIFIED, false) && file.length() == SIZE) {
            return State.Ready
        }
        val id = prefs.getLong(KEY_DOWNLOAD, -1)
        if (id == -1L) {
            return if (file.length() == SIZE) State.Verifying else State.Missing
        }
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return State.Missing
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> State.Verifying
                DownloadManager.STATUS_FAILED -> State.Failed
                else -> State.Downloading(bytes, SIZE, status == DownloadManager.STATUS_PAUSED)
            }
        }
        return State.Missing
    }

    fun startDownload(context: Context) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getLong(KEY_DOWNLOAD, -1).takeIf { it != -1L }?.let { manager.remove(it) }
        file(context).delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Ordo KI")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, "models", NAME)
        prefs.edit().putLong(KEY_DOWNLOAD, manager.enqueue(request)).putBoolean(KEY_VERIFIED, false).apply()
    }

    /** Hashes the downloaded file; a wrong file is deleted so the next attempt starts clean. */
    suspend fun verify(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = file(context)
        val ok = file.length() == SIZE && sha256(file) == SHA256
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DOWNLOAD).putBoolean(KEY_VERIFIED, ok).apply()
        if (!ok) {
            file.delete()
        }
        ok
    }

    fun remove(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getLong(KEY_DOWNLOAD, -1).takeIf { it != -1L }?.let {
            context.getSystemService(DownloadManager::class.java).remove(it)
        }
        prefs.edit().clear().apply()
        CommandEngine.release()
        file(context).delete()
    }

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
