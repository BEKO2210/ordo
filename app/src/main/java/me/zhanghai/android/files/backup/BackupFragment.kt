/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.backup

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.time.LocalDate
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.createOpenDocumentTreeIntentCompat
import me.zhanghai.android.files.compat.directoryCompat
import me.zhanghai.android.files.compat.getDescriptionCompat
import me.zhanghai.android.files.compat.isPrimaryCompat
import me.zhanghai.android.files.compat.isRemovableCompat
import me.zhanghai.android.files.compat.stateCompat
import me.zhanghai.android.files.compat.storageVolumesCompat
import me.zhanghai.android.files.databinding.ArchivePasswordDialogBinding
import me.zhanghai.android.files.databinding.BackupFragmentBinding
import me.zhanghai.android.files.databinding.BackupItemBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.home.setTintedCircleIcon
import me.zhanghai.android.files.navigation.StandardDirectoriesLiveData
import me.zhanghai.android.files.navigation.StandardDirectory
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.fitBottomBarInsets
import me.zhanghai.android.files.util.dpToDimensionPixelSize
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.isMaterial3Theme
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.vault.Vault

class BackupFragment : Fragment() {
    private lateinit var binding: BackupFragmentBinding

    private lateinit var encryptSwitch: SwitchCompat

    private lateinit var biometricPrompt: BiometricPrompt

    private var items = emptyList<BackupItem>()

    private var usbVolume: StorageVolume? = null
    private var sdVolume: StorageVolume? = null
    private var hasUsb = false
    private var usbPulse: ObjectAnimator? = null

    private var lastStatus: BackupStatus? = null

    private var restorePasswordDialog: AlertDialog? = null

