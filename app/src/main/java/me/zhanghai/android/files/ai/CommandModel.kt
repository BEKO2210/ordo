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

/**
 * The on-device model: device check, download through DownloadManager, hash check. A design may add a LoRA adapter
 * ([CommandDesign.adapter]); it is downloaded, checked and deleted together with the model, as one more [Part].
 */
object CommandModel {
    /** One downloaded, hash-checked file in the model folder. */
    data class Part(val fileName: String, val size: Long, val sha256: String, val url: String) {
        init {
            require(fileName.isNotBlank() && '/' !in fileName && size > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    // Qwen3.5-4B (Apache-2.0), Q4_0 by unsloth, pinned to one revision and checked by hash. Q4_0, not
    // Q4_K_M: Arm's KleidiAI kernels speed it up 3x reading and 1.5x writing (Tab S9 Ultra) at the
    // same accuracy (docs/KI-BEFEHLE.md).
    private const val URL = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/" +
        "e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_0.gguf"
    const val SIZE = 2_583_221_408L
    internal const val SHA256 = "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb"
    const val FILE_NAME = "qwen3.5-4b-q4_0.gguf"
    private const val PREFS = "command_model"
    private const val KEY_DOWNLOAD = "download_id"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_STAMP = "stamp"
    private const val KEY_DOWNLOAD_FOR = "download_for"

    // The 4B was only measured on 12 GB devices (Tab S9 Ultra: 4.3 GB app PSS with Q4_K_M). totalMem of 12 GB devices lies
    // above 10 GB, of "8 GB" phones below, so smaller phones stay off until one is measured (docs/KI-BEFEHLE.md).
    const val MIN_RAM = 10_000_000_000L
    const val SPARE_SPACE = 500_000_000L
    val MODEL = Part(FILE_NAME, SIZE, SHA256, URL)

    /** The model first, then the design's adapter, if it has one. */
    fun parts(design: CommandDesign): List<Part> = listOfNotNull(MODEL, design.adapter)

    /** Bytes still to download for [design]: parts already complete on disk do not count. */
    fun missingBytes(context: Context, design: CommandDesign): Long =
        parts(design).filter { file(context, it).length() != it.size }.sumOf { it.size }

    data class DeviceCheck(
        val ramBytes: Long, val freeBytes: Long, val is64Bit: Boolean, val dotProd: Boolean, val needBytes: Long = SIZE
    ) {
        val ramOk get() = ramBytes >= MIN_RAM
        val spaceOk get() = freeBytes >= needBytes + SPARE_SPACE
        val ok get() = ModelChoice.pick(ramBytes, is64Bit, dotProd) != ModelChoice.TOO_WEAK && spaceOk
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long, val paused: Boolean, val waitingForWifi: Boolean) : State
        data class Failed(val noSpace: Boolean) : State
        data object Verifying : State
        data object Ready : State
    }

    // Null when shared storage is not mounted; then the download fails cleanly instead of crashing.
    fun file(context: Context, part: Part = MODEL): File =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), part.fileName)

    fun check(context: Context, design: CommandDesign): DeviceCheck {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val dir = file(context).parentFile!!.apply { mkdirs() }
        val alreadyThere = parts(design).sumOf { part -> file(context, part).takeIf { it.exists() }?.length() ?: 0L }
        return DeviceCheck(
            ramBytes = memory.totalMem,
            freeBytes = try {
                StatFs(dir.path).availableBytes + alreadyThere
            } catch (e: IllegalArgumentException) {
                0L
            },
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && LlamaEngine.isSupported,
            dotProd = try {
                ModelChoice.hasDotProd(File("/proc/cpuinfo").readText())
            } catch (e: Exception) {
                false
            },
            needBytes = parts(design).sumOf { it.size }
        )
    }

    /** All parts of [design] together: failed if one failed, downloading while one downloads, ready when all are. */
    fun state(context: Context, design: CommandDesign): State {
        val parts = parts(design)
        // A model or adapter of an earlier app version or another design would only take up space.
        file(context).parentFile?.listFiles { other -> other.name.endsWith(".gguf") && parts.none { it.fileName == other.name } }
            ?.forEach { it.delete() }
        return combine(parts.map { partState(context, it) }, parts)
    }

    internal fun combine(states: List<State>, parts: List<Part>): State {
        states.firstOrNull { it is State.Failed }?.let { return it }
        val downloading = states.filterIsInstance<State.Downloading>()
        if (downloading.isNotEmpty()) {
            val bytes = states.zip(parts).sumOf { (state, part) ->
                when (state) {
                    is State.Downloading -> state.bytes
                    State.Missing -> 0L
                    else -> part.size
                }
            }
            return State.Downloading(bytes, parts.sumOf { it.size }, downloading.any { it.paused },
                downloading.any { it.waitingForWifi })
        }
        return when {
            State.Missing in states -> State.Missing
            State.Verifying in states -> State.Verifying
            else -> State.Ready
        }
    }

