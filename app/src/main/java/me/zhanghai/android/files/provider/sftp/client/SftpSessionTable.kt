/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.provider.sftp.client

/**
 * Job threads get their own sessions so cancelling one copy cannot drop another
 * job's socket, or the shared listing session. Map updates never do network I/O.
 */
internal class SftpSessionTable<K : Any, V : Any> {
    private val lock = Any()
    private val shared = mutableMapOf<K, V>()
    private val jobs = mutableMapOf<Thread, MutableMap<K, V>>()
    private val cancelled = mutableSetOf<Thread>()

    fun bindJob(thread: Thread) {
        synchronized(lock) {
            cancelled -= thread
            jobs.getOrPut(thread) { mutableMapOf() }
        }
    }

    fun isJobBound(thread: Thread): Boolean = synchronized(lock) { thread in jobs }

    fun wasCancelled(thread: Thread): Boolean = synchronized(lock) { thread in cancelled }

    fun lookupJob(thread: Thread, key: K): V? = synchronized(lock) { jobs[thread]?.get(key) }

    fun lookupShared(key: K): V? = synchronized(lock) { shared[key] }

    fun storeJob(thread: Thread, key: K, value: V): SftpJobStore<V> = synchronized(lock) {
        val map = jobs[thread] ?: return SftpJobStore.Unbound
        SftpJobStore.Stored(map.put(key, value))
    }

    fun putSharedIfAbsent(key: K, value: V): V = synchronized(lock) {
        val existing = shared[key]
        if (existing != null) existing else {
            shared[key] = value
            value
        }
    }

    fun takeJob(thread: Thread): List<V> = synchronized(lock) {
        cancelled += thread
        jobs.remove(thread)?.values?.toList().orEmpty()
    }

    fun dropMatching(match: (V) -> Boolean): List<V> = synchronized(lock) {
        val removed = mutableListOf<V>()
        val sharedKeys = shared.entries.filter { match(it.value) }.map { it.key }
        for (key in sharedKeys) {
            shared.remove(key)?.let { removed += it }
        }
        for (map in jobs.values) {
            val keys = map.entries.filter { match(it.value) }.map { it.key }
            for (key in keys) {
                map.remove(key)?.let { removed += it }
            }
        }
        removed
    }
}

internal sealed class SftpJobStore<out V> {
    object Unbound : SftpJobStore<Nothing>()
    data class Stored<V>(val previous: V?) : SftpJobStore<V>()
}

/** Close runs on [executor], so a UI-thread cancel does not wait for SSH disconnect. */
internal fun sftpCloseOnBackground(executor: java.util.concurrent.Executor, close: () -> Unit) {
    executor.execute { close() }
}
