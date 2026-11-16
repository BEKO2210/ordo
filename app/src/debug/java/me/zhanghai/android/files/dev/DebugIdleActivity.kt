/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.dev

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random
import me.zhanghai.android.files.ai.AuroraView

/**
 * Test phones only (debug builds, started by adb): keeps the screen on with nothing standing still, so it cannot burn
 * in while a phone waits for the next test. The aurora drifts by itself; the clock jumps to a new place every 20 s.
 * A tap or any test that starts an activity ends it.
 */
class DebugIdleActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.15f }
        clock = TextView(this).apply {
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 28f
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(AuroraView(this@DebugIdleActivity), FrameLayout.LayoutParams(-1, -1))
            addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            setOnClickListener { finish() }
        }
        setContentView(root)
    }

    private val move = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            val parent = clock.parent as FrameLayout
            clock.translationX = Random.nextInt(0, maxOf(1, parent.width - clock.width)).toFloat()
            clock.translationY = Random.nextInt(0, maxOf(1, parent.height - clock.height)).toFloat()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(move)
    }

    override fun onPause() {
        handler.removeCallbacks(move)
        super.onPause()
    }
}
