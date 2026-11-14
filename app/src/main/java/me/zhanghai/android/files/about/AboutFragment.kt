/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.about

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AboutFragmentBinding
import me.zhanghai.android.files.legal.LegalActivity
import me.zhanghai.android.files.ui.LicensesDialogFragment
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.startActivitySafe

class AboutFragment : Fragment() {
    private lateinit var binding: AboutFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        AboutFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.byDeveloperText.text = getString(R.string.about_by_developer, getString(R.string.about_developer_name))
        binding.developerLayout.setOnClickListener { startActivitySafe(DEVELOPER_URI.createViewIntent()) }
        binding.gitHubLayout.setOnClickListener { startActivitySafe(GITHUB_URI.createViewIntent()) }
        binding.licensesLayout.setOnClickListener { LicensesDialogFragment.show(this) }
        binding.privacyPolicyLayout.isVisible = true
        binding.privacyPolicyLayout.setOnClickListener {
            startActivitySafe(LegalActivity::class.createIntent())
        }
    }

    companion object {
        private val GITHUB_URI = Uri.parse("https://github.com/BEKO2210/ordo")
        private val DEVELOPER_URI = Uri.parse("https://github.com/BEKO2210")
    }
}
