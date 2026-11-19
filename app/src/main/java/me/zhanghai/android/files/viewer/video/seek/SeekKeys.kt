/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.seek

import android.view.KeyEvent

/** Keyboard and remote shortcuts of the video player as pure logic (unit-tested). */
internal object SeekKeys {
    enum class Action {
        PLAY_PAUSE, SEEK_BACK, SEEK_AHEAD, VOLUME_UP, VOLUME_DOWN, TOGGLE_CONTROLS, MUTE, FRAME_BACK, FRAME_AHEAD
    }

    /**
     * The action for [keyCode], or null. Keys with ctrl/alt/meta ([hasModifiers]) are left to the system; while a
     * button has focus ([focusOnControl]) the DPAD keys and SPACE stay with focus navigation and button clicks.
     */
    fun action(keyCode: Int, hasModifiers: Boolean, focusOnControl: Boolean): Action? {
        if (hasModifiers) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> Action.PLAY_PAUSE.takeUnless { focusOnControl }
            KeyEvent.KEYCODE_DPAD_LEFT -> Action.SEEK_BACK.takeUnless { focusOnControl }
            KeyEvent.KEYCODE_DPAD_RIGHT -> Action.SEEK_AHEAD.takeUnless { focusOnControl }
            KeyEvent.KEYCODE_DPAD_UP -> Action.VOLUME_UP.takeUnless { focusOnControl }
            KeyEvent.KEYCODE_DPAD_DOWN -> Action.VOLUME_DOWN.takeUnless { focusOnControl }
            KeyEvent.KEYCODE_K -> Action.PLAY_PAUSE
            KeyEvent.KEYCODE_J -> Action.SEEK_BACK
            KeyEvent.KEYCODE_L -> Action.SEEK_AHEAD
            KeyEvent.KEYCODE_F -> Action.TOGGLE_CONTROLS
            KeyEvent.KEYCODE_M -> Action.MUTE
            KeyEvent.KEYCODE_COMMA -> Action.FRAME_BACK
            KeyEvent.KEYCODE_PERIOD -> Action.FRAME_AHEAD
            else -> null
        }
    }
}
