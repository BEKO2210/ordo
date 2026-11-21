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

    enum class Reason { NOT_ENHANCED, SMALL, UNSUPPORTED, TOO_SLOW, MEASURING, ANIME, ANIME_FILM, FILM }

    /**
     * Automatic's network and the reason for using it or Lanczos. [measure]: the network this device has no time for
     * yet; Lanczos plays while it is measured offscreen ([DetailPlusBench]), never on screen.
     */
    data class Auto(val model: Model?, val reason: Reason, val predictedMs: Float?, val measure: Model? = null)

    /** Last measured network cost per input megapixel, so one sample predicts other source resolutions. */
    class Budget(initial: Map<Model, Float> = emptyMap()) {
        private val msPerMp = initial.toMutableMap()

        fun predict(model: Model, width: Int, height: Int): Float? {
            if (width <= 0 || height <= 0) return null
            return msPerMp[model]?.times(width.toLong() * height / 1_000_000f)
        }

        fun record(model: Model, width: Int, height: Int, meanMs: Float) {
            if (width <= 0 || height <= 0) return
            msPerMp[model] = meanMs * 1_000_000f / (width.toLong() * height)
        }
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
     * Automatic in policy order. Anime tries the cartoon network, then the lighter film network (it beat Lanczos+CAS on
     * cartoons too, docs/VIDEO-PLAYER.md), then Lanczos; everything else the film network, then Lanczos. A network is
     * skipped when this film already fell back with it ([fellBack]) or its [predict]ed GPU time is over [MAX_GPU_MS].
     * An unknown time (null) is never tried on screen (a budget tablet took 81 ms a frame): Lanczos until it is measured.
     */
    fun auto(
        enhanced: Boolean,
        scale: Float,
        anime: Boolean,
        unsupported: Set<Model>,
        fellBack: Set<Model>,
        predict: (Model) -> Float?
    ): Auto {
        if (!enhanced) return Auto(null, Reason.NOT_ENHANCED, null)
        if (scale < MIN_SCALE) return Auto(null, Reason.SMALL, null)
        val usable = (if (anime) listOf(Model.CARTOON, Model.FILM) else listOf(Model.FILM)) - unsupported
        if (usable.isEmpty()) return Auto(null, Reason.UNSUPPORTED, null)
        for (model in usable) {
            if (model in fellBack) continue
            val ms = predict(model) ?: return Auto(null, Reason.MEASURING, null, measure = model)
            if (ms > MAX_GPU_MS) continue
            val reason = when {
                model == Model.CARTOON -> Reason.ANIME
                anime -> Reason.ANIME_FILM
                else -> Reason.FILM
            }
            return Auto(model, reason, ms)
        }
        return Auto(null, Reason.TOO_SLOW, usable.mapNotNull(predict).minOrNull())
    }

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
     * Back to Lanczos when the network was [slow] this second and the one before ([slowBefore]; one second alone can be
     * a clock read just as the GPU speeds up: Tab, 21.11.2026, 8.1 ms once in 3 min of 1.4 ms), or when any frame was
     * dropped in settled playback ([steady]).
     */
    fun fallBack(gpuMeanMs: Double, timedFrames: Int, slowBefore: Boolean, droppedSinceLast: Int, steady: Boolean): Boolean =
        (slowBefore && slow(gpuMeanMs, timedFrames)) || (steady && droppedSinceLast > 0)

    /** The second's mean GPU time of the network over [MAX_GPU_MS], over at least a few timed frames. */
    fun slow(gpuMeanMs: Double, timedFrames: Int): Boolean = timedFrames >= MIN_TIMED_FRAMES && gpuMeanMs > MAX_GPU_MS

    // shortcut: words in the path only; a release-group or tag database would catch more, upgrade if users ask.
    private val ANIME = Regex(
        "anime|zeichentrick|cartoon|manga|donghua|\\banimation\\b|\\bova\\b|\\bona\\b|crunchyroll|subsplease|erai-raws|horriblesubs|" +
            "fansub|\\[[^\\]/]+\\]\\s*[^/]*\\s-\\s\\d{2,4}\\b"
    )

    /** Whether a file or folder name suggests anime or cartoons: "Anime/", "[SubsPlease] Title - 01 (1080p).mkv". */
    fun looksLikeAnime(name: String?): Boolean = name != null && ANIME.containsMatchIn(name.lowercase())
}
