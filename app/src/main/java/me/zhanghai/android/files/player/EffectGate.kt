/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

/**
 * When the platform effects (DynamicsProcessing, BassBoost) may be switched on or off. Switching them is audible (latency
 * jump, cut to a plain copy), so it never happens while sound is heard; but with every setting neutral they are released,
 * so "everything off" is bit-perfect on the device too:
 * - neutral and playing: they stay on (at 0 dB) until the next silence;
 * - silence (paused after its fade, stopped, ended, waiting after a seek) with everything neutral and arrived: released;
 * - a setting becomes non-neutral while silent: on at once, before anything plays again;
 * - while playing: on inside a short fade of the player volume, so the switch itself is not heard.
 */
internal class EffectGate {
    var enabled = false
        private set

    enum class Action { NONE, ENABLE, ENABLE_IN_FADE, DISABLE }

    /** The effects were (re)created, switched off. */
    fun reset() {
        enabled = false
    }

    /** The settings changed; [neutral] when every gain, band, clarity and bass is at its neutral value. */
    fun onSettings(neutral: Boolean, audible: Boolean): Action {
        if (neutral || enabled) return Action.NONE
        enabled = true
        return if (audible) Action.ENABLE_IN_FADE else Action.ENABLE
    }

    /** Nothing is heard; [settled] when the glides have arrived at their (neutral) targets. */
    fun onSilent(neutral: Boolean, settled: Boolean): Action {
        if (!enabled || !neutral || !settled) return Action.NONE
        enabled = false
        return Action.DISABLE
    }
}
