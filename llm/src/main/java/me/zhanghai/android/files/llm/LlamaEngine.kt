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
class LlamaEngine private constructor(private var handle: Long) : Closeable {
    fun prepare(prefix: String): Boolean = nativePrepare(checkOpen(), prefix)

    /** The answer, or null when cancelled or when the native side failed. */
    fun generate(suffix: String, grammar: String, maxTokens: Int): String? =
        nativeGenerate(checkOpen(), suffix, grammar, maxTokens)

    fun cancel() {
        if (handle != 0L) {
            nativeCancel(handle)
        }
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }

    private fun checkOpen(): Long = handle.also { check(it != 0L) { "engine closed" } }

    companion object {
        /** False on 32-bit phones, where the library is not shipped. */
        val isSupported: Boolean by lazy {
            try {
                System.loadLibrary("ordo_llm")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        fun load(path: String, threads: Int, contextSize: Int): LlamaEngine? {
            if (!isSupported) {
                return null
            }
            val handle = nativeLoad(path, threads, contextSize)
            return if (handle != 0L) LlamaEngine(handle) else null
        }

        @JvmStatic
        private external fun nativeLoad(path: String, threads: Int, contextSize: Int): Long

        @JvmStatic
        private external fun nativePrepare(handle: Long, prefix: String): Boolean

        @JvmStatic
        private external fun nativeGenerate(handle: Long, suffix: String, grammar: String, maxTokens: Int): String?

        @JvmStatic
        private external fun nativeCancel(handle: Long)

        @JvmStatic
        private external fun nativeFree(handle: Long)
    }
}
