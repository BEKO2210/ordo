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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialFadeThrough
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** Command in, plan preview out. Nothing changes on disk before the user taps the confirm button. */
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
        binding.headerIcon.setTintedCircleIcon(
            R.drawable.assistant_icon_white_24dp, R.color.home_assistant
        )
        binding.loadingText.setFactory {
            TextView(requireContext()).apply {
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            }
        }
        resources.getStringArray(R.array.ai_examples).forEach { example ->
            binding.exampleChips.addView(Chip(requireContext()).apply {
                this.text = example
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
            job?.cancel()
            showInput()
        }
        binding.editButton.setOnClickListener { showInput() }
        binding.confirmButton.setOnClickListener { execute() }
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as BottomSheetDialog
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.window?.apply {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply {
                    blurBehindRadius = (24 * resources.displayMetrics.density).toInt()
                }
            }
        }
        if (ready == null && job?.isActive != true) {
            binding.commandEdit.requestFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun submit() {
        text = binding.commandEdit.text.toString().trim()
        if (text.isEmpty()) {
            return
        }
        hideKeyboard()
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            show(binding.loadingLayout)
            val thinking = launch {
                val steps = resources.getStringArray(R.array.ai_thinking)
                for (step in steps) {
                    binding.loadingText.setText(step)
                    delay(2_500)
                }
            }
            val plan = try {
                CommandPlanner.plan(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                thinking.cancel()
                showMessage(R.string.ai_error_title, getString(R.string.ai_error_text))
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
        folders.forEach { folder ->
            binding.folderChips.addView(Chip(requireContext()).apply {
                this.text = folder.name
                setChipIconResource(R.drawable.directory_icon_white_24dp)
                setOnClickListener {
                    val plan = if (outcome.forDestination) {
                        outcome.plan.copy(destination = folder.path)
                    } else {
                        outcome.plan.copy(source = folder.path)
                    }
                    viewLifecycleOwner.lifecycleScope.launch { evaluate(plan) }
                }
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

    private fun showMessage(titleRes: Int, message: String) {
        ready = null
        binding.folderChips.removeAllViews()
        binding.messageTitle.setText(titleRes)
        binding.messageText.text = message
        show(binding.messageLayout)
        binding.exampleScroll.isVisible = true
    }

    private fun showPreview(outcome: Outcome.Ready) {
        ready = outcome
        val plan = outcome.plan
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
        val context = requireContext()
        binding.summaryText.text = context.getQuantityString(
            R.plurals.ai_summary, files.size, files.size,
            files.sumOf { it.length() }.asFileSize().formatHumanReadable(context)
        )
        val shown = files.take(FILES_SHOWN).joinToString("\n") { it.name }
        binding.fileListText.text = if (files.size > FILES_SHOWN) {
            val more = files.size - FILES_SHOWN
            shown + "\n" + context.getQuantityString(R.plurals.ai_more_files, more, more)
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
        val color = ContextCompat.getColor(context, colorRes)
        binding.confirmButton.text = getString(R.string.ai_confirm_format, getString(titleRes), files.size)
        binding.confirmButton.backgroundTintList = ColorStateList.valueOf(
            if (plan.action == "delete") color else ContextCompat.getColor(context, R.color.home_assistant)
        )
        show(binding.previewCard)
        binding.previewCard.apply {
            alpha = 0f
            translationY = 24 * resources.displayMetrics.density
            animate().alpha(1f).translationY(0f).setDuration(280).start()
        }
        for (i in 0 until binding.filterChips.childCount) {
            binding.filterChips.getChildAt(i).apply {
                alpha = 0f
                animate().alpha(1f).setStartDelay(120L + 50L * i).setDuration(200).start()
            }
        }
    }

    /** Every filter is a chip; closing one widens the plan and refreshes the preview. */
    private fun bindFilters(outcome: Outcome.Ready) {
        val plan = outcome.plan
        binding.filterChips.removeAllViews()
        fun chip(label: String, without: CommandPlan) {
            binding.filterChips.addView(Chip(requireContext()).apply {
                text = label
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
            chip(requireContext().getQuantityString(plurals, plan.olderThan, plan.olderThan),
                plan.copy(olderThan = 0))
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
                val placed = chosen.map { File(destination, it.name).nioPath() }
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
                    FileJobService.move(group.map { it.nioPath() }, target.nioPath(), context)
                    group.map { File(target, it.name).nioPath() }
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
        Snackbar.make(anchor, context.getQuantityString(done, chosen.size, chosen.size), UNDO_MILLIS)
            .setAction(R.string.ai_undo) { undo() }
            .show()
        dismiss()
    }

    private fun show(view: View) {
        TransitionManager.beginDelayedTransition(binding.contentLayout, MaterialFadeThrough())
        listOf(binding.loadingLayout, binding.messageLayout, binding.previewCard).forEach {
            it.isVisible = it == view
        }
        binding.exampleScroll.isVisible = false
    }

    private fun showInput() {
        ready = null
        TransitionManager.beginDelayedTransition(binding.contentLayout, MaterialFadeThrough())
        listOf(binding.loadingLayout, binding.messageLayout, binding.previewCard).forEach { it.isVisible = false }
        binding.exampleScroll.isVisible = true
        binding.commandEdit.requestFocus()
        binding.commandEdit.setSelection(binding.commandEdit.length())
        requireContext().getSystemService(InputMethodManager::class.java)
            .showSoftInput(binding.commandEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.commandEdit.windowToken, 0)
        binding.commandEdit.clearFocus()
    }

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

    private fun Context.getQuantityString(id: Int, quantity: Int, vararg args: Any): String =
        resources.getQuantityString(id, quantity, *args)

    companion object {
        const val TAG = "CommandSheet"
        private const val FILES_SHOWN = 5
        private const val UNDO_MILLIS = 8_000
        private val KINDS = CommandFiles.KINDS.keys + "other"
    }
}
