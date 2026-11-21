/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import me.zhanghai.android.files.viewer.video.picture.DetailPlus.Model
import me.zhanghai.android.files.viewer.video.picture.DetailPlus.Reason
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
    fun automaticIsDecidedByAutoNotByModel() {
        // VideoPicture asks auto() for Automatic (with the device's times); model() never picks a network for it.
        assertNull(model(Upscaler.AUTO, name = "/sdcard/Anime/Frieren/E01.mkv"))
        assertNull(model(Upscaler.AUTO, name = "/sdcard/Movies/Heat (1995).mkv"))
    }

    @Test
    fun animeHeuristic() {
        assertTrue(DetailPlus.looksLikeAnime("content://media/Zeichentrick/Biene Maja.mp4"))
        assertTrue(DetailPlus.looksLikeAnime("/storage/Cartoons/x.mp4"))
        assertTrue(DetailPlus.looksLikeAnime("/x/[Erai-raws] Show - 12 [1080p].mkv"))
        assertTrue(DetailPlus.looksLikeAnime("/x/Ghibli OVA.mkv"))
        assertTrue(DetailPlus.looksLikeAnime("Animation"))
        assertFalse(DetailPlus.looksLikeAnime("Animal Kingdom"))
        assertFalse(DetailPlus.looksLikeAnime("/storage/DCIM/Camera/VID_20260101.mp4"))
        assertFalse(DetailPlus.looksLikeAnime("/x/Novara Rennen.mp4"))
        // A bracketed tag only counts with an episode number after " - ".
        assertFalse(DetailPlus.looksLikeAnime("/x/[2024] Urlaub.mp4"))
        assertFalse(DetailPlus.looksLikeAnime(null))
    }

    @Test
    fun automaticReasonsFollowPolicyOrder() {
        fun auto(
            enhanced: Boolean = true,
            scale: Float = 2f,
            anime: Boolean = false,
            unsupported: Set<Model> = emptySet(),
            fellBack: Set<Model> = emptySet(),
            predictedMs: Float? = 4f,
            cartoonMs: Float? = predictedMs
        ) = DetailPlus.auto(enhanced, scale, anime, unsupported, fellBack) {
            if (it == Model.CARTOON) cartoonMs else predictedMs
        }

        assertEquals(Reason.NOT_ENHANCED, auto(enhanced = false, scale = 1f, unsupported = setOf(Model.FILM), predictedMs = 9f).reason)
        assertEquals(Reason.SMALL, auto(scale = 1.49f, unsupported = setOf(Model.FILM), predictedMs = 9f).reason)
        assertEquals(Reason.UNSUPPORTED, auto(unsupported = setOf(Model.FILM), predictedMs = 9f).reason)
        assertEquals(Reason.TOO_SLOW, auto(predictedMs = 8.01f).reason)
        assertEquals(DetailPlus.Auto(Model.FILM, Reason.FILM, 8f), auto(predictedMs = 8f))
        // Never measured: Lanczos while the network is timed offscreen (a budget tablet took 81 ms a frame).
        assertEquals(DetailPlus.Auto(null, Reason.MEASURING, null, measure = Model.FILM), auto(predictedMs = null))
        assertEquals(Model.CARTOON, auto(anime = true, cartoonMs = null).measure)
        // Cartoon's offscreen timing failed (skipped like a fallback): film is next, not MEASURING forever.
        assertEquals(Model.FILM, auto(anime = true, fellBack = setOf(Model.CARTOON), predictedMs = null).measure)
        assertEquals(Model.FILM, auto(anime = true, fellBack = setOf(Model.CARTOON)).model)
        // Measured 81 ms: Lanczos without the network ever on screen.
        assertEquals(DetailPlus.Auto(null, Reason.TOO_SLOW, 81f), auto(predictedMs = 81f))
        // Cartoon measured too slow, film not yet: measure film next.
        assertEquals(Model.FILM, auto(anime = true, predictedMs = null, cartoonMs = 20f).measure)
        assertEquals(DetailPlus.Auto(Model.CARTOON, Reason.ANIME, 4f), auto(anime = true))
        // Anime steps down to the film network before Lanczos: cartoon unsupported, too slow or fallen back.
        assertEquals(DetailPlus.Auto(Model.FILM, Reason.ANIME_FILM, 4f), auto(anime = true, unsupported = setOf(Model.CARTOON)))
        assertEquals(DetailPlus.Auto(Model.FILM, Reason.ANIME_FILM, 4f), auto(anime = true, cartoonMs = 8.1f))
        assertEquals(Model.FILM, auto(anime = true, fellBack = setOf(Model.CARTOON)).model)
        assertEquals(Reason.UNSUPPORTED, auto(anime = true, unsupported = setOf(Model.CARTOON, Model.FILM)).reason)
        assertEquals(DetailPlus.Auto(null, Reason.TOO_SLOW, 9f), auto(anime = true, predictedMs = 9f, cartoonMs = 20f))
        // Dropped frames although the network was fast enough: still Lanczos for this film.
        assertEquals(DetailPlus.Auto(null, Reason.TOO_SLOW, 4f), auto(fellBack = setOf(Model.FILM)))
        assertEquals(Reason.SMALL, auto(scale = 1.2f, fellBack = setOf(Model.FILM)).reason)
    }

    @Test
    fun withAGpuTimerOnlyTwoSlowSecondsTurnTheNetworkOff() {
        // Tab, 21.11.2026: 0.8 ms and a few drops from start or seek must keep Detail plus.
        assertFalse(DetailPlus.fallBack(0.8, 24, slowBefore = false, 14, steady = true, gpuTimed = true))
        assertFalse(DetailPlus.fallBack(0.8, 24, slowBefore = true, 1, steady = true, gpuTimed = true))
        assertFalse(DetailPlus.fallBack(8.1, 24, slowBefore = false, 0, steady = true, gpuTimed = true))
        assertTrue(DetailPlus.fallBack(8.1, 24, slowBefore = true, 0, steady = true, gpuTimed = true))
        // Without a GPU timer, drops in settled playback are all there is.
        assertTrue(DetailPlus.fallBack(0.0, 0, slowBefore = false, 1, steady = true, gpuTimed = false))
    }

    @Test
    fun timerResultsThatCantBeTrustedAreNull() {
        assertEquals(2.5f, DetailPlus.timerMs(2_500_000, 0, false)!!, 0.0001f)
        // A failed query reads 0: it must not look like a free network.
        assertNull(DetailPlus.timerMs(0, 0, false))
        assertNull(DetailPlus.timerMs(2_500_000, 0x502, false))
        assertNull(DetailPlus.timerMs(2_500_000, 0, true))
    }

    @Test
    fun firstOffscreenDrawStopsTheTimingOnlyWhenClearlyTooSlow() {
        // W90-like: 81 ms per 540p frame = 156 ms per megapixel.
        assertTrue(DetailPlus.clearlyTooSlow(156f))
        // Tab S9: 6 ms per megapixel; and a device just over the limit still gets the full timing.
        assertFalse(DetailPlus.clearlyTooSlow(6f))
        assertFalse(DetailPlus.clearlyTooSlow(20f))
    }

    @Test
    fun budgetPredictsFromInputPixelsAndRecordOverwrites() {
        val budget = DetailPlus.Budget()
        assertNull(budget.predict(Model.FILM, 1_000, 1_000))
        budget.record(Model.FILM, 1_000, 1_000, 4f)
        assertEquals(4f, budget.predict(Model.FILM, 1_000, 1_000)!!, 0.0001f)
        assertEquals(8f, budget.predict(Model.FILM, 2_000, 1_000)!!, 0.0001f)
        assertNull(budget.predict(Model.CARTOON, 1_000, 1_000))
        budget.record(Model.FILM, 1_000, 1_000, 3f)
        assertEquals(3f, budget.predict(Model.FILM, 1_000, 1_000)!!, 0.0001f)
    }

    @Test
    fun fallBackOnGpuTimeOrAnyDropInSteadyPlayback() {
        assertFalse(DetailPlus.fallBack(7.9, 30, slowBefore = true, 0, steady = true, gpuTimed = false))
        assertTrue(DetailPlus.fallBack(8.1, 30, slowBefore = true, 0, steady = true, gpuTimed = false))
        // One slow second alone is not enough.
        assertFalse(DetailPlus.fallBack(8.1, 30, slowBefore = false, 0, steady = true, gpuTimed = false))
        assertTrue(DetailPlus.slow(8.1, 30))
        assertFalse(DetailPlus.slow(8.1, 9))
        // Too few timed frames say nothing yet.
        assertFalse(DetailPlus.fallBack(20.0, 9, slowBefore = true, 0, steady = true, gpuTimed = false))
        assertTrue(DetailPlus.fallBack(2.0, 30, slowBefore = false, 1, steady = true, gpuTimed = false))
        // Drops right after a start, a seek or new effects are the pipeline settling.
        assertFalse(DetailPlus.fallBack(2.0, 30, slowBefore = false, 3, steady = false, gpuTimed = false))
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
