/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.sound

import android.content.Context
import android.net.TrafficStats
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * The DeepFilterNet3 model for "Dialog freistellen plus" (MIT OR Apache-2.0, code and weights, DeepFilterNet README
 * "License"). Never shipped in the APK and never fetched on its own: only a tap on "Load" downloads it, from the
 * pinned commit, and only a file with the expected SHA-256 is kept. Afterwards Ordo stays offline.
 */
internal object DialogModel {
    const val URL = "https://github.com/Rikorose/DeepFilterNet/raw/7b14b895fe7fc42cab72b4a4c1e49c982bd6404b/" +
        "models/DeepFilterNet3_onnx.tar.gz"
    const val SIZE = 7_983_136L
    const val SHA256 = "c94d91f70911001c946e0fabb4aa9adc37045f45a03b56008cb0c8244cb63616"
    private const val FILE_NAME = "deepfilternet3_onnx.tar.gz"
    private const val PREFS = "video_dialog_model"
    private const val KEY_RTF = "rtf"
    private const val TRAFFIC_TAG = 0x0DF3

    /**
     * Above this real-time factor (compute time per hop / hop length) the option is not offered: the research
     * note's limit for budget chips (LOKALE-QUALITAETS-KI.md 5, item 3c), leaving room for decoding and the rest.
     */
    const val MAX_RTF = 0.7f

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long) : State
        data object Failed : State
        data object Ready : State
    }

    @Volatile
    var state: State = State.Missing
        private set

    private val executor = Executors.newSingleThreadExecutor()

    fun file(context: Context): File = File(File(context.filesDir, "models"), FILE_NAME)

    /** Offered at all: the library is in this APK (64-bit ABIs) and the device was not measured too slow. */
    fun offered(context: Context): Boolean = DeepFilterNative.available && !tooSlow(measuredRtf(context))

    internal fun tooSlow(rtf: Float): Boolean = !rtf.isNaN() && rtf > MAX_RTF

    fun measuredRtf(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_RTF, Float.NaN)

    fun saveRtf(context: Context, rtf: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_RTF, rtf).apply()
    }

    /** Current state; a finished file counts as ready (it was only renamed into place after its hash check). */
    fun refresh(context: Context): State {
        val current = state
        if (current is State.Downloading) return current
        if (file(context).length() == SIZE) state = State.Ready
        else if (current == State.Ready) state = State.Missing
        return state
    }

    /** Starts the download the user asked for; [onDone] runs on the download thread. */
    fun download(context: Context, onDone: () -> Unit) {
        if (state is State.Downloading || refresh(context) == State.Ready) return
        state = State.Downloading(0)
        val app = context.applicationContext
        executor.execute {
            // Tagged, so Android's per-app network statistics (and StrictMode's untagged-socket check) know it.
            TrafficStats.setThreadStatsTag(TRAFFIC_TAG)
            try {
                state = if (fetch(app)) State.Ready else State.Failed
            } finally {
                TrafficStats.clearThreadStatsTag()
            }
            onDone()
        }
    }

    private fun fetch(context: Context): Boolean {
        val target = file(context)
        val part = File(target.parentFile, "$FILE_NAME.part")
        return try {
            target.parentFile!!.mkdirs()
            val connection = URL(URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            // A server sending more than the pinned file is not sending that file.
                            if (total > SIZE) return false
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            state = State.Downloading(total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (part.length() != SIZE || hex(digest.digest()) != SHA256) return false
            part.renameTo(target)
        } catch (_: Exception) {
            false
        } finally {
            part.delete()
        }
    }

    /** Hash check of the stored file before each load (off the main thread); a damaged file is deleted. */
    fun verify(context: Context): Boolean {
        val file = file(context)
        val ok = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            hex(digest.digest()) == SHA256
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            file.delete()
            state = State.Missing
        }
        return ok
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Real-time factor of [filter] on this device: [HOPS] hops of a voice-like test signal (harmonics with noise, so
     * the whole network runs rather than its shortcut for silence), compute time over audio time. A failed hop is not a
     * measurement: one more try, then NaN, which is never saved, so the next playback measures again.
     */
    fun measureRtf(filter: DeepFilter): Float = measureRtf(filter.hop, filter.sampleRate, filter::process)

    internal fun measureRtf(hop: Int, sampleRate: Int, process: (FloatArray, FloatArray) -> Boolean): Float =
        measureOnce(hop, sampleRate, process).takeUnless { it.isNaN() } ?: measureOnce(hop, sampleRate, process)

    private fun measureOnce(hop: Int, sampleRate: Int, process: (FloatArray, FloatArray) -> Boolean): Float {
        val input = FloatArray(hop)
        val output = FloatArray(hop)
        val random = java.util.Random(1)
        var sample = 0
        var nanos = 0L
        repeat(HOPS) {
            for (i in input.indices) {
                val t = (sample++).toDouble() / sampleRate
                var voice = 0.0
                for (k in 1..8) voice += sin(2 * PI * 140 * k * t) / k
                input[i] = (0.1 * voice + 0.02 * random.nextGaussian()).toFloat()
            }
            val start = System.nanoTime()
            if (!process(input, output)) return Float.NaN
            nanos += System.nanoTime() - start
        }
        return (nanos / 1e9 / (HOPS.toDouble() * hop / sampleRate)).toFloat()
    }

    private const val HOPS = 300
}
