/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialFadeThrough
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ai.CommandCheck.Outcome
import me.zhanghai.android.files.ai.CommandCheck.Reason
import me.zhanghai.android.files.databinding.CommandSheetBinding
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.home.setTintedCircleIcon
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.trash.Trash
import me.zhanghai.android.files.util.valueCompat
import java.io.File

/**
 * The on-device AI: set up once (device check, download), then command in, plan preview out.
 * Nothing changes on disk before the user taps the confirm button.
 */
class CommandSheetFragment : BottomSheetDialogFragment() {
    private var _binding: CommandSheetBinding? = null
    private val binding get() = _binding!!

    private val root: File = Environment.getExternalStorageDirectory()
    private var job: Job? = null
    private var text = ""
    private var ready: Outcome.Ready? = null
    private var files: List<File> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = CommandSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.loadingText.setFactory {
            TextView(requireContext()).apply {
                gravity = Gravity.CENTER
                TextViewCompat.setTextAppearance(
                    this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
                )
            }
        }
        resources.getStringArray(R.array.ai_examples).forEach { example ->
            binding.exampleChips.addView(glassChip(example).apply {
                setOnClickListener {
                    binding.commandEdit.setText(example)
                    submit()
                }
            })
        }
        binding.commandLayout.setEndIconOnClickListener { submit() }
        binding.commandEdit.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_GO).also { if (it) submit() }
        }
        binding.cancelButton.setOnClickListener {
            CommandEngine.cancel()
            job?.cancel()
            showInput()
        }
        binding.editButton.setOnClickListener { showInput() }
        binding.confirmButton.setOnClickListener {
            it.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            execute()
        }
        binding.retryButton.setOnClickListener { if (text.isNotEmpty()) submit() else showInput() }
        if (CommandModel.state(requireContext()) == CommandModel.State.Ready) {
            showInput()
            warmUp()
        } else {
            showSetup()
        }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as BottomSheetDialog
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(R.drawable.ai_sheet_background)
        dialog.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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

    // ---- Setup -------------------------------------------------------------------------------

    private fun showSetup() {
        val context = requireContext()
        binding.titleText.setText(R.string.ai_setup_title)
        binding.subtitleText.setText(R.string.ai_setup_subtitle)
        binding.headerOrb.isVisible = false
        show(binding.setupLayout)
        val check = CommandModel.check(context)
        bindCheck(binding.checkRam, check.ramOk, getString(
            R.string.ai_check_ram, gigabytes(check.ramBytes), gigabytes(CommandModel.MIN_RAM)
        ))
        bindCheck(binding.checkSpace, check.spaceOk, getString(
            R.string.ai_check_space, gigabytes(check.freeBytes), gigabytes(CommandModel.SIZE + 500_000_000L)
        ))
        bindCheck(binding.checkDevice, check.is64Bit, getString(
            if (check.is64Bit) R.string.ai_check_device_ok else R.string.ai_check_device_bad
        ))
        listOf(binding.setupPrivacy, binding.checkRam, binding.checkSpace, binding.checkDevice)
            .forEachIndexed { i, row -> row.reveal(80L + 90L * i) }
        if (!check.ok) {
            binding.setupButton.isEnabled = false
            binding.setupButton.text = getString(R.string.ai_setup_not_enough)
            binding.setupButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ai_card))
            binding.setupButton.setTextColor(
                MaterialColors.getColor(binding.setupButton, android.R.attr.textColorSecondary)
            )
            return
        }
        binding.setupButton.text = getString(R.string.ai_setup_download, gigabytes(CommandModel.SIZE))
        binding.setupButton.setOnClickListener {
            CommandModel.startDownload(context)
            followDownload()
        }
        if (CommandModel.state(context) != CommandModel.State.Missing) {
            followDownload()
        }
    }

    private fun bindCheck(row: TextView, ok: Boolean, label: String) {
        row.text = label
        val color = ContextCompat.getColor(requireContext(), if (ok) R.color.ai_accent else R.color.ai_delete)
        // Only the status mark is colored; the row's own icon keeps the neutral tint from the style.
        val mark = ContextCompat.getDrawable(
            requireContext(), if (ok) R.drawable.ai_check_icon_white_24dp else R.drawable.ai_cross_icon_white_24dp
        )!!.mutate().apply { setTint(color) }
        val icon = row.compoundDrawablesRelative[0]?.mutate()?.apply {
            setTint(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        }
        TextViewCompat.setCompoundDrawableTintList(row, null)
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, mark, null)
    }

    /** Polls DownloadManager while the sheet is open; the download itself runs on without it. */
    private fun followDownload() {
        val context = requireContext()
        binding.setupButton.isVisible = false
        binding.downloadProgress.isVisible = true
        binding.downloadText.isVisible = true
        binding.setupOrb.thinking = true
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                when (val state = withContext(Dispatchers.IO) { CommandModel.state(context) }) {
                    is CommandModel.State.Downloading -> {
                        val permille = (state.bytes * 1000 / state.total).toInt()
                        binding.downloadProgress.setProgressCompat(permille, true)
                        binding.downloadText.text = if (state.paused) {
                            getString(R.string.ai_download_paused)
                        } else {
                            getString(
                                R.string.ai_download_progress, gigabytes(state.bytes), gigabytes(state.total),
                                permille / 10
                            )
                        }
                    }
                    CommandModel.State.Verifying -> {
                        binding.downloadProgress.isIndeterminate = true
                        binding.downloadText.setText(R.string.ai_verifying)
                        if (CommandModel.verify(context)) {
                            binding.downloadText.setText(R.string.ai_ready)
                            delay(700)
                            onModelReady()
                        } else {
                            downloadStopped(R.string.ai_verify_failed)
                        }
                        return@launch
                    }
                    CommandModel.State.Failed, CommandModel.State.Missing -> {
                        downloadStopped(R.string.ai_download_failed)
                        return@launch
                    }
                    CommandModel.State.Ready -> {
                        onModelReady()
                        return@launch
                    }
                }
                delay(500)
            }
        }
    }

    private fun downloadStopped(messageRes: Int) {
        binding.setupOrb.thinking = false
        binding.downloadProgress.isVisible = false
        binding.downloadText.setText(messageRes)
        binding.setupButton.isVisible = true
        binding.setupButton.setText(R.string.ai_setup_retry)
    }

    private fun onModelReady() {
        binding.titleText.setText(R.string.ai_title)
        binding.subtitleText.setText(R.string.ai_subtitle)
        binding.headerOrb.isVisible = true
        showInput()
        warmUp()
    }

    /** Loads the model while the user types, so the first answer does not pay for it. */
    private fun warmUp() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                CommandEngine.warmUp(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---- Command -----------------------------------------------------------------------------

    private fun submit() {
        text = binding.commandEdit.text.toString().trim()
        if (text.isEmpty()) {
            return
        }
        hideKeyboard()
        job?.cancel()
        val context = requireContext().applicationContext
        job = viewLifecycleOwner.lifecycleScope.launch {
            show(binding.loadingLayout)
            binding.thinkingOrb.thinking = true
            val thinking = launch {
                if (!CommandEngine.isLoaded) {
                    binding.loadingText.setText(getString(R.string.ai_starting))
                    while (!CommandEngine.isLoaded) delay(200)
                }
                for (step in resources.getStringArray(R.array.ai_thinking)) {
                    binding.loadingText.setText(step)
                    delay(2_200)
                }
            }
            val plan = try {
                CommandEngine.plan(context, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                thinking.cancel()
                showMessage(R.string.ai_start_failed_title, getString(R.string.ai_start_failed_text), retry = true)
                return@launch
            }
            thinking.cancel()
            if (plan == null) {
                showRefused(Reason.UNSUPPORTED)
            } else {
                evaluate(plan)
            }
        }
    }

    private suspend fun evaluate(plan: CommandPlan) {
        when (val outcome = CommandCheck.check(text, plan, null, root)) {
            is Outcome.Ready -> {
                if (outcome.plan.action == "delete" && !Trash.isEnabled) {
                    showMessage(
                        R.string.ai_refused_trash_off_title, getString(R.string.ai_refused_trash_off_text)
                    )
                    return
                }
                show(binding.loadingLayout)
                binding.loadingText.setText(getString(R.string.ai_finding_files))
                files = withContext(Dispatchers.IO) { CommandFiles.collect(outcome.plan, outcome.source) }
                if (files.isEmpty()) {
                    showMessage(
                        R.string.ai_no_files_title, getString(R.string.ai_no_files_text, display(outcome.source))
                    )
                } else {
                    showPreview(outcome)
                }
            }
            is Outcome.AskFolder -> showAsk(outcome)
            is Outcome.Refused -> showRefused(outcome.reason)
        }
    }

    private fun showAsk(outcome: Outcome.AskFolder) {
        showMessage(
            if (outcome.forDestination) R.string.ai_ask_destination else R.string.ai_ask_source,
            getString(R.string.ai_ask_text)
        )
        val folders = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        folders.forEachIndexed { i, folder ->
            binding.folderChips.addView(glassChip(folder.name).apply {
                setChipIconResource(R.drawable.directory_icon_white_24dp)
                chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ai_accent))
                setOnClickListener {
                    val plan = if (outcome.forDestination) {
                        outcome.plan.copy(destination = folder.path)
                    } else {
                        outcome.plan.copy(source = folder.path)
                    }
                    viewLifecycleOwner.lifecycleScope.launch { evaluate(plan) }
                }
                reveal(60L + 40L * i)
            })
        }
    }

    private fun showRefused(reason: Reason) {
        when (reason) {
            Reason.UNSUPPORTED -> showMessage(
                R.string.ai_refused_unsupported_title, getString(R.string.ai_refused_unsupported_text)
            )
            Reason.UNSURE_DELETE -> showMessage(
                R.string.ai_refused_delete_title, getString(R.string.ai_refused_delete_text)
            )
            Reason.PROTECTED_FOLDER -> showMessage(
                R.string.ai_refused_protected_title, getString(R.string.ai_refused_protected_text)
            )
        }
    }

    private fun showMessage(titleRes: Int, message: String, retry: Boolean = false) {
        ready = null
        binding.folderChips.removeAllViews()
        binding.messageTitle.setText(titleRes)
        binding.messageText.text = message
        binding.retryButton.isVisible = retry
        show(binding.messageLayout, withInput = true)
        binding.messageLayout.reveal(0)
    }

    private fun showPreview(outcome: Outcome.Ready) {
        ready = outcome
        val plan = outcome.plan
        val context = requireContext()
        val (titleRes, iconRes, colorRes) = when (plan.action) {
            "move" -> Triple(R.string.ai_action_move, R.drawable.move_icon_control_normal_24dp, R.color.home_documents)
            "copy" -> Triple(R.string.ai_action_copy, R.drawable.copy_icon_control_normal_24dp, R.color.home_downloads)
            "delete" -> Triple(R.string.ai_action_delete, R.drawable.delete_icon_white_24dp, R.color.home_trash)
            else -> Triple(R.string.ai_action_organize, R.drawable.sort_icon_control_normal_24dp, R.color.home_assistant)
        }
        binding.actionIcon.setTintedCircleIcon(iconRes, colorRes)
        binding.actionTitle.setText(titleRes)
        binding.routeText.text = when {
            outcome.destination != null ->
                getString(R.string.ai_route_from_to, display(outcome.source), display(outcome.destination))
            plan.action == "organize" -> getString(
                if (plan.groupBy == "month") R.string.ai_route_by_month else R.string.ai_route_by_type,
                display(outcome.source)
            )
            else -> getString(R.string.ai_route_from, display(outcome.source))
        }
        bindFilters(outcome)
        binding.summaryText.text = context.quantity(
            R.plurals.ai_summary, files.size, files.size,
            files.sumOf { it.length() }.asFileSize().formatHumanReadable(context)
        )
        val shown = files.take(FILES_SHOWN).joinToString("\n") { it.name }
        binding.fileListText.text = if (files.size > FILES_SHOWN) {
            val more = files.size - FILES_SHOWN
            shown + "\n" + context.quantity(R.plurals.ai_more_files, more, more)
        } else {
            shown
        }
        val note = when {
            plan.action == "delete" -> getString(
                R.string.ai_note_trash, Settings.TRASH_RETENTION_DAYS.valueCompat.toIntOrNull() ?: 30
            )
            outcome.newDestination -> getString(R.string.ai_note_new_folder, outcome.destination!!.name)
            else -> null
        }
        binding.noteText.isVisible = note != null
        binding.noteText.text = note
        val delete = plan.action == "delete"
        binding.confirmButton.text = getString(R.string.ai_confirm_format, getString(titleRes), files.size)
        binding.confirmButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (delete) R.color.ai_delete else R.color.ai_accent)
        )
        binding.confirmButton.setTextColor(
            ContextCompat.getColor(context, if (delete) R.color.ai_on_delete else R.color.ai_on_accent)
        )
        show(binding.previewCard, withInput = true)
        binding.previewCard.reveal(0)
        for (i in 0 until binding.filterChips.childCount) {
            binding.filterChips.getChildAt(i).reveal(140L + 50L * i)
        }
        binding.confirmButton.reveal(260)
    }

    /** Every filter is a chip; closing one widens the plan and refreshes the preview. */
    private fun bindFilters(outcome: Outcome.Ready) {
        val plan = outcome.plan
        binding.filterChips.removeAllViews()
        fun chip(label: String, without: CommandPlan) {
            binding.filterChips.addView(glassChip(label).apply {
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        files = withContext(Dispatchers.IO) { CommandFiles.collect(without, outcome.source) }
                        showPreview(outcome.copy(plan = without))
                    }
                }
            })
        }
        if (plan.cleanExtensions.isEmpty()) {
            plan.kinds.forEach { kind -> chip(kindName(kind), plan.copy(kinds = plan.kinds - kind)) }
        }
        plan.cleanExtensions.forEach { extension ->
            chip(getString(R.string.ai_filter_extension, extension),
                plan.copy(extensions = plan.extensions.filter { !it.trim('.', ' ').equals(extension, true) },
                    kinds = emptyList()))
        }
        if (plan.nameContains.isNotBlank()) {
            chip(getString(R.string.ai_filter_name, plan.nameContains.trim()), plan.copy(nameContains = ""))
        }
        if (plan.olderThan > 0) {
            val plurals = when (plan.ageUnit) {
                "weeks" -> R.plurals.ai_filter_older_weeks
                "months" -> R.plurals.ai_filter_older_months
                "years" -> R.plurals.ai_filter_older_years
                else -> R.plurals.ai_filter_older_days
            }
            chip(requireContext().quantity(plurals, plan.olderThan, plan.olderThan), plan.copy(olderThan = 0))
        }
        if (plan.largerThan > 0) {
            chip(getString(R.string.ai_filter_larger, plan.largerThan, plan.sizeUnit.uppercase()),
                plan.copy(largerThan = 0))
        }
        binding.filterChips.isVisible = binding.filterChips.childCount > 0
    }

    private fun execute() {
        val outcome = ready ?: return
        val plan = outcome.plan
        val context = requireContext().applicationContext
        val anchor = requireActivity().findViewById<View>(android.R.id.content)
        val chosen = files
        val source = outcome.source
        val undo: () -> Unit
        when (plan.action) {
            "move", "copy" -> {
                val destination = outcome.destination!!
                destination.mkdirs()
                // Undo only touches files this command put there, never a file that was there before.
                val placed = chosen.map { File(destination, it.name) }.filter { !it.exists() }.map { it.nioPath() }
                if (plan.action == "move") {
                    FileJobService.move(chosen.map { it.nioPath() }, destination.nioPath(), context)
                    undo = { FileJobService.move(placed, source.nioPath(), context) }
                } else {
                    FileJobService.copy(chosen.map { it.nioPath() }, destination.nioPath(), context)
                    undo = { FileJobService.delete(placed, context) }
                }
            }
            "delete" -> {
                FileJobService.delete(chosen.map { it.nioPath() }, context)
                val originals = chosen.map { it.path }.toSet()
                undo = {
                    FileJobService.restoreFromTrash(
                        Trash.entries().filter { it.originalPath in originals }, context
                    )
                }
            }
            else -> {
                val kindNames = KINDS.associateWith { kindName(it) }
                val groups = chosen.groupBy { CommandFiles.organizeFolder(it, plan.groupBy, kindNames) }
                val placed = groups.flatMap { (folder, group) ->
                    val target = File(source, folder).apply { mkdirs() }
                    val fresh = group.map { File(target, it.name) }.filter { !it.exists() }.map { it.nioPath() }
                    FileJobService.move(group.map { it.nioPath() }, target.nioPath(), context)
                    fresh
                }
                undo = { FileJobService.move(placed, source.nioPath(), context) }
            }
        }
        val done = when (plan.action) {
            "move" -> R.plurals.ai_done_move
            "copy" -> R.plurals.ai_done_copy
            "delete" -> R.plurals.ai_done_delete
            else -> R.plurals.ai_done_organize
        }
        // shortcut: undo right after confirming can race the running job; fine for a few files, a job
        // result callback is the upgrade when large moves need it.
        Snackbar.make(anchor, context.quantity(done, chosen.size, chosen.size), UNDO_MILLIS)
            .setAction(R.string.ai_undo) { undo() }
            .setActionTextColor(ContextCompat.getColor(context, R.color.ai_glow_1))
            .show()
        dismiss()
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun show(view: View, withInput: Boolean = false) {
        TransitionManager.beginDelayedTransition(binding.contentLayout, MaterialFadeThrough())
        listOf(binding.setupLayout, binding.loadingLayout, binding.messageLayout, binding.previewCard)
            .forEach { it.isVisible = it == view }
        binding.commandLayout.isVisible = withInput || view == binding.loadingLayout
        binding.exampleScroll.isVisible = view == binding.messageLayout
        binding.subtitleText.isVisible = view == binding.setupLayout
        binding.thinkingOrb.thinking = view == binding.loadingLayout
    }

    private fun showInput() {
        ready = null
        TransitionManager.beginDelayedTransition(binding.contentLayout, MaterialFadeThrough())
        listOf(binding.setupLayout, binding.loadingLayout, binding.messageLayout, binding.previewCard)
            .forEach { it.isVisible = false }
        binding.commandLayout.isVisible = true
        binding.exampleScroll.isVisible = true
        binding.subtitleText.isVisible = true
        for (i in 0 until binding.exampleChips.childCount) {
            binding.exampleChips.getChildAt(i).reveal(120L + 60L * i)
        }
        binding.commandEdit.requestFocus()
        binding.commandEdit.setSelection(binding.commandEdit.length())
        binding.commandEdit.post {
            requireContext().getSystemService(InputMethodManager::class.java)
                .showSoftInput(binding.commandEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.commandEdit.windowToken, 0)
        binding.commandEdit.clearFocus()
    }

    private fun glassChip(label: String): Chip = Chip(requireContext()).apply {
        text = label
        chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ai_card))
        chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ai_glass_stroke))
        chipStrokeWidth = resources.displayMetrics.density
        closeIconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ai_accent))
    }

    /** Fade and rise into place; skipped when the system turned animations off. */
    private fun View.reveal(startDelay: Long) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        alpha = 0f
        translationY = 16 * resources.displayMetrics.density
        animate().alpha(1f).translationY(0f).setStartDelay(startDelay).setDuration(320)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    private fun gigabytes(bytes: Long): String = Formatter.formatShortFileSize(requireContext(), bytes)

    private fun display(folder: File): String =
        if (folder == root) getString(R.string.ai_internal_storage) else folder.path.removePrefix(root.path + "/")

    private fun kindName(kind: String): String = getString(
        when (kind) {
            "audio" -> R.string.ai_kind_audio
            "video" -> R.string.ai_kind_video
            "image" -> R.string.ai_kind_image
            "document" -> R.string.ai_kind_document
            "archive" -> R.string.ai_kind_archive
            else -> R.string.ai_kind_other
        }
    )

    private fun File.nioPath() = Paths.get(path)

    private fun Context.quantity(id: Int, quantity: Int, vararg args: Any): String =
        resources.getQuantityString(id, quantity, *args)

    companion object {
        const val TAG = "CommandSheet"
        private const val FILES_SHOWN = 5
        private const val UNDO_MILLIS = 8_000
        private val KINDS = CommandFiles.KINDS.keys + "other"
    }
}
