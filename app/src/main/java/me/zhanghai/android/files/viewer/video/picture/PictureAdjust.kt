/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.zhanghai.android.files.viewer.video.picture

/** Per-video picture adjustments of the "Bild" sheet, each a step in -3..+3 (0 = as the film is). deinterlace: null = automatic (detected), true/false = the viewer's choice. */
data class PictureAdjustments(
    val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0,
    val temperature: Int = 0, val sharpness: Int = 0, val deinterlace: Boolean? = null
) {
    /** True when all five steps are 0 (deinterlace is not part of it). */
    val isNeutral: Boolean
        get() = brightness == 0 && contrast == 0 && saturation == 0 && temperature == 0 && sharpness == 0

    fun encode(): String {
        val di = when (deinterlace) {
            null -> ""
            true -> "1"
            false -> "0"
        }
        return "$brightness,$contrast,$saturation,$temperature,$sharpness,$di"
    }

    companion object {
        fun decode(value: String?): PictureAdjustments? {
            if (value == null) return null
            val parts = value.split(',')
            if (parts.size != 6) return null
            val steps = parts.take(5).map { it.trim().toIntOrNull() ?: return null }
                .map(PictureAdjust::clampStep)
            val deinterlace = when (parts[5].trim()) {
                "" -> null
                "1" -> true
                "0" -> false
                else -> return null
            }
            return PictureAdjustments(steps[0], steps[1], steps[2], steps[3], steps[4], deinterlace)
        }
    }
}

object PictureAdjust {
    const val MIN_STEP = -3
    const val MAX_STEP = 3
    const val BRIGHTNESS_PER_STEP = 0.03f
    const val CONTRAST_PER_STEP = 0.08f
    const val SATURATION_PER_STEP = 0.15f
    const val TEMPERATURE_PER_STEP = 0.03f
    const val SHARPEN_PER_STEP = 0.2f
    const val SOFTEN_PER_STEP = 0.2f

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    fun clampStep(step: Int): Int = step.coerceIn(MIN_STEP, MAX_STEP)

    /**
     * The 4x4 colour matrix for the shader (GLSL mat4, COLUMN-MAJOR float[16], applied as M * vec4(rgb, 1)).
     * Applied to a pixel in this order: temperature, then saturation, then contrast, then brightness,
     * i.e. M = B * C * S * T.
     */
    fun colorMatrix(a: PictureAdjustments): FloatArray {
        val t = TEMPERATURE_PER_STEP * clampStep(a.temperature)
        val s = 1f + SATURATION_PER_STEP * clampStep(a.saturation)
        val c = 1f + CONTRAST_PER_STEP * clampStep(a.contrast)
        val b = BRIGHTNESS_PER_STEP * clampStep(a.brightness)

        val temperature = identity().also { it[0] = 1f + t; it[10] = 1f - t }
        val luma = floatArrayOf(LUMA_R, LUMA_G, LUMA_B)
        val saturation = identity()
        for (row in 0..2) for (col in 0..2) {
            saturation[col * 4 + row] = (if (row == col) s else 0f) + (1f - s) * luma[col]
        }
        val contrast = identity().also {
            it[0] = c; it[5] = c; it[10] = c
            it[12] = (1f - c) * 0.5f; it[13] = (1f - c) * 0.5f; it[14] = (1f - c) * 0.5f
        }
        val brightness = identity().also { it[12] = b; it[13] = b; it[14] = b }

        return multiply(brightness, multiply(contrast, multiply(saturation, temperature)))
    }

    /** CAS sharpness: the enhancement's own [base] (0 when off) plus a boost for positive steps, clamped to 0..1. */
    fun sharpness(base: Float, step: Int): Float {
        val s = clampStep(step)
        return if (s > 0) (base + SHARPEN_PER_STEP * s).coerceIn(0f, 1f) else base
    }

    /** Softening amount 0..0.6 for negative steps. */
    fun soften(step: Int): Float {
        val s = clampStep(step)
        return if (s < 0) SOFTEN_PER_STEP * -s else 0f
    }

    /** Applies a column-major 4x4 to (r,g,b,1) and returns the rgb (no clamping). */
    fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3) { row -> m[row] * r + m[4 + row] * g + m[8 + row] * b + m[12 + row] }

    private fun identity(): FloatArray = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) for (row in 0..3) {
            var sum = 0f
            for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
        return out
    }
}
