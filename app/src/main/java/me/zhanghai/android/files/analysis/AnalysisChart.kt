/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

/**
 * Prepares the numbers for the overview's charts. The rules here are what keeps a chart readable when one part
 * dominates: small parts merge, and what is drawn has a floor. Labels always show the real numbers.
 */
object AnalysisChart {
    /** Key of the part that stands for several small ones. */
    const val OTHERS = "others"

    /** Under this share of the ring a file type is a sliver; two or more of them merge. */
    const val MIN_SLICE_SHARE = 0.03

    /** Degrees a slice gets at least, so the smallest one can still be seen. */
    const val MIN_SLICE_SWEEP = 7f

    /** A folder with this share of the map or more is opened up into its subfolders. */
    const val SPLIT_SHARE = 0.4

    /** Under this share a folder is not worth a tile of its own. */
    const val MIN_TILE_SHARE = 0.02

    /** Share of the map a tile gets at least: enough for a finger and a label. */
    const val MIN_TILE_AREA = 0.07f

    const val MAX_TILES = 8

    /** A part of the ring; [members] are the keys an [OTHERS] part stands for. */
    class Part(val key: String, val value: Long, val members: List<String> = emptyList())

    /**
     * A tile of the folder map. [direct] is a folder's own files without its subfolders, shown when the folder was
     * opened up. An [OTHERS] tile has no path and stands for [count] folders.
     */
    class Tile(val path: String, val size: Long, val direct: Boolean = false, val count: Int = 1)

    /**
     * Parts for a ring. The given order stays (colour follows the kind, never its rank) and empty parts drop out.
     * Parts under [minShare] merge into one [OTHERS] part at the end, because a ring of slivers says nothing;
     * a single small part stays itself.
     */
    fun slices(values: List<Pair<String, Long>>, minShare: Double = MIN_SLICE_SHARE): List<Part> {
        val present = values.filter { it.second > 0 }
        val total = present.sumOf { it.second }
        val small = present.filter { it.second < total * minShare }
        if (small.size < 2) return present.map { Part(it.first, it.second) }
        return present.filter { it !in small }.map { Part(it.first, it.second) } +
            Part(OTHERS, small.sumOf { it.second }, small.map { it.first })
    }

    /**
     * Sizes on screen (degrees of a ring, area of a map) for [values], adding up to [whole]: proportional, but
     * never under [min], so the smallest part can still be seen and tapped. The floor is taken from the larger
     * parts in proportion to their size.
     */
    fun extents(values: List<Long>, whole: Float, min: Float): FloatArray {
        val result = FloatArray(values.size)
        if (values.isEmpty()) return result
        if (values.size * min >= whole) return result.apply { fill(whole / values.size) }
        val floored = BooleanArray(values.size)
        // A part lifted to the floor shrinks the others, which can push the next one under it: repeat until stable.
        while (true) {
            val rest = whole - floored.count { it } * min
            val sum = values.indices.filter { !floored[it] }.sumOf { values[it].toDouble() }
            var changed = false
            for (i in values.indices) {
                result[i] = if (floored[i] || sum <= 0) min else (values[i] / sum * rest).toFloat()
                if (!floored[i] && result[i] < min) {
                    floored[i] = true
                    changed = true
                }
            }
            if (!changed) return result
        }
    }

    /**
     * The folders that hold the space, as deep as it takes. A folder that dominates ([splitShare] of all files
     * or more) is replaced by its subfolders, again and again, so the map shows where inside it the space is
     * instead of one giant tile. Largest first; folders under [minShare] and everything beyond [maxTiles] merge
     * into one [OTHERS] tile.
     */
    fun tiles(
        root: String, files: List<ScannedFile>, maxTiles: Int = MAX_TILES, splitShare: Double = SPLIT_SHARE,
        minShare: Double = MIN_TILE_SHARE
    ): List<Tile> {
        val whole = files.sumOf { it.size }
        if (whole <= 0) return emptyList()
        // [always] is for the storage itself; any other folder only opens when most of it is in its subfolders.
        // A folder that is mostly its own files (a camera folder with a thumbnail cache) is the answer as it is.
        fun open(folder: String, always: Boolean = false): List<Tile>? {
            val (folders, own) = StorageAnalysis.children(folder, files)
            val direct = own.sumOf { it.size }
            if (folders.isEmpty() || (!always && folders.sumOf { it.size } < direct)) return null
            return folders.map { Tile(it.path, it.size) } +
                listOfNotNull(direct.takeIf { it > 0 }?.let { Tile(folder, it, direct = true) })
        }
        var tiles = open(root, always = true) ?: return emptyList()
        // Each round opens the largest dominant folder that has subfolders; a few rounds reach any depth that matters.
        repeat(MAX_SPLITS) {
            val (big, parts) = tiles.filter { !it.direct && it.size >= whole * splitShare }
                .sortedByDescending { it.size }.firstNotNullOfOrNull { tile -> open(tile.path)?.let { tile to it } }
                ?: return@repeat
            tiles = tiles - big + parts
        }
        val sorted = tiles.filter { it.size > 0 }.sortedByDescending { it.size }
        val small = sorted.filter { it.size < whole * minShare }
        // One small folder keeps its name; several are one tile.
        val named = (if (small.size < 2) sorted else sorted - small.toSet()).take(maxTiles - 1)
        val rest = sorted - named.toSet()
        return when (rest.size) {
            0 -> named
            1 -> named + rest
            else -> named + Tile("", rest.sumOf { it.size }, count = rest.size)
        }
    }

    private const val MAX_SPLITS = 6
}
