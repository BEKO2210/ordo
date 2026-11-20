/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

/** What may be measured and kept about a film's sound; pure, so the rules are unit-tested. */
internal enum class LoudnessPolicy { SKIP, MEMORY, CACHE }

/**
 * Only files on this device (`file:`) are measured; vault items (`ordo-vault:`) and anything else never. With
 * "Don't remember places" the result stays in memory for this player and is not written to the cache.
 */
internal fun loudnessPolicy(mediaId: String, noHistory: Boolean): LoudnessPolicy = when {
    scheme(mediaId) != "file" -> LoudnessPolicy.SKIP
    noHistory -> LoudnessPolicy.MEMORY
    else -> LoudnessPolicy.CACHE
}

/** A delay is kept for Ordo's own paths, never for another app's `content:` items or vault items. */
internal fun remembersDelay(mediaId: String): Boolean = when (scheme(mediaId)) {
    null, "content", VAULT_SCHEME -> false
    else -> true
}

private const val VAULT_SCHEME = "ordo-vault"

private fun scheme(mediaId: String): String? =
    mediaId.substringBefore(':', "").lowercase().takeIf { it.isNotEmpty() }
