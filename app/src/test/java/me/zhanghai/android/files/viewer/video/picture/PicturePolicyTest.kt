/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import androidx.media3.common.C
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.FrameRateMode
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Level
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Off
import me.zhanghai.android.files.viewer.video.picture.PicturePolicy.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PicturePolicyTest {
    private fun decide(
        level: Level = Level.BALANCED,
        switchedOn: Boolean = true,
        hdr: Boolean = false,
        drm: Boolean = false,
        video: Pair<Int, Int> = 1280 to 720,
        surface: Pair<Int, Int> = 2400 to 1350,
        tooSlow: Boolean = false,
        failed: Boolean = false,
        software: Boolean = false
    ) = PicturePolicy.decide(
        level, switchedOn, hdr, drm, video.first, video.second, surface.first, surface.second, tooSlow, failed,
        software
    )

    @Test
    fun settingValuesFallBackToTheDefaults() {
        assertEquals(FrameRateMode.SEAMLESS, PicturePolicy.frameRateMode(null))
        assertEquals(FrameRateMode.ALWAYS, PicturePolicy.frameRateMode("always"))
        assertEquals(FrameRateMode.SEAMLESS, PicturePolicy.frameRateMode("sometimes"))
        assertEquals(Level.BALANCED, PicturePolicy.level(null))
        assertEquals(Level.MAX, PicturePolicy.level("max"))
        assertEquals(Level.OFF, PicturePolicy.level("off"))
    }

    @Test
    fun frameRateSeamlessLeavesItToMedia3() {
        val plan = PicturePolicy.frameRatePlan(FrameRateMode.SEAMLESS, 34, 23.976f)
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, plan.media3Strategy)
        assertEquals(0f, plan.ownRate)
    }

    @Test
    fun frameRateAlwaysTakesOverFromApi31WithTheFilmsRate() {
        val plan = PicturePolicy.frameRatePlan(FrameRateMode.ALWAYS, 31, 23.976f)
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, plan.media3Strategy)
        assertEquals(23.976f, plan.ownRate)
    }

    @Test
    fun frameRateAlwaysIsSeamlessOnApi30AndWithoutAKnownRate() {
        for (plan in listOf(
            PicturePolicy.frameRatePlan(FrameRateMode.ALWAYS, 30, 25f),
            PicturePolicy.frameRatePlan(FrameRateMode.ALWAYS, 34, -1f),
            PicturePolicy.frameRatePlan(FrameRateMode.ALWAYS, 34, 1000f)
        )) {
            assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, plan.media3Strategy)
            assertEquals(0f, plan.ownRate)
        }
    }

    @Test
    fun frameRateOffStopsMedia3Too() {
        val plan = PicturePolicy.frameRatePlan(FrameRateMode.OFF, 34, 24f)
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, plan.media3Strategy)
        assertEquals(0f, plan.ownRate)
    }

    @Test
    fun scaleKeepsTheAspect() {
        assertEquals(1.875f, PicturePolicy.scale(1280, 720, 2400, 1350), 1e-4f)
        // A 4:3 film on a 20:9 screen is limited by the height.
        assertEquals(1.875f, PicturePolicy.scale(640, 480, 2400, 900), 1e-4f)
        assertEquals(0f, PicturePolicy.scale(0, 480, 2400, 900))
    }

    @Test
    fun runsOnSmallSdrVideo() {
        val decision = decide()
        assertTrue(decision.on)
        assertEquals(Level.BALANCED, decision.level)
    }

    @Test
    fun hdrDrmAndBigVideoStayUntouched() {
        assertEquals(Off.HDR, decide(hdr = true).off)
        assertEquals(Off.DRM, decide(drm = true).off)
        assertEquals(Off.SOFTWARE, decide(software = true).off)
        assertEquals(Off.NOT_SMALLER, decide(video = 1920 to 1080, surface = 1920 to 1080).off)
        // 1.1 times larger is not worth it either.
        assertEquals(Off.NOT_SMALLER, decide(video = 2000 to 1000, surface = 2200 to 1100).off)
        assertEquals(Off.NO_VIDEO, decide(video = 0 to 0).off)
    }

    @Test
    fun hdrWinsOverEveryOtherReason() {
        // The reason decides RECREATE, so HDR must be named even when the toggle is off.
        assertEquals(Off.HDR, decide(hdr = true, switchedOn = false, tooSlow = true, failed = true).off)
    }

    @Test
    fun switchesAndFailures() {
        assertEquals(Off.SETTING, decide(level = Level.OFF, switchedOn = false).off)
        assertEquals(Off.SWITCHED_OFF, decide(switchedOn = false).off)
        assertEquals(Off.TOO_SLOW, decide(tooSlow = true).off)
        assertEquals(Off.FAILED, decide(failed = true).off)
        // The toggle switches it on although the setting is off: balanced.
        val toggled = decide(level = Level.OFF, switchedOn = true)
        assertTrue(toggled.on)
        assertEquals(Level.BALANCED, toggled.level)
    }

    @Test
    fun shaderParamsFollowLibplaceboAndCas() {
        val balanced = PicturePolicy.shaderParams(Level.BALANCED, 2f)
        assertEquals(0.4f, balanced.sharpness)
        assertEquals(1, balanced.debandIterations)
        assertEquals(0.003f, balanced.debandThreshold, 1e-6f)
        assertEquals(32f, balanced.debandRadius)
        assertEquals(1f / 255f, balanced.ditherAmplitude)
        val max = PicturePolicy.shaderParams(Level.MAX, 1.5f)
        assertEquals(0.8f, max.sharpness)
        assertEquals(2, max.debandIterations)
        assertEquals(0.004f, max.debandThreshold, 1e-6f)
        assertEquals(0.004f, max.grain, 1e-6f)
        assertEquals(24f, max.debandRadius)
        // The radius grows with the upscale but stays bounded.
        assertEquals(64f, PicturePolicy.shaderParams(Level.MAX, 6f).debandRadius)
        assertEquals(16f, PicturePolicy.shaderParams(Level.MAX, 0.5f).debandRadius)
    }

    @Test
    fun pipelineEngagesOnceAndRecreatesOnlyForHdrOrDrm() {
        assertEquals(Pipeline.ENGAGE, PicturePolicy.pipeline(false, decide()))
        assertEquals(Pipeline.KEEP_PLAIN, PicturePolicy.pipeline(false, decide(hdr = true)))
        assertEquals(Pipeline.KEEP_PLAIN, PicturePolicy.pipeline(false, decide(switchedOn = false)))
        assertEquals(Pipeline.APPLY, PicturePolicy.pipeline(true, decide()))
        assertEquals(Pipeline.CLEAR, PicturePolicy.pipeline(true, decide(switchedOn = false)))
        assertEquals(Pipeline.CLEAR, PicturePolicy.pipeline(true, decide(video = 3840 to 2160)))
        assertEquals(Pipeline.RECREATE, PicturePolicy.pipeline(true, decide(hdr = true)))
        assertEquals(Pipeline.RECREATE, PicturePolicy.pipeline(true, decide(drm = true)))
        // FFmpeg draws onto the surface itself: the GL pipeline has to go, an unengaged player stays plain.
        assertEquals(Pipeline.RECREATE, PicturePolicy.pipeline(true, decide(software = true)))
        assertEquals(Pipeline.KEEP_PLAIN, PicturePolicy.pipeline(false, decide(software = true)))
    }

    @Test
    fun probeReadsHdrFromTheTransferOrTheCodec() {
        fun hdr(mime: String, transfer: Int? = null, profile: Int? = null) =
            PicturePolicy.probeFrom(mime, 1280, 720, 0, transfer, profile, drm = false)?.hdr
        assertEquals(true, hdr("video/hevc", transfer = 6))
        assertEquals(true, hdr("video/hevc", transfer = 7))
        assertEquals(false, hdr("video/hevc", transfer = 3))
        assertEquals(true, hdr("video/dolby-vision", transfer = 3))
        assertEquals(false, hdr("video/avc"))
        assertEquals(false, hdr("video/hevc", profile = 1))
        // HEVC Main 10 without a transfer may be HDR: unknown, nothing is decided ahead.
        assertNull(hdr("video/hevc", profile = 2))
        assertNull(hdr("video/x-vnd.on2.vp9"))
        assertNull(PicturePolicy.probeFrom("video/avc", 0, 0, 0, null, null, false))
        // A phone video recorded upright: 1920x1080 turned by 90° shows as 1080x1920.
        val turned = PicturePolicy.probeFrom("video/avc", 1920, 1080, 90, null, null, false)
        assertEquals(1080, turned?.width)
        assertEquals(1920, turned?.height)
    }

    @Test
    fun preEngageDecidesBeforePrepare() {
        val small = PicturePolicy.Probe(640, 360, hdr = false, drm = false)
        val big = PicturePolicy.Probe(2400, 1080, hdr = false, drm = false)
        val hdr = PicturePolicy.Probe(640, 360, hdr = true, drm = false)
        val unknown = PicturePolicy.Probe(640, 360, hdr = null, drm = false)
        val drm = PicturePolicy.Probe(640, 360, hdr = false, drm = true)
        fun pre(
            current: PicturePolicy.Probe?,
            next: PicturePolicy.Probe? = null,
            on: Boolean = true,
            failed: Boolean = false
        ) = PicturePolicy.preEngage(on, failed, current, next, 1080, 2400)
        assertTrue(pre(small))
        // A phone in portrait: the film will be shown in landscape, both orientations count.
        assertTrue(pre(PicturePolicy.Probe(1280, 720, hdr = false, drm = false)))
        assertFalse(pre(big))
        // A big SDR video first and a small one next: the pipeline starts with it, the first passes through.
        assertTrue(pre(big, next = small))
        assertFalse(pre(big, next = hdr))
        // HDR, unknown HDR, DRM or no probe: plain, the re-prepare stays the fallback.
        assertFalse(pre(hdr, next = small))
        assertFalse(pre(unknown))
        assertFalse(pre(drm))
        assertFalse(pre(null, next = small))
        assertFalse(pre(small, on = false))
        assertFalse(pre(small, failed = true))
    }

    @Test
    fun tooSlowNeedsMoreThanTwoDropsASecond() {
        assertTrue(PicturePolicy.tooSlow(50, 10_000))
        assertFalse(PicturePolicy.tooSlow(50, 30_000))
        // A few drops when playback stops say nothing.
        assertFalse(PicturePolicy.tooSlow(5, 100))
        assertTrue(PicturePolicy.tooSlow(10, 0))
    }

    @Test
    fun videoIsUntouchedOnlyWithoutEnhancementAndToneMapping() {
        assertTrue(SignalPath.videoChanges(enhanced = false, isHdr = false, hdrShownAsHdr = false).isEmpty())
        assertTrue(SignalPath.videoChanges(enhanced = false, isHdr = true, hdrShownAsHdr = true).isEmpty())
        assertEquals(
            listOf(SignalPath.VideoChange.ENHANCED, SignalPath.VideoChange.TONE_MAPPED),
            SignalPath.videoChanges(enhanced = true, isHdr = true, hdrShownAsHdr = false)
        )
    }

    @Test
    fun soundIsUntouchedOnlyWhenNothingProcesses() {
        fun changes(
            passthrough: Boolean = false,
            stages: List<String> = emptyList(),
            speed: Float = 1f,
            trackRate: Int = 48_000,
            mixerRate: Int = 48_000,
            channels: Int = 2,
            deviceChannels: Int = 2,
            spatialized: Boolean = false
        ) = SignalPath.audioChanges(
            passthrough, stages, speed, trackRate, mixerRate, channels, deviceChannels, spatialized
        )
        assertTrue(changes().isEmpty())
        assertEquals(listOf(SignalPath.AudioChange.RESAMPLED), changes(trackRate = 44_100))
        assertEquals(listOf(SignalPath.AudioChange.DOWNMIXED), changes(channels = 6))
        assertEquals(listOf(SignalPath.AudioChange.SPATIALIZED), changes(channels = 6, spatialized = true))
        assertEquals(listOf(SignalPath.AudioChange.SPEED), changes(speed = 1.5f))
        assertEquals(listOf(SignalPath.AudioChange.STAGES), changes(stages = listOf("Night mode")))
        // Unknown rates and channel limits claim nothing; a passed-through stream is untouched by definition.
        assertTrue(changes(mixerRate = 0, channels = 6, deviceChannels = 0).isEmpty())
        assertTrue(changes(passthrough = true, speed = 2f, trackRate = 44_100).isEmpty())
    }

    @Test
    fun softwareDecodersByName() {
        assertTrue(SignalPath.isSoftwareDecoderName("c2.android.avc.decoder"))
        assertTrue(SignalPath.isSoftwareDecoderName("OMX.google.h264.decoder"))
        assertFalse(SignalPath.isSoftwareDecoderName("c2.qti.avc.decoder"))
        assertFalse(SignalPath.isSoftwareDecoderName("c2.exynos.hevc.decoder"))
        assertTrue(SignalPath.isSoftwareDecoderName("ffmpegLavc60.3.100-hevc"))
        assertTrue(SignalPath.isFfmpegDecoderName("ffmpegLavc60.3.100-hevc"))
        assertFalse(SignalPath.isFfmpegDecoderName("c2.android.hevc.decoder"))
    }
}
