/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileViewTypeTest {
    @Test fun `saved view types keep their meaning`() {
        // Settings store the ordinal: LIST and GRID were saved before sizes existed.
        assertEquals(0, FileViewType.LIST.ordinal)
        assertEquals(1, FileViewType.GRID.ordinal)
        assertEquals(FileItemSize.MEDIUM, FileViewType.LIST.size)
        assertEquals(FileItemSize.MEDIUM, FileViewType.GRID.size)
    }

    @Test fun `every layout exists in every size`() {
        for (isGrid in listOf(false, true)) {
            for (size in FileItemSize.entries) {
                val viewType = FileViewType.LIST.with(isGrid, size)
                assertEquals(isGrid, viewType.isGrid)
                assertEquals(size, viewType.size)
            }
        }
        assertEquals(6, FileViewType.entries.size)
    }

    @Test fun `switching layout keeps the size and switching size keeps the layout`() {
        assertEquals(FileViewType.GRID_SMALL, FileViewType.LIST_SMALL.with(isGrid = true))
        assertEquals(FileViewType.LIST_LARGE, FileViewType.GRID_LARGE.with(isGrid = false))
        assertEquals(FileViewType.GRID_LARGE, FileViewType.GRID.with(size = FileItemSize.LARGE))
        assertEquals(FileViewType.LIST, FileViewType.LIST_SMALL.with(size = FileItemSize.MEDIUM))
    }

    @Test fun `smaller sizes show more files`() {
        // Phone widths in dp: small phone, Galaxy S21, Pixel 11 Pro XL; then a tablet.
        for (widthDp in listOf(360, 411, 448, 800)) {
            val columns = FileItemSize.entries.map { it.gridSpanCount(widthDp) }
            assertTrue("$widthDp dp: $columns", columns[0] > columns[1] && columns[1] > columns[2])
        }
        assertEquals(listOf(4, 3, 2), FileItemSize.entries.map { it.gridSpanCount(411) })
        val rowHeights = FileItemSize.entries.map { it.listItemHeightDp }
        assertEquals(rowHeights.sorted(), rowHeights)
        assertEquals(3, rowHeights.toSet().size)
    }

    @Test fun `grid never drops below two columns`() {
        for (size in FileItemSize.entries) {
            assertEquals(2, size.gridSpanCount(120))
        }
    }

    @Test fun `list thumbnails fit into their row with room to tap`() {
        for (size in FileItemSize.entries) {
            assertTrue(size.listThumbnailSizeDp + 8 <= size.listItemHeightDp)
            assertTrue(size.listIconSizeDp <= size.listThumbnailSizeDp)
            // 48 dp is the smallest comfortable touch target.
            assertTrue(size.listThumbnailSizeDp + 8 >= 48)
        }
    }

    @Test
    fun compactGridTilesAreEvenlySpaced() {
        for (spacing in listOf(18, 24, 32, 42)) {
            for (spanCount in 2..8) {
                val offsets = (0 until spanCount).map {
                    FileItemSize.gridItemOffsets(it, spanCount, spacing)
                }
                assertEquals(spacing, offsets.first().first)
                assertEquals(spacing, offsets.last().second)
                offsets.zipWithNext { left, right ->
                    assertEquals(spacing, left.second + right.first)
                }
                // Equal offsets per cell mean equally wide tiles; whole pixels allow 1 px.
                val widths = offsets.map { it.first + it.second }
                assertTrue(widths.max() - widths.min() <= 1)
            }
        }
    }
}
