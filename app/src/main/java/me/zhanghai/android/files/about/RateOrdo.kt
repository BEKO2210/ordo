/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.createViewAppInMarket
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser

/**
 * Rating, sharing and feedback without any Play library (Ordo stays free of proprietary code): a store link, the
 * system share sheet and the public issue tracker. Plus one gentle rating hint, shown at most once ever.
 */
object RateOrdo {
    /** The released app's id; the dev build has its own id that is not in the store. */
    private const val PACKAGE = "com.beko2210.ordo"
    const val WEB_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"
    private const val ISSUES_URL = "https://github.com/BEKO2210/ordo/issues/new"

    const val MIN_DAYS = 7
    const val MIN_SUCCESSES = 20
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private const val PREFS = "rate_hint"
    private const val KEY_FIRST = "first"
    private const val KEY_SUCCESSES = "successes"
    private const val KEY_SHOWN = "shown"

    /** Due once: a week after the first finished file operation and at the 20th one. */
    fun isDue(now: Long, firstUse: Long, successes: Int, shown: Boolean): Boolean =
        !shown && firstUse > 0 && successes >= MIN_SUCCESSES && now - firstUse >= MIN_DAYS * DAY_MS

    /** Called after a file operation finished without an error; only counts, never shows anything. */
    fun recordSuccess(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) return
        prefs.edit().apply {
            if (!prefs.contains(KEY_FIRST)) putLong(KEY_FIRST, System.currentTimeMillis())
            putInt(KEY_SUCCESSES, prefs.getInt(KEY_SUCCESSES, 0) + 1)
        }.apply()
    }

    /** True exactly once, when the hint is due; marks it as shown so it never comes back. */
    fun takeIfDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = isDue(
            System.currentTimeMillis(), prefs.getLong(KEY_FIRST, 0), prefs.getInt(KEY_SUCCESSES, 0),
            prefs.getBoolean(KEY_SHOWN, false)
        )
        if (due) prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        return due
    }

    /** The store app if one is installed, otherwise the store page in the browser. */
    fun openStore(context: Context) {
        try {
            context.startActivity(Intent::class.createViewAppInMarket(PACKAGE))
        } catch (e: ActivityNotFoundException) {
            context.startActivitySafe(Uri.parse(WEB_URL).createViewIntent())
        }
    }

    /** The share sheet with a short text the receiving app lets the user edit before sending. */
    fun share(context: Context) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.settings_share_text, WEB_URL))
        context.startActivitySafe(intent.withChooser(context.getString(R.string.settings_share_title)))
    }

    /** A new public issue, prefilled with version and device type only: no names, files or accounts. */
    fun reportIssue(context: Context) {
        val body = context.getString(
            R.string.settings_feedback_body, BuildConfig.VERSION_NAME, "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE
        )
        val uri = Uri.parse(ISSUES_URL).buildUpon().appendQueryParameter("body", body).build()
        context.startActivitySafe(uri.createViewIntent())
    }
}
