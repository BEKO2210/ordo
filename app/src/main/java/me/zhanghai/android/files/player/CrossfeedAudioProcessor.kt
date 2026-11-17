/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The filter design and its constants are ported from libbs2b (Bauer stereophonic-to-binaural DSP),
 * src/bs2b.c and src/bs2b.h, https://github.com/alexmarsev/libbs2b, under this licence:
 *
 * Copyright (c) 2005 Boris Mikhaylov
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package me.zhanghai.android.files.player

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Headphone crossfeed after Bauer (bs2b): each ear also hears the other channel through a first-order low-pass,
 * while the direct path gets a matching high-shelf boost, so the sound moves from inside the head to the front.
 */
class CrossfeedAudioProcessor : StereoEffectProcessor() {
    /** 1 light, 2 medium, 3 strong. */
    @Volatile
    var level: Int = 2

    private var appliedLevel = -1
    private var appliedRate = -1
    private var a0Lo = 0.0
    private var b1Lo = 0.0
    private var a0Hi = 0.0
    private var a1Hi = 0.0
    private var b1Hi = 0.0
    private var gain = 1.0
    private var loLeft = 0.0
    private var loRight = 0.0
    private var hiLeft = 0.0
    private var hiRight = 0.0
    private var lastLeft = 0.0
    private var lastRight = 0.0

    override fun prepare() {
        val current = level.coerceIn(1, AudioSettings.LEVELS)
        if (current == appliedLevel && sampleRate == appliedRate) return
        appliedLevel = current
        appliedRate = sampleRate
        val (cutHz, feedDb) = LEVEL_PRESETS[current - 1]
        // bs2b init(): bs2b only accepts 2-384 kHz and falls back to 44.1 kHz outside.
        val rate = if (sampleRate in MIN_RATE..MAX_RATE) sampleRate.toDouble() else DEFAULT_SAMPLE_RATE.toDouble()
        val gbLo = feedDb * -5.0 / 6.0 - 3.0
        val gbHi = feedDb / 6.0 - 3.0
        val gLo = 10.0.pow(gbLo / 20.0)
        val gHi = 1.0 - 10.0.pow(gbHi / 20.0)
        val fcHi = cutHz * 2.0.pow((gbLo - 20.0 * log10(gHi)) / 12.0)
        var x = exp(-2.0 * PI * cutHz / rate)
        b1Lo = x
        a0Lo = gLo * (1.0 - x)
        x = exp(-2.0 * PI * fcHi / rate)
        b1Hi = x
        a0Hi = 1.0 - gHi * (1.0 - x)
        a1Hi = -x
        gain = 1.0 / (1.0 - gHi + gLo)
    }

    override fun clear() {
        loLeft = 0.0
        loRight = 0.0
        hiLeft = 0.0
        hiRight = 0.0
        lastLeft = 0.0
        lastRight = 0.0
    }

    // bs2b cross_feed_d()
    override fun processFrame(left: Float, right: Float) {
        loLeft = a0Lo * left + b1Lo * loLeft
        loRight = a0Lo * right + b1Lo * loRight
        hiLeft = a0Hi * left + a1Hi * lastLeft + b1Hi * hiLeft
        hiRight = a0Hi * right + a1Hi * lastRight + b1Hi * hiRight
        lastLeft = left.toDouble()
        lastRight = right.toDouble()
        outLeft = ((hiLeft + loRight) * gain).toFloat()
        outRight = ((hiRight + loLeft) * gain).toFloat()
    }

    companion object {
        private const val MIN_RATE = 2_000
        private const val MAX_RATE = 384_000

        /**
         * Cut frequency (Hz) and feed level (dB) of bs2b's named presets: BS2B_JMEIER_CLEVEL (650 Hz, 9.5 dB),
         * BS2B_DEFAULT_CLEVEL (700 Hz, 4.5 dB) and BS2B_HIGH_CLEVEL (700 Hz, 3 dB). A lower feed level crosses more.
         */
        private val LEVEL_PRESETS = arrayOf(650.0 to 9.5, 700.0 to 4.5, 700.0 to 3.0)
    }
}