    // The model keeps the keys it always had, so an update does not lose a finished download or hash check. An adapter's
    // keys name its expected hash too: a new adapter under the same file name is hashed again, never taken as checked.
    private fun key(part: Part, name: String) = if (part == MODEL) name else "$name:${part.fileName}:${part.sha256}"

    private fun partState(context: Context, part: Part): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = file(context, part)
        if (prefs.getBoolean(key(part, KEY_VERIFIED), false)) {
            // The hash only counts for the exact file it was computed on; any change means hash again.
            if (file.length() == part.size && prefs.getString(key(part, KEY_STAMP), null) == stamp(file)) {
                return State.Ready
            }
            prefs.edit().putBoolean(key(part, KEY_VERIFIED), false).apply()
        }
        var id = prefs.getLong(key(part, KEY_DOWNLOAD), -1)
        // A download started by an older app version was for another model file: forget it, so its old
        // result (e.g. "not enough space") is not shown for the current model.
        if (id != -1L && prefs.getString(key(part, KEY_DOWNLOAD_FOR), null) != part.fileName) {
            context.getSystemService(DownloadManager::class.java).remove(id)
            prefs.edit().remove(key(part, KEY_DOWNLOAD)).commit()
            id = -1L
        }
        if (id == -1L) {
            return if (file.length() == part.size) State.Verifying else State.Missing
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
                        bytes, part.size, status == DownloadManager.STATUS_PAUSED,
                        reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                    )
                }
            }
        }
        // The download entry is gone (cleared, cancelled): a complete file is still worth a hash check.
        return if (file.length() == part.size) State.Verifying else State.Missing
    }

    /** False when the system download service refused a job. Wi-Fi only unless [mobileData]. */
    fun startDownload(context: Context, mobileData: Boolean, design: CommandDesign): Boolean =
        parts(design).all { part -> partState(context, part) == State.Ready || startDownload(context, mobileData, part) }

    private fun startDownload(context: Context, mobileData: Boolean, part: Part): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getLong(key(part, KEY_DOWNLOAD), -1).takeIf { it != -1L }?.let { manager.remove(it) }
        prefs.edit().remove(key(part, KEY_DOWNLOAD)).putBoolean(key(part, KEY_VERIFIED), false).commit()
        val file = file(context, part)
        if (file.length() == part.size) {
            return true // complete already; state() sends it to the hash check
        }
        file.delete()
        return try {
            val request = DownloadManager.Request(Uri.parse(part.url))
                .setTitle("Ordo KI")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(mobileData)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, "models", part.fileName)
            // commit, not apply: if the app dies right after enqueue, the id must already be stored.
            prefs.edit().putLong(key(part, KEY_DOWNLOAD), manager.enqueue(request))
                .putString(key(part, KEY_DOWNLOAD_FOR), part.fileName).commit()
            true
        } catch (e: RuntimeException) {
            e.printStackTrace()
            false
        }
    }

    /** Hashes the downloaded files; a wrong file is deleted so the next attempt starts clean. */
    suspend fun verify(context: Context, design: CommandDesign): Boolean = verifyLock.withLock {
        withContext(Dispatchers.IO) { parts(design).all { verifyLocked(context, it) } }
    }

    // One hash at a time: reopening the sheet mid-check must not start a second one on the same file.
    private val verifyLock = Mutex()

    private fun verifyLocked(context: Context, part: Part): Boolean {
        val file = file(context, part)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(key(part, KEY_VERIFIED), false) && prefs.getString(key(part, KEY_STAMP), null) == stamp(file)) {
            return true
        }
        val ok = try {
            file.length() == part.size && sha256(file) == part.sha256
        } catch (e: java.io.IOException) {
            false
        }
        prefs.edit().remove(key(part, KEY_DOWNLOAD)).putBoolean(key(part, KEY_VERIFIED), ok)
            .putString(key(part, KEY_STAMP), stamp(file)).apply()
        if (!ok) {
            file.delete()
        }
        return ok
    }

    /** Frees the model from memory first, then deletes it and every adapter next to it. */
    suspend fun remove(context: Context) {
        CommandEngine.release()
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.all.filterKeys { it == KEY_DOWNLOAD || it.startsWith("$KEY_DOWNLOAD:") }.values.forEach {
                (it as? Long)?.takeIf { id -> id != -1L }?.let { id ->
                    context.getSystemService(DownloadManager::class.java).remove(id)
                }
            }
            prefs.edit().clear().commit()
            file(context).parentFile?.listFiles { other -> other.name.endsWith(".gguf") }?.forEach { it.delete() }
            // The saved prompt state belongs to this model and is useless without it.
            CommandEngine.stateDir(context).deleteRecursively()
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
