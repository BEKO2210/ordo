/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import java.io.File
import me.zhanghai.android.files.viewer.audio.AudioPlayerColorWash
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controls' veil (drawable/video_controls_scrim.xml) over a white video frame, where the text rows sit. */
class VideoScrimTest {
    private val xml = File("src/main/res/drawable/video_controls_scrim.xml").readText()

    @Test
    fun titleAndTimesReadOnAWhiteFrame() {
        // Ordo's and a wallpaper (Material You) dark onSurface, and white.
        val texts = intArrayOf(0xffe6edf5.toInt(), 0xffe6e1e5.toInt(), 0xffffffff.toInt())
        // Title: status bar (up to 40 dp) + half the title bar; time row: gesture/nav inset (up to 48 dp) + one
        // 48 dp button row + half the time row, as the Tab and phones lay it out.
        for ((gravity, rowDp) in listOf("top" to 70.0, "bottom" to 120.0, "bottom" to 140.0)) {
            val background = over(alphaAt(gravity, rowDp), over(flat(), 0xffffffff.toInt()))
            for (text in texts) {
                val ratio = AudioPlayerColorWash.contrastRatio(text, background)
                assertTrue("$gravity at $rowDp dp: $ratio", ratio >= 4.5)
            }
        }
    }

    private fun flat() = hex(Regex("""<color android:color="#(\w{8})"""").find(xml)!!.groupValues[1])

    /** The gradient layer's black at [dp] from its edge: start at the edge, centre halfway, end at its height. */
    private fun alphaAt(gravity: String, dp: Double): Int {
        val item = Regex("""gravity="$gravity" android:height="(\d+)dp"[\s\S]*?startColor="#(\w{8})" """ +
            """android:centerColor="#(\w{8})"\s+android:endColor="#(\w{8})"""").find(xml)!!.groupValues
        val height = item[1].toDouble()
        val (start, centre, end) = item.drop(2).map { (hex(it) ushr 24).toDouble() }
        val t = (dp / height).coerceIn(0.0, 1.0)
        val alpha = if (t < 0.5) start + (centre - start) * t * 2 else centre + (end - centre) * (t - 0.5) * 2
        return alpha.toInt() shl 24
    }

    private fun hex(value: String) = value.toLong(16).toInt()

    private fun over(top: Int, bottom: Int): Int {
        val alpha = (top ushr 24) / 255.0
        fun channel(shift: Int) =
            ((top shr shift and 0xff) * alpha + (bottom shr shift and 0xff) * (1 - alpha)).toInt() shl shift
        return -0x1000000 or channel(16) or channel(8) or channel(0)
    }
}
