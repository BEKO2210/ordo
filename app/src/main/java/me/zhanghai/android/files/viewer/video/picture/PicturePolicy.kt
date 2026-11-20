/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import androidx.media3.common.C
import kotlin.math.min

/**
 * The picture decisions of Ordo Video without Android in them (unit-tested in PicturePolicyTest): how the screen's
 * refresh rate follows the film, when the picture enhancement runs, its shader values, and what the player has to do
 * when the decision changes. Stored values are the ListPreference entry values in donottranslate_ordo_prefs.xml;
 * anything unknown falls back to the default.
 */
object PicturePolicy {
    /** "Match frame rate": never, only where the screen can switch without a blink (default), or always. */
    enum class FrameRateMode { OFF, SEAMLESS, ALWAYS }

    /** "Picture enhancement": off, balanced (default) or maximum. */
    enum class Level { OFF, BALANCED, MAX }

    /** Why the enhancement does not run on this video, in the order they are checked. */
    enum class Off { NO_VIDEO, HDR, DRM, SOFTWARE, FAILED, SETTING, SWITCHED_OFF, TOO_SLOW, NOT_SMALLER }

    /**
     * [media3Strategy] is handed to `ExoPlayer.setVideoChangeFrameRateStrategy`; [ownRate] > 0 means Ordo itself calls
     * `Surface.setFrameRate(ownRate, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` (API 31+).
     */
    data class FrameRatePlan(val media3Strategy: Int, val ownRate: Float)

    /** On when [off] is null; [scale] is how much the picture grows to fill its place on the screen. */
    data class Decision(val level: Level, val off: Off?, val scale: Float) {
        val on: Boolean
            get() = off == null
    }

    /** The values the one shader pass gets (PictureShader's uniforms). */
    data class ShaderParams(
        val sharpness: Float,
        val debandIterations: Int,
        val debandThreshold: Float,
        val debandRadius: Float,
        val grain: Float,
        val ditherAmplitude: Float
    )

    /** What happens to the player when a decision is made for the playing video. */
    enum class Pipeline {
        /** No effect pipeline and none needed: the decoder draws straight onto the screen. */
        KEEP_PLAIN,
        /** Set the effects and re-prepare once: Media3 only builds its OpenGL pipeline when the renderer is reset. */
        ENGAGE,
        /** The pipeline runs: set (or update) the effects. */
        APPLY,
        /** The pipeline runs but this video gets no enhancement: an empty effect list (a plain copy). */
        CLEAR,
        /**
         * HDR, DRM or FFmpeg's software decoder while the pipeline runs: only a new player draws them untouched
         * (Media3 keeps the pipeline for the renderer's lifetime, and FFmpeg writes to the surface itself), so the
         * screen is rebuilt and starts plain.
         */
        RECREATE
    }

    /** Below this the screen's own scaler is as good: Lanczos and sharpening only pay off from 15 % growth. */
    const val MIN_SCALE = 1.15f

    /** libplacebo's default deband radius, in source pixels; it grows with the upscale up to [MAX_RADIUS]. */
    private const val BASE_RADIUS = 16f
    private const val MAX_RADIUS = 64f
    private const val MAX_FRAME_RATE = 240f

    /** More than this many dropped frames per second (over at least [MIN_DROPS]) means the device can't keep up. */
    private const val DROPS_PER_SECOND = 2
    private const val MIN_DROPS = 10

    fun frameRateMode(value: String?): FrameRateMode = enumOf(value, FrameRateMode.SEAMLESS)

    fun level(value: String?): Level = enumOf(value, Level.BALANCED)

    /**
     * Media3 already asks for the film's rate itself, but only "if seamless" (VideoFrameReleaseHelper). "Always" takes
     * that over from API 31, where Surface.setFrameRate takes CHANGE_FRAME_RATE_ALWAYS; on API 30 only seamless
     * exists, below it nothing. Ordo's own rate is the film's, not times the speed: "hold for 2×" must not switch the
     * screen's mode (a blink each time) and back.
     */
    fun frameRatePlan(mode: FrameRateMode, sdkInt: Int, fps: Float): FrameRatePlan = when (mode) {
        FrameRateMode.OFF -> FrameRatePlan(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, 0f)
        FrameRateMode.SEAMLESS -> FrameRatePlan(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, 0f)
        FrameRateMode.ALWAYS ->
            if (sdkInt >= 31 && fps > 0f && fps <= MAX_FRAME_RATE) {
                FrameRatePlan(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, fps)
            } else {
                FrameRatePlan(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS, 0f)
            }
    }

    /** How much a video of this size grows to fit the surface (aspect kept), 0 if either is unknown. */
    fun scale(videoWidth: Int, videoHeight: Int, surfaceWidth: Int, surfaceHeight: Int): Float =
        if (videoWidth <= 0 || videoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            0f
        } else {
            min(surfaceWidth.toFloat() / videoWidth, surfaceHeight.toFloat() / videoHeight)
        }

    /**
     * The enhancement runs only on SDR video without DRM that is shown at least [MIN_SCALE] times larger than it is,
     * while it is switched on and the device keeps up. [switchedOn] is the ⋮ toggle (it starts as "setting not off");
     * [failed]: the GPU could not run the pipeline once in this process, so it is not tried again. [software]: FFmpeg
     * decodes this video on the CPU because the chip can't; it draws straight onto the surface and needs all the power.
     */
    fun decide(
        level: Level,
        switchedOn: Boolean,
        isHdr: Boolean,
        isDrm: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        tooSlow: Boolean,
        failed: Boolean = false,
        software: Boolean = false
    ): Decision {
        val scale = scale(videoWidth, videoHeight, surfaceWidth, surfaceHeight)
        // The toggle can switch it on although the setting says off: then it runs balanced.
        val effective = if (level == Level.OFF) Level.BALANCED else level
        val off = when {
            videoWidth <= 0 || videoHeight <= 0 -> Off.NO_VIDEO
            isHdr -> Off.HDR
            isDrm -> Off.DRM
            software -> Off.SOFTWARE
            failed -> Off.FAILED
            !switchedOn -> if (level == Level.OFF) Off.SETTING else Off.SWITCHED_OFF
            tooSlow -> Off.TOO_SLOW
            scale < MIN_SCALE -> Off.NOT_SMALLER
            else -> null
        }
        return Decision(effective, off, scale)
    }

