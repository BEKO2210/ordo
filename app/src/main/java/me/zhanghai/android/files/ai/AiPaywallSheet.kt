/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AiPaywallSheetBinding
import me.zhanghai.android.files.settings.Settings

/**
 * Shown instead of the AI while it is locked: what the one-time purchase gives, the price from Google Play, buy,
 * restore, not now. When the purchase goes through, it opens the AI right away.
 */
class AiPaywallSheet : BottomSheetDialogFragment() {
    private var _binding: AiPaywallSheetBinding? = null
    private val binding get() = _binding!!

    private val unlock get() = AiUnlock.instance
    private var restoring = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = AiPaywallSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val context = requireContext()
        binding.downloadText.text = getString(R.string.ai_paywall_download,
            Formatter.formatShortFileSize(context, CommandModel.SIZE))
        binding.buyButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { unlock.buy(requireActivity()) }
        }
        binding.restoreButton.setOnClickListener {
            restoring = true
            binding.statusText.isVisible = false
            viewLifecycleOwner.lifecycleScope.launch {
                unlock.refresh()
                if (restoring && unlock.state.value is AiUnlockState.Offer) {
                    binding.statusText.setText(R.string.ai_paywall_not_found)
                    binding.statusText.isVisible = true
                }
                restoring = false
            }
        }
        binding.notNowButton.setOnClickListener { dismiss() }
        if (BuildConfig.DEBUG) {
            binding.debugUnlockButton.isVisible = true
            binding.debugUnlockButton.setOnClickListener {
                Settings.AI_DEBUG_UNLOCK.putValue(true)
                openAi()
            }
        }
        // Only phones that can run the model may buy it: free space can be made, RAM and a 64-bit CPU cannot.
        val check = CommandModel.check(context)
        val supported = check.ramOk && check.is64Bit
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                unlock.state.collect { bind(it, supported) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        unlock.refreshLater()
        val dialog = dialog as BottomSheetDialog
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(R.drawable.ai_sheet_background)
        dialog.window?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply {
                    blurBehindRadius = (28 * resources.displayMetrics.density).toInt()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bind(state: AiUnlockState, supported: Boolean) {
        if (state == AiUnlockState.Unlocked) {
            openAi()
            return
        }
        val status = when {
            state == AiUnlockState.Pending -> R.string.ai_paywall_pending
            state == AiUnlockState.Unavailable -> R.string.ai_paywall_unavailable
            !supported -> R.string.ai_paywall_unsupported
            else -> null
        }
        if (status != null || !restoring) {
            binding.statusText.isVisible = status != null
            status?.let { binding.statusText.text = getString(it,
                Formatter.formatShortFileSize(requireContext(), CommandModel.MIN_RAM)) }
        }
        binding.buyButton.isVisible = status == null
        binding.buyButton.isEnabled = state is AiUnlockState.Offer
        binding.buyButton.text = if (state is AiUnlockState.Offer) {
            getString(R.string.ai_paywall_buy, state.price)
        } else getString(R.string.ai_paywall_checking)
    }

    private fun openAi() {
        val manager = parentFragmentManager
        dismiss()
        if (manager.findFragmentByTag(CommandSheetFragment.TAG) == null) {
            CommandSheetFragment().apply { arguments = this@AiPaywallSheet.arguments?.getBundle(ARG_SHEET) }
                .show(manager, CommandSheetFragment.TAG)
        }
    }

    companion object {
        const val TAG = "AiPaywallSheet"
        private const val ARG_SHEET = "sheet"

        /** [sheetArguments]: what the AI sheet was opened with (e.g. a storage suggestion), for after the purchase. */
        fun newInstance(sheetArguments: Bundle?) = AiPaywallSheet().apply {
            arguments = Bundle().apply { putBundle(ARG_SHEET, sheetArguments) }
        }
    }
}
