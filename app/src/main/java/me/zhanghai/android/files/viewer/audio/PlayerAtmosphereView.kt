/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import me.zhanghai.android.files.ui.Motion
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The player's background: the cover, shrunk and blurred, under a veil of the cover-tinted surface whose opacity
 * [AudioPlayerColorWash.veilAlpha] chose so text and controls keep their WCAG contrast; two soft fields of the cover's
 * colours drift through it. Idea from Metrolist's liquid background (GPL-3.0, Compose), rebuilt as a View:
 * an AGSL shader with a slow liquid warp from Android 13, a RenderEffect blur on Android 12, a moving pre-blurred
 * bitmap below. It moves only while music plays, the screen shows it, motion is on in full and battery saver is off;
 * otherwise it stands still. Frames are capped at [FRAME_MS].
 */
class PlayerAtmosphereView(context: Context) : View(context) {
    /** One song's look: the blurred cover (null without a cover), the field colours, and the veil over both. */
    class Look(val cover: Bitmap?, val fieldColors: IntArray, val veil: Int, val veilAlpha: Float)

    private var current: Look? = null
    private var previous: Look? = null
    private var fade = 1f
    private var fadeAnimator: ValueAnimator? = null
    private var shaders = HashMap<Look, Any>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var timeSeconds = 0f
    private var lastFrameNanos = 0L
    private var ticking = false

    var playing = false
        set(value) {
            if (field == value) return
            field = value
            updateTicking()
        }

    private val tick = Runnable { invalidate() }

