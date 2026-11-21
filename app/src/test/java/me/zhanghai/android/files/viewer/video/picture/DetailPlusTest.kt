/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import me.zhanghai.android.files.viewer.video.picture.DetailPlus.Model
import me.zhanghai.android.files.viewer.video.picture.DetailPlus.Upscaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPlusTest {
    @Test
    fun storedValuesAndUnknownFallBackToStandard() {
        assertEquals(Upscaler.STANDARD, DetailPlus.upscaler("standard"))
        assertEquals(Upscaler.FILM, DetailPlus.upscaler("film"))
        assertEquals(Upscaler.CARTOON, DetailPlus.upscaler("cartoon"))
        assertEquals(Upscaler.AUTO, DetailPlus.upscaler("auto"))
        assertEquals(Upscaler.STANDARD, DetailPlus.upscaler(null))
        assertEquals(Upscaler.STANDARD, DetailPlus.upscaler("vl"))
    }

    private fun model(
        upscaler: Upscaler,
        scale: Float = 2f,
        name: String? = "/sdcard/Movies/Film.mp4",
        enhanced: Boolean = true,
        fellBack: Boolean = false,
        unsupported: Set<Model> = emptySet()
    ) = DetailPlus.model(upscaler, enhanced, scale, name, fellBack, unsupported)

    @Test
    fun runsOnlyWhenEnhancedAtOneAndAHalfTimesAndNotFallenBack() {
        assertEquals(Model.FILM, model(Upscaler.FILM))
        assertEquals(Model.CARTOON, model(Upscaler.CARTOON))
        assertEquals(Model.FILM, model(Upscaler.FILM, scale = 1.5f))
        assertNull(model(Upscaler.FILM, scale = 1.49f))
        assertNull(model(Upscaler.STANDARD))
        // Enhancement off covers HDR, DRM, the toggle and "not smaller" (PicturePolicy.decide).
        assertNull(model(Upscaler.FILM, enhanced = false))
        assertNull(model(Upscaler.FILM, fellBack = true))
        assertNull(model(Upscaler.CARTOON, unsupported = setOf(Model.CARTOON)))
        // A network that failed to build on this GPU does not take the other one with it.
        assertEquals(Model.FILM, model(Upscaler.FILM, unsupported = setOf(Model.CARTOON)))
    }

    @Test
    fun automaticPicksCartoonByName() {
        assertEquals(Model.CARTOON, model(Upscaler.AUTO, name = "/sdcard/Anime/Frieren/E01.mkv"))
        assertEquals(Model.CARTOON, model(Upscaler.AUTO, name = "/sdcard/Download/[SubsPlease] Dandadan - 05 (720p).mkv"))
        assertEquals(Model.FILM, model(Upscaler.AUTO, name = "/sdcard/Movies/Heat (1995).mkv"))
        assertEquals(Model.FILM, model(Upscaler.AUTO, name = null))
    }

    @Test
    fun animeHeuristic() {
        assertTrue(DetailPlus.looksLikeAnime("content://media/Zeichentrick/Biene Maja.mp4"))
        assertTrue(DetailPlus.looksLikeAnime("/storage/Cartoons/x.mp4"))
        assertTrue(DetailPlus.looksLikeAnime("/x/[Erai-raws] Show - 12 [1080p].mkv"))
        assertTrue(DetailPlus.looksLikeAnime("/x/Ghibli OVA.mkv"))
        assertFalse(DetailPlus.looksLikeAnime("/storage/DCIM/Camera/VID_20260101.mp4"))
        assertFalse(DetailPlus.looksLikeAnime("/x/Novara Rennen.mp4"))
        // A bracketed tag only counts with an episode number after " - ".
        assertFalse(DetailPlus.looksLikeAnime("/x/[2024] Urlaub.mp4"))
        assertFalse(DetailPlus.looksLikeAnime(null))
    }

    @Test
    fun fallBackOnGpuTimeOrAnyDropInSteadyPlayback() {
        assertFalse(DetailPlus.fallBack(7.9, 30, 0, steady = true))
        assertTrue(DetailPlus.fallBack(8.1, 30, 0, steady = true))
        // Too few timed frames say nothing yet.
        assertFalse(DetailPlus.fallBack(20.0, 9, 0, steady = true))
        assertTrue(DetailPlus.fallBack(2.0, 30, 1, steady = true))
        // Drops right after a start, a seek or new effects are the pipeline settling.
        assertFalse(DetailPlus.fallBack(2.0, 30, 3, steady = false))
    }

    @Test
    fun modelsPointAtShippedShaders() {
        for (model in Model.values()) {
            assertTrue(model.asset, java.io.File("src/main/assets/" + model.asset).isFile)
        }
        assertTrue(Model.FILM.luma)
        assertFalse(Model.CARTOON.luma)
    }
}
