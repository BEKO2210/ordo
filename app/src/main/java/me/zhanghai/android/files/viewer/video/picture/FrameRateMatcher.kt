/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.picture

import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * "Match frame rate: always": asks the screen for the film's rate even when switching blinks (CHANGE_FRAME_RATE_ALWAYS,
 * API 31+). Media3 does the seamless kind itself; this only acts when [rate] > 0, and clears exactly what it set: when
 * the player leaves the screen, goes into picture-in-picture, or the rate goes back to 0. The surface can be recreated
 * (screen off and on), so it is applied again whenever the holder reports a surface.
 */
internal class FrameRateMatcher(private val surfaceView: SurfaceView) : SurfaceHolder.Callback {
    private var rate = 0f
    private var active = true
    private var applied: Surface? = null

    init {
        surfaceView.holder.addCallback(this)
    }

    /** The film's rate (0: none of Ordo's own; Media3's strategy rules). */
    fun setRate(rate: Float) {
        if (this.rate == rate) return
        this.rate = rate
        apply()
    }

    /** False while in picture-in-picture or off screen: the display goes back to its own rate. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        apply()
    }

    fun release() {
        surfaceView.holder.removeCallback(this)
        clear()
    }

    private fun apply() {
        val surface = surfaceView.holder.surface
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || rate <= 0f || !active || surface == null ||
            !surface.isValid) {
            clear()
            return
        }
        try {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            applied = surface
            if (Log.isLoggable(PICTURE_LOG_TAG, Log.DEBUG)) Log.d(PICTURE_LOG_TAG, "setFrameRate $rate ALWAYS")
        } catch (e: IllegalStateException) {
            // The surface went away between the check and the call.
            Log.w(PICTURE_LOG_TAG, "setFrameRate failed", e)
        }
    }

    private fun clear() {
        val surface = applied ?: return
        applied = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !surface.isValid) return
        try {
            surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
        } catch (e: IllegalStateException) {
            Log.w(PICTURE_LOG_TAG, "clearing the frame rate failed", e)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = apply()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = apply()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        applied = null
    }
}
