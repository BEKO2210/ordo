/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.llm

import java.io.Closeable

/**
 * One loaded model. Not thread-safe: call everything but [cancel] from one thread.
 * [prepare] decodes a fixed prompt prefix once; each [generate] restarts from that saved state.
 */
class LlamaEngine private constructor(@Volatile private var handle: Long) : Closeable {
    // cancel() comes from another thread; the lock keeps it from touching an engine close() just freed.
    private val lock = Any()

    fun prepare(prefix: String): Boolean = nativePrepare(checkOpen(), prefix.utf8())

    /** Bytes of the state [prepare] or [restoreState] left, 0 when there is none. */
    val stateSize: Long
        get() = nativeStateSize(checkOpen())

    /** Writes the prompt state to [path] (atomically); false on any write error, nothing half-written stays. */
    fun saveState(path: String): Boolean = nativeSaveState(checkOpen(), path.utf8())

    /**
     * Loads a state saved by [saveState] in place of [prepare]. False when the file is missing, damaged or does not
     * fit this model; then call [prepare]. The caller keys the file by model, prompt and context size.
     */
    fun restoreState(path: String): Boolean = nativeRestoreState(checkOpen(), path.utf8())

    /** The answer, or null when cancelled or when the native side failed. */
    fun generate(suffix: String, grammar: String, maxTokens: Int): String? =
        nativeGenerate(checkOpen(), suffix.utf8(), grammar.utf8(), maxTokens)?.toString(Charsets.UTF_8)

    fun cancel() {
        synchronized(lock) {
            if (handle != 0L) {
                nativeCancel(handle)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                nativeFree(handle)
                handle = 0
            }
        }
    }

    private fun checkOpen(): Long = handle.also { check(it != 0L) { "engine closed" } }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

    companion object {
        /** False on 32-bit phones, where the library is not shipped. */
        val isSupported: Boolean by lazy {
            try {
                System.loadLibrary("ordo_llm")
                true
            } catch (e: Throwable) {
                false
            }
        }

        fun load(path: String, threads: Int, contextSize: Int): LlamaEngine? {
            if (!isSupported) {
                return null
            }
            val handle = nativeLoad(path.toByteArray(Charsets.UTF_8), threads, contextSize)
            return if (handle != 0L) LlamaEngine(handle) else null
        }

        @JvmStatic
        private external fun nativeLoad(path: ByteArray, threads: Int, contextSize: Int): Long

        @JvmStatic
        private external fun nativePrepare(handle: Long, prefix: ByteArray): Boolean

        @JvmStatic
        private external fun nativeGenerate(handle: Long, suffix: ByteArray, grammar: ByteArray, maxTokens: Int): ByteArray?

        @JvmStatic
        private external fun nativeStateSize(handle: Long): Long

        @JvmStatic
        private external fun nativeSaveState(handle: Long, path: ByteArray): Boolean

        @JvmStatic
        private external fun nativeRestoreState(handle: Long, path: ByteArray): Boolean

        @JvmStatic
        private external fun nativeCancel(handle: Long)

        @JvmStatic
        private external fun nativeFree(handle: Long)
    }
}
