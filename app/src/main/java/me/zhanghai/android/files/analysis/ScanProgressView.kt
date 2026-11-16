/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.analysis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.util.getQuantityString
import java.text.NumberFormat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The scan as a picture: a ring that fills with the file types found so far while the numbers count up, a light
 * running round it because the end is not known, and at the end the ring flies to where the chart of the overview
 * is and becomes it. With motion off it is a still ring with the numbers.
 */
class ScanProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val glows = run {
        val accent = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val container = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, accent)
        val onContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, accent)
        intArrayOf(accent, ColorUtils.blendARGB(accent, container, 0.4f), ColorUtils.blendARGB(accent, onContainer, 0.25f))
    }
    private val primary = MaterialColors.getColor(this, android.R.attr.textColorPrimary)
    private val secondary = MaterialColors.getColor(this, android.R.attr.textColorSecondary)

    // One slot per file type in the chart's order, and a last one for the merged small types of the final ring.
    private val slots = StorageInsights.KINDS.size + 1
    private var colors: Array<IntArray> = Array(slots) { intArrayOf(Color.TRANSPARENT) }
    private val shown = FloatArray(slots)
    private val target = FloatArray(slots)
    private val from = FloatArray(slots)

    private var files = 0
    private var bytes = 0L
    private var shownFiles = 0f
    private var shownBytes = 0f
    private var compared = -1f
    private var shownCompared = 0f
    private var comparing = 0f
    private var activity = 0f
    private var folder = ""
    private var drawnFolder = ""
    private var folderAlpha = 0f
    private var folderSince = 0L
    private var first = true
    private var shownAt = 0L

    private var time = 0f
    private var lastFrame = 0L
    private var appear = 0f
    private var morph = 0f
    private var morphing = false
    private var toX = 0f
    private var toY = 0f
    private var toRadius = 0f
    private var toStroke = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.chart_track)
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val comparePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var beam: SweepGradient? = null
    private var compareShader: SweepGradient? = null
    private var halos: List<RadialGradient> = emptyList()
    private var head: RadialGradient? = null

    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(34f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        // Digits of one width: a counting number must not wobble.
        fontFeatureSettings = "tnum"
        color = primary
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        color = secondary
    }
    private val sizePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(16f)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
        color = primary
    }
    private val phasePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(16f)
        textAlign = Paint.Align.CENTER
        color = primary
    }
    private val folderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        color = secondary
    }
    private val number = NumberFormat.getIntegerInstance()
    private var countText = ""
    private var countValue = -1
    private var sizeText = ""
    private var sizeValue = -1L

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }
    private var morphAnimator: ValueAnimator? = null

    /** Colours per file type in [StorageInsights.KINDS] order, the same the chart uses. */
    fun setKindColors(kinds: IntArray) {
        colors = Array(slots) { intArrayOf(kinds.getOrElse(it) { Color.TRANSPARENT }) }
    }

    /** Milliseconds this view should still be seen, so a scan of a split second does not flash by. */
    val remaining: Long
        get() = if (Motion.any && shownAt > 0) (MIN_VISIBLE_MILLIS - (SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0) else 0

    /** Starts over, for a new scan. */
    fun reset() {
        morphAnimator?.cancel()
        morphing = false
        morph = 0f
        first = true
        shownAt = SystemClock.uptimeMillis()
        appear = if (Motion.any) 0f else 1f
        shown.fill(0f)
        target.fill(0f)
        files = 0
        bytes = 0
        shownFiles = 0f
        shownBytes = 0f
        compared = -1f
        comparing = 0f
        shownCompared = 0f
        folder = ""
        drawnFolder = ""
        folderAlpha = 0f
        invalidate()
    }

    fun update(progress: ScanProgress) {
        if (morphing) return
        if (progress.files > files) activity = 1f
        files = progress.files
        bytes = progress.bytes
        compared = progress.compared
        if (progress.folder.isNotEmpty()) folder = progress.folder
        val total = progress.kinds.sum()
        for (i in progress.kinds.indices) target[i] = if (total > 0) 360f * progress.kinds[i] / total else 0f
        target[slots - 1] = 0f
        // After a rotation the numbers are already high: stand at them instead of counting up again.
        if (first || !Motion.any) {
            first = false
            snap()
        }
        contentDescription = "${phase()}, ${context.getQuantityString(R.plurals.analysis_file_count, files, files)}, " +
            bytes.asFileSize().formatHumanReadable(context)
        if (!ticker.isStarted) invalidate()
    }

    private fun snap() {
        target.copyInto(shown)
        shownFiles = files.toFloat()
        shownBytes = bytes.toFloat()
        shownCompared = compared.coerceAtLeast(0f)
        comparing = if (compared >= 0f) 1f else 0f
        drawnFolder = folder
        folderAlpha = 1f
    }

    private fun phase() = context.getString(
        if (compared >= 0f) R.string.analysis_scan_comparing else R.string.analysis_scan_walking
    )

    /**
     * The end: the ring takes the final [sweeps] and [finalColors] (in the order of the slots: the file types,
     * then the merged small ones), flies to the circle at [x], [y] with [radius] and [stroke], everything else
     * fades, and [onEnd] runs when it has landed.
     */
    fun finishInto(
        x: Float, y: Float, radius: Float, stroke: Float, sweeps: FloatArray, finalColors: Array<IntArray>,
        onEnd: () -> Unit
    ) {
        morphing = true
        shown.copyInto(from)
        sweeps.copyInto(target)
        colors = finalColors
        toX = x
        toY = y
        toRadius = radius
        toStroke = stroke
        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_MILLIS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                morph = it.animatedValue as Float
                // The slices settle first, then the ring is already the chart while it travels.
                val settle = (morph / 0.5f).coerceAtMost(1f)
                for (i in shown.indices) shown[i] = from[i] + (target[i] - from[i]) * settle
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = baseRadius()
        val scale = scale()
        countPaint.textSize = sp(34f) * scale
        labelPaint.textSize = sp(13f) * scale
        sizePaint.textSize = sp(16f) * scale
        phasePaint.textSize = sp(16f) * (1f + (scale - 1f) / 2)
        folderPaint.textSize = sp(13f) * (1f + (scale - 1f) / 2)
        beam = SweepGradient(0f, 0f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x99FFFFFF.toInt()),
            floatArrayOf(0f, 0.72f, 1f))
        compareShader = SweepGradient(0f, 0f, intArrayOf(glows[0], glows[1], glows[2], glows[0]), null)
        halos = glows.map {
            RadialGradient(0f, 0f, radius * 1.25f, intArrayOf(it and 0x00FFFFFF or 0x38000000, it and 0x00FFFFFF), null,
                Shader.TileMode.CLAMP)
        }
        head = RadialGradient(0f, 0f, baseStroke() * 1.1f,
            intArrayOf(Color.WHITE, glows[0] and 0x00FFFFFF or 0x99000000.toInt(), glows[0] and 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    }

    // A wide screen gets a ring that fills it, and everything else grows along.
    private fun baseRadius() = min(min(width, height) * 0.27f, 132 * density * MAX_SCALE)
    private fun scale() = (baseRadius() / (132 * density)).coerceIn(1f, MAX_SCALE)
    private fun baseStroke() = 16 * density * scale()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Motion.any && isShown) start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        morphAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!Motion.any) return
        if (isVisible) start() else ticker.cancel()
    }

    private fun start() {
        if (ticker.isStarted) return
        lastFrame = 0L
        ticker.start()
    }

    // Every shown number follows its real one a little each frame, so nothing jumps however rarely news arrive.
    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        time += dt
        appear = min(1f, appear + dt / 0.4f)
        val ease = min(1f, dt * 6f)
        if (!morphing) for (i in shown.indices) shown[i] += (target[i] - shown[i]) * ease
        shownFiles += (files - shownFiles) * ease
        shownBytes += (bytes - shownBytes) * ease
        // The caption only moves on once the count on screen has arrived: it must not contradict the number.
        val counted = files - shownFiles < 1.5f
        comparing += ((if (compared >= 0f && counted) 1f else 0f) - comparing) * min(1f, dt * 4f)
        shownCompared += (compared.coerceAtLeast(0f) - shownCompared) * ease
        activity = (activity - dt * 0.8f).coerceAtLeast(0f)
        // A folder name stays long enough to be read, then the next one fades through.
        val millis = SystemClock.uptimeMillis()
        if (drawnFolder != folder && millis - folderSince > FOLDER_MILLIS) {
            folderAlpha -= dt * 7f
            if (folderAlpha <= 0f) {
                drawnFolder = folder
                folderSince = millis
                folderAlpha = 0f
            }
        } else {
            folderAlpha = min(1f, folderAlpha + dt * 7f)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val fade = 1f - morph
        val look = appear * appear * (3 - 2 * appear)
        val radius = lerp(baseRadius() * (0.92f + 0.08f * look), toRadius, morph)
        val stroke = lerp(baseStroke(), toStroke, morph)
        val cx = lerp(width / 2f, toX, morph)
        val cy = lerp(height * 0.42f, toY, morph)
        val textAlpha = look * (1f - morph * 2.5f).coerceAtLeast(0f)
        val full = Motion.full

        if (full && fade > 0f) drawHalo(canvas, cx, cy, radius, look * fade)
        trackPaint.strokeWidth = stroke
        trackPaint.alpha = (Color.alpha(ContextCompat.getColor(context, R.color.chart_track)) * look * fade).toInt()
        canvas.drawCircle(cx, cy, radius, trackPaint)
        Ring.draw(canvas, ringPaint, cx, cy, radius, stroke, 2.5f * density, shown, colors, alpha = look)
        if (Motion.any && fade > 0f) drawBeam(canvas, cx, cy, radius, stroke, look * fade * fade)
        if (comparing > 0.01f && fade > 0f) drawCompared(canvas, cx, cy, radius + stroke / 2 + 9 * density, look * fade)
        if (full && activity > 0.01f && fade > 0f) drawParticles(canvas, cx, cy, radius, stroke, look * fade)
        if (textAlpha > 0f) drawTexts(canvas, cx, cy, radius + stroke / 2, textAlpha)
    }

    // Aurora behind the ring: three soft lights on slow orbits.
    private fun drawHalo(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float) {
        halos.forEachIndexed { i, shader ->
            val angle = time * (0.35f + i * 0.13f) + i * 2.1f
            val x = cx + cos(angle) * radius * 0.55f
            val y = cy + sin(angle * 1.2f) * radius * 0.55f
            matrix.setTranslate(x, y)
            shader.setLocalMatrix(matrix)
            haloPaint.shader = shader
            haloPaint.alpha = (255 * alpha).toInt()
            canvas.drawCircle(x, y, radius * 1.25f, haloPaint)
        }
    }

    // The light that runs round the ring while the end is unknown, with a bright head.
    private fun drawBeam(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float, alpha: Float) {
        val angle = time * 200f % 360f
        matrix.setRotate(angle, 0f, 0f)
        matrix.postTranslate(cx, cy)
        beam?.setLocalMatrix(matrix)
        beamPaint.shader = beam
        beamPaint.strokeWidth = stroke
        beamPaint.alpha = (255 * alpha).toInt()
        canvas.drawCircle(cx, cy, radius, beamPaint)
        val a = Math.toRadians(angle.toDouble())
        val x = cx + radius * cos(a).toFloat()
        val y = cy + radius * sin(a).toFloat()
        matrix.setTranslate(x, y)
        head?.setLocalMatrix(matrix)
        headPaint.shader = head
        headPaint.alpha = (255 * alpha).toInt()
        canvas.drawCircle(x, y, baseStroke() * 1.1f, headPaint)
    }

    // Comparing possible duplicates has a known end: a thin arc outside the ring shows how far it is.
    private fun drawCompared(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float) {
        comparePaint.strokeWidth = 3 * density
        comparePaint.shader = null
        comparePaint.color = trackPaint.color
        comparePaint.alpha = (Color.alpha(trackPaint.color) * alpha * comparing).toInt()
        canvas.drawCircle(cx, cy, radius, comparePaint)
        matrix.setRotate(-90f, 0f, 0f)
        matrix.postTranslate(cx, cy)
        compareShader?.setLocalMatrix(matrix)
        comparePaint.shader = compareShader
        comparePaint.alpha = (255 * alpha * comparing).toInt()
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f,
            360f * shownCompared.coerceIn(0.005f, 1f), false, comparePaint)
    }

    // Files on their way into the ring: small lights in the colours of the file types, only while files come in.
    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float, alpha: Float) {
        for (i in 0 until PARTICLES) {
            val phase = (time * (0.45f + 0.05f * (i % 5)) + i * 0.137f) % 1f
            val angle = i * 2.399f + (time * 0.1f)
            val distance = radius * (1.75f - 0.75f * phase * phase)
            val light = sin(phase * Math.PI.toFloat())
            particlePaint.color = colors[i % StorageInsights.KINDS.size][0]
            particlePaint.alpha = (220 * light * alpha * activity).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx + cos(angle) * distance, cy + sin(angle) * distance,
                stroke * (0.1f + 0.12f * light), particlePaint)
        }
    }

    private fun drawTexts(canvas: Canvas, cx: Float, cy: Float, outer: Float, alpha: Float) {
        val a = (255 * alpha).toInt()
        val count = shownFiles.toInt().let { if (files - it <= 1) files else it }
        if (count != countValue) {
            countValue = count
            countText = number.format(count)
        }
        val size = shownBytes.toLong().let { if (bytes - it < bytes / 500 + 1) bytes else it }
        if (size != sizeValue) {
            sizeValue = size
            sizeText = size.asFileSize().formatHumanReadable(context)
        }
        countPaint.alpha = a
        labelPaint.alpha = a
        sizePaint.alpha = a
        phasePaint.alpha = a
        // The number shrinks before it would touch the ring.
        val room = (outer - baseStroke() - 14 * density) * 2
        val base = countPaint.textSize
        countPaint.textSize = base * min(1f, room / countPaint.measureText(countText).coerceAtLeast(1f))
        val gap = 4 * density
        val block = -countPaint.ascent() + gap - labelPaint.ascent() + labelPaint.descent() + gap * 2 -
            sizePaint.ascent() + sizePaint.descent()
        var y = cy - block / 2 - countPaint.ascent()
        canvas.drawText(countText, cx, y, countPaint)
        y += gap - labelPaint.ascent()
        canvas.drawText(resources.getQuantityText(R.plurals.analysis_scan_files, countValue).toString(), cx, y, labelPaint)
        y += labelPaint.descent() + gap * 2 - sizePaint.ascent()
        canvas.drawText(sizeText, cx, y, sizePaint)
        countPaint.textSize = base

        val below = cy + outer + 44 * density * scale()
        canvas.drawText(context.getString(if (comparing > 0.5f) R.string.analysis_scan_comparing
            else R.string.analysis_scan_walking), cx, below, phasePaint)
        folderPaint.alpha = (a * folderAlpha * (1f - comparing)).toInt()
        if (drawnFolder.isNotEmpty() && folderPaint.alpha > 0) {
            val text = TextUtils.ellipsize(drawnFolder.replace("/", " › "), folderPaint, width - 48 * density,
                TextUtils.TruncateAt.START)
            canvas.drawText(text.toString(), cx, below + 26 * density * scale(), folderPaint)
        }
        if (comparing > 0.01f) {
            folderPaint.alpha = (a * comparing).toInt()
            canvas.drawText(NumberFormat.getPercentInstance().format(shownCompared.toDouble()), cx,
                below + 26 * density * scale(), folderPaint)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val MIN_VISIBLE_MILLIS = 1100L
        const val MORPH_MILLIS = 700L
        const val FOLDER_MILLIS = 450L
        const val PARTICLES = 16
        const val MAX_SCALE = 1.7f
    }
}
