/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.about.AboutActivity
import me.zhanghai.android.files.ai.CommandModel
import me.zhanghai.android.files.ai.showAiSheet
import me.zhanghai.android.files.backup.BackupActivity
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.legal.LegalActivity
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.startActivitySafe

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private var localePreference: LocalePreference? = null

    private val section: String?
        get() = (requireActivity() as SettingsActivity).section

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(
            when (section) {
                SettingsActivity.SECTION_APPEARANCE -> R.xml.settings_appearance
                SettingsActivity.SECTION_AI -> R.xml.settings_ai
                SettingsActivity.SECTION_FILES -> R.xml.settings_files
                SettingsActivity.SECTION_STORAGE -> R.xml.settings_storage
                SettingsActivity.SECTION_TRASH -> R.xml.settings_trash
                else -> R.xml.settings
            }
        )

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference?.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }
        if (section == null) bindOverview()
    }

    private fun bindOverview() {
        fun open(key: String, intent: () -> Intent) {
            preferenceScreen.findPreference<Preference>(key)!!.setOnPreferenceClickListener {
                startActivitySafe(intent())
                true
            }
        }
        SettingsActivity.SECTIONS.forEach { section -> open("section_$section") { SettingsActivity.createIntent(section) } }
        open("link_backup") { BackupActivity::class.createIntent() }
        open("link_ftp_server") { FtpServerActivity::class.createIntent() }
        open("link_legal") { LegalActivity::class.createIntent() }
        open("link_about") { AboutActivity::class.createIntent() }
        preferenceScreen.findPreference<Preference>("link_about")!!.summary =
            getString(R.string.settings_about_summary, BuildConfig.VERSION_NAME)
    }

    // The model's state can change while this screen is open (a download in the sheet), so it is read on resume.
    private fun bindAiModel() {
        val preference = preferenceScreen.findPreference<Preference>("ai_model") ?: return
        preference.setOnPreferenceClickListener {
            childFragmentManager.showAiSheet()
            true
        }
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { CommandModel.state(context) }
            preference.summary = when (state) {
                CommandModel.State.Ready -> getString(R.string.settings_ai_model_ready,
                    CommandModel.SIZE.asFileSize().formatHumanReadable(context))
                is CommandModel.State.Downloading -> getString(R.string.settings_ai_model_downloading,
                    if (state.total > 0) (state.bytes * 100 / state.total).toInt() else 0)
                CommandModel.State.Verifying -> getString(R.string.settings_ai_model_verifying)
                is CommandModel.State.Failed -> getString(R.string.settings_ai_model_failed)
                CommandModel.State.Missing -> getString(R.string.settings_ai_model_missing)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // Every tappable row gets Ordo's rounded, accent-tinted touch feedback instead of a full-width flash.
        listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (view.isClickable && view.getTag(R.id.ordo_row_ripple) == null) {
                    view.background = ContextCompat.getDrawable(view.context, R.drawable.ordo_row_ripple)
                    view.setTag(R.id.ordo_row_ripple, true)
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        val viewLifecycleOwner = viewLifecycleOwner
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        //Settings.THEME_COLOR.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.NIGHT_MODE.observe(viewLifecycleOwner) { NightModeHelper.sync() }
        //Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
        Settings.ACCENT.observe(viewLifecycleOwner, this::onAccentChanged)
        Settings.WALLPAPER_COLORS.observe(viewLifecycleOwner, this::onWallpaperColorsChanged)
    }

    private fun onAccentChanged(accent: String) {
        CustomThemeHelper.sync()
    }

    private fun onWallpaperColorsChanged(wallpaperColors: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onThemeColorChanged(themeColor: ThemeColor) {
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference?.notifyChanged()
        }
        if (section == SettingsActivity.SECTION_AI) bindAiModel()
    }
}
