/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AnalysisChartTest {
    private fun f(path: String, size: Long) = ScannedFile(path, size, 1)

    // The Tab that showed the old donut's failure: videos 88 %, two types far under 1 %.
    private val tab = listOf("image" to 489L, "audio" to 14L, "document" to 5L, "video" to 6_430L, "apps" to 0L,
        "other" to 347L)

    @Test
    fun slices_smallOnesMergeAtTheEndAndOrderStays() {
        val parts = AnalysisChart.slices(tab)
        assertEquals(listOf("image", "video", "other", AnalysisChart.OTHERS), parts.map { it.key })
        assertEquals(19L, parts.last().value)
        assertEquals(listOf("audio", "document"), parts.last().members)
        assertEquals(tab.sumOf { it.second }, parts.sumOf { it.value })
    }

    @Test
    fun slices_oneSmallPartStaysItself() {
        val parts = AnalysisChart.slices(listOf("image" to 990L, "audio" to 10L, "video" to 0L))
        assertEquals(listOf("image", "audio"), parts.map { it.key })
    }

    @Test
    fun slices_shareIsOfTheRealNumbersNotOfTheDrawing() {
        val parts = AnalysisChart.slices(tab)
        val total = parts.sumOf { it.value }
        // 19 of 7285 is 0.26 %: the label says so although the slice is drawn wider.
        assertEquals("0.3%", StorageInsights.share(parts.last().value, total, Locale.US))
        assertEquals("88%", StorageInsights.share(6_430, total, Locale.US))
    }

    @Test
    fun extents_floorKeepsTheSmallestVisibleAndTheSumExact() {
        val sweeps = AnalysisChart.extents(listOf(489L, 6_430L, 347L, 19L), 360f, 7f)
        assertEquals(360f, sweeps.sum(), 0.01f)
        assertEquals(7f, sweeps[3], 0.001f)
        // The larger parts keep their proportions among themselves.
        assertEquals(6_430f / 489f, sweeps[1] / sweeps[0], 0.01f)
        assertTrue(sweeps[1] > sweeps[0] && sweeps[0] > sweeps[2])
    }

    @Test
    fun extents_flooringOnePartCanPushTheNextUnderTheFloor() {
        // 2 % of 100 is 2, but after the first part takes 10 the second would get 9.2: both end at the floor.
        val areas = AnalysisChart.extents(listOf(880L, 100L, 20L), 100f, 10f)
        assertEquals(listOf(80f, 10f, 10f), areas.toList())
    }

    @Test
    fun extents_noRoomForTheFloorSplitsEvenly() {
        assertEquals(listOf(50f, 50f), AnalysisChart.extents(listOf(1L, 99L), 100f, 60f).toList())
        assertEquals(0, AnalysisChart.extents(emptyList(), 100f, 5f).size)
    }

    @Test
    fun tiles_aDominantFolderOpensUpUntilItShowsWhereTheSpaceIs() {
        // "Android" is 91 % of everything: the old treemap drew one giant tile.
        val files = listOf(
            f("/r/Android/media/chat/Video/a.mp4", 500), f("/r/Android/media/chat/Images/b.jpg", 300),
            f("/r/Android/media/chat/c.db", 60), f("/r/Android/obb/game.obb", 50),
            f("/r/DCIM/d.jpg", 60), f("/r/Download/e.zip", 30)
        )
        val tiles = AnalysisChart.tiles("/r", files)
        assertEquals(
            listOf("/r/Android/media/chat/Video" to 500L, "/r/Android/media/chat/Images" to 300L,
                "/r/DCIM" to 60L, "/r/Android/media/chat" to 60L, "/r/Android/obb" to 50L, "/r/Download" to 30L),
            tiles.map { it.path to it.size }
        )
        // The files that lie in "chat" itself are a tile of their own, marked as such.
        assertEquals(listOf(false, false, false, true, false, false), tiles.map { it.direct })
        assertEquals(files.sumOf { it.size }, tiles.sumOf { it.size })
    }

    @Test
    fun tiles_aDominantFolderWithoutSubfoldersStaysOneTile() {
        val files = listOf(f("/r/DCIM/Camera/a.mp4", 960), f("/r/Music/b.mp3", 30), f("/r/Download/c.zip", 10))
        val tiles = AnalysisChart.tiles("/r", files)
        // DCIM opens into Camera; Camera holds only files and stays. The 1 % folder keeps its name: it is alone.
        assertEquals(listOf("/r/DCIM/Camera", "/r/Music", "/r/Download"), tiles.map { it.path })
    }

    @Test
    fun tiles_aFolderThatIsMostlyItsOwnFilesIsNotOpened() {
        // "Movies" holds the videos itself; its thumbnail cache is no reason to split it into "files in Movies".
        val files = listOf(f("/r/Movies/a.mp4", 900), f("/r/Movies/.thumbnails/t.jpg", 5), f("/r/DCIM/b.jpg", 95))
        assertEquals(listOf("/r/Movies" to 905L, "/r/DCIM" to 95L),
            AnalysisChart.tiles("/r", files).map { it.path to it.size })
    }

    @Test
    fun tiles_smallFoldersAndTheTailBecomeOneOthersTile() {
        val files = (1..12).map { f("/r/F$it/x", if (it <= 6) 150L else 10L) } + f("/r/loose.txt", 40)
        val tiles = AnalysisChart.tiles("/r", files, maxTiles = 5)
        assertEquals(5, tiles.size)
        assertEquals(listOf("/r/F1", "/r/F2", "/r/F3", "/r/F4", ""), tiles.map { it.path })
        // Two big folders beyond the limit, six small ones and the loose files of the storage itself.
        assertEquals(9, tiles.last().count)
        assertEquals(2 * 150L + 6 * 10L + 40L, tiles.last().size)
    }

    @Test
    fun tiles_emptyStorageHasNoMap() {
        assertEquals(0, AnalysisChart.tiles("/r", emptyList()).size)
        assertEquals(0, AnalysisChart.tiles("/r", listOf(f("/r/a", 0))).size)
    }
}
