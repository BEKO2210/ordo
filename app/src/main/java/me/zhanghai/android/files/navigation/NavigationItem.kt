/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import java8.nio.file.Path
import me.zhanghai.android.files.compat.getDrawableCompat

abstract class NavigationItem {
    abstract val id: Long

    open fun getIcon(context: Context): Drawable? = context.getDrawableCompat(iconRes!!)

    /** False for an icon with its own colours (a built-in app's launcher icon), which the menu's tint would flatten. */
    open val tintsIcon: Boolean
        get() = true

    @get:DrawableRes
    protected abstract val iconRes: Int?

    abstract fun getTitle(context: Context): String

    /** Runs on the main thread, so it must not touch the disk; an item whose subtitle does has to cache it. */
    open fun getSubtitle(context: Context): String? = null

    /** The current subtitle, read off the main thread; the adapter shows [getSubtitle] first and then this. */
    open fun loadSubtitle(context: Context): String? = getSubtitle(context)

    open fun isChecked(listener: Listener): Boolean = false

    abstract fun onClick(listener: Listener)

    open fun onLongClick(listener: Listener): Boolean = false

    interface Listener {
        val currentPath: Path?
        fun navigateTo(path: Path)
        fun navigateToRoot(path: Path)
        fun launchIntent(intent: Intent)
        fun closeNavigationDrawer()
    }
}
