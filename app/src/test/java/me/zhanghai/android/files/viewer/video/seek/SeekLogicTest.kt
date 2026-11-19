/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video.seek

import android.view.KeyEvent
import me.zhanghai.android.files.viewer.video.seek.SeekKeys.Action
import me.zhanghai.android.files.viewer.video.seek.SeekLogic.AbRepeat
import me.zhanghai.android.files.viewer.video.seek.SeekLogic.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekLogicTest {
    private val twoHours = 7_200_000L

    @Test
    fun thumbnailBucketsAreCappedAndFloored() {
        assertEquals(10_000L, SeekLogic.bucketSizeMs(twoHours))
        assertEquals(1_000L, SeekLogic.bucketSizeMs(60_000L))
        assertEquals(3_605_000L, SeekLogic.thumbnailTimeMs(3_600_000L, twoHours))
        assertEquals(12_500L, SeekLogic.thumbnailTimeMs(12_345L, 60_000L))
    }

    @Test
    fun positionsInOneBucketShareOneThumbnail() {
        assertEquals(
            SeekLogic.thumbnailTimeMs(3_601_000L, twoHours),
            SeekLogic.thumbnailTimeMs(3_609_999L, twoHours)
        )
    }

    @Test
    fun thumbnailStaysInsideTheFilm() {
        assertEquals(59_500L, SeekLogic.thumbnailTimeMs(60_000L, 60_000L))
        assertEquals(1_499L, SeekLogic.thumbnailTimeMs(1_400L, 1_500L))
        assertEquals(500L, SeekLogic.thumbnailTimeMs(-5L, 60_000L))
    }

    @Test
    fun unknownDurationStillWorks() {
        assertEquals(25_500L, SeekLogic.thumbnailTimeMs(25_000L, 0L))
        assertEquals(500L, SeekLogic.thumbnailTimeMs(-5L, 0L))
    }

    @Test
    fun chaptersAreSortedDeduplicatedAndNeedTwo() {
        val result = SeekLogic.normalizeChapters(
            listOf(Chapter(5_000, "b"), Chapter(0, "a"), Chapter(5_000, "dup"), Chapter(-1, "bad"))
        )
        assertEquals(listOf(0L, 5_000L), result.map { it.startMs })
        assertEquals("b", result[1].title)
        assertTrue(SeekLogic.normalizeChapters(listOf(Chapter(0, "a"))).isEmpty())
        assertTrue(SeekLogic.normalizeChapters(listOf(Chapter(0, "a"), Chapter(0, "b"))).isEmpty())
        assertTrue(SeekLogic.normalizeChapters(emptyList()).isEmpty())
    }

    @Test
    fun chapterIndexFollowsThePosition() {
        val chapters = listOf(Chapter(1_000, null), Chapter(5_000, null), Chapter(9_000, null))
        assertEquals(-1, SeekLogic.chapterIndexAt(chapters, 999))
        assertEquals(0, SeekLogic.chapterIndexAt(chapters, 1_000))
        assertEquals(0, SeekLogic.chapterIndexAt(chapters, 4_999))
        assertEquals(1, SeekLogic.chapterIndexAt(chapters, 5_000))
        assertEquals(2, SeekLogic.chapterIndexAt(chapters, 100_000))
        assertEquals(-1, SeekLogic.chapterIndexAt(emptyList(), 10))
    }

    @Test
    fun frameStepAt30Fps() {
        assertEquals(66L, SeekLogic.frameStepTargetMs(33, 30f, true, 0))
        assertEquals(0L, SeekLogic.frameStepTargetMs(33, 30f, false, 0))
        assertEquals(33L, SeekLogic.frameStepTargetMs(0, 30f, true, 0))
    }

    @Test
    fun frameStepAt25Fps() {
        assertEquals(80L, SeekLogic.frameStepTargetMs(40, 25f, true, 0))
        assertEquals(0L, SeekLogic.frameStepTargetMs(40, 25f, false, 0))
        assertEquals(120L, SeekLogic.frameStepTargetMs(80, 25f, true, 0))
        assertEquals(40L, SeekLogic.frameStepTargetMs(80, 25f, false, 0))
    }

    @Test
    fun frameStepNeverGoesBelowZero() {
        assertEquals(0L, SeekLogic.frameStepTargetMs(0, 25f, false, 0))
    }

    @Test
    fun frameStepFallsBackAndStaysBelowDuration() {
        assertEquals(66L, SeekLogic.frameStepTargetMs(33, 0f, true, 0))
        assertEquals(66L, SeekLogic.frameStepTargetMs(33, Float.NaN, true, 0))
        assertEquals(999L, SeekLogic.frameStepTargetMs(990, 30f, true, 1_000))
    }

    @Test
    fun abRepeatSetsAThenB() {
        val a = AbRepeat().next(2_000)
        assertTrue(a.hasA)
        assertFalse(a.isLooping)
        assertEquals(2_000L, a.aMs)
        val ab = a.next(8_000)
        assertTrue(ab.isLooping)
        assertEquals(2_000L, ab.aMs)
        assertEquals(8_000L, ab.bMs)
    }

    @Test
    fun abRepeatSwapsWhenBIsBeforeA() {
        val ab = AbRepeat().next(8_000).next(2_000)
        assertEquals(2_000L, ab.aMs)
        assertEquals(8_000L, ab.bMs)
    }

    @Test
    fun abRepeatTooShortKeepsWaitingAndThirdPressClears() {
        val a = AbRepeat().next(2_000)
        assertEquals(a, a.next(2_499))
        assertEquals(a, a.next(1_501))
        val cleared = a.next(8_000).next(9_000)
        assertEquals(AbRepeat(), cleared)
        assertFalse(cleared.hasA)
    }

    @Test
    fun zoomIsClamped() {
        assertEquals(1f, SeekLogic.clampZoom(0.5f), 0f)
        assertEquals(5f, SeekLogic.clampZoom(9f), 0f)
        assertEquals(1f, SeekLogic.clampZoom(Float.NaN), 0f)
        assertEquals(2.5f, SeekLogic.clampZoom(2.5f), 0f)
    }

    @Test
    fun panIsClampedToTheZoomedEdges() {
        assertEquals(0f, SeekLogic.clampPan(300f, 1f, 1000), 0f)
        assertEquals(500f, SeekLogic.clampPan(900f, 2f, 1000), 0f)
        assertEquals(-500f, SeekLogic.clampPan(-900f, 2f, 1000), 0f)
        assertEquals(120f, SeekLogic.clampPan(120f, 2f, 1000), 0f)
    }

    @Test
    fun keysMapToActions() {
        fun a(code: Int) = SeekKeys.action(code, hasModifiers = false, focusOnControl = false)
        assertEquals(Action.PLAY_PAUSE, a(KeyEvent.KEYCODE_SPACE))
        assertEquals(Action.PLAY_PAUSE, a(KeyEvent.KEYCODE_K))
        assertEquals(Action.SEEK_BACK, a(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Action.SEEK_BACK, a(KeyEvent.KEYCODE_J))
        assertEquals(Action.SEEK_AHEAD, a(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(Action.SEEK_AHEAD, a(KeyEvent.KEYCODE_L))
        assertEquals(Action.VOLUME_UP, a(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Action.VOLUME_DOWN, a(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(Action.TOGGLE_CONTROLS, a(KeyEvent.KEYCODE_F))
        assertEquals(Action.MUTE, a(KeyEvent.KEYCODE_M))
        assertEquals(Action.FRAME_BACK, a(KeyEvent.KEYCODE_COMMA))
        assertEquals(Action.FRAME_AHEAD, a(KeyEvent.KEYCODE_PERIOD))
        assertNull(a(KeyEvent.KEYCODE_A))
    }

    @Test
    fun modifiersDisableEveryKey() {
        for (code in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_F)) {
            assertNull(SeekKeys.action(code, hasModifiers = true, focusOnControl = false))
        }
    }

    @Test
    fun focusOnAControlKeepsDpadAndSpaceForNavigation() {
        fun a(code: Int) = SeekKeys.action(code, hasModifiers = false, focusOnControl = true)
        assertNull(a(KeyEvent.KEYCODE_SPACE))
        assertNull(a(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(a(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(a(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(a(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(Action.PLAY_PAUSE, a(KeyEvent.KEYCODE_K))
        assertEquals(Action.SEEK_BACK, a(KeyEvent.KEYCODE_J))
        assertEquals(Action.FRAME_AHEAD, a(KeyEvent.KEYCODE_PERIOD))
        assertEquals(Action.MUTE, a(KeyEvent.KEYCODE_M))
    }
}
