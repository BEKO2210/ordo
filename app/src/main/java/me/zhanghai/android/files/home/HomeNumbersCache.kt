/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

/**
 * The home screen's numbers from the last reload (category tiles, apps, trash), so a cold start shows them at once
 * instead of zeros and replaces them quietly when its own reload is done. Recent files and storage load anyway.
 */
object HomeNumbersCache {
    private const val VERSION = "1"

    fun format(state: HomeState): String =
        listOf(VERSION, state.appCount?.toString().orEmpty(), state.trashCount?.toString().orEmpty(),
            state.trashSize.toString(), state.categories.joinToString(",") { "${it.category.name}=${it.count}:${it.size}" })
            .joinToString(";")

    /** [HomeState] with the cached numbers, still loading; null for anything unreadable or from another version. */
    fun parse(stored: String?): HomeState? {
        val parts = stored?.split(';')?.takeIf { it.size == 5 && it[0] == VERSION } ?: return null
        val byName = parts[4].split(',').mapNotNull { item ->
            val (name, numbers) = item.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val (count, size) = numbers.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            name to CategorySummary(
                HomeCategory.entries.firstOrNull { it.name == name } ?: return@mapNotNull null,
                count.toLongOrNull() ?: return@mapNotNull null, size.toLongOrNull() ?: return@mapNotNull null
            )
        }.toMap()
        val categories = HomeCategory.entries.map { byName[it.name] ?: return null }
        return HomeState(
            categories = categories, isLoading = true, appCount = parts[1].toIntOrNull(),
            trashCount = parts[2].toIntOrNull(), trashSize = parts[3].toLongOrNull() ?: 0
        )
    }
}
