/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.viewer.audio.MusicLauncherActivity
import me.zhanghai.android.files.viewer.video.VideoLauncherActivity

/**
 * The entries a long press on Ordo's icon shows. Published at run time, not from res/xml: static shortcuts need a
 * fixed package name (the dev build has its own) and belong to one launcher component, while Ordo's icon is one of
 * eight colour aliases. Here they follow the alias that is on, and the package is always the running one.
 */
object LauncherShortcuts {
    fun publish(context: Context) {
        val color = LauncherIcons.color(context, IconAliases.HOME) ?: return
        val home = ComponentName(context, IconAliases.name(IconAliases.HOME, color))
        fun shortcut(id: String, label: Int, icon: Int, intent: Intent) =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(context.getString(label))
                .setIcon(IconCompat.createWithResource(context, icon))
                .setIntent(intent)
                .setActivity(home)
                .build()
        val shortcuts = listOf(
            shortcut(
                "music", R.string.launcher_music_label, R.mipmap.ic_launcher_music,
                Intent(Intent.ACTION_MAIN).setClass(context, MusicLauncherActivity::class.java)
            ),
            shortcut(
                "video", R.string.launcher_video_label, R.mipmap.ic_launcher_video,
                Intent(Intent.ACTION_MAIN).setClass(context, VideoLauncherActivity::class.java)
            ),
            shortcut(
                "downloads", R.string.shortcut_downloads_title, R.mipmap.downloads_shortcut_icon,
                Intent("me.zhanghai.android.files.intent.action.VIEW_DOWNLOADS")
                    .setClass(context, FileListActivity::class.java)
            ),
            shortcut(
                "ftp_server", R.string.shortcut_ftp_server_title, R.mipmap.ftp_server_shortcut_icon,
                Intent(Intent.ACTION_MAIN).setClass(context, FtpServerActivity::class.java)
            )
        )
        try {
            ShortcutManagerCompat.setDynamicShortcuts(
                context, shortcuts.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
            )
        } catch (e: Exception) {
            // Rate limit or a launcher that refuses: the long press then simply shows no entries.
            e.printStackTrace()
        }
    }
}
