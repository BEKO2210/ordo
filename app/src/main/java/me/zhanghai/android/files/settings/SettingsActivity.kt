/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.add
import androidx.lifecycle.Lifecycle
import androidx.fragment.app.commit
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.theme.custom.CustomThemeHelper.OnThemeChangedListener
import me.zhanghai.android.files.theme.night.NightModeHelper.OnNightModeChangedListener
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.getArgsOrNull
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.startActivitySafe

class SettingsActivity : AppActivity(), OnThemeChangedListener, OnNightModeChangedListener {
    private var isRestarting = false

    /** Null for the overview; otherwise one of [SECTIONS]. */
    val section: String?
        // An intent from an older version or another app may not unparcel as these Args: then show the overview.
        get() = runCatching { intent.extras?.getArgsOrNull<Args>()?.section }.getOrNull()?.takeIf { it in SECTIONS }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The state carried over by restart() stays in this process: this activity is exported, and a saved state
        // Bundle from another app's intent could make it restore fragments of that app's choosing.
        val savedInstanceState = savedInstanceState ?: restartState.also { restartState = null }
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { add<SettingsFragment>(android.R.id.content) }
        }
    }

    fun setApplicationLocalesPre33(locales: LocaleListCompat) {
        // HACK: Prevent this activity from being recreated due to locale change.
        delegate.onDestroy()
        AppCompatDelegate.setApplicationLocales(locales)
        restart()
    }

    override fun onThemeChanged(@StyleRes theme: Int) {
        // ActivityCompat.recreate() may call ActivityRecreator.recreate() without calling
        // Activity.recreate(), so we cannot simply override it. To work around this, we just
        // manually call restart().
        restartOrRecreate()
    }

    override fun onNightModeChangedFromHelper(nightMode: Int) {
        // ActivityCompat.recreate() may call ActivityRecreator.recreate() without calling
        // Activity.recreate(), so we cannot simply override it. To work around this, we just
        // manually call restart().
        restartOrRecreate()
    }

    // The overview stays in the back stack under a section: restarting it would start it on top of the section.
    private fun restartOrRecreate() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) restart() else recreate()
    }

    private fun restart() {
        restartState = Bundle().apply {
            onSaveInstanceState(this)
        }
        finish()
        val intent = SettingsActivity::class.createIntent().putArgs(Args(section))
        startActivitySafe(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        isRestarting = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return isRestarting || super.dispatchKeyEvent(event)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        return isRestarting || super.dispatchKeyShortcutEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return isRestarting || super.dispatchTouchEvent(event)
    }

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
        return isRestarting || super.dispatchTrackballEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return isRestarting || super.dispatchGenericMotionEvent(event)
    }

    @Parcelize
    class Args(val section: String? = null) : ParcelableArgs

    companion object {
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_AI = "ai"
        const val SECTION_FILES = "files"
        const val SECTION_STORAGE = "storage"
        const val SECTION_TRASH = "trash"
        const val SECTION_LAUNCHER_ICONS = "launcher_icons"
        /** Ordo Musik's own settings page (its side menu); not listed in Ordo's overview. */
        const val SECTION_MUSIC = "music"
        val SECTIONS = listOf(
            SECTION_APPEARANCE, SECTION_AI, SECTION_LAUNCHER_ICONS, SECTION_FILES, SECTION_STORAGE, SECTION_TRASH
        )

        private var restartState: Bundle? = null

        fun createIntent(section: String) = SettingsActivity::class.createIntent().putArgs(Args(section))
    }
}
