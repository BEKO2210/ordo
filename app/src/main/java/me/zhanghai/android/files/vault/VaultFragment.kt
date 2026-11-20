/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.vault

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import java.io.File
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.CheckableFileItemBinding
import me.zhanghai.android.files.databinding.VaultFragmentBinding
import me.zhanghai.android.files.databinding.VaultNoticeItemBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isPdf
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.home.setTintedCircleIcon
import me.zhanghai.android.files.ui.setUpRestoreDeleteBar
import me.zhanghai.android.files.ui.showRestoreDeleteBar
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.pdf.PdfViewerActivity
import me.zhanghai.android.files.viewer.video.vault.VaultVideos

class VaultFragment : Fragment() {
    private val viewModel by viewModels<VaultViewModel>()

    private lateinit var binding: VaultFragmentBinding

    private lateinit var biometricPrompt: BiometricPrompt

    private val noticeAdapter = NoticeAdapter()
    private val adapter = Adapter()

    private var wasUnlocked: Boolean? = null

    private val isDeviceSecure: Boolean
        get() = requireContext().getSystemService<KeyguardManager>()!!.isDeviceSecure

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        VaultFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.adapter = ConcatAdapter(noticeAdapter, adapter)
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.reload() }
        view.findViewById<View>(R.id.restoreDeleteBar).setUpRestoreDeleteBar(
            onRestore = {
                FileJobService.restoreFromVault(selectedEntries(), requireContext())
                viewModel.selectedIds.clear()
                updateSelection()
                reloadWhileJobsRun()
            },
            onDelete = { confirmDelete(selectedEntries()) }
        )
        // An open lock tells "out of the vault" apart from the bin next to it.
        view.findViewById<MaterialButton>(R.id.restoreButton)
            .setIconResource(R.drawable.lock_open_icon_white_24dp)
        binding.lockedIcon.setTintedCircleIcon(R.drawable.lock_icon_white_24dp, R.color.home_vault)
        binding.unlockButton.setOnClickListener {
            if (isDeviceSecure) {
                authenticate()
            } else {
                startActivitySafe(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
            }
        }
        biometricPrompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    viewModel.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Backing out of the prompt needs no explanation.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        showToast(errString)
                    }
                }
            }
        )
        viewModel.isUnlocked.observe(viewLifecycleOwner) { updateState() }
        viewModel.isLoading.observe(viewLifecycleOwner) {
            binding.swipeRefreshLayout.isRefreshing = it
            updateState()
        }
        viewModel.hasError.observe(viewLifecycleOwner) { updateState() }
        viewModel.items.observe(viewLifecycleOwner) {
            adapter.items = it
            updateState()
            updateSelection()
        }
        viewModel.openedFile.observe(viewLifecycleOwner) {
            if (it != null) {
                viewModel.consumeOpenedFile()
                view(it)
            }
        }
        viewModel.hasOpenError.observe(viewLifecycleOwner) {
            if (it) {
                viewModel.consumeOpenError()
                showToast(R.string.vault_open_error)
            }
        }
    }

    override fun onStart() {
        super.onStart()

        viewModel.onStart()
        // The screen lock may have been set up in the settings meanwhile.
        updateState()
        viewModel.reload()
        if (viewModel.isUnlocked.value != true && !viewModel.hasPrompted && isDeviceSecure) {
            viewModel.hasPrompted = true
            authenticate()
        }
    }

    override fun onStop() {
        super.onStop()

        if (!requireActivity().isChangingConfigurations) {
            viewModel.onStop()
        }
    }

    private fun authenticate() {
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.vault_prompt_title))
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }

    private fun updateState() {
        val isUnlocked = viewModel.isUnlocked.value == true
        if (wasUnlocked != null && wasUnlocked != isUnlocked) {
            TransitionManager.beginDelayedTransition(binding.contentLayout, MaterialFadeThrough())
        }
        wasUnlocked = isUnlocked
        binding.lockedView.isVisible = !isUnlocked
        binding.swipeRefreshLayout.isVisible = isUnlocked
        if (!isUnlocked) {
            val isSecure = isDeviceSecure
            binding.lockedTitle.setText(
                if (isSecure) R.string.vault_locked_title else R.string.vault_no_screen_lock_title
            )
            binding.lockedMessage.setText(
                if (isSecure) {
                    R.string.vault_locked_message
                } else {
                    R.string.vault_no_screen_lock_message
                }
            )
            binding.unlockButton.setText(
                if (isSecure) R.string.vault_unlock_action else R.string.vault_open_settings_action
            )
        }
        val isEmpty = adapter.items.isEmpty()
        noticeAdapter.isVisible = !isEmpty
        binding.emptyView.isVisible = isUnlocked && isEmpty && viewModel.isLoading.value != true
        binding.emptyView.setText(
            if (viewModel.hasError.value == true) {
                R.string.vault_error_message
            } else {
                R.string.vault_empty
            }
        )
        updateSelection()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.vault, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onPrepareOptionsMenu(menu: Menu) {
        val isUnlocked = viewModel.isUnlocked.value == true
        val hasSelection = viewModel.selectedIds.isNotEmpty()
        menu.findItem(R.id.action_lock).isVisible = isUnlocked && !hasSelection
        menu.findItem(R.id.action_select_all).isVisible = adapter.items.isNotEmpty()
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                requireActivity().finish()
                true
            }
            R.id.action_lock -> {
                viewModel.lock()
                true
            }
            R.id.action_select_all -> {
                viewModel.selectedIds += adapter.items.map { it.id }
                adapter.notifyDataSetChanged()
                updateSelection()
                true
            }
            else -> @Suppress("DEPRECATION") super.onOptionsItemSelected(item)
        }

    private fun selectedEntries(): List<VaultEntry> =
        adapter.items.filter { it.id in viewModel.selectedIds }

    private fun confirmDelete(entries: List<VaultEntry>) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(
                requireContext().getQuantityString(
                    R.plurals.trash_delete_message_format, entries.size, entries.size
                )
            )
            .setPositiveButton(R.string.trash_delete_permanently_action) { _, _ ->
                viewModel.delete(entries)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Restoring runs in FileJobService without a callback, so the list follows by polling.
    private fun reloadWhileJobsRun() {
        binding.recyclerView.postDelayed({
            if (view != null) {
                viewModel.reload()
                if (FileJobService.runningJobCount > 0) {
                    reloadWhileJobsRun()
                }
            }
        }, 800)
    }

    private fun view(file: File) {
        val path = Paths.get(file.path)
        val mimeType = MimeType.guessFromPath(file.path)
        val intent = path.fileProviderUri.createViewIntent(mimeType).apply {
            extraPath = path
            // Images and PDFs stay inside Ordo; other types need an app that can show them.
            if (mimeType.isImage) {
                setClass(requireContext(), ImageViewerActivity::class.java)
                ImageViewerActivity.putExtras(this, listOf(path), 0)
            } else if (mimeType.isPdf) {
                setClass(requireContext(), PdfViewerActivity::class.java)
            }
        }
        startActivitySafe(intent)
    }

    private fun toggleSelection(entry: VaultEntry, position: Int) {
        if (!viewModel.selectedIds.remove(entry.id)) {
            viewModel.selectedIds += entry.id
        }
        adapter.notifyItemChanged(position)
        updateSelection()
    }

    private fun updateSelection() {
        val count = viewModel.selectedIds.size
        requireActivity().title = if (count > 0) {
            getString(R.string.trash_selected_format, count)
        } else {
            getString(R.string.vault_title)
        }
        requireView().findViewById<View>(R.id.restoreDeleteBar)
            .showRestoreDeleteBar(count > 0, binding.recyclerView)
        requireActivity().invalidateOptionsMenu()
    }

    private class NoticeAdapter : RecyclerView.Adapter<NoticeAdapter.ViewHolder>() {
        var isVisible = false
            set(value) {
                if (field != value) {
                    field = value
                    notifyDataSetChanged()
                }
            }

        override fun getItemCount(): Int = if (isVisible) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                VaultNoticeItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {}

        class ViewHolder(binding: VaultNoticeItemBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.ViewHolder>() {
        var items: List<VaultEntry> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                CheckableFileItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            ).apply {
                // Unlike in the trash, a tap on the row opens the file; the box selects it.
                binding.checkBox.isClickable = true
                binding.checkBox.isFocusable = true
            }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val context = holder.binding.root.context
            holder.binding.iconImage.setImageResource(MimeType.guessFromPath(entry.name).iconRes)
            holder.binding.nameText.text = entry.name
            val addedAt = DateUtils.formatDateTime(
                context, entry.addedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE
            )
            holder.binding.descriptionText.text = getString(
                R.string.vault_item_description_format, addedAt,
                entry.size.asFileSize().formatHumanReadable(context),
                // "Download/Urlaub" reads better than the full "/storage/emulated/0/…" path.
                entry.originalPath.substringBeforeLast('/')
                    .removePrefix(Environment.getExternalStorageDirectory().path + "/")
            )
            holder.binding.checkBox.isChecked = entry.id in viewModel.selectedIds
            holder.binding.checkBox.setOnClickListener {
                toggleSelection(entry, holder.bindingAdapterPosition)
            }
            holder.binding.root.setOnClickListener {
                if (viewModel.selectedIds.isEmpty() && MimeType.guessFromPath(entry.name).isVideo) {
                    // Streamed and decrypted in Ordo's player, never written out as plaintext.
                    val videos = items.filter { MimeType.guessFromPath(it.name).isVideo }
                    VaultVideos.play(requireContext(), videos, entry)
                    viewModel.onViewerStarted()
                } else if (viewModel.selectedIds.isEmpty()) {
                    viewModel.open(entry)
                } else {
                    toggleSelection(entry, holder.bindingAdapterPosition)
                }
            }
            holder.binding.root.setOnLongClickListener {
                toggleSelection(entry, holder.bindingAdapterPosition)
                true
            }
        }

        inner class ViewHolder(val binding: CheckableFileItemBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
