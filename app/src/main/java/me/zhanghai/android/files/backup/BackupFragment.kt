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
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import androidx.activity.result.contract.ActivityResultContracts
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
    private var hasUsb = false
    private var usbPulse: ObjectAnimator? = null

    private var lastStatus: BackupStatus? = null

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
        binding.otherIcon.setTintedCircleIcon(
            R.drawable.cloud_icon_white_24dp, R.color.home_documents
        )
        binding.encryptIcon.setTintedCircleIcon(R.drawable.lock_icon_white_24dp, R.color.home_vault)
        binding.usbRow.reportCheckedState(binding.usbRadio)
        binding.usbRow.setOnClickListener { selectTarget(true) }
        binding.otherRow.reportCheckedState(binding.otherRadio)
        binding.otherRow.setOnClickListener { selectTarget(false) }

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
        super.onDestroyView()

        usbPulse?.cancel()
        usbPulse = null
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

    private fun selectTarget(isUsb: Boolean) {
        binding.usbRadio.isChecked = isUsb
        binding.otherRadio.isChecked = !isUsb
        showError(null)
        updateUsb()
    }

    private fun updateUsb() {
        val context = requireContext()
        val volume = context.getSystemService<StorageManager>()!!.storageVolumesCompat.firstOrNull {
            it.isRemovableCompat && !it.isPrimaryCompat &&
                it.stateCompat == Environment.MEDIA_MOUNTED
        }
        usbVolume = volume
        val hadUsb = hasUsb
        // A drive Android cannot read yet (or at all) still shows up as a USB device.
        hasUsb = volume != null ||
            context.getSystemService<UsbManager>()?.deviceList?.values.orEmpty().any { device ->
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                }
            }
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
        val volume = usbVolume
        if (binding.usbRadio.isChecked && volume != null &&
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
        val isRunning = status is BackupStatus.Running
        if (!isRunning) {
            binding.progress.isVisible = false
        }
        binding.percentText.isVisible = isRunning
        binding.statusIcon.isVisible = !isRunning
        binding.cancelButton.isVisible = isRunning
        binding.statusButton.isVisible = !isRunning
        val context = requireContext()
        when (status) {
            is BackupStatus.Idle -> {}
            is BackupStatus.Running -> {
                if (status.progress < 0) {
                    // The indicator refuses to become indeterminate while it is visible.
                    binding.progress.isVisible = false
                    binding.progress.isIndeterminate = true
                    binding.percentText.text = null
                } else {
                    binding.progress.setProgressCompat(status.progress, true)
                    binding.percentText.text =
                        NumberFormat.getPercentInstance().format(status.progress / 100.0)
                }
                binding.progress.isVisible = true
                binding.statusTitle.setText(R.string.backup_running_title)
                binding.statusMessage.maxLines = 1
                binding.statusMessage.text =
                    status.fileName ?: getString(R.string.backup_counting)
                binding.statusDetail.setText(R.string.backup_running_notice)
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
                binding.statusButton.setText(R.string.backup_retry_action)
                binding.statusButton.setOnClickListener {
                    BackupStatusLiveData.value = BackupStatus.Idle
                }
            }
        }
        binding.statusDetail.isVisible = !binding.statusDetail.text.isNullOrEmpty()
    }

    companion object {
        private const val KEY_VAULT = "vault"

        private const val PASSWORD_MIN_LENGTH = 8

        private const val SKIPPED_NAMES_MAX = 5

        private const val USB_POLL_INTERVAL_MILLIS = 1500L

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
