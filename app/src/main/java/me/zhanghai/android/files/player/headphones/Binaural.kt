/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.headphones

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Virtual loudspeakers in headphones: every speaker channel of a 5.1/7.1 track convolved with the head-related impulse
 * responses (HRIRs) of its direction, both ears summed. HRIRs: SADIE II database, subject D1 (Neumann KU100 dummy
 * head), 48 kHz, 256 taps, diffuse-field equalised; University of York 2018, Apache License 2.0; Armstrong, Thresh,
 * Murphy, Kearney, Applied Sciences 8(11), 2018, doi:10.3390/app8112029. Only the ten horizontal directions Ordo uses
 * are shipped (20 KB, tools/headphones/extract_hrir.py). A dummy head, not the listener's own ears: front/back
 * confusions are common with any foreign HRTF (Wenzel et al., JASA 1993, doi:10.1121/1.407089).
 *
 * Speaker angles (counter-clockwise, as in SOFA): front ±30°, centre 0°, surrounds ±110° (ITU-R BS.775), 7.1 back
 * ±150° and sides ±90°, 6.1 back centre 180°. LFE is left out, as in Ordo's BS.775 downmix.
 */
object Binaural {
    const val TAPS = 256
    const val HRIR_RATE = 48_000
    /** Partition and hop of the convolution: 128 frames, 2.7 ms at 48 kHz, is also its whole latency. */
    const val BLOCK = 128

    /**
     * Level match with Ordo's stereo downmix (BS.775 with 1/(1 + 0.7071)): measured with decorrelated pink noise on
     * every channel, K-weighted: 0.480 for 5.1, 0.471 for 7.1 (tools/headphones, numpy), checked to ±1 LU by
     * HeadphonePackTest.binauralLevelMatchesTheDownmix.
     */
    const val GAIN = 0.475f

    private val AZIMUTHS = intArrayOf(0, 30, 90, 110, 150, 180, 210, 250, 270, 330)
    private const val RESOURCE = "/me/zhanghai/android/files/player/headphones/hrir_sadie2_d1_48k.bin"

    /** Azimuth of each channel in Android's channel order, −1 for LFE; null for layouts not rendered. */
    fun azimuths(channels: Int): IntArray? = when (channels) {
        3 -> intArrayOf(30, 330, 0) // FL FR FC
        4 -> intArrayOf(30, 330, 110, 250) // FL FR BL BR
        5 -> intArrayOf(30, 330, 0, 110, 250) // FL FR FC BL BR
        6 -> intArrayOf(30, 330, 0, -1, 110, 250) // FL FR FC LFE BL BR
        7 -> intArrayOf(30, 330, 0, -1, 110, 250, 180) // + BC
        8 -> intArrayOf(30, 330, 0, -1, 150, 210, 90, 270) // FL FR FC LFE BL BR SL SR
        else -> null
    }

    /** The 48 kHz pairs, [azimuth index][ear][tap]. */
    private val hrirs: Array<Array<FloatArray>> by lazy {
        val bytes = Binaural::class.java.getResourceAsStream(RESOURCE)!!.use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        check(floats.remaining() == AZIMUTHS.size * 2 * TAPS)
        Array(AZIMUTHS.size) { Array(2) { FloatArray(TAPS).also { floats.get(it) } } }
    }

    /** One pair per channel at [sampleRate] (LFE: silence), ready for [PartitionedConvolver]. */
    fun responses(channels: Int, sampleRate: Int): Array<Array<FloatArray>>? {
        val azimuths = azimuths(channels) ?: return null
        return Array(channels) { channel ->
            val azimuth = azimuths[channel]
            if (azimuth < 0) arrayOf(FloatArray(1), FloatArray(1))
            else hrirs[AZIMUTHS.indexOf(azimuth)].let { pair ->
                Array(2) { ear -> resample(pair[ear], HRIR_RATE, sampleRate) }
            }
        }
    }

    /**
     * Band-limited resampling of an impulse response (Hann-windowed sinc, 32 zero crossings), scaled by the rate ratio
     * so the frequency response keeps its level. Done once per format, never on the audio thread's hot path.
     */
    internal fun resample(ir: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return ir.copyOf()
        val ratio = from.toDouble() / to
        val cutoff = minOf(1.0, 1 / ratio)
        val length = ((ir.size + 2 * HALF_WIDTH) / ratio).toInt()
        val out = FloatArray(length)
        for (n in out.indices) {
            // Shifted by HALF_WIDTH input samples, so the sinc's leading half is not cut off.
            val t = n * ratio - HALF_WIDTH
            val first = floor(t - HALF_WIDTH / cutoff).toInt().coerceAtLeast(0)
            val last = minOf(ir.size - 1, (t + HALF_WIDTH / cutoff).toInt())
            var sum = 0.0
            for (k in first..last) {
                val x = (t - k) * cutoff
                if (abs(x) >= HALF_WIDTH) continue
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val window = 0.5 + 0.5 * cos(PI * x / HALF_WIDTH)
                sum += ir[k] * sinc * window * cutoff
            }
            out[n] = (sum * ratio).toFloat()
        }
        return out
    }

    private const val HALF_WIDTH = 32
}
