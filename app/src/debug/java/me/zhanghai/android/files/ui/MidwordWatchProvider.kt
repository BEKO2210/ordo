/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.util.WeakHashMap

/**
 * Debug builds only: after every layout of an activity or a dialog (debounced), every visible TextView's line breaks
 * are checked with [WordBreaks]; a break inside a word is logged once as
 * `OrdoMidword: MIDWORD screen=<name> id=<view id> text="<line>|<next line>"`. e2e/no-midword-breaks.sh walks the main
 * screens and fails on any such line. A ContentProvider starts it because providers are created with the process.
 */
class MidwordWatchProvider : ContentProvider() {
    private val watched = WeakHashMap<View, Unit>()
    private val reported = HashSet<String>()
    private val rect = Rect()

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            (f as? DialogFragment)?.dialog?.window?.decorView?.let { watch(it, f.javaClass.simpleName) }
        }
    }

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as? FragmentActivity)?.supportFragmentManager
                    ?.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
            }

            override fun onActivityResumed(activity: Activity) =
                watch(activity.window.decorView, activity.javaClass.simpleName)

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        return true
    }

    private fun watch(root: View, screen: String) {
        if (watched.containsKey(root)) return
        watched[root] = Unit
        val scan = Runnable { scan(root, screen) }
        root.viewTreeObserver.addOnGlobalLayoutListener {
            root.removeCallbacks(scan)
            root.postDelayed(scan, SCAN_DELAY_MS)
        }
        root.postDelayed(scan, SCAN_DELAY_MS)
    }

    private fun scan(view: View, screen: String) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) scan(view.getChildAt(i), screen)
            return
        }
        if (view !is TextView || !view.isShown || !view.getGlobalVisibleRect(rect)) return
        val layout = view.layout ?: return
        if (layout.lineCount < 2) return
        val text = layout.text
        val ends = (0 until layout.lineCount - 1).filter { layout.getEllipsisCount(it) == 0 }
            .map { layout.getLineEnd(it) }.toIntArray()
        for (end in WordBreaks.midWord(text, ends)) {
            val line = layout.getLineForOffset(end - 1)
            val shown = ("${text.subSequence(layout.getLineStart(line), end)}|" +
                "${text.subSequence(end, layout.getLineEnd(line + 1))}").replace('\n', ' ')
            val id = if (view.id != View.NO_ID) {
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("?")
            } else "-"
            if (reported.add("$screen|$id|$text")) {
                Log.w(TAG, "MIDWORD screen=$screen id=$id width=${view.width} text=\"$shown\"")
            }
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ) = null

    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val TAG = "OrdoMidword"
        private const val SCAN_DELAY_MS = 400L
    }
}
