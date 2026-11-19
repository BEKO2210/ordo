/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.graphics.drawable.VectorDrawable
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.about.AboutActivity
import me.zhanghai.android.files.ai.CommandDesign
import me.zhanghai.android.files.ai.CommandModel
import me.zhanghai.android.files.ai.CommandSheetFragment
import me.zhanghai.android.files.backup.BackupActivity
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.legal.LegalActivity
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.Motion
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.startActivitySafe

open class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private var localePreference: LocalePreference? = null
    private var iconSettleCount = 0

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
                SettingsActivity.SECTION_LAUNCHER_ICONS -> R.xml.settings_launcher_icons
                SettingsActivity.SECTION_MUSIC -> R.xml.settings_music
                SettingsActivity.SECTION_VIDEO -> R.xml.settings_video
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
        if (section == SettingsActivity.SECTION_MUSIC) {
            preferenceScreen.findPreference<Preference>("music_icon_colors")?.setOnPreferenceClickListener {
                startActivitySafe(SettingsActivity.createIntent(SettingsActivity.SECTION_LAUNCHER_ICONS))
                true
            }
        }
        if (section == SettingsActivity.SECTION_VIDEO) bindVideo()
        if (section == null) {
            bindOverview()
        } else {
            // Rows without an icon keep the same text start as rows that have one.
            reserveIconSpace(preferenceScreen)
        }
    }

    private fun reserveIconSpace(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            when (val preference = group.getPreference(i)) {
                is PreferenceCategory -> reserveIconSpace(preference)
                else -> preference.isIconSpaceReserved = true
            }
        }
        group.tintIcons()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is VisualListPreference) {
            if (parentFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) == null) {
                displayPreferenceDialog(VisualListPreferenceDialogFragment(), preference.key)
            }
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    /**
     * Ordo Video's page: the folders to hide come from the device's videos (read in the background), the summary
     * names the hidden ones; clearing "Continue watching" asks first.
     */
    private fun bindVideo() {
        val hidden = preferenceScreen.findPreference<androidx.preference.MultiSelectListPreference>(
            getString(R.string.pref_key_video_hidden_folders)
        )
        if (hidden != null) {
            hidden.summaryProvider = Preference.SummaryProvider<androidx.preference.MultiSelectListPreference> {
                it.values.map { path -> path.substringAfterLast('/') }.sorted().joinToString(", ")
                    .ifEmpty { getString(R.string.video_settings_hidden_folders_none) }
            }
            hidden.isEnabled = false
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val folders = withContext(Dispatchers.IO) {
                    // Every folder with videos, hidden ones included, so a hidden folder can be shown again.
                    me.zhanghai.android.files.viewer.video.VideoLibrary(
                        me.zhanghai.android.files.viewer.video.queryVideos(context), true,
                        java.text.Collator.getInstance(), me.zhanghai.android.files.viewer.video.VideoPrefs.Sort.NAME
                    ).folders.sortedBy { it.name.lowercase() }
                }
                val paths = (folders.map { it.path } + hidden.values).distinct()
                hidden.entries = paths.map { path ->
                    val folder = folders.find { it.path == path }
                    if (folder != null) "${folder.name} (${folder.videos.size})" else path.substringAfterLast('/')
                }.toTypedArray()
                hidden.entryValues = paths.toTypedArray()
                hidden.isEnabled = paths.isNotEmpty()
            }
        }
        preferenceScreen.findPreference<Preference>("video_clear_history")?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.video_settings_clear_history_confirm)
                .setPositiveButton(R.string.video_settings_clear_history_action) { _, _ ->
                    me.zhanghai.android.files.viewer.video.VideoResumeStore(requireContext()).clear()
                    android.widget.Toast.makeText(
                        requireContext(), R.string.video_settings_clear_history_done, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
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
            if (childFragmentManager.findFragmentByTag(CommandSheetFragment.TAG) == null) {
                CommandSheetFragment().show(childFragmentManager, CommandSheetFragment.TAG)
            }
            true
        }
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val design = CommandDesign.current(context)
            val state = withContext(Dispatchers.IO) { CommandModel.state(context, design) }
            preference.summary = when (state) {
                CommandModel.State.Ready -> getString(R.string.settings_ai_model_ready,
                    CommandModel.parts(design).sumOf { it.size }.asFileSize().formatHumanReadable(context))
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
        // On sub-pages the first eight icons settle once (scale + fade); with motion off they simply stand.
        listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (view.isClickable && view.getTag(R.id.ordo_row_ripple) == null) {
                    view.background = ContextCompat.getDrawable(view.context, R.drawable.ordo_row_ripple)
                    view.setTag(R.id.ordo_row_ripple, true)
                }
                if (section != null) settleIcon(view)
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
        Settings.MUSIC_ACCENT.observe(viewLifecycleOwner, this::onAccentChanged)
        Settings.VIDEO_ACCENT.observe(viewLifecycleOwner, this::onAccentChanged)
        Settings.MUSIC_COVER_COLORS.observe(viewLifecycleOwner, this::onMusicCoverColorsChanged)
    }

    // The mini player picture above redraws with the choice (live preview).
    private fun onMusicCoverColorsChanged(coverColors: Boolean) {
        listView?.adapter?.notifyDataSetChanged()
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

    private fun settleIcon(row: View) {
        if (!Motion.any || iconSettleCount >= ICON_SETTLE_MAX) return
        val icon = row.findViewById<ImageView>(android.R.id.icon) ?: return
        if (icon.visibility != View.VISIBLE || icon.drawable == null) return
        if (icon.getTag(R.id.on_first_sight) != null) return
        val delay = iconSettleCount * ICON_SETTLE_STAGGER_MS
        iconSettleCount++
        icon.setTag(R.id.on_first_sight, true)
        icon.alpha = 0f
        icon.scaleX = 0.85f
        icon.scaleY = 0.85f
        icon.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(ICON_SETTLE_DURATION_MS)
            .setInterpolator(EMPHASIZED)
            .start()
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

    companion object {
        // @see PreferenceFragmentCompat.DIALOG_FRAGMENT_TAG
        private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"
        private const val ICON_SETTLE_MAX = 8
        private const val ICON_SETTLE_STAGGER_MS = 30L
        private const val ICON_SETTLE_DURATION_MS = 240L
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/**
 * The symbols of settings rows are white vectors; without a tint they vanish on the light theme. Pictures drawn in code
 * (night mode, black night mode) keep their own colours.
 */
fun PreferenceGroup.tintIcons() {
    val color = context.getColorByAttr(androidx.appcompat.R.attr.colorControlNormal)
    for (i in 0 until preferenceCount) {
        val preference = getPreference(i)
        if (preference is PreferenceGroup) {
            preference.tintIcons()
            continue
        }
        val icon = preference.icon ?: continue
        if (icon is VectorDrawable || icon is VectorDrawableCompat) {
            preference.icon = icon.mutate().apply { setTint(color) }
        }
    }
}
