/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Structure and tuning follow Freeverb by Jezar at Dreampoint (2000), released into the public domain:
 * "You may do with the code whatever you wish." https://github.com/sinshu/freeverb/blob/main/readme.txt
 * Background: https://ccrma.stanford.edu/~jos/pasp/Freeverb.html
 */

package me.zhanghai.android.files.player

import kotlin.math.abs

/**
 * Freeverb: eight parallel low-pass-feedback comb filters and four series all-passes per channel, the right channel
 * with slightly longer delays (stereo spread). Delay lengths are the original's at 44.1 kHz, scaled to the sample rate.
 */
class ReverbAudioProcessor : StereoEffectProcessor() {
    @Volatile
    var preset: ReverbPreset = ReverbPreset.STUDIO

    /** Wet share, 0..100 %. */
    @Volatile
    var mixPercent: Int = 25

    private var combsLeft = emptyArray<Comb>()
    private var combsRight = emptyArray<Comb>()
    private var allpassesLeft = emptyArray<Allpass>()
    private var allpassesRight = emptyArray<Allpass>()
    private var allocatedRate = -1
    private var feedback = 0f
    private var damp1 = 0f
    private var damp2 = 1f
    private var wet = 0f
    private var dry = 1f
    private var targetFeedback = 0f
    private var targetDamp = 0f
    private var targetWet = 0f
    private var targetDry = 1f
    private var snap = true

    init {
        onSampleRate(DEFAULT_SAMPLE_RATE)
    }

    override fun onSampleRate(sampleRate: Int) {
        if (sampleRate == allocatedRate) return
        allocatedRate = sampleRate
        val scale = sampleRate / 44_100f
        fun length(samples: Int) = maxOf(1, (samples * scale).toInt())
        combsLeft = Array(COMB_TUNING.size) { Comb(length(COMB_TUNING[it])) }
        combsRight = Array(COMB_TUNING.size) { Comb(length(COMB_TUNING[it] + STEREO_SPREAD)) }
        allpassesLeft = Array(ALLPASS_TUNING.size) { Allpass(length(ALLPASS_TUNING[it])) }
        allpassesRight = Array(ALLPASS_TUNING.size) { Allpass(length(ALLPASS_TUNING[it] + STEREO_SPREAD)) }
    }

    override fun prepare() {
        val current = preset
        targetFeedback = current.roomSize * SCALE_ROOM + OFFSET_ROOM
        targetDamp = current.damping * SCALE_DAMP
        val mix = mixPercent.coerceIn(0, 100) / 100f
        // Freeverb's default wet level is 1 with its 0.015 input gain; the dry signal steps back as the room grows,
        // so switching it on does not simply make everything louder.
        targetWet = mix
        targetDry = 1f - mix * 0.5f
        if (snap) {
            feedback = targetFeedback
            damp1 = targetDamp
            wet = targetWet
            dry = targetDry
            snap = false
        }
    }

    override fun clear() {
        snap = true
        combsLeft.forEach { it.clear() }
        combsRight.forEach { it.clear() }
        allpassesLeft.forEach { it.clear() }
        allpassesRight.forEach { it.clear() }
    }

    override fun processFrame(left: Float, right: Float) {
        // Another room or share glides in; a jump of the dry level or the feedback would crack.
        feedback += (targetFeedback - feedback) * glide
        damp1 += (targetDamp - damp1) * glide
        damp2 = 1f - damp1
        wet += (targetWet - wet) * glide
        dry += (targetDry - dry) * glide
        val input = (left + right) * FIXED_GAIN
        var reverbLeft = 0f
        var reverbRight = 0f
        for (i in combsLeft.indices) {
            reverbLeft += combsLeft[i].process(input, feedback, damp1, damp2)
            reverbRight += combsRight[i].process(input, feedback, damp1, damp2)
        }
        for (i in allpassesLeft.indices) {
            reverbLeft = allpassesLeft[i].process(reverbLeft)
            reverbRight = allpassesRight[i].process(reverbRight)
        }
        outLeft = reverbLeft * wet + left * dry
        outRight = reverbRight * wet + right * dry
    }

    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f

        fun process(input: Float, feedback: Float, damp1: Float, damp2: Float): Float {
            val output = buffer[index]
            store = output * damp2 + store * damp1
            // The original's undenormalise(): tiny values would slow the decay tail down on some CPUs.
            if (abs(store) < DENORMAL) store = 0f
            buffer[index] = input + store * feedback
            if (++index >= buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
            store = 0f
        }
    }

    private class Allpass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun process(input: Float): Float {
            val buffered = buffer[index]
            buffer[index] = input + buffered * ALLPASS_FEEDBACK
            if (++index >= buffer.size) index = 0
            return buffered - input
        }

        fun clear() {
            buffer.fill(0f)
            index = 0
        }
    }

    companion object {
        // Freeverb tuning.h
        private val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)
        private const val STEREO_SPREAD = 23
        private const val FIXED_GAIN = 0.015f
        private const val SCALE_DAMP = 0.4f
        private const val SCALE_ROOM = 0.28f
        private const val OFFSET_ROOM = 0.7f
        private const val ALLPASS_FEEDBACK = 0.5f
        private const val DENORMAL = 1e-20f
    }
}
