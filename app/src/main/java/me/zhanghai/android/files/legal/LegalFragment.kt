/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.legal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ArrayRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.LegalFragmentBinding
import me.zhanghai.android.files.databinding.LegalItemBinding

/** The legal notice and the privacy policy, readable without a network connection. */
class LegalFragment : Fragment() {
    private lateinit var binding: LegalFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        LegalFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        addSections(binding.imprintLayout, R.array.legal_imprint_titles, R.array.legal_imprint_texts)
        addSections(binding.privacyLayout, R.array.legal_privacy_titles, R.array.legal_privacy_texts)
    }

    private fun addSections(layout: ViewGroup, @ArrayRes titlesRes: Int, @ArrayRes textsRes: Int) {
        val titles = resources.getStringArray(titlesRes)
        val texts = resources.getStringArray(textsRes)
        check(titles.size == texts.size)
        for ((title, text) in titles.zip(texts)) {
            val itemBinding = LegalItemBinding.inflate(layoutInflater, layout, true)
            itemBinding.titleText.text = title
            itemBinding.bodyText.text = text
        }
    }
}
