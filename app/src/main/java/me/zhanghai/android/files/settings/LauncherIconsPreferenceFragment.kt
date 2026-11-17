/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.os.Bundle
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import me.zhanghai.android.files.R
import me.zhanghai.android.files.launcher.BuiltInApp
import me.zhanghai.android.files.launcher.LauncherIcons

/**
 * One switch per built-in app. The switch shows the real state of the launcher alias, read from the system each
 * time the page is shown, so it stays right after a reinstall or when something else changed the alias. Ordo
 * never switches an icon on by itself: only this page does, on the user's tap.
 */
class LauncherIconsPreferenceFragment : SettingsPreferenceFragment() {
    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferencesFix(savedInstanceState, rootKey)

        BuiltInApp.entries.forEach { app ->
            val preference = switchFor(app) ?: return@forEach
            if (!app.isAvailable) {
                preference.isEnabled = false
                preference.isChecked = false
                return@forEach
            }
            preference.setOnPreferenceChangeListener { _, newValue -> onSwitched(app, newValue as Boolean) }
        }
    }

    override fun onResume() {
        super.onResume()

        val context = requireContext()
        BuiltInApp.available.forEach { app ->
            switchFor(app)?.isChecked = LauncherIcons.isEnabled(context, app)
        }
    }

    private fun onSwitched(app: BuiltInApp, enabled: Boolean): Boolean {
        val context = requireContext()
        if (!LauncherIcons.setEnabled(context, app, enabled)) {
            Snackbar.make(requireView(), R.string.launcher_icon_failed, Snackbar.LENGTH_LONG).show()
            return false
        }
        Snackbar.make(
            requireView(),
            getString(
                if (enabled) R.string.launcher_icon_enabled_message else R.string.launcher_icon_disabled_message,
                getString(app.labelRes)
            ),
            // Two lines of about 16 words need about 5 s to read; LENGTH_LONG (2.75 s) was gone before that.
            MESSAGE_DURATION_MS
        ).show()
        return true
    }

    private fun switchFor(app: BuiltInApp): SwitchPreferenceCompat? =
        preferenceScreen.findPreference("$KEY_PREFIX${app.name.lowercase()}")

    companion object {
        const val KEY_PREFIX = "launcher_"
        private const val MESSAGE_DURATION_MS = 6_000
    }
}
