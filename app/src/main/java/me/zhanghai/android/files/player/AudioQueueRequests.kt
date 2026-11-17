/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java8.nio.file.Path

/**
 * The queue Ordo's own screens hand to the player. AudioPlayerActivity is exported, so the queue never travels in
 * the intent: the intent only carries a random token for a request kept in this process. Another app cannot forge
 * one, and after the process is gone the token is void.
 */
object AudioQueueRequests {
    class Request(val paths: List<Path>, val startIndex: Int)

    private val pending = ConcurrentHashMap<String, Request>()

    /** Keeps [request] for the player about to open and returns its token. */
    fun register(request: Request): String =
        UUID.randomUUID().toString().also { pending[it] = request }

    fun from(token: String?): Request? = token?.let { pending[it] }

    /** The player has taken the queue over (or is gone for good): the token is void. */
    fun release(token: String?) {
        token?.let { pending.remove(it) }
    }
}
