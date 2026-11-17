/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import kotlin.math.PI
import kotlin.math.cos

/**
 * Gentle play, pause and stop: the player volume glides along a cosine to silence before a pause or stop
 * ([OUT_MS]) and up from silence after play ([IN_MS]). The player volume is applied in the mixer when the sound is
 * heard, which ramps each step linearly over one mix period; a fade in a PCM processor would come 250-750 ms too
 * early, because processors run ahead of the AudioTrack buffer (androidx/media#2). Seeks need nothing here: Media3
 * already ramps the first 20 ms of every new AudioTrack (docs/AUDIO-QUALITAET-RECHERCHE.md, 1.1).
 *
 * A newer command never swallows an older one: its pending action runs at once before the new fade starts.
 */
class TransportFader(
    private val now: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val apply: (Float) -> Unit
) {
    var gain = 1f
        private set
    private var from = 1f
    private var to = 1f
    private var start = 0L
    private var duration = 0L
    private var then: (() -> Unit)? = null
    private val tick = Runnable { step() }

    /** Pause, stop: fade out, then [action], then back to full volume (the player is quiet anyway). */
    fun fadeOutThen(action: () -> Unit) {
        settle()
        rampTo(0f, OUT_MS) {
            action()
            setGain(1f)
        }
    }

    /** Play: start silent, run [action], fade in. */
    fun fadeIn(action: () -> Unit) {
        settle()
        setGain(0f)
        action()
        rampTo(1f, IN_MS, null)
    }

    /** Nothing to fade (nothing is playing, or the headphones were pulled out): [action] at once at full volume. */
    fun immediate(action: () -> Unit) {
        settle()
        setGain(1f)
        action()
    }

    private fun settle() {
        unschedule(tick)
        val pending = then
        then = null
        pending?.invoke()
    }

    private fun setGain(value: Float) {
        gain = value
        apply(value)
    }

    private fun rampTo(target: Float, durationMs: Long, done: (() -> Unit)?) {
        unschedule(tick)
        from = gain
        to = target
        start = now()
        duration = durationMs
        then = done
        step()
    }

    private fun step() {
        val elapsed = now() - start
        val fraction = if (duration <= 0 || from == to) 1f else (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        setGain(from + (to - from) * cosineEase(fraction))
        if (fraction < 1f) {
            schedule(tick, STEP_MS)
        } else {
            val done = then
            then = null
            done?.invoke()
        }
    }

    companion object {
        const val OUT_MS = 120L
        const val IN_MS = 80L
        /** 5 ms steps keep every step of the cosine under a tenth of full volume, even over the shorter 80 ms. */
        const val STEP_MS = 5L

        /** 0 → 0, 1 → 1, flat at both ends: no corner where the fade starts or lands. */
        internal fun cosineEase(fraction: Float): Float = ((1 - cos(PI * fraction)) / 2).toFloat()
    }
}
