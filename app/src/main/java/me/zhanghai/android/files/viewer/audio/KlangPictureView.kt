/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import me.zhanghai.android.files.R
import me.zhanghai.android.files.player.AudioSettings
import me.zhanghai.android.files.ui.Motion

/**
 * The small live picture on each card of the lower Sound sheet: what the setting does, drawn on the canvas in theme
 * colours. [setState] moves it to a new value in a calm transition; with full motion an "on" picture also breathes in
 * a slow loop, which pauses while the picture is scrolled out of sight. Purely a picture: the sheet sets its
 * contentDescription with the current value.
 */
class KlangPictureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), ViewTreeObserver.OnScrollChangedListener {
    enum class Kind {
        BASS, CROSSFEED, WIDTH, REVERB, WARMTH, CLARITY, NORMALIZE, MONO, SPEED, SKIP_SILENCE, SLEEP, FOCUS, HEADPHONES,
        HIGHS, QUIET
    }

    var kind = Kind.BASS
        set(value) {
            field = value
            invalidate()
        }

    /** Swings per second of the speed pendulum: the playback tempo. */
    var tempo = 1f
        set(value) {
            field = value
            invalidate()
        }

    // Drawn values: 0..1 each, moved together by one animator.
    private var amount = 0f
    private var onness = 0f
    private var targetAmount = 0f
    private var targetOn = 0f
    private var initialized = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TRANSITION_MS
        interpolator = FastOutSlowInInterpolator()
    }
    private var waitingForSight = false
    private val visibleRect = Rect()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val cutPath = Path()
    private val oval = RectF()
    private val headphones = ContextCompat.getDrawable(context, R.drawable.audio_headphones_icon_24dp)!!.mutate()
    private val speaker = ContextCompat.getDrawable(context, R.drawable.audio_speaker_icon_24dp)!!.mutate()
    private var primary = 0
    private var muted = 0
    private var track = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        resolveColors()
    }

    /**
     * [on] whether the setting is active, [value] 0..1 how strong (or, for the sleep timer, how much time is left).
     * The first call places the picture without a transition.
     */
    fun setState(on: Boolean, value: Float) {
        val newOn = if (on) 1f else 0f
        val newAmount = value.coerceIn(0f, 1f)
        if (initialized && newOn == targetOn && newAmount == targetAmount) return
        val fromAmount = amount
        val fromOn = onness
        targetAmount = newAmount
        targetOn = newOn
        animator.cancel()
        animator.removeAllUpdateListeners()
        if (!initialized || !Motion.any || !isLaidOut) {
            amount = newAmount
            onness = newOn
        } else {
            animator.addUpdateListener {
                val t = it.animatedValue as Float
                amount = fromAmount + (newAmount - fromAmount) * t
                onness = fromOn + (newOn - fromOn) * t
                invalidate()
            }
            animator.start()
        }
        initialized = true
        invalidate()
    }

    private fun looping(): Boolean = Motion.full && (targetOn > 0f || kind == Kind.SPEED)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
        viewTreeObserver.addOnScrollChangedListener(this)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(this)
        super.onDetachedFromWindow()
    }

    // Scrolled back into sight: the paused loop picks up again.
    override fun onScrollChanged() {
        if (waitingForSight && looping() && getLocalVisibleRect(visibleRect)) {
            waitingForSight = false
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(144f).roundToInt(), widthMeasureSpec),
            resolveSize(dp(55f).roundToInt(), heightMeasureSpec)
        )
    }

    private fun resolveColors() {
        primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        muted = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface)
        track = ColorUtils.setAlphaComponent(onSurface, 0x33)
    }

    /** The active colour fades in with the setting; off pictures stay in the quiet text colour. */
    private fun accent(alphaFraction: Float = 1f): Int {
        val color = ColorUtils.blendARGB(muted, primary, onness)
        val enabledAlpha = if (isEnabled) 1f else DISABLED_ALPHA
        // Switched off, the whole picture steps back, so "off" reads at a glance and not only from the switch.
        val presence = OFF_PRESENCE + (1f - OFF_PRESENCE) * onness
        return ColorUtils.setAlphaComponent(
            color, (255 * alphaFraction.coerceIn(0f, 1f) * enabledAlpha * presence).roundToInt()
        )
    }

    private fun trackColor(): Int =
        if (isEnabled) track else ColorUtils.setAlphaComponent(track, (Color.alpha(track) * DISABLED_ALPHA).roundToInt())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val loop = looping()
        val phase = if (loop) (SystemClock.uptimeMillis() % LOOP_MS) / LOOP_MS.toFloat() else STILL_PHASE
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        stroke.strokeWidth = dp(2.5f)
        when (kind) {
            Kind.BASS -> drawBass(canvas, left, top, right, bottom, phase)
            Kind.CROSSFEED -> drawCrossfeed(canvas, left, top, right, bottom, phase)
            Kind.WIDTH -> drawWidth(canvas, left, top, right, bottom, phase)
            Kind.REVERB -> drawReverb(canvas, left, top, right, bottom, phase)
            Kind.WARMTH -> drawWarmth(canvas, left, top, right, bottom, phase)
            Kind.CLARITY -> drawBars(canvas, left, top, right, bottom, phase, CLARITY_BARS, amount * onness)
            Kind.NORMALIZE -> drawBars(canvas, left, top, right, bottom, phase, SONG_BARS, onness)
            Kind.MONO -> drawMono(canvas, left, top, right, bottom, phase)
            Kind.SPEED -> drawPendulum(canvas, left, top, right, bottom)
            Kind.SKIP_SILENCE -> drawSkipSilence(canvas, left, top, right, bottom, phase)
            Kind.SLEEP -> drawSleep(canvas, left, top, right, bottom, phase)
            Kind.FOCUS -> drawFocus(canvas, left, top, right, bottom, phase)
            Kind.HEADPHONES -> drawHeadphones(canvas, left, top, right, bottom, phase)
            Kind.HIGHS -> drawHighs(canvas, left, top, right, bottom, phase)
            Kind.QUIET -> drawQuiet(canvas, left, top, right, bottom, phase)
        }
        if (loop) {
            // Only while some of the picture is on screen; scrolling it back in restarts the loop.
            if (getLocalVisibleRect(visibleRect)) postInvalidateDelayed(FRAME_MS) else waitingForSight = true
        }
    }

    /** Low, long waves; their height grows with the strength. */
    private fun drawBass(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cy = (t + b) / 2
        val maxAmplitude = (b - t) * 0.42f
        val amplitude = maxAmplitude * (0.15f + 0.85f * amount) * (0.35f + 0.65f * onness)
        for (layer in 2 downTo 0) {
            stroke.color = accent(1f - layer * 0.3f)
            val scale = 1f - layer * 0.28f
            wave(l, r) { x -> cy - amplitude * scale * sin((x * 1.4f + phase + layer * 0.12f) * TWO_PI) }
            canvas.drawPath(path, stroke)
        }
    }

    /** Headphones in the middle, sound arcs on both sides that spread further with a stronger setting. */
    private fun drawCrossfeed(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val icon = dp(24f)
        headphones.setTint(accent())
        headphones.setBounds(
            (cx - icon / 2).roundToInt(), (cy - icon / 2).roundToInt(),
            (cx + icon / 2).roundToInt(), (cy + icon / 2).roundToInt()
        )
        headphones.draw(canvas)
        val spread = dp(9f) * (0.6f + 0.9f * amount * onness)
        val sweep = 50f + 40f * amount * onness
        for (i in 0 until ARCS) {
            val step = (i + phase) % ARCS
            val radius = icon * 0.75f + spread * (step + 0.5f)
            stroke.color = accent(1f - step / ARCS)
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(oval, 180f - sweep / 2, sweep, false, stroke)
            canvas.drawArc(oval, -sweep / 2, sweep, false, stroke)
        }
    }

    /**
     * A listener in the middle and a speaker on each side, sending sound arcs towards the head: at 0 % (mono) both
     * speakers stand right beside the head, with more width they move apart. Nothing in it looks like a handle.
     */
    private fun drawWidth(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val head = minOf((b - t) * 0.22f, dp(13f))
        val icon = minOf(dp(24f), (b - t) * 0.6f)
        val nearest = head + dp(10f) + icon / 2
        val farthest = (r - l) / 2 - icon / 2
        val shown = amount * onness + (1f - onness) * 100f / AudioSettings.MAX_WIDTH_PERCENT
        val distance = nearest + (farthest - nearest) * shown
        // The listener: a head with two ears.
        fill.color = accent()
        canvas.drawCircle(cx, cy, head, fill)
        canvas.drawCircle(cx - head, cy, head * 0.32f, fill)
        canvas.drawCircle(cx + head, cy, head * 0.32f, fill)
        for (side in SIDES) {
            val x = cx + side * distance
            speaker.setTint(accent())
            canvas.save()
            if (side > 0) canvas.scale(-1f, 1f, x, cy)
            speaker.setBounds(
                (x - icon / 2).roundToInt(), (cy - icon / 2).roundToInt(),
                (x + icon / 2).roundToInt(), (cy + icon / 2).roundToInt()
            )
            speaker.draw(canvas)
            canvas.restore()
            // Arcs travel from the speaker towards the listener.
            val gap = distance - nearest + dp(6f)
            for (i in 0 until ARCS) {
                val step = (i + phase) % ARCS
                val radius = icon / 2 + dp(4f) + gap * (step + 0.5f) / ARCS * 0.8f
                stroke.color = accent(0.8f * (1f - step / ARCS))
                oval.set(x - radius, cy - radius, x + radius, cy + radius)
                val sweep = 50f
                canvas.drawArc(oval, (if (side < 0) 0f else 180f) - sweep / 2, sweep, false, stroke)
            }
        }
    }

    /** A source and the echo rings around it: bigger rooms reach further, a larger share rings brighter. */
    private fun drawReverb(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val reach = (r - l) / 2 * (0.3f + 0.7f * amount)
        for (i in 0 until RINGS) {
            val step = (i + phase) % RINGS
            val fraction = (step + 1) / RINGS
            stroke.color = accent((1f - fraction) * (0.3f + 0.7f * onness))
            val radiusX = reach * fraction
            val radiusY = minOf(radiusX, (b - t) / 2 - dp(2f))
            oval.set(cx - radiusX, cy - radiusY, cx + radiusX, cy + radiusY)
            canvas.drawOval(oval, stroke)
        }
        fill.color = accent()
        canvas.drawCircle(cx, cy, dp(5f), fill)
    }

    /** A sine that bends into a soft-clipped, rounder shape with more warmth; the clean sine stays behind it. */
    private fun drawWarmth(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cy = (t + b) / 2
        val amplitude = (b - t) * 0.42f
        stroke.color = trackColor()
        wave(l, r) { x -> cy - amplitude * sin((x * 2f + phase) * TWO_PI) }
        canvas.drawPath(path, stroke)
        val drive = 0.05f + 3.5f * amount * onness
        stroke.color = accent()
        wave(l, r) { x -> cy - amplitude * tanh(drive * sin((x * 2f + phase) * TWO_PI)) / tanh(drive) }
        canvas.drawPath(path, stroke)
    }

    /** Bars of uneven height that even out by [settle]: clarity (by level) and volume levelling (on/off). */
    private fun drawBars(
        canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float, heights: FloatArray, settle: Float
    ) {
        val count = heights.size
        val slot = (r - l) / count
        val barWidth = minOf(slot * 0.5f, dp(10f))
        val mean = heights.average().toFloat()
        stroke.strokeWidth = barWidth
        for (i in 0 until count) {
            val wobble = 0.06f * sin((phase + i / count.toFloat()) * TWO_PI) * onness
            val height = (heights[i] + (mean - heights[i]) * settle + wobble).coerceIn(0.1f, 1f)
            val x = l + slot * (i + 0.5f)
            stroke.color = accent(0.55f + 0.45f * height)
            canvas.drawLine(x, b - barWidth / 2, x, b - barWidth / 2 - (b - t - barWidth) * height, stroke)
        }
        stroke.strokeWidth = dp(1f)
        stroke.color = trackColor()
        val level = b - barWidth / 2 - (b - t - barWidth) * mean
        canvas.drawLine(l, level, r, level, stroke)
    }

    /** Left and right channel as two waves that merge into one when mono is on. */
    private fun drawMono(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cy = (t + b) / 2
        val apart = (b - t) * 0.2f * (1f - onness)
        val amplitude = (b - t) * 0.22f
        for (index in SIDES.indices) {
            val sign = SIDES[index]
            stroke.color = accent(if (index == 0) 1f else 0.6f + 0.4f * onness)
            val shift = 0.25f * (1f - onness) * sign
            wave(l, r) { x -> cy + sign * apart - amplitude * sin((x * 2f + phase + shift) * TWO_PI) }
            canvas.drawPath(path, stroke)
        }
    }

    /**
     * A pendulum that swings at the playback tempo (full motion), or rests at its turning point. It hangs from a small
     * bar inside the picture, so neither the pivot nor the bob touches the edge.
     */
    private fun drawPendulum(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val cx = (l + r) / 2
        val bob = dp(7f) + dp(3f) * amount
        val pivotY = t + dp(8f)
        val length = b - pivotY - bob - dp(4f)
        val maxAngle = 35f
        val angle = if (Motion.full) {
            // One full swing (there and back) per beat pair: at 1× about 1 s per swing.
            maxAngle * sin(SystemClock.uptimeMillis() / 1_000f * PI.toFloat() * tempo)
        } else maxAngle * 0.7f
        stroke.color = trackColor()
        oval.set(cx - length, pivotY - length, cx + length, pivotY + length)
        canvas.drawArc(oval, 90f - maxAngle, maxAngle * 2, false, stroke)
        stroke.color = accent(0.6f)
        canvas.drawLine(cx - dp(14f), pivotY, cx + dp(14f), pivotY, stroke)
        val radians = Math.toRadians(angle.toDouble())
        val bobX = cx + length * sin(radians).toFloat()
        val bobY = pivotY + length * cos(radians).toFloat()
        stroke.color = accent()
        canvas.drawLine(cx, pivotY, bobX, bobY, stroke)
        fill.color = accent()
        canvas.drawCircle(cx, pivotY, dp(3.5f), fill)
        canvas.drawCircle(bobX, bobY, bob, fill)
    }

    /** A waveform with quiet gaps that close up when silence is skipped. */
    private fun drawSkipSilence(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cy = (t + b) / 2
        val gap = (r - l) * 0.16f * (1f - onness)
        val body = (r - l - gap * 2) / 3
        val half = (b - t) / 2 - dp(3f)
        stroke.strokeWidth = dp(2f)
        var x = l
        for (segment in 0 until 3) {
            val end = x + body
            stroke.color = accent()
            var px = x
            var i = 0
            while (px <= end) {
                val envelope = sin((px - x) / body * PI.toFloat())
                val level = abs(sin((i * 0.9f + segment * 2.3f + phase * 2f) * 2.1f)) * envelope
                canvas.drawLine(px, cy - half * level, px, cy + half * level, stroke)
                px += dp(4f)
                i++
            }
            if (segment < 2 && gap > dp(1f)) {
                stroke.color = trackColor()
                canvas.drawLine(end + dp(3f), cy, end + gap - dp(3f), cy, stroke)
            }
            x = end + gap
        }
    }

    /** A moon inside a ring that runs down with the time left. */
    private fun drawSleep(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val radius = (b - t) / 2 - dp(4f)
        stroke.color = trackColor()
        canvas.drawCircle(cx, cy, radius, stroke)
        if (onness > 0f) {
            stroke.color = accent()
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(oval, -90f, 360f * amount * onness, false, stroke)
        }
        val moon = radius * 0.55f
        path.reset()
        path.addCircle(cx, cy, moon, Path.Direction.CW)
        cutPath.reset()
        cutPath.addCircle(cx + moon * 0.55f, cy - moon * 0.35f, moon * 0.85f, Path.Direction.CW)
        path.op(cutPath, Path.Op.DIFFERENCE)
        fill.color = accent()
        canvas.drawPath(path, fill)
        // Two stars beside the ring, twinkling slowly with full motion.
        for (index in SIDES.indices) {
            val side = SIDES[index]
            val twinkle = 0.5f + 0.5f * sin((phase + index * 0.5f) * TWO_PI)
            fill.color = accent(0.35f + 0.65f * twinkle * onness)
            canvas.drawCircle(cx + side * (radius + dp(16f + index * 6f)), cy - dp(6f - index * 10f), dp(2.5f), fill)
        }
    }

    /**
     * Another app's sound (top, quiet colour) and Ordo's (bottom): with "quieter" Ordo's wave shrinks under the other
     * one, with "pause" it lies flat behind a pause sign. [amount] 1 = quieter, 0 = pause.
     */
    private fun drawFocus(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val height = b - t
        val otherY = t + height * 0.28f
        val ownY = t + height * 0.72f
        val amplitude = height * 0.18f
        stroke.color = ColorUtils.setAlphaComponent(muted, 160)
        wave(l, r) { x -> otherY - amplitude * sin((x * 3f + phase) * TWO_PI) }
        canvas.drawPath(path, stroke)
        val own = amplitude * (0.05f + 0.35f * amount)
        stroke.color = accent()
        wave(l, r - dp(22f)) { x -> ownY - own * sin((x * 2f + phase) * TWO_PI) }
        canvas.drawPath(path, stroke)
        // Pause sign fades in as the choice moves to "pause".
        fill.color = accent(1f - amount)
        val x = r - dp(10f)
        canvas.drawRect(x - dp(7f), ownY - dp(7f), x - dp(3f), ownY + dp(7f), fill)
        canvas.drawRect(x + dp(1f), ownY - dp(7f), x + dp(5f), ownY + dp(7f), fill)
    }

    /** Headphones with a pulse running out of them: playback picks up again when they connect. */
    private fun drawHeadphones(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val icon = minOf(dp(28f), (b - t) * 0.6f)
        // Wide pulse ovals: in a 55 dp picture round rings would have no room to run out.
        val reachX = minOf((r - l) / 2, icon * 4f)
        for (i in 0 until RINGS) {
            val step = (i + phase) % RINGS
            val fraction = (step + 1) / RINGS
            val radiusX = icon * 0.7f + (reachX - icon * 0.7f) * fraction
            val radiusY = icon * 0.7f + ((b - t) / 2 - dp(2f) - icon * 0.7f) * fraction
            stroke.color = accent((1f - fraction) * (0.3f + 0.7f * onness))
            oval.set(cx - radiusX, cy - radiusY, cx + radiusX, cy + radiusY)
            canvas.drawOval(oval, stroke)
        }
        headphones.setTint(accent())
        headphones.setBounds(
            (cx - icon / 2).roundToInt(), (cy - icon / 2).roundToInt(),
            (cx + icon / 2).roundToInt(), (cy + icon / 2).roundToInt()
        )
        headphones.draw(canvas)
    }

    /**
     * A spectrum that falls off at the encoder's cut (three quarters of the way); the band above it fills in softly,
     * higher with the stronger level, and never as tall as the music below.
     */
    private fun drawHighs(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val bars = 16
        val cut = 12
        val slot = (r - l) / bars
        val barWidth = minOf(slot * 0.55f, dp(9f))
        stroke.strokeWidth = barWidth
        for (i in 0 until bars) {
            val x = l + slot * (i + 0.5f)
            val wobble = 0.05f * sin((phase + i / bars.toFloat()) * TWO_PI) * onness
            val height = if (i < cut) {
                (0.85f - 0.035f * i + wobble).coerceIn(0.1f, 1f)
            } else {
                // Above the cut: nothing in the file; the filled-in part stays well below the music.
                (0.06f + (0.22f + 0.1f * amount - 0.05f * (i - cut)) * onness + wobble).coerceIn(0.04f, 0.4f)
            }
            stroke.color = if (i < cut) accent(0.55f + 0.45f * height) else accent(0.35f + 0.3f * onness)
            canvas.drawLine(x, b - barWidth / 2, x, b - barWidth / 2 - (b - t - barWidth) * height, stroke)
        }
        stroke.strokeWidth = dp(1f)
        stroke.color = trackColor()
        val cutX = l + slot * cut
        canvas.drawLine(cutX, t, cutX, b, stroke)
    }

    /**
     * A volume level on the left (the louder, the taller), and the frequency response beside it: flat when loud, with
     * the lows (and a little of the highs) lifted as the volume goes down. [amount] is the current lift, 0..1.
     */
    private fun drawQuiet(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, phase: Float) {
        val barWidth = dp(8f)
        val volume = 1f - amount * 0.8f
        stroke.strokeWidth = barWidth
        stroke.color = trackColor()
        val barX = l + barWidth
        canvas.drawLine(barX, b - barWidth / 2, barX, t + barWidth / 2, stroke)
        stroke.color = accent()
        canvas.drawLine(barX, b - barWidth / 2, barX, b - barWidth / 2 - (b - t - barWidth) * volume, stroke)
        stroke.strokeWidth = dp(2.5f)
        val left = l + barWidth * 3
        val mid = (t + b) / 2 + (b - t) * 0.15f
        val lift = (b - t) * 0.4f * amount * onness
        val breathe = 1f + 0.06f * sin(phase * TWO_PI) * onness
        stroke.color = trackColor()
        canvas.drawLine(left, mid, r, mid, stroke)
        stroke.color = accent()
        wave(left, r) { x ->
            // Lows rise most (left), the mids stay, the very highs rise a little (right).
            val low = (1f - x / 0.45f).coerceAtLeast(0f)
            val high = ((x - 0.75f) / 0.25f).coerceAtLeast(0f) * 0.35f
            mid - lift * breathe * (low * low + high)
        }
        canvas.drawPath(path, stroke)
    }

    /** Fills [path] with y(x) sampled across the width, x passed as 0..1. */
    private inline fun wave(l: Float, r: Float, y: (Float) -> Float) {
        path.reset()
        val steps = 48
        for (i in 0..steps) {
            val fraction = i / steps.toFloat()
            val px = l + (r - l) * fraction
            if (i == 0) path.moveTo(px, y(fraction)) else path.lineTo(px, y(fraction))
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val TRANSITION_MS = 320L
        private const val LOOP_MS = 4_000L
        private const val FRAME_MS = 33L
        private const val STILL_PHASE = 0.15f
        private const val DISABLED_ALPHA = 0.38f
        private const val OFF_PRESENCE = 0.45f
        private const val TWO_PI = (2 * PI).toFloat()
        private const val ARCS = 3
        private const val RINGS = 4
        private val CLARITY_BARS = floatArrayOf(0.35f, 0.9f, 0.5f, 0.75f, 0.25f, 0.85f, 0.45f, 0.65f, 0.3f)
        private val SONG_BARS = floatArrayOf(0.3f, 0.8f, 0.55f, 0.95f, 0.4f, 0.7f)
        private val SIDES = floatArrayOf(-1f, 1f)
    }
}
