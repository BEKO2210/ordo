/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.viewer.audio

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.CastChooserSheetBinding
import me.zhanghai.android.files.databinding.CastDeviceRowBinding
import me.zhanghai.android.files.player.cast.CastDevice
import me.zhanghai.android.files.player.cast.CastDiscovery
import me.zhanghai.android.files.player.cast.CastManager

/** Lists this phone and the cast devices on the Wi-Fi; picking one moves playback there. */
@OptIn(UnstableApi::class)
class CastChooserSheet : BottomSheetDialogFragment() {
    private var _binding: CastChooserSheetBinding? = null
    private val binding get() = checkNotNull(_binding)
    private var discovery: CastDiscovery? = null
    private var devices = emptyList<CastDevice>()
    private var searchedLong = false
    private var lastPhase = CastManager.phase
    private val handler = Handler(Looper.getMainLooper())
    private val searchedLongEnough = Runnable {
        searchedLong = true
        render()
    }
    private val castListener: () -> Unit = {
        // Once the TV plays, the sheet has done its job.
        if (lastPhase == CastManager.Phase.CONNECTING && CastManager.phase == CastManager.Phase.CASTING) {
            dismissAllowingStateLoss()
        }
        lastPhase = CastManager.phase
        render()
        askAboutChangedDevice()
    }
    private var confirmDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CastChooserSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        discovery = CastDiscovery(requireContext()) {
            devices = it
            render()
        }
        binding.stop.setOnClickListener { CastManager.disconnect() }
        render()
    }

    override fun onStart() {
        super.onStart()
        discovery?.start()
        CastManager.addListener(castListener)
        handler.postDelayed(searchedLongEnough, NONE_FOUND_AFTER_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(searchedLongEnough)
        CastManager.removeListener(castListener)
        discovery?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        confirmDialog?.dismiss()
        confirmDialog = null
        _binding = null
        discovery = null
        super.onDestroyView()
    }

    private fun render() {
        val binding = _binding ?: return
        val active = CastManager.device
        val phase = CastManager.phase
        // The device in use stays listed even when its mDNS record has not come in yet.
        val shown = devices.filter { it.id != active?.id }.let { if (active != null) listOf(active) + it else it }
        binding.search.found = shown.isNotEmpty()
        binding.status.setText(
            if (shown.isEmpty() && searchedLong) R.string.cast_none_found else R.string.cast_searching
        )
        binding.status.isVisible = shown.isEmpty() || phase == CastManager.Phase.IDLE
        binding.devices.removeAllViews()
        addRow(
            R.drawable.cast_phone_icon_24dp, getString(R.string.cast_this_phone),
            getString(R.string.cast_this_phone_summary), phase == CastManager.Phase.IDLE, false
        ) { CastManager.disconnect() }
        for (device in shown) {
            val isActive = device.id == active?.id
            val unfamiliar = !isActive && CastManager.isUnfamiliar(device)
            val summary = when {
                isActive && phase == CastManager.Phase.CONNECTING -> getString(R.string.cast_connecting)
                isActive -> getString(R.string.cast_playing_here)
                unfamiliar -> getString(R.string.cast_unfamiliar)
                else -> device.model.orEmpty()
            }
            addRow(
                if (device.hasVideo) R.drawable.cast_tv_icon_24dp else R.drawable.audio_speaker_icon_24dp,
                device.name, summary, isActive && phase == CastManager.Phase.CASTING,
                isActive && phase == CastManager.Phase.CONNECTING, unfamiliar
            ) { if (!isActive) CastManager.connect(device) }
        }
        binding.stop.isVisible = phase != CastManager.Phase.IDLE
        binding.effectsNote.isVisible = phase == CastManager.Phase.CASTING
    }

    private fun addRow(
        icon: Int,
        name: String,
        summary: String,
        selected: Boolean,
        connecting: Boolean,
        warning: Boolean = false,
        onClick: () -> Unit
    ) {
        val row = CastDeviceRowBinding.inflate(layoutInflater, binding.devices, true)
        row.icon.setImageResource(icon)
        row.icon.background.mutate().alpha = ICON_DISC_ALPHA
        row.name.text = name
        row.summary.text = summary
        row.summary.isVisible = summary.isNotEmpty()
        if (warning) row.summary.setTextColor(MaterialColors.getColor(row.summary, androidx.appcompat.R.attr.colorError))
        row.selected.isVisible = selected
        row.progress.isVisible = connecting
        row.root.isSelected = selected
        row.root.setOnClickListener { onClick() }
    }

    /** A familiar name with another device behind it: connect only when the user says so. */
    private fun askAboutChangedDevice() {
        val device = CastManager.confirmation ?: return
        if (confirmDialog?.isShowing == true || !isAdded) return
        confirmDialog = MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.cast_tv_icon_24dp)
            .setTitle(getString(R.string.cast_changed_title, device.name))
            .setMessage(getString(R.string.cast_changed_message, device.name))
            .setPositiveButton(R.string.cast_changed_connect) { _, _ -> CastManager.connect(device, confirmed = true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> CastManager.dismissConfirmation() }
            .setOnCancelListener { CastManager.dismissConfirmation() }
            .show()
    }

    companion object {
        const val TAG = "CastChooserSheet"
        private const val NONE_FOUND_AFTER_MS = 8_000L
        private const val ICON_DISC_ALPHA = 0x24
    }
}
