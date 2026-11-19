/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SettingsFragmentBinding

class SettingsFragment : Fragment() {
    private lateinit var binding: SettingsFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        SettingsFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (childFragmentManager.findFragmentById(R.id.preferenceContainer) == null) {
            val fragment = when ((requireActivity() as SettingsActivity).section) {
                SettingsActivity.SECTION_LAUNCHER_ICONS -> LauncherIconsPreferenceFragment()
                else -> SettingsPreferenceFragment()
            }
            childFragmentManager.commit { add(R.id.preferenceContainer, fragment) }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        when ((activity as SettingsActivity).section) {
            SettingsActivity.SECTION_APPEARANCE -> R.string.settings_section_appearance
            SettingsActivity.SECTION_AI -> R.string.settings_section_ai
            SettingsActivity.SECTION_FILES -> R.string.settings_section_files
            SettingsActivity.SECTION_STORAGE -> R.string.settings_section_storage
            SettingsActivity.SECTION_TRASH -> R.string.settings_trash_title
            SettingsActivity.SECTION_LAUNCHER_ICONS -> R.string.settings_section_launcher_icons
            SettingsActivity.SECTION_MUSIC -> R.string.settings_music_title
            else -> null
        }?.let { activity.title = getString(it) }
    }
}
