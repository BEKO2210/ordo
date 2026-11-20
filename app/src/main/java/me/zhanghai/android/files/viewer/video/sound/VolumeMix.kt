/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

/**
 * The video player's volume as a product of independent factors, like Ordo Music's service (sleep × focus ×
 * transport): the effect fader's dip ([fader]) and the sleep timer's fade-out ([sleep]) each set only their own factor,
 * so neither overrides the other. Every change writes the product through [apply].
 */
class VolumeMix(private val apply: (Float) -> Unit) {
    var fader = 1f
        set(value) {
            field = value
            push()
        }

    var sleep = 1f
        set(value) {
            field = value
            push()
        }

    val volume: Float get() = (fader * sleep).coerceIn(0f, 1f)

    private fun push() = apply(volume)
}
