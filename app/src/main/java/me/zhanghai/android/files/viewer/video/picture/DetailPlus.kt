/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

/**
 * "Detail plus" (setting "Upscaling"): a small convolutional network doubles the picture before Lanczos fits it to the
 * screen. Film is FSRCNNX x2 8-0-4-1 (igv, LGPL-3.0-or-later), cartoon is Anime4K CNN x2 M (bloc97, MIT); both run as
 * the authors' mpv shaders from assets/detailplus ([MpvHook], [DetailPlusEffect]). The rules here have no Android in
 * them (DetailPlusTest); measured on pop-os in docs/VIDEO-PLAYER.md.
 */
object DetailPlus {
    enum class Upscaler { STANDARD, FILM, CARTOON, AUTO }

    enum class Model(val asset: String, val luma: Boolean) {
        FILM("detailplus/FSRCNNX_x2_8-0-4-1.glsl", true),
        CARTOON("detailplus/Anime4K_Upscale_CNN_x2_M.glsl", false)
    }

    /** Below this growth Lanczos alone is as good, and the network's doubling would mostly be thrown away again. */
    const val MIN_SCALE = 1.5f

    /** The network's GPU time per frame above which it gives way to Lanczos (a 30 fps frame has 33 ms for everything). */
    const val MAX_GPU_MS = 8f

    /** Seconds of playback after a start, seek or new effect before a dropped frame counts against the network. */
    const val SETTLE_MS = 2_000L

    private const val MIN_TIMED_FRAMES = 10

    fun upscaler(value: String?): Upscaler =
        Upscaler.values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Upscaler.STANDARD

    /**
     * The network for this video, or null for Lanczos alone. [enhanced]: the picture enhancement runs on it (SDR, no
     * DRM, smaller than the screen, switched on); [name] is its path or URI for "automatic"; [fellBack]: it was too
     * slow on this screen already; [unsupported]: networks the GPU can't run (no OpenGL ES 3 with float render targets).
     */
    fun model(
        upscaler: Upscaler,
        enhanced: Boolean,
        scale: Float,
        name: String?,
        fellBack: Boolean,
        unsupported: Set<Model>
    ): Model? {
        if (!enhanced || fellBack || scale < MIN_SCALE) return null
        val model = when (upscaler) {
            Upscaler.STANDARD -> null
            Upscaler.FILM -> Model.FILM
            Upscaler.CARTOON -> Model.CARTOON
            Upscaler.AUTO -> if (looksLikeAnime(name)) Model.CARTOON else Model.FILM
        }
        return model?.takeIf { it !in unsupported }
    }

    /**
     * Back to Lanczos when the last second's mean GPU time of the network was over [MAX_GPU_MS] (over at least a few
     * timed frames), or when any frame was dropped in settled playback ([steady]).
     */
    fun fallBack(gpuMeanMs: Double, timedFrames: Int, droppedSinceLast: Int, steady: Boolean): Boolean =
        (timedFrames >= MIN_TIMED_FRAMES && gpuMeanMs > MAX_GPU_MS) || (steady && droppedSinceLast > 0)

    // shortcut: words in the path only; a release-group or tag database would catch more, upgrade if users ask.
    private val ANIME = Regex(
        "anime|zeichentrick|cartoon|manga|donghua|\\bova\\b|\\bona\\b|crunchyroll|subsplease|erai-raws|horriblesubs|" +
            "fansub|\\[[^\\]/]+\\]\\s*[^/]*\\s-\\s\\d{2,4}\\b"
    )

    /** Whether a file or folder name suggests anime or cartoons: "Anime/", "[SubsPlease] Title - 01 (1080p).mkv". */
    fun looksLikeAnime(name: String?): Boolean = name != null && ANIME.containsMatchIn(name.lowercase())
}
