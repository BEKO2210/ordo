/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.settings

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import me.zhanghai.android.files.R
import me.zhanghai.android.files.launcher.BuiltInApp
import me.zhanghai.android.files.launcher.IconAliases
import me.zhanghai.android.files.launcher.IconColor
import me.zhanghai.android.files.launcher.LauncherIcons

/**
 * A picture of the icons, one switch per built-in app that is ready, the icon colour of Ordo and of every app that
 * has an icon, and the apps still to come. Switches and colours
 * show the real state of the launcher aliases, read from the system each time the page is shown, so they stay right
 * after a reinstall or when something else changed an alias. Ordo never switches an icon on by itself: only this
 * page does, on the user's tap.
 */
class LauncherIconsPreferenceFragment : SettingsPreferenceFragment() {
    private val colorRows = mutableListOf<IconColorPreference>()

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferencesFix(savedInstanceState, rootKey)

        BuiltInApp.available.forEach { app ->
            switchFor(app)?.setOnPreferenceChangeListener { _, newValue -> onSwitched(app, newValue as Boolean) }
        }

        val context = preferenceManager.context
        val category = PreferenceCategory(context).apply {
            key = KEY_COLORS
            setTitle(R.string.launcher_colors_title)
        }
        preferenceScreen.addPreference(category)
        colorRows.clear()
        (listOf(IconAliases.HOME to getString(R.string.app_name)) +
            BuiltInApp.available.map { it.aliasPrefix!! to getString(it.labelRes) })
            .forEach { (prefix, label) ->
                val row = IconColorPreference(context, prefix) { onColorChosen(prefix, label, it) }.apply {
                    key = "$KEY_COLOR_PREFIX${prefix.substringAfterLast('.').lowercase()}"
                    title = label
                }
                category.addPreference(row)
                colorRows += row
            }
        val soon = BuiltInApp.entries.filter { !it.isAvailable }
        if (soon.isNotEmpty()) {
            preferenceScreen.addPreference(SoonAppsPreference(context, soon).apply { key = KEY_SOON })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Room after the last row, on top of the navigation bar inset the list already gets as padding.
        val end = resources.getDimensionPixelSize(R.dimen.space_section)
        listView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (parent.getChildAdapterPosition(view) == state.itemCount - 1) outRect.bottom = end
            }
        })
    }

    override fun onResume() {
        super.onResume()

        val context = requireContext()
        BuiltInApp.available.forEach { app ->
            switchFor(app)?.isChecked = LauncherIcons.isEnabled(context, app)
        }
        refreshColors()
    }

    private fun refreshColors() {
        val context = requireContext()
        colorRows.forEach {
            it.show(LauncherIcons.chosenColor(context, it.prefix), LauncherIcons.color(context, it.prefix) != null)
        }
        refreshIcons()
    }

    /** The page's picture and each app's switch show the icons in the colours they have now. */
    private fun refreshIcons() {
        val context = requireContext()
        preferenceScreen.findPreference<LauncherIconsHeaderPreference>(KEY_HEADER)?.show(
            colorRows.map {
                val color = LauncherIcons.chosenColor(context, it.prefix)
                LauncherIconsHeaderPreference.Icon(it.prefix, it.title.toString(), color)
            }
        )
        BuiltInApp.available.forEach { app ->
            val prefix = app.aliasPrefix!!
            switchFor(app)?.icon = LauncherIcons.icon(context, prefix, LauncherIcons.chosenColor(context, prefix))
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
        refreshColors()
        return true
    }

    private fun onColorChosen(prefix: String, label: String, color: IconColor): Boolean {
        val context = requireContext()
        val iconOn = LauncherIcons.color(context, prefix) != null
        if (!LauncherIcons.setColor(context, prefix, color)) {
            Snackbar.make(requireView(), R.string.launcher_icon_failed, Snackbar.LENGTH_LONG).show()
            return false
        }
        Snackbar.make(
            requireView(),
            getString(
                if (iconOn) R.string.launcher_color_changed_message else R.string.launcher_color_saved_message,
                label,
                getString(color.labelRes)
            ),
            MESSAGE_DURATION_MS
        ).show()
        // After the swatch's own animation has started: the picture and the switch's icon follow.
        view?.post { if (isAdded) refreshIcons() }
        return true
    }

    private fun switchFor(app: BuiltInApp): SwitchPreferenceCompat? =
        preferenceScreen.findPreference("$KEY_PREFIX${app.name.lowercase()}")

    companion object {
        const val KEY_PREFIX = "launcher_"
        private const val KEY_HEADER = "launcher_icons_info"
        private const val KEY_COLORS = "launcher_colors"
        private const val KEY_SOON = "launcher_soon"
        private const val KEY_COLOR_PREFIX = "launcher_color_"
        private const val MESSAGE_DURATION_MS = 6_000
    }
}
