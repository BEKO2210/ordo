/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.video

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.google.android.material.materialswitch.MaterialSwitch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.viewer.audio.KlangSheet

/**
 * ⋮ in Ordo Video: the player's further actions as Klang cards, grouped (picture and sound, playback, file), side by
 * side on wide sheets. The entries come from [menu] (res/menu/video_player_more.xml plus what VideoSeekPro adds), so
 * what is hidden there (no chapters, no picture-in-picture, a Tresor video) is not offered here either. A tap closes
 * the sheet and runs the entry; a checkable entry shows its state as a switch.
 */
internal object VideoMoreSheet {
    private class Group(val id: Int, @DrawableRes val icon: Int, @StringRes val title: Int)

    private val GROUPS = listOf(
        Group(R.id.groupVideoPictureSound, R.drawable.audio_equalizer_icon_24dp, R.string.video_more_picture_sound),
        Group(R.id.groupVideoPlayback, R.drawable.audio_playback_icon_24dp, R.string.video_more_playback),
        Group(R.id.groupVideoFile, R.drawable.information_icon_white_24dp, R.string.video_more_file)
    )

    /**
     * [items] in [groups] order, each group keeping the items' order; an item of an unknown group goes to the last one,
     * so no entry is ever lost. Empty groups are left out (as index → items).
     */
    internal fun <T> grouped(items: List<T>, groups: List<Int>, groupOf: (T) -> Int): List<Pair<Int, List<T>>> =
        items.groupBy { groups.indexOf(groupOf(it)).takeIf { index -> index >= 0 } ?: groups.lastIndex }
            .toSortedMap().map { (index, entries) -> index to entries }

    fun show(context: Context, menu: Menu) {
        val sheet = KlangSheet(context, R.drawable.more_vertical_icon_white_24dp, R.string.video_more_title)
        val items = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }
        val grid = sheet.grid()
        for ((index, entries) in grouped(items, GROUPS.map { it.id }) { it.groupId }) {
            val group = GROUPS[index]
            val card = sheet.card(group.icon, context.getString(group.title), parent = grid)
            for (item in entries) entry(sheet, card, menu, item)
        }
        sheet.show()
    }

    private fun entry(sheet: KlangSheet, card: android.view.ViewGroup, menu: Menu, item: MenuItem) {
        val context = sheet.context
        val switch = if (item.isCheckable) MaterialSwitch(context).apply {
            isChecked = item.isChecked
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else null
        val row = sheet.row(card, item.icon, item.title ?: "", null, switch) {
            sheet.dialog.dismiss()
            menu.performIdentifierAction(item.itemId, 0)
        }
        if (switch != null) {
            ViewCompat.setStateDescription(
                row, context.getString(if (item.isChecked) R.string.audio_sound_on else R.string.audio_sound_off)
            )
        }
    }
}
