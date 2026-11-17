/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player

import me.zhanghai.android.files.player.EffectGate.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The platform effects switch only while nothing is heard, and "everything off" ends bit-perfect. */
class EffectGateTest {
    @Test
    fun neutralWhilePlayingStaysOnUntilSilence() {
        val gate = EffectGate()
        assertEquals(Action.ENABLE, gate.onSettings(neutral = false, audible = false))
        // The user turns the equalizer off while music plays: the bands glide to 0 dB, the effect stays on.
        assertEquals(Action.NONE, gate.onSettings(neutral = true, audible = true))
        assertTrue(gate.enabled)
        // Paused (after the 120 ms fade), but the glide has not arrived yet: still on.
        assertEquals(Action.NONE, gate.onSilent(neutral = true, settled = false))
        // Arrived: released, the path is bit-perfect again.
        assertEquals(Action.DISABLE, gate.onSilent(neutral = true, settled = true))
        assertFalse(gate.enabled)
        assertEquals(Action.NONE, gate.onSilent(neutral = true, settled = true))
    }

    @Test
    fun aChangeWhilePausedSwitchesOnBeforePlay() {
        val gate = EffectGate()
        assertEquals(Action.NONE, gate.onSettings(neutral = true, audible = false))
        assertEquals(Action.ENABLE, gate.onSettings(neutral = false, audible = false))
        assertTrue(gate.enabled)
        // Silence with a non-neutral setting never releases.
        assertEquals(Action.NONE, gate.onSilent(neutral = false, settled = true))
    }

    @Test
    fun aChangeWhilePlayingSwitchesOnInsideAFade() {
        val gate = EffectGate()
        assertEquals(Action.ENABLE_IN_FADE, gate.onSettings(neutral = false, audible = true))
        assertEquals(Action.NONE, gate.onSettings(neutral = false, audible = true))
    }

    @Test
    fun recreatedEffectsStartOff() {
        val gate = EffectGate()
        gate.onSettings(neutral = false, audible = false)
        gate.reset()
        assertFalse(gate.enabled)
    }
}