    private val addFolderLauncher = registerForActivityResult(
        FileListActivity.OpenDirectoryContract()
    ) { path ->
        when {
            path == null -> {}
            // The backup reads plain files; network and archive folders are out of its reach.
            !path.isLinuxPath -> showError(R.string.backup_add_folder_error)
            else -> {
                val folder = path.toString()
                Settings.BACKUP_EXTRA_FOLDERS.putValue(
                    Settings.BACKUP_EXTRA_FOLDERS.valueCompat + folder
                )
                setItemChecked(folder, true)
                loadItems()
            }
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = result.data?.data
        // The form may have been restored meanwhile, so it is checked again.
        if (result.resultCode == Activity.RESULT_OK && target != null && validate()) {
            FileJobService.backup(
                checkedKeys().filter { it != KEY_VAULT }.map { Paths.get(it) }, isVaultChecked,
                target, password, requireContext()
            )
        }
    }

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && source != null) {
            FileJobService.inspectBackup(source, null, requireContext())
        }
    }

    private val isDeviceSecure: Boolean
        get() = requireContext().getSystemService<KeyguardManager>()!!.isDeviceSecure

    private val isVaultChecked: Boolean
        get() = KEY_VAULT in checkedKeys()

    // Read from the field when it is needed, so it is never kept anywhere else.
    private val password: String?
        get() = if (encryptSwitch.isChecked) binding.passwordEdit.text.toString() else null

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
        BackupFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        binding.usbIcon.setTintedCircleIcon(R.drawable.usb_icon_white_24dp, R.color.home_backup)
        binding.restoreIcon.setTintedCircleIcon(
            R.drawable.restore_icon_white_24dp, R.color.home_backup
        )
        binding.restoreRow.setOnClickListener { pickBackup() }
        binding.otherIcon.setTintedCircleIcon(
            R.drawable.cloud_icon_white_24dp, R.color.home_documents
        )
        binding.encryptIcon.setTintedCircleIcon(R.drawable.lock_icon_white_24dp, R.color.home_vault)
        binding.usbRow.reportCheckedState(binding.usbRadio)
        binding.usbRow.setOnClickListener { selectTarget(Target.USB) }
        binding.sdIcon.setTintedCircleIcon(R.drawable.sd_card_icon_white_24dp, R.color.home_backup)
        binding.sdRow.reportCheckedState(binding.sdRadio)
        binding.sdRow.setOnClickListener { selectTarget(Target.SD) }
        binding.otherRow.reportCheckedState(binding.otherRadio)
        binding.otherRow.setOnClickListener { selectTarget(Target.OTHER) }

        layoutInflater.inflate(
            if (activity.isMaterial3Theme) {
                R.layout.switch_preference_compat_widget_material3
            } else {
                R.layout.switch_preference_compat_widget
            },
            binding.switchFrame
        )
        encryptSwitch = binding.switchFrame.findViewById(R.id.switchWidget)
        binding.encryptRow.reportCheckedState(encryptSwitch)
        binding.encryptRow.setOnClickListener {
            encryptSwitch.toggle()
            updateProtection()
        }
        binding.passwordEdit.doAfterTextChanged { binding.passwordLayout.error = null }
        binding.passwordRepeatEdit.doAfterTextChanged { binding.passwordRepeatLayout.error = null }
        for (edit in arrayOf(binding.passwordEdit, binding.passwordRepeatEdit)) {
            edit.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    revealFocusedField()
                }
            }
        }

        binding.startBar.fitBottomBarInsets()
        binding.startBar.addOnLayoutChangeListener { bar, _, top, _, bottom, _, _, _, _ ->
            // CoordinatorScrollingFrameLayout already pads the form for what is in bar's padding.
            binding.formContent.updatePadding(bottom = bottom - top - bar.paddingBottom)
            revealFocusedField()
        }
        binding.startButton.setOnClickListener { start() }
        biometricPrompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    pickTarget()
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

        loadItems()
        viewLifecycleOwner.lifecycleScope.launch {
            // There is no broadcast for "a drive is plugged in but not mounted yet".
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateUsb()
                    delay(USB_POLL_INTERVAL_MILLIS)
                }
            }
        }
        BackupStatusLiveData.observe(viewLifecycleOwner) { updateStatus(it) }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        updateProtection()
    }

    override fun onDestroyView() {
        usbPulse?.cancel()
        usbPulse = null
        restorePasswordDialog?.setOnDismissListener(null)
        restorePasswordDialog?.dismiss()
        restorePasswordDialog = null

        super.onDestroyView()
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                requireActivity().finish()
                true
            }
            else -> @Suppress("DEPRECATION") super.onOptionsItemSelected(item)
        }

    // The form keeps its size when the keyboard opens, so it does not follow the focus by itself.
    private fun revealFocusedField() {
        val focused = binding.formContent.findFocus() ?: return
        val bar = binding.startBar
        bar.post {
            val location = IntArray(2)
            focused.getLocationInWindow(location)
            val focusedBottom = location[1] + focused.height
            bar.getLocationInWindow(location)
            val overlap = focusedBottom - location[1]
            if (overlap > 0) {
                binding.formView.smoothScrollBy(
                    0, overlap + bar.context.dpToDimensionPixelSize(16)
                )
            }
        }
    }

    private enum class Target { USB, SD, OTHER }

    private fun selectTarget(target: Target) {
        binding.usbRadio.isChecked = target == Target.USB
        binding.sdRadio.isChecked = target == Target.SD
        binding.otherRadio.isChecked = target == Target.OTHER
        showError(null)
        updateUsb()
    }

    private fun updateSd() {
        val context = requireContext()
        val volume = sdVolume
        binding.sdRow.isVisible = volume != null
        if (volume == null) {
            // The card went away while it was chosen: back to the first choice.
            if (binding.sdRadio.isChecked) selectTarget(Target.USB)
            return
        }
        binding.sdTitle.text = volume.getDescriptionCompat(context)
        val freeSpace = volume.directoryCompat?.freeSpace ?: 0
        binding.sdSummary.text = getString(
            R.string.backup_target_sd_summary_format, freeSpace.asFileSize().formatHumanReadable(context)
        )
    }

    private fun updateUsb() {
        val context = requireContext()
        val removable = context.getSystemService<StorageManager>()!!.storageVolumesCompat.filter {
            it.isRemovableCompat && !it.isPrimaryCompat &&
                it.stateCompat == Environment.MEDIA_MOUNTED
        }
        // A drive Android cannot read yet (or at all) still shows up as a USB device.
        val massStorage = context.getSystemService<UsbManager>()?.deviceList?.values.orEmpty().any { device ->
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }
        val split = splitRemovable(removable.map { it.getDescriptionCompat(context) }, massStorage)
        val volume = split.usb?.let { removable[it] }
        usbVolume = volume
        sdVolume = split.sd?.let { removable[it] }
        updateSd()
        val hadUsb = hasUsb
        hasUsb = volume != null || massStorage
        if (hasUsb && !hadUsb) {
            showError(null)
        }
        binding.usbTitle.text = volume?.getDescriptionCompat(context)
            ?: getString(R.string.backup_target_usb)
        val freeSpace = volume?.directoryCompat?.freeSpace ?: 0
        binding.usbSummary.text = when {
            !hasUsb -> getString(R.string.backup_target_usb_waiting)
            freeSpace > 0 -> getString(
                R.string.backup_target_usb_connected_format,
                freeSpace.asFileSize().formatHumanReadable(context)
            )
            else -> getString(R.string.backup_target_usb_connected)
        }
        val isWaiting = !hasUsb && binding.usbRadio.isChecked &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
        if (isWaiting && usbPulse == null) {
            usbPulse = ObjectAnimator.ofFloat(binding.usbIcon, View.ALPHA, 1f, 0.35f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else if (!isWaiting && usbPulse != null) {
            usbPulse!!.cancel()
            usbPulse = null
            binding.usbIcon.alpha = 1f
        }
    }

    private fun loadItems() {
        val context = requireContext()
        val standardDirectories = StandardDirectoriesLiveData.valueCompat
        val extraFolders = Settings.BACKUP_EXTRA_FOLDERS.valueCompat
        viewLifecycleOwner.lifecycleScope.launch {
            items = withContext(Dispatchers.IO) {
                loadBackupItems(context, standardDirectories, extraFolders)
            }
            bindItems()
        }
    }

    private fun bindItems() {
        val excluded = Settings.BACKUP_EXCLUDED.valueCompat
        binding.itemList.removeAllViews()
        for (item in items) {
            val itemBinding = BackupItemBinding.inflate(layoutInflater, binding.itemList, true)
            itemBinding.iconImage.setTintedCircleIcon(item.iconRes, item.colorRes)
            itemBinding.titleText.text = item.title
            itemBinding.summaryText.text = item.summary
            itemBinding.summaryText.isVisible = item.summary != null
            itemBinding.checkBox.isChecked = item.key !in excluded
            itemBinding.root.reportCheckedState(itemBinding.checkBox)
            itemBinding.root.setOnClickListener {
                itemBinding.checkBox.toggle()
                setItemChecked(item.key, itemBinding.checkBox.isChecked)
                showError(null)
                updateProtection()
            }
        }
        val addBinding = BackupItemBinding.inflate(layoutInflater, binding.itemList, true)
        addBinding.iconImage.setTintedCircleIcon(
            R.drawable.add_icon_white_24dp, R.color.home_backup
        )
        addBinding.titleText.setText(R.string.backup_add_folder)
        addBinding.summaryText.isVisible = false
        addBinding.checkBox.isVisible = false
        addBinding.root.setOnClickListener {
            addFolderLauncher.launch(Paths.get(Environment.getExternalStorageDirectory().path))
        }
        updateProtection()
    }

    private fun checkedKeys(): List<String> {
        val excluded = Settings.BACKUP_EXCLUDED.valueCompat
        return items.map { it.key }.filter { it !in excluded }
    }

    private fun setItemChecked(key: String, checked: Boolean) {
        val excluded = Settings.BACKUP_EXCLUDED.valueCompat
        Settings.BACKUP_EXCLUDED.putValue(if (checked) excluded - key else excluded + key)
    }

    private fun updateProtection() {
        // Vault files never leave the device unencrypted.
        val isVaultChecked = isVaultChecked
        if (isVaultChecked) {
            encryptSwitch.isChecked = true
        }
        binding.encryptRow.isEnabled = !isVaultChecked
        encryptSwitch.isEnabled = !isVaultChecked
        binding.encryptSummary.setText(
            if (isVaultChecked) {
                R.string.backup_encrypt_vault_summary
            } else {
                R.string.backup_encrypt_summary
            }
        )
        if (binding.passwordFields.isVisible != encryptSwitch.isChecked) {
            TransitionManager.beginDelayedTransition(binding.formContent)
            binding.passwordFields.isVisible = encryptSwitch.isChecked
        }
    }

    private fun showError(textRes: Int?) {
        binding.errorText.isVisible = textRes != null
        if (textRes != null) {
            binding.errorText.setText(textRes)
        }
    }

    private fun validate(): Boolean {
        val error = when {
            binding.usbRadio.isChecked && !hasUsb -> R.string.backup_target_usb_missing_error
            checkedKeys().isEmpty() -> R.string.backup_content_empty_error
            else -> null
        }
        showError(error)
        if (error != null) {
            return false
        }
        val password = password ?: return true
        val isShort = password.length < PASSWORD_MIN_LENGTH
        val isMismatch = !isShort && password != binding.passwordRepeatEdit.text.toString()
        binding.passwordLayout.error =
            if (isShort) getString(R.string.backup_password_short_error) else null
        binding.passwordRepeatLayout.error =
            if (isMismatch) getString(R.string.backup_password_mismatch_error) else null
        when {
            isShort -> binding.passwordEdit.requestFocus()
            isMismatch -> binding.passwordRepeatEdit.requestFocus()
        }
        return !isShort && !isMismatch
    }

    private fun start() {
        if (!validate()) {
            return
        }
        if (!isVaultChecked) {
            pickTarget()
        } else if (isDeviceSecure) {
            biometricPrompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.backup_vault_prompt_title))
                    .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                    .build()
            )
        } else {
            showError(R.string.vault_no_screen_lock_title)
        }
    }

    // The system file picker is the one way to write to USB drives and cloud apps alike.
    private fun pickTarget() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(
                Intent.EXTRA_TITLE,
                getString(R.string.backup_file_name_format, LocalDate.now().toString()) + ".zip"
            )
        val volume = if (binding.sdRadio.isChecked) sdVolume else usbVolume
        if ((binding.usbRadio.isChecked || binding.sdRadio.isChecked) && volume != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Opens the picker right on the drive.
            @Suppress("DEPRECATION")
            intent.putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                volume.createOpenDocumentTreeIntentCompat()
                    .getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
            )
        }
        try {
            createDocumentLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            showToast(R.string.activity_not_found)
        }
    }

    private fun pickBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
        try {
            openBackupLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            showToast(R.string.activity_not_found)
        }
    }

    private fun showRestorePasswordDialog(source: Uri) {
        if (restorePasswordDialog?.isShowing == true) {
            return
        }
        val passwordBinding = ArchivePasswordDialogBinding.inflate(layoutInflater)
        passwordBinding.passwordEdit.doAfterTextChanged {
            passwordBinding.passwordLayout.error = null
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_password_title)
            .setMessage(R.string.restore_password_message)
            .setView(passwordBinding.root)
            .setPositiveButton(R.string.restore_password_action, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                if ((BackupStatusLiveData.value as? BackupStatus.RestoreNeedsPassword)?.source ==
                    source) {
                    BackupStatusLiveData.value = BackupStatus.Idle
                }
            }
            .create()
        restorePasswordDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener {
            if ((BackupStatusLiveData.value as? BackupStatus.RestoreNeedsPassword)?.source ==
                source) {
                BackupStatusLiveData.value = BackupStatus.Idle
            }
        }
        dialog.setOnDismissListener {
            if (restorePasswordDialog === dialog) {
                restorePasswordDialog = null
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordBinding.passwordEdit.text.toString()
                if (password.isEmpty()) {
                    passwordBinding.passwordLayout.error =
                        getString(R.string.restore_password_empty_error)
                    return@setOnClickListener
                }
                FileJobService.inspectBackup(source, password, requireContext())
                dialog.dismiss()
            }
            passwordBinding.passwordEdit.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun updateStatus(status: BackupStatus) {
        val lastStatus = lastStatus
        val isChanged = lastStatus != null && lastStatus::class != status::class
        if (isChanged) {
            TransitionManager.beginDelayedTransition(
                binding.contentLayout,
                // The transition would put the icon's scale back to where its own animation starts.
                MaterialFadeThrough().excludeTarget(binding.statusIcon, true)
            )
        }
        this.lastStatus = status
        val isIdle = status is BackupStatus.Idle
        if (!isIdle) {
            // The top bar lifts with the form's scroll position, also while the form is hidden.
            binding.formView.scrollTo(0, 0)
        }
        binding.formView.isVisible = isIdle
        binding.startBar.isVisible = isIdle
        binding.statusView.isVisible = !isIdle
        binding.progress.isVisible = false
        binding.percentText.isVisible = false
        binding.statusIcon.isVisible = true
        binding.cancelButton.isVisible = false
        binding.statusButton.isVisible = false
        binding.statusMessage.text = null
        binding.statusDetail.text = null
        val context = requireContext()
        when (status) {
            is BackupStatus.Idle -> {}
            is BackupStatus.Running -> {
                showProgress(status.progress.takeIf { it >= 0 })
                binding.statusTitle.setText(R.string.backup_running_title)
                binding.statusMessage.maxLines = 1
                binding.statusMessage.text =
                    status.fileName ?: getString(R.string.backup_counting)
                binding.statusDetail.setText(R.string.backup_running_notice)
                binding.cancelButton.isVisible = true
                binding.cancelButton.setText(R.string.backup_cancel_action)
                binding.cancelButton.setOnClickListener {
                    FileJobService.cancelJob(status.jobId)
                    BackupStatusLiveData.value = BackupStatus.Idle
                }
            }
            is BackupStatus.Done -> {
                binding.statusIcon.setTintedCircleIcon(
                    R.drawable.check_icon_control_normal_24dp, R.color.home_downloads
                )
                if (isChanged) {
                    binding.statusIcon.scaleX = 0.6f
                    binding.statusIcon.scaleY = 0.6f
                    binding.statusIcon.animate().scaleX(1f).scaleY(1f).setDuration(350)
                        .setInterpolator(OvershootInterpolator()).start()
                }
                binding.statusTitle.setText(R.string.backup_done_toast)
                binding.statusMessage.maxLines = Int.MAX_VALUE
                binding.statusMessage.text = context.getQuantityString(
                    R.plurals.backup_done_message_format, status.fileCount, status.fileCount,
                    status.size.asFileSize().formatHumanReadable(context)
                )
                val skippedCount = status.skipped.size
                binding.statusDetail.text = if (skippedCount > 0) {
                    var names = status.skipped.take(SKIPPED_NAMES_MAX).joinToString("\n")
                    if (skippedCount > SKIPPED_NAMES_MAX) {
                        names += "\n…"
                    }
                    context.getQuantityString(
                        R.plurals.backup_done_skipped_format, skippedCount, skippedCount, names
                    )
                } else {
                    getString(R.string.backup_done_restore_hint)
                }
                binding.statusButton.isVisible = true
                binding.statusButton.setText(R.string.backup_done_action)
                binding.statusButton.setOnClickListener {
                    BackupStatusLiveData.value = BackupStatus.Idle
                    requireActivity().finish()
                }
            }
            is BackupStatus.Failed -> {
                binding.statusIcon.setTintedCircleIcon(
                    R.drawable.error_icon_white_24dp, R.color.home_trash
                )
                binding.statusTitle.setText(R.string.backup_failed_title)
                binding.statusMessage.maxLines = Int.MAX_VALUE
                binding.statusMessage.text = status.message ?: getString(
                    when {
                        status.detail == null -> R.string.backup_failed_empty
                        status.isLarge -> R.string.backup_failed_large
                        else -> R.string.backup_failed_message
                    }
                )
                binding.statusDetail.text = status.detail
                binding.statusButton.isVisible = true
                binding.statusButton.setText(R.string.backup_retry_action)
                binding.statusButton.setOnClickListener {
                    BackupStatusLiveData.value = BackupStatus.Idle
                }
            }
            is BackupStatus.RestoreChecking -> {
                showProgress(null)
                binding.statusTitle.setText(R.string.restore_checking_title)
                binding.statusMessage.maxLines = Int.MAX_VALUE
                binding.statusMessage.setText(R.string.restore_checking_message)
                binding.cancelButton.isVisible = true
                binding.cancelButton.setText(R.string.restore_cancel_action)
                binding.cancelButton.setOnClickListener {
                    FileJobService.cancelJob(status.jobId)
                    BackupStatusLiveData.value = BackupStatus.Idle
                }
            }
            is BackupStatus.RestoreNeedsPassword -> {
                binding.statusIcon.setTintedCircleIcon(
                    R.drawable.lock_icon_white_24dp, R.color.home_vault
                )
                binding.statusTitle.setText(R.string.restore_password_title)
                binding.statusMessage.maxLines = Int.MAX_VALUE
                binding.statusMessage.setText(R.string.restore_password_message)
                showRestorePasswordDialog(status.source)
            }
            is BackupStatus.RestorePreview -> {
                binding.statusIcon.setTintedCircleIcon(
                    R.drawable.restore_icon_white_24dp, R.color.home_backup
                )
                binding.statusTitle.setText(R.string.restore_preview_title)
                binding.statusMessage.maxLines = Int.MAX_VALUE
                binding.statusMessage.text = context.getQuantityString(
                    R.plurals.restore_preview_files_format, status.fileCount, status.fileCount,
                    status.size.asFileSize().formatHumanReadable(context)
                )
                val details = mutableListOf(
                    getString(
                        R.string.restore_preview_destination_format,
                        Environment.getExternalStorageDirectory().path
                    ),
                    getString(R.string.restore_conflict_rule)
                )
                vaultNotice(status.vaultFileCount)?.let { details += it }
                binding.statusDetail.text = details.joinToString("\n\n")
                binding.cancelButton.isVisible = true
                binding.cancelButton.setText(R.string.restore_choose_another_action)
                binding.cancelButton.setOnClickListener {
                    BackupStatusLiveData.value = BackupStatus.Idle
                    pickBackup()
                }
                binding.statusButton.isVisible = true
                binding.statusButton.setText(R.string.restore_confirm_action)
                binding.statusButton.setOnClickListener {
                    FileJobService.restoreBackup(
                        status.source, status.password, status.fileCount, status.size,
                        status.vaultFileCount, requireContext()
                    )
                }
            }
            is BackupStatus.RestoreRunning -> {
                showProgress(status.progress)
                binding.statusTitle.setText(R.string.restore_running_title)
                binding.statusMessage.maxLines = 1
                binding.statusMessage.text =
                    status.fileName ?: getString(R.string.backup_counting)
                binding.statusDetail.setText(R.string.restore_running_notice)
                binding.cancelButton.isVisible = true
                binding.cancelButton.setText(R.string.restore_cancel_action)
                binding.cancelButton.setOnClickListener {
                    FileJobService.cancelJob(status.jobId)
                    BackupStatusLiveData.value = BackupStatus.Idle
                }
            }
            is BackupStatus.RestoreDone -> {
                if (status.failed == 0) {
                    binding.statusIcon.setTintedCircleIcon(
                        R.drawable.check_icon_control_normal_24dp, R.color.home_downloads
                    )
                    animateDoneIcon(isChanged)
                } else {
                    binding.statusIcon.setTintedCircleIcon(
                        R.drawable.error_icon_white_24dp, R.color.home_trash
                    )
                }
                binding.statusTitle.setText(
                    if (status.failed == 0) {
                        R.string.restore_done_title
                    } else {
                        R.string.restore_done_with_problems_title
                    }
                )
                binding.statusMessage.maxLines = Int.MAX_VALUE
                binding.statusMessage.text = getString(
                    R.string.restore_counts_format, status.restored, status.skipped, status.failed
                )
                val details = mutableListOf<String>()
                if (status.skipped > 0) {
                    details += getString(R.string.restore_done_detail)
                }
                if (status.failed > 0) {
                    details += getString(R.string.restore_done_failed_detail)
                }
                vaultNotice(status.vaultSkipped)?.let { details += it }
                binding.statusDetail.text = details.joinToString("\n\n")
                binding.statusButton.isVisible = true
                binding.statusButton.setText(R.string.backup_done_action)
                binding.statusButton.setOnClickListener {
                    BackupStatusLiveData.value = BackupStatus.Idle
                }
            }
            is BackupStatus.RestoreFailed -> {
                binding.statusIcon.setTintedCircleIcon(
                    R.drawable.error_icon_white_24dp, R.color.home_trash
                )
                binding.statusTitle.setText(R.string.restore_failed_title)
                binding.statusMessage.maxLines = Int.MAX_VALUE
                binding.statusMessage.text = status.message
                val details = mutableListOf(
                    getString(
                        R.string.restore_counts_format, status.restored, status.skipped, status.failed
                    )
                )
                vaultNotice(status.vaultSkipped)?.let { details += it }
                binding.statusDetail.text = details.joinToString("\n\n")
                binding.statusButton.isVisible = true
                binding.statusButton.setText(
                    if (status.passwordRequired && status.source != null) {
                        R.string.restore_retry_password_action
                    } else {
                        R.string.restore_choose_another_action
                    }
                )
                binding.statusButton.setOnClickListener {
                    if (status.passwordRequired && status.source != null) {
                        BackupStatusLiveData.value =
                            BackupStatus.RestoreNeedsPassword(status.source)
                    } else {
                        BackupStatusLiveData.value = BackupStatus.Idle
                        pickBackup()
                    }
                }
            }
        }
        binding.statusDetail.isVisible = !binding.statusDetail.text.isNullOrEmpty()
    }

    private fun showProgress(progress: Int?) {
        // The indicator refuses to change mode while it is visible.
        binding.progress.isVisible = false
        binding.progress.isIndeterminate = progress == null
        if (progress == null) {
            binding.percentText.text = null
        } else {
            binding.progress.setProgressCompat(progress, true)
            binding.percentText.text =
                NumberFormat.getPercentInstance().format(progress / 100.0)
            binding.percentText.isVisible = true
        }
        binding.statusIcon.isVisible = false
        binding.progress.isVisible = true
    }

    private fun animateDoneIcon(animate: Boolean) {
        if (!animate) {
            return
        }
        binding.statusIcon.scaleX = 0.6f
        binding.statusIcon.scaleY = 0.6f
        binding.statusIcon.animate().scaleX(1f).scaleY(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator()).start()
    }

    private fun vaultNotice(count: Int): String? =
        count.takeIf { it > 0 }?.let {
            requireContext().getQuantityString(
                R.plurals.restore_vault_not_supported_format, it, it
            )
        }

    companion object {
        private const val KEY_VAULT = "vault"

        private const val PASSWORD_MIN_LENGTH = 8

        private const val SKIPPED_NAMES_MAX = 5

        private const val USB_POLL_INTERVAL_MILLIS = 1500L

        class RemovableSplit(val usb: Int?, val sd: Int?)

        /**
         * Which mounted removable volume is the USB drive and which the SD card, by the label Android gives them
         * ("SanDisk USB drive", "SD-Karte"). An unlabelled one counts as USB only while a USB drive is plugged in.
         */
        internal fun splitRemovable(descriptions: List<String>, massStorage: Boolean): RemovableSplit {
            var usb = descriptions.indexOfFirst { it.contains("USB", ignoreCase = true) }.takeIf { it >= 0 }
            val sdLabel = Regex("\\bSD\\b", RegexOption.IGNORE_CASE)
            var sd = descriptions.indices.firstOrNull { it != usb && sdLabel.containsMatchIn(descriptions[it]) }
            if (usb == null && massStorage) usb = descriptions.indices.firstOrNull { it != sd }
            if (sd == null) sd = descriptions.indices.firstOrNull { it != usb }
            return RemovableSplit(usb, sd)
        }

        private fun loadBackupItems(
            context: Context,
            standardDirectories: List<StandardDirectory>,
            extraFolders: Set<String>
        ): List<BackupItem> {
            val items = mutableListOf<BackupItem>()
            val vaultCount = try {
                Vault.entriesIfCreated().size
            } catch (e: IOException) {
                e.printStackTrace()
                0
            }
            if (vaultCount > 0) {
                items += BackupItem(
                    KEY_VAULT, R.drawable.lock_icon_white_24dp, R.color.home_vault,
                    context.getString(R.string.navigation_vault),
                    context.getQuantityString(
                        R.plurals.backup_vault_summary_format, vaultCount, vaultCount
                    )
                )
            }
            val storage = Environment.getExternalStorageDirectory()
            // Android/ holds app data the system blocks anyway; empty folders are noise.
            val folders = storage.listFiles { file ->
                file.isDirectory && !file.isHidden && file.name != "Android" &&
                    !file.list().isNullOrEmpty()
            }.orEmpty()
            items += folders.map { folder ->
                val standardDirectory = standardDirectories.find { it.relativePath == folder.name }
                BackupItem(
                    folder.path,
                    standardDirectory?.iconRes ?: R.drawable.directory_icon_white_24dp,
                    when (folder.name) {
                        Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES ->
                            R.color.home_images
                        Environment.DIRECTORY_MOVIES -> R.color.home_videos
                        Environment.DIRECTORY_MUSIC -> R.color.home_audio
                        Environment.DIRECTORY_DOCUMENTS -> R.color.home_documents
                        Environment.DIRECTORY_DOWNLOADS -> R.color.home_downloads
                        else -> R.color.home_archives
                    },
                    standardDirectory?.getTitle(context) ?: folder.name, null
                )
            }.sortedBy { it.title.lowercase() }
            for (path in extraFolders.sorted()) {
                if (File(path).isDirectory && folders.none { it.path == path }) {
                    items += BackupItem(
                        path, R.drawable.directory_icon_white_24dp, R.color.home_archives,
                        File(path).name,
                        // The path only says something new for a folder inside another one.
                        backupEntryName(path, storage.path).takeIf { it.contains('/') }
                    )
                }
            }
            return items
        }
    }
}

/** [key] is the folder path, or a fixed word for the vault. */
private class BackupItem(
    val key: String,
    val iconRes: Int,
    val colorRes: Int,
    val title: String,
    val summary: String?
)

/** The row is the control; its box, radio button or switch only shows the state. */
private fun View.reportCheckedState(button: CompoundButton) {
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfoCompat
        ) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.isCheckable = true
            info.isChecked = button.isChecked
        }
    })
}