    init {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S until Build.VERSION_CODES.TIRAMISU) {
            val radius = 24 * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR))
        }
    }

    fun show(look: Look) {
        fadeAnimator?.cancel()
        previous?.let { shaders.remove(it) }
        previous = current
        current = look
        if (previous == null || !Motion.any) {
            previous?.let { shaders.remove(it) }
            previous = null
            fade = 1f
            invalidate()
            return
        }
        fade = 0f
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CROSSFADE_MS
            addUpdateListener {
                fade = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicking()
    }

    override fun onDetachedFromWindow() {
        fadeAnimator?.cancel()
        removeCallbacks(tick)
        ticking = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTicking()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateTicking()
    }

    private fun updateTicking() {
        val powerSave = context.getSystemService<PowerManager>()?.isPowerSaveMode == true
        val now = playing && Motion.full && !powerSave && isAttachedToWindow && windowVisibility == VISIBLE &&
            isShown
        if (now == ticking) return
        ticking = now
        lastFrameNanos = 0L
        removeCallbacks(tick)
        if (now) invalidate()
    }

    @SuppressLint("NewApi")
    override fun onDraw(canvas: Canvas) {
        if (ticking) {
            val nanos = System.nanoTime()
            if (lastFrameNanos != 0L) timeSeconds += ((nanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
            lastFrameNanos = nanos
            removeCallbacks(tick)
            postDelayed(tick, FRAME_MS)
        }
        val base = previous ?: current ?: return
        canvas.drawColor(base.veil)
        previous?.let { draw(canvas, it, 1f) }
        current?.let { draw(canvas, it, if (previous != null) fade else 1f) }
        if (fade >= 1f && previous != null) {
            shaders.remove(previous!!)
            previous = null
        }
    }

    private fun draw(canvas: Canvas, look: Look, alpha: Float) {
        val cover = look.cover
        if (cover != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawShader(canvas, look, cover, alpha)
            return
        }
        if (cover != null) drawCover(canvas, cover, alpha)
        drawFields(canvas, look.fieldColors, alpha)
        paint.shader = null
        paint.color = look.veil
        paint.alpha = (look.veilAlpha * alpha * 255).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /** Android 7–12: the blurred cover, enlarged past the edges, turns and wanders very slowly. */
    private fun drawCover(canvas: Canvas, cover: Bitmap, alpha: Float) {
        val scale = max(width, height) * OVERSCAN / cover.width
        val t = timeSeconds
        matrix.reset()
        matrix.postTranslate(-cover.width / 2f, -cover.height / 2f)
        matrix.postRotate(8f * sin(t * 0.05f))
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            width / 2f + width * 0.04f * sin(t * 0.07f), height / 2f + height * 0.03f * cos(t * 0.05f)
        )
        paint.shader = null
        paint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(cover, matrix, paint)
    }

    private fun drawFields(canvas: Canvas, colors: IntArray, alpha: Float) {
        val t = timeSeconds
        colors.forEachIndexed { i, color ->
            val phase = i * 2.1f
            val x = width * (0.5f + 0.32f * sin(t * 0.09f + phase))
            val y = height * (0.45f + 0.28f * cos(t * 0.06f + phase))
            val radius = max(width, height) * 0.55f
            val center = Color.argb((FIELD_ALPHA * alpha * 255).toInt(), Color.red(color), Color.green(color),
                Color.blue(color))
            blobPaint.shader = RadialGradient(x, y, radius, center, center and 0xffffff, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, radius, blobPaint)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawShader(canvas: Canvas, look: Look, cover: Bitmap, alpha: Float) {
        val shader = shaders.getOrPut(look) {
            RuntimeShader(AGSL).apply {
                setInputShader("cover", BitmapShader(cover, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                })
                setFloatUniform("coverSize", cover.width.toFloat(), cover.height.toFloat())
                val a = look.fieldColors.getOrElse(0) { look.veil }
                val b = look.fieldColors.getOrElse(1) { a }
                setFloatUniform("fieldA", Color.red(a) / 255f, Color.green(a) / 255f, Color.blue(a) / 255f, FIELD_ALPHA)
                setFloatUniform("fieldB", Color.red(b) / 255f, Color.green(b) / 255f, Color.blue(b) / 255f, FIELD_ALPHA)
                setFloatUniform("veil", Color.red(look.veil) / 255f, Color.green(look.veil) / 255f,
                    Color.blue(look.veil) / 255f, look.veilAlpha)
            }
        } as RuntimeShader
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("time", timeSeconds)
        paint.shader = shader
        paint.alpha = (alpha * 255).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        /** About 30 frames a second: the drift is slow, more frames would only cost battery. */
        private const val FRAME_MS = 33L
        private const val CROSSFADE_MS = 300L
        private const val OVERSCAN = 1.35f
        private const val FIELD_ALPHA = 0.32f

        // The cover fills the view enlarged, turns a few degrees and flows in a slow liquid warp; two colour fields
        // drift on top; the veil decides the final contrast; a little noise keeps gradients free of banding.
        private const val AGSL = """
uniform shader cover;
uniform float2 size;
uniform float2 coverSize;
uniform float time;
uniform half4 fieldA;
uniform half4 fieldB;
uniform half4 veil;

half4 main(float2 p) {
    float k = max(size.x, size.y) * 1.35;
    float2 c = (p - size * 0.5) / k;
    float a = 0.14 * sin(time * 0.05);
    c = float2(c.x * cos(a) - c.y * sin(a), c.x * sin(a) + c.y * cos(a));
    c += 0.045 * float2(sin(c.y * 5.0 + time * 0.31), cos(c.x * 4.0 + time * 0.23));
    c += float2(0.04 * sin(time * 0.07), 0.03 * cos(time * 0.05));
    half3 color = cover.eval((c + 0.5) * coverSize).rgb;
    float2 uv = p / size;
    float2 ca = float2(0.5 + 0.32 * sin(time * 0.09), 0.45 + 0.28 * cos(time * 0.06));
    float2 cb = float2(0.5 + 0.32 * sin(time * 0.09 + 2.1), 0.45 + 0.28 * cos(time * 0.06 + 2.1));
    color = mix(color, fieldA.rgb, fieldA.a * smoothstep(0.75, 0.0, distance(uv, ca)));
    color = mix(color, fieldB.rgb, fieldB.a * smoothstep(0.75, 0.0, distance(uv, cb)));
    color = mix(color, veil.rgb, veil.a);
    float noise = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    return half4(color + noise / 255.0, 1.0);
}
"""
    }
}