    /**
     * The shader's values. Balanced: CAS 0.4 and one deband step with libplacebo's defaults (threshold 3, grain lower
     * at 2, as the dither already hides residual steps). Maximum: CAS 0.8, two steps, threshold 4,
     * libplacebo's grain 4.
     * libplacebo's numbers are per mille of full scale; the radius is in source pixels, so it grows with the upscale.
     */
    fun shaderParams(level: Level, scale: Float): ShaderParams {
        val radius = (BASE_RADIUS * scale.coerceAtLeast(1f)).coerceAtMost(MAX_RADIUS)
        return when (level) {
            Level.MAX -> ShaderParams(0.8f, 2, 4f / 1000f, radius, 4f / 1000f, 1f / 255f)
            else -> ShaderParams(0.4f, 1, 3f / 1000f, radius, 2f / 1000f, 1f / 255f)
        }
    }

    fun pipeline(engaged: Boolean, decision: Decision): Pipeline = when {
        !engaged -> if (decision.on) Pipeline.ENGAGE else Pipeline.KEEP_PLAIN
        decision.off == Off.HDR || decision.off == Off.DRM || decision.off == Off.SOFTWARE -> Pipeline.RECREATE
        decision.on -> Pipeline.APPLY
        else -> Pipeline.CLEAR
    }

    /**
     * Media3 reports dropped frames in batches (every 50, and when playback stops). More than [DROPS_PER_SECOND] a
     * second over a batch of at least [MIN_DROPS] means the enhancement costs more than the device has.
     */
    fun tooSlow(droppedFrames: Int, elapsedMs: Long): Boolean =
        droppedFrames >= MIN_DROPS && droppedFrames * 1000L > DROPS_PER_SECOND * elapsedMs.coerceAtLeast(1L)

    /**
     * What a quick look into the file says before the player opens it: the picture as shown (rotation applied),
     * [hdr] null when the file does not say (then nothing is decided ahead), [drm] when it carries PSSH data.
     */
    data class Probe(val width: Int, val height: Int, val hdr: Boolean?, val drm: Boolean)

    // MediaFormat's values, repeated so this stays free of Android.
    private const val TRANSFER_ST2084 = 6
    private const val TRANSFER_HLG = 7
    private const val HEVC_MAIN = 0x1
    private const val VP9_PROFILE_0 = 0x1
    private const val AV1_MAIN_8 = 0x1

    /**
     * The probe from MediaExtractor's video format. HDR is the colour transfer when the file names it; without it only
     * codecs and profiles that cannot carry HDR count as SDR (H.264, MPEG-4/2, VP8, HEVC Main, VP9 profile 0, AV1
     * Main 8); Dolby Vision is always HDR. Anything else stays unknown.
     */
    fun probeFrom(
        mimeType: String?,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        colorTransfer: Int?,
        profile: Int?,
        drm: Boolean
    ): Probe? {
        if (width <= 0 || height <= 0) return null
        val hdr = when {
            mimeType == "video/dolby-vision" -> true
            colorTransfer != null && colorTransfer > 0 ->
                colorTransfer == TRANSFER_ST2084 || colorTransfer == TRANSFER_HLG
            mimeType in EIGHT_BIT_CODECS -> false
            mimeType == "video/hevc" -> if (profile == HEVC_MAIN) false else null
            mimeType == "video/x-vnd.on2.vp9" -> if (profile == VP9_PROFILE_0) false else null
            mimeType == "video/av01" -> if (profile == AV1_MAIN_8) false else null
            else -> null
        }
        val turned = rotationDegrees % 180 == 90
        return Probe(if (turned) height else width, if (turned) width else height, hdr, drm)
    }

    private val EIGHT_BIT_CODECS = setOf("video/avc", "video/mp4v-es", "video/mpeg2", "video/x-vnd.on2.vp8")

    /** Would this video be enhanced on a window of this size, in either orientation (phones turn for the film)? */
    fun probeEligible(probe: Probe?, windowWidth: Int, windowHeight: Int): Boolean =
        probe != null && probe.hdr == false && !probe.drm &&
            maxOf(
                scale(probe.width, probe.height, windowWidth, windowHeight),
                scale(probe.width, probe.height, windowHeight, windowWidth)
            ) >= MIN_SCALE

    /**
     * Whether the player starts with Media3's OpenGL pipeline, decided before prepare() so no video has to be
     * prepared twice: yes when the first video is SDR without DRM and it or the next one in the queue gets the
     * enhancement (an SDR video in between passes through the pipeline untouched). An unknown or HDR first video
     * starts plain; then the re-prepare in [pipeline] stays the fallback.
     */
    fun preEngage(
        switchedOn: Boolean,
        failed: Boolean,
        current: Probe?,
        next: Probe?,
        windowWidth: Int,
        windowHeight: Int
    ): Boolean {
        if (!switchedOn || failed || current == null || current.hdr != false || current.drm) return false
        return probeEligible(current, windowWidth, windowHeight) || probeEligible(next, windowWidth, windowHeight)
    }

    private inline fun <reified E : Enum<E>> enumOf(value: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
}
