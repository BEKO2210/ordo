/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.os.Environment
import android.text.format.Formatter
import android.view.Gravity
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.analysis.AnalysisActivity
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.ShowFileListLiveData
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The on-device AI: set up once (device check, download), then command in, plan preview out.
 * Nothing changes on disk before the user taps the confirm button.
 */
class CommandSheetFragment : BottomSheetDialogFragment() {
    private var _binding: CommandSheetBinding? = null
    private val binding get() = _binding!!

    private val root: File = Environment.getExternalStorageDirectory()
    private var job: Job? = null
    private var executeGeneration = 0
    private var text = ""
    private var ready: Outcome.Ready? = null
    private var files: List<File> = emptyList()
    // Size and time of [files] when the preview was made: the runner skips a file that changed since.
    private var seen: Map<String, Pair<Long, Long>> = emptyMap()
    private lateinit var design: CommandDesign
    // What the net left out or doubts about the current plan, said in the preview or under the list (net designs).
    private var netNote: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = CommandSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        design = CommandDesign.current(requireContext())
        binding.loadingText.setFactory {
            TextView(requireContext()).apply {
                gravity = Gravity.CENTER
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                TextViewCompat.setTextAppearance(
                    this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
                )
            }
        }
        // While the preview is on, two of the new kinds of commands come first; otherwise the examples as before.
        val preview = if (design === CommandDesign.Guarded) resources.getStringArray(R.array.ai_examples_preview).toList() else emptyList()
        (preview + resources.getStringArray(R.array.ai_examples)).forEach { example ->
            binding.exampleChips.addView(glassChip(example).apply {
                setOnClickListener {
                    binding.commandEdit.setText(example)
                    submit()
                }
            })
        }
        binding.commandLayout.setEndIconOnClickListener { submit() }
        // Wraps like a multi-line field, but the keyboard sees one line and shows its "Go" key.
        binding.commandEdit.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        binding.commandEdit.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            (actionId == EditorInfo.IME_ACTION_GO || enter).also { if (it) submit() }
        }
        binding.removeModelButton.setOnClickListener { confirmRemoveModel() }
        // Set once, before any state shows them: the sheet can open on a download already waiting for Wi-Fi, where
        // "use mobile data" is shown without the setup page having run.
        binding.setupButton.setOnClickListener { startDownload(mobileData = false) }
        binding.mobileDataButton.setOnClickListener { startDownload(mobileData = true) }
        binding.cancelButton.setOnClickListener {
            CommandEngine.cancel()
            job?.cancel()
            showInput()
        }
        binding.editButton.setOnClickListener { showInput() }
        viewLifecycleOwner.lifecycleScope.launch {
            CommandUndo.offers.collect { offer ->
                if (offer != null) binding.root.post { if (_binding != null) showQueuedResult() }
            }
        }
        binding.confirmButton.setOnClickListener {
            it.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            val outcome = ready
            if (outcome != null && CommandCheck.needsSecondConfirm(outcome.plan, outcome.source, root, files.size)) {
                confirmLargeDelete(outcome)
            } else {
                execute()
            }
        }
        binding.retryButton.setOnClickListener { if (text.isNotEmpty()) submit() else showInput() }
        // Debug builds only (DebugAiActivity, e2e tests): a model answer given from outside, so the rules, checks,
        // lists and hints run without the model. BuildConfig.DEBUG is a constant, so release builds drop this.
        val injected = if (BuildConfig.DEBUG) arguments?.getString(ARG_MODEL_ANSWER) else null
        if (injected != null && state == null) {
            // The net designs take the answer as it is; the older ones are tested with K+R as before.
            if (design.decide("", null, emptyList(), LocalDateTime.now()) { 0 } == null) design = CommandDesign.KR
            text = arguments?.getString(ARG_TEXT).orEmpty()
            binding.commandEdit.setText(text)
            binding.removeModelButton.isVisible = false
            answer { injected }
            return
        }
        // Opened with a ready plan (e.g. an analysis suggestion): no model needed, straight to the preview.
        // Arguments survive rotation and process death; a chip edit from this visit wins over them.
        val argumentPlan = arguments?.getString(ARG_PLAN)?.let(CommandPlan::parse)
        val presetPlan = if (argumentPlan != null) {
            state?.getString(STATE_PLAN)?.let(CommandPlan::parse) ?: argumentPlan
        } else {
            null
        }
        val restoredText = state?.getString(STATE_TEXT)
        val restoredPending = state?.getBoolean(STATE_PENDING) == true
        when (CommandUndo.restore(
            undoPending = CommandUndo.offers.value != null,
            commandRunning = CommandUndo.running,
            presetPlan = presetPlan != null,
            restoredPending = restoredPending,
        )) {
            CommandUndo.Restore.ShowPendingUndo, CommandUndo.Restore.WaitForRunningCommand -> {
                if (!restoredText.isNullOrEmpty()) binding.commandEdit.setText(restoredText)
                show(binding.loadingLayout)
                binding.cancelButton.isVisible = false
                binding.loadingText.setText(getString(R.string.ai_running))
                return
            }
            CommandUndo.Restore.EvaluatePreset -> {
                val plan = presetPlan ?: return
                text = restoredText?.takeIf { it.isNotEmpty() }
                    ?: arguments?.getString(ARG_TEXT).orEmpty()
                binding.commandEdit.setText(text)
                binding.removeModelButton.isVisible = false
                viewLifecycleOwner.lifecycleScope.launch { evaluate(plan) }
                return
            }
            CommandUndo.Restore.ResumePendingText, CommandUndo.Restore.Fresh -> Unit
        }
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val modelState = withContext(Dispatchers.IO) { CommandModel.state(context, design) }
            if (modelState == CommandModel.State.Ready) {
                showInput()
                warmUp()
                // After a rotation, pick up where the user was instead of starting over.
                if (!restoredText.isNullOrEmpty()) {
                    binding.commandEdit.setText(restoredText)
                    if (restoredPending) submit()
                }
            } else {
                showSetup(modelState)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let {
            outState.putString(STATE_TEXT, it.commandEdit.text.toString())
            outState.putBoolean(STATE_PENDING, ready != null || it.loadingLayout.isVisible)
        }
        ready?.plan?.toJson()?.let { outState.putString(STATE_PLAN, it) }
    }

    private var blurListener: Consumer<Boolean>? = null

    override fun onStart() {
        super.onStart()
        val dialog = dialog as BottomSheetDialog
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(R.drawable.ai_sheet_background)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val windowManager = requireContext().getSystemService(WindowManager::class.java)
            applyBlur(windowManager.isCrossWindowBlurEnabled)
            // Blur can go away while the sheet is open, e.g. when battery saver turns on.
            val listener = Consumer<Boolean> { applyBlur(it) }
            windowManager.addCrossWindowBlurEnabledListener(listener)
            blurListener = listener
        } else {
            applyBlur(false)
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { requireContext().getSystemService(WindowManager::class.java).removeCrossWindowBlurEnabledListener(it) }
            blurListener = null
        }
        super.onStop()
    }

    /** Glass with blur behind it; opaque without, because the glass alone lets the text below shine through sharp. */
    private fun applyBlur(blur: Boolean) {
        val dialog = dialog ?: return
        val glass = ContextCompat.getColor(requireContext(), R.color.ai_sheet_glass)
        (dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background?.mutate() as? GradientDrawable)
            ?.setColor(if (blur) glass else ColorUtils.setAlphaComponent(glass, 255))
        val window = dialog.window ?: return
        if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = (28 * resources.displayMetrics.density).toInt() }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    override fun onDestroyView() {
        // Do not keep the cores busy for an answer nobody will see.
        if (job?.isCompleted == false) CommandEngine.cancel()
        super.onDestroyView()
        _binding = null
    }

    // ---- Setup -------------------------------------------------------------------------------

    private fun showSetup(modelState: CommandModel.State) {
        val context = requireContext()
        binding.titleText.setText(R.string.ai_setup_title)
        binding.subtitleText.setText(R.string.ai_setup_subtitle)
        binding.headerOrb.isVisible = false
        binding.removeModelButton.isVisible = false
        show(binding.setupLayout)
        // A download that already runs (or a file waiting for its check) always gets followed,
        // whatever the free space says now.
        if (modelState != CommandModel.State.Missing && modelState !is CommandModel.State.Failed) {
            followDownload()
            return
        }
        val check = CommandModel.check(context, design)
        bindCheck(binding.checkRam, check.ramOk, getString(
            R.string.ai_check_ram, gigabytes(check.ramBytes), gigabytes(CommandModel.MIN_RAM)
        ))
        bindCheck(binding.checkSpace, check.spaceOk, getString(
            R.string.ai_check_space, gigabytes(check.freeBytes),
            gigabytes(check.needBytes + CommandModel.SPARE_SPACE)
        ))
        bindCheck(binding.checkDevice, check.is64Bit && check.dotProd, getString(when {
            !check.is64Bit -> R.string.ai_check_device_bad
            !check.dotProd -> R.string.ai_check_device_old
            else -> R.string.ai_check_device_ok
        }))
        listOf(binding.setupPrivacy, binding.checkRam, binding.checkSpace, binding.checkDevice)
            .forEachIndexed { i, row -> row.reveal(80L + 90L * i) }
        if (modelState is CommandModel.State.Failed) {
            binding.downloadText.isVisible = true
            binding.downloadText.setText(
                when {
                    modelState.noSpace -> R.string.ai_download_no_space
                    modelState.notFound -> R.string.ai_download_not_published
                    else -> R.string.ai_download_failed
                }
            )
        }
        if (!check.ok) {
            binding.setupButton.isEnabled = false
            binding.setupButton.text = getString(R.string.ai_setup_not_enough)
            binding.setupButton.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ai_card))
            binding.setupButton.setTextColor(
                MaterialColors.getColor(binding.setupButton, android.R.attr.textColorSecondary)
            )
            binding.mobileDataButton.isVisible = false
            return
        }
        binding.setupButton.text = getString(R.string.ai_setup_download, gigabytes(CommandModel.missingBytes(context, design)))
        binding.mobileDataButton.isVisible = true
    }

    private fun startDownload(mobileData: Boolean) {
        val context = requireContext().applicationContext
        if (CommandModel.startDownload(context, mobileData, design)) {
            followDownload()
        } else {
            downloadStopped(R.string.ai_download_failed)
        }
    }

    private fun bindCheck(row: TextView, ok: Boolean, label: String) {
        row.text = label
        row.contentDescription = label + ", " + getString(if (ok) R.string.ai_check_passed else R.string.ai_check_failed)
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
        TransitionManager.beginDelayedTransition(binding.contentLayout, MaterialFadeThrough())
        // The checks are done; from here the sphere carries the progress.
        binding.setupPrivacy.isVisible = false
        binding.checkList.isVisible = false
        binding.setupButton.isVisible = false
        binding.mobileDataButton.isVisible = false
        binding.downloadProgress.isVisible = true
        binding.downloadProgress.isIndeterminate = false
        binding.downloadText.isVisible = true
        binding.downloadText.setText(R.string.ai_download_starting)
        binding.phaseText.isVisible = true
        binding.phaseText.setText(R.string.ai_phase_download)
        binding.percentText.isVisible = true
        binding.percentText.text = percent(0)
        binding.setupOrb.thinking = true
        binding.setupOrb.progress = 0f
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            var lastBytes = -1L
            var lastTime = 0L
            var rate = 0.0
            while (isActive) {
                when (val state = withContext(Dispatchers.IO) { CommandModel.state(context, design) }) {
                    is CommandModel.State.Downloading -> {
                        val now = System.nanoTime()
                        if (lastBytes >= 0 && state.bytes > lastBytes) {
                            val sample = (state.bytes - lastBytes) / ((now - lastTime) / 1e9)
                            rate = if (rate == 0.0) sample else rate * 0.8 + sample * 0.2
                        }
                        lastBytes = state.bytes
                        lastTime = now
                        val fraction = state.bytes.toFloat() / state.total
                        binding.setupOrb.progress = fraction
                        binding.percentText.text = percent((fraction * 100).toInt())
                        binding.downloadProgress.setProgressCompat((fraction * 1000).toInt(), true)
                        binding.mobileDataButton.isVisible = state.waitingForWifi
                        binding.downloadText.text = when {
                            state.waitingForWifi -> getString(R.string.ai_download_waiting_wifi)
                            state.paused -> getString(R.string.ai_download_paused)
                            rate <= 0.0 -> getString(R.string.ai_download_starting)
                            else -> getString(
                                R.string.ai_download_progress_rate, gigabytes(state.bytes), gigabytes(state.total),
                                gigabytes(rate.toLong()), eta(((state.total - state.bytes) / rate).toLong())
                            )
                        }
                    }
                    CommandModel.State.Verifying -> {
                        binding.phaseText.setText(R.string.ai_phase_verify)
                        binding.setupOrb.progress = -1f
                        binding.percentText.isVisible = false
                        binding.downloadProgress.isIndeterminate = true
                        binding.downloadText.setText(R.string.ai_verifying)
                        if (CommandModel.verify(context, design)) {
                            binding.phaseText.setText(R.string.ai_phase_ready)
                            binding.setupOrb.thinking = false
                            binding.setupOrb.celebrate()
                            binding.percentText.text = "✓"
                            binding.percentText.isVisible = true
                            binding.downloadProgress.isIndeterminate = false
                            binding.downloadProgress.setProgressCompat(1000, true)
                            binding.downloadText.setText(R.string.ai_ready)
                            binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            delay(1_400)
                            onModelReady()
                        } else {
                            downloadStopped(R.string.ai_verify_failed)
                        }
                        return@launch
                    }
                    is CommandModel.State.Failed -> {
                        downloadStopped(when {
                            state.noSpace -> R.string.ai_download_no_space
                            state.notFound -> R.string.ai_download_not_published
                            else -> R.string.ai_download_failed
                        })
                        return@launch
                    }
                    CommandModel.State.Missing -> {
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
        binding.setupOrb.progress = -1f
        binding.percentText.isVisible = false
        binding.phaseText.isVisible = false
        binding.downloadProgress.isVisible = false
        binding.downloadText.setText(messageRes)
        binding.setupButton.isVisible = true
        binding.setupButton.setText(R.string.ai_setup_retry)
        binding.mobileDataButton.isVisible = true
    }

    private fun confirmRemoveModel() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_remove_title)
            .setMessage(getString(R.string.ai_remove_text, gigabytes(CommandModel.parts(design).sumOf { it.size })))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_remove_confirm) { _, _ ->
                val context = requireContext().applicationContext
                val anchor = requireActivity().findViewById<View>(android.R.id.content)
                UNDO_SCOPE.launch {
                    CommandModel.remove(context)
                    withContext(Dispatchers.Main) {
                        Snackbar.make(anchor, R.string.ai_removed, Snackbar.LENGTH_SHORT).show()
                    }
                }
                dismiss()
            }
            .show()
    }

    private fun percent(value: Int): String = String.format(java.util.Locale.getDefault(), "%d %%", value)

    private fun eta(seconds: Long): String = if (seconds >= 60) {
        requireContext().quantity(R.plurals.ai_eta_minutes, ((seconds + 59) / 60).toInt(), ((seconds + 59) / 60).toInt())
    } else {
        requireContext().quantity(R.plurals.ai_eta_seconds, seconds.toInt(), seconds.toInt())
    }

    private fun onModelReady() {
        binding.titleText.setText(R.string.ai_title)
        binding.subtitleText.setText(R.string.ai_subtitle)
        binding.headerOrb.isVisible = true
        binding.removeModelButton.isVisible = true
        showInput()
        warmUp()
    }

    /** Loads the model while the user types, so the first answer does not pay for it. */
    private fun warmUp() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                CommandEngine.warmUp(context, design)
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
        netNote = null
        if (job?.isActive == true) CommandEngine.cancel()
        job?.cancel()
        val context = requireContext().applicationContext
        answer { CommandEngine.answer(context, text, design) }
    }

    /** Router first (no model), then the model's plan put right by the rules, then the usual checks. */
    private fun answer(model: suspend () -> String) {
        design.route(text)?.let { route ->
            // Posted: this can run while the sheet is still being created, too early to open a screen or dismiss.
            binding.root.post { if (_binding != null) onRoute(route) }
            return
        }
        job = viewLifecycleOwner.lifecycleScope.launch {
            show(binding.loadingLayout)
            binding.cancelButton.isVisible = true
            binding.thinkingOrb.thinking = true
            // Net designs: what Ordo cannot do or cannot know is answered before the model.
            val folders = if (design === CommandDesign.Today) emptyList() else withContext(Dispatchers.IO) { folderNames() }
            val now = LocalDateTime.now()
            // one count per decision: each source listed once, closing the sheet stops it
            val running = currentCoroutineContext()[Job]
            val counter = CommandNetCount(root, text, active = { running?.isActive != false })
            val before = withContext(Dispatchers.IO) { netDecide(null, folders, now, counter) }
            if (before is NetOutcome.Refuse) {
                onNet(before)
                return@launch
            }
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
            var net: NetOutcome? = null
            val plan = try {
                val answer = model()
                net = withContext(Dispatchers.IO) { netDecide(answer, folders, now, counter) }
                // The rules read folder names after "aus/nach"; the shipped design has no rules and needs none.
                if (net == null) CommandPlan.parse(answer)?.let { design.fix(text, it, folders) } else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                thinking.cancel()
                showMessage(R.string.ai_start_failed_title, getString(R.string.ai_start_failed_text), retry = true)
                return@launch
            }
            thinking.cancel()
            when {
                net != null -> onNet(net)
                plan == null -> showRefused(Reason.UNSUPPORTED)
                else -> evaluate(plan)
            }
        }
    }

    /**
     * The net design's answer (null for the older designs). A command the net cannot read at all (a date that does
     * not exist, a number too large: the lab's Python stops there too) is asked back, never guessed.
     */
    private fun netDecide(answer: String?, folders: List<String>, now: LocalDateTime, counter: CommandNetCount): NetOutcome? = try {
        val started = SystemClock.elapsedRealtime()
        design.decide(text, answer, folders, now, counter::count).also {
            // e2e/ai-guarded.sh reads the counting time; no command text in the log
            if (BuildConfig.DEBUG && answer != null && it != null) {
                Log.i(TAG, "net decision in ${SystemClock.elapsedRealtime() - started} ms, ${counter.listings} folders listed")
            }
        }
    } catch (e: CancellationException) {
        if (BuildConfig.DEBUG) Log.i(TAG, "net counting stopped: the sheet was closed")
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "net could not read the command", e)
        NetOutcome.Ask("model", emptyList())
    }

    private suspend fun onNet(outcome: NetOutcome) {
        when (outcome) {
            is NetOutcome.Plan -> {
                if (outcome.warnings.isNotEmpty()) Log.i(TAG, "net warnings: ${outcome.warnings}")
                netNote = netNote(outcome)
                if (outcome.plan.action == "none") {
                    // chat, or something no file command does: say what Ordo can do, "show" included
                    showMessage(R.string.ai_refused_unsupported_title, getString(R.string.ai_net_none))
                } else {
                    evaluate(outcome.plan, paths = true)
                }
            }
            is NetOutcome.Ask -> showNetAsk(outcome)
            is NetOutcome.Refuse -> showNetRefusal(outcome.reason)
        }
    }

    /** A filter word as the user typed it (the net keeps folded stems). */
    private fun typed(term: String) = CommandNetPlan.typed(text, term)

    /** Words the net left out and a count that does not fit, in words; null when there is nothing to say. */
    private fun netNote(outcome: NetOutcome.Plan): String? {
        val quote = { words: List<String> ->
            val q = words.map { typed(it) }.distinct().map { getString(R.string.ai_net_quoted, it) }
            // „Telekom“, „Vodafone“ und „Rechnung“
            if (q.size < 2) q.joinToString() else q.dropLast(1).joinToString(", ") + getString(R.string.ai_net_and) + q.last()
        }
        val kept = outcome.plan.nameTerms + outcome.plan.textTerms
        val dropped = outcome.dropped.takeIf { it.isNotEmpty() }?.let {
            if (kept.isEmpty()) getString(R.string.ai_net_dropped_all, quote(it))
            else getString(R.string.ai_net_dropped, quote(kept + it), quote(kept))
        }
        // rules2's warning reads "count 3 said, 5 matched"
        val count = outcome.warnings.firstNotNullOfOrNull { Regex("count (\\d+) said, (\\d+) matched").find(it) }?.let {
            getString(R.string.ai_net_count_warning, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        return listOfNotNull(dropped, count).joinToString("\n").ifEmpty { null }
    }

    /** A question back from the net: friendly, with the typed command kept above so it can be refined. */
    private fun showNetAsk(outcome: NetOutcome.Ask) {
        val arg = outcome.args.firstOrNull().orEmpty()
        val question = when (outcome.reason) {
            "no_source" -> getString(R.string.ai_net_ask_no_source)
            "no_dest" -> getString(R.string.ai_net_ask_no_dest)
            "ambiguous_folder" -> getString(R.string.ai_net_ask_ambiguous_folder, arg)
            "unknown_folder" -> getString(R.string.ai_net_ask_unknown_folder, arg)
            "uncovered" -> getString(R.string.ai_net_ask_uncovered, arg)
            "no_reading" -> getString(R.string.ai_net_ask_no_reading, arg)
            "vague_time" -> getString(R.string.ai_net_ask_vague_time, arg)
            "vague_size" -> getString(R.string.ai_net_ask_vague_size, arg)
            else -> getString(R.string.ai_net_ask_model)
        }
        showMessage(R.string.ai_net_ask_title, question + "\n\n" + getString(R.string.ai_net_ask_refine))
        binding.messageIcon.setTintedCircleIcon(R.drawable.ai_hint_icon_white_24dp, R.color.ai_accent)
        binding.messageIcon.isVisible = true
        popIcon()
    }

    /** Something Ordo cannot do or cannot know: the hint style of the older designs. */
    private fun showNetRefusal(reason: String) {
        val title = if (reason == "memory") R.string.ai_hint_title else R.string.ai_refused_unsupported_title
        hint(getString(when (reason) {
            "memory" -> R.string.ai_net_refuse_memory
            "archive" -> R.string.ai_net_refuse_archive
            "convert" -> R.string.ai_net_refuse_convert
            "rename" -> R.string.ai_net_refuse_rename
            "share" -> R.string.ai_net_refuse_share
            "open" -> R.string.ai_net_refuse_open
            "restore" -> R.string.ai_net_refuse_restore
            else -> R.string.ai_net_refuse_edit
        }), title)
    }

    private suspend fun evaluate(plan: CommandPlan, paths: Boolean = false) {
        if (plan.action == "show") {
            showResults(plan)
            return
        }
        when (val outcome = withContext(Dispatchers.IO) { CommandCheck.check(text, plan, null, root, paths) }) {
            is Outcome.Ready -> {
                if (outcome.plan.action == "delete" && !Trash.isEnabled) {
                    showMessage(
                        R.string.ai_refused_trash_off_title, getString(R.string.ai_refused_trash_off_text)
                    )
                    return
                }
                show(binding.loadingLayout)
                binding.loadingText.setText(getString(R.string.ai_finding_files))
                withContext(Dispatchers.IO) { collect(outcome.plan, outcome.source) }
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
            getString(if (outcome.candidates.isEmpty()) R.string.ai_ask_text else R.string.ai_ask_which)
        )
        val folders = outcome.candidates.ifEmpty {
            root.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
        folders.forEachIndexed { i, folder ->
            binding.folderChips.addView(glassChip(display(folder)).apply {
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

    private fun collect(plan: CommandPlan, source: File) {
        val found = CommandFiles.collect(plan, source)
        seen = CommandFiles.snapshot(found)
        files = found
    }

    private fun showMessage(titleRes: Int, message: String, retry: Boolean = false) {
        ready = null
        binding.folderChips.removeAllViews()
        binding.messageIcon.isVisible = false
        binding.hintActionButton.isVisible = false
        binding.hintDismissText.isVisible = false
        binding.messageLayout.setOnClickListener(null)
        binding.messageLayout.isClickable = false
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
        val note = listOfNotNull(netNote, when {
            plan.action == "delete" -> getString(
                R.string.ai_note_trash, Settings.TRASH_RETENTION_DAYS.valueCompat.toIntOrNull() ?: 30
            )
            outcome.newDestination -> getString(R.string.ai_note_new_folder, outcome.destination!!.name)
            else -> null
        }).joinToString("\n\n").ifEmpty { null }
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
                    // the net's note was about the plan it made; after the user widened it, it may no longer apply
                    netNote = null
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { collect(without, outcome.source) }
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
        if (plan.duplicates) {
            chip(getString(R.string.ai_filter_duplicates), plan.copy(duplicates = false))
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
        if (plan.contentContains.isNotBlank()) {
            chip(getString(R.string.ai_filter_word, plan.contentContains.trim()), plan.copy(contentContains = ""))
        }
        if (plan.today) {
            chip(getString(R.string.ai_filter_today), plan.copy(today = false))
        }
        if (plan.newerThan > 0) {
            val plurals = when (plan.ageUnit) {
                "weeks" -> R.plurals.ai_filter_newer_weeks
                "months" -> R.plurals.ai_filter_newer_months
                "years" -> R.plurals.ai_filter_newer_years
                else -> R.plurals.ai_filter_newer_days
            }
            chip(requireContext().quantity(plurals, plan.newerThan, plan.newerThan), plan.copy(newerThan = 0))
        }
        plan.nameTerms.forEach { chip(getString(R.string.ai_filter_name, typed(it)), plan.copy(nameTerms = plan.nameTerms - it)) }
        plan.textTerms.forEach { chip(getString(R.string.ai_filter_word, typed(it)), plan.copy(textTerms = plan.textTerms - it)) }
        dateRange(plan)?.let { chip(it, plan.copy(dateFrom = "", dateTo = "")) }
        sizeRange(plan)?.let { chip(it, plan.copy(minBytes = 0, maxBytes = 0)) }
        exclusion(plan)?.let {
            chip(it, plan.copy(excludeKinds = emptyList(), excludeExtensions = emptyList(), excludeTerms = emptyList()))
        }
        if (plan.limit > 0) {
            chip(requireContext().quantity(limitPlurals(plan.sort), plan.limit, plan.limit), plan.copy(limit = 0))
        }
        binding.filterChips.isVisible = binding.filterChips.childCount > 0
    }

    /** A whole top-level folder or many files: a second, explicit confirmation with count and size. */
    private fun confirmLargeDelete(outcome: Outcome.Ready) {
        val context = requireContext()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(context.quantity(R.plurals.ai_large_delete_title, files.size, files.size))
            .setMessage(getString(R.string.ai_large_delete_text, display(outcome.source),
                files.sumOf { it.length() }.asFileSize().formatHumanReadable(context)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_action_delete) { _, _ -> execute() }
            .show()
    }

    private fun execute() {
        val outcome = ready ?: return
        val plan = outcome.plan
        val context = requireContext().applicationContext
        val chosen = files
        val chosenSeen = seen
        val kindNames = KINDS.associateWith { kindName(it) }
        val runner = runner(context, outcome)
        val generation = ++executeGeneration
        job?.cancel()
        show(binding.loadingLayout)
        binding.cancelButton.isVisible = false
        binding.loadingText.setText(getString(R.string.ai_running))
        CommandUndo.running = true
        job = UNDO_SCOPE.launch {
            try {
                val journal = runner.run(plan, outcome.source, outcome.destination, chosen, kindNames, chosenSeen)
                scan(context, journal)
                val done = when (plan.action) {
                    "move" -> R.plurals.ai_done_move
                    "copy" -> R.plurals.ai_done_copy
                    "delete" -> R.plurals.ai_done_delete
                    else -> R.plurals.ai_done_organize
                }
                var message = context.resources.getQuantityString(done, journal.done, journal.done)
                // Files changed since the preview are named on their own, not counted twice as "skipped".
                val skipped = journal.skipped - journal.changed
                if (skipped > 0) {
                    message += " · " + context.resources.getQuantityString(R.plurals.ai_skipped, skipped, skipped)
                }
                if (journal.changed > 0) {
                    message += " · " + context.resources.getQuantityString(
                        R.plurals.ai_changed, journal.changed, journal.changed
                    )
                }
                val offer = CommandUndo.Offer(
                    message = message,
                    canUndo = journal.done > 0,
                    undo = { runner.undo(journal) },
                    undoneText = { undone ->
                        context.resources.getQuantityString(R.plurals.ai_undone, undone, undone)
                    },
                    scan = { scan(context, journal) },
                )
                withContext(Dispatchers.Main) { deliver(offer) }
            } finally {
                if (executeGeneration == generation) CommandUndo.running = false
            }
        }
    }

    /** Shows the result on this activity, or keeps it until the recreated sheet can. */
    private fun deliver(offer: CommandUndo.Offer) {
        val anchor = liveAnchor()
        if (anchor == null) {
            CommandUndo.offer(offer)
            return
        }
        present(anchor, offer)
    }

    private fun showQueuedResult() {
        val offer = CommandUndo.offers.value ?: return
        val anchor = liveAnchor() ?: return
        if (!CommandUndo.consume(offer)) return
        present(anchor, offer)
    }

    private fun liveAnchor(): View? {
        val host = activity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return null
        return host.findViewById(android.R.id.content)
    }

    private fun present(anchor: View, offer: CommandUndo.Offer) {
        val snackbar = Snackbar.make(anchor, offer.message, UNDO_MILLIS).setTextMaxLines(3)
        if (offer.canUndo) {
            snackbar.setAction(R.string.ai_undo) {
                UNDO_SCOPE.launch {
                    val undone = offer.undo()
                    offer.scan()
                    withContext(Dispatchers.Main) {
                        // The sheet is gone by now; the snackbar's own anchor is still on screen.
                        Snackbar.make(anchor, offer.undoneText(undone), Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
        snackbar.show()
        // The command is done. After a rotation the state may already be saved, and the sheet must still close.
        if (isAdded) dismissAllowingStateLoss()
    }

    private fun runner(context: Context, outcome: Outcome.Ready) = CommandRunner(
        // The check already refused every protected folder except a camera folder the user named,
        // so a folder that is only allowed with the camera exception means the camera was named.
        root, cameraNamed = CommandCheck.isProtected(outcome.source, root, false) ||
            outcome.destination?.let { CommandCheck.isProtected(it, root, false) } == true,
        trash = { file ->
            // Delete only ever means trash; with the trash switched off nothing is removed.
            if (!Trash.isEnabled) null else try {
                CommandRunner.Trashed(file, Trash.moveToTrash(Paths.get(file.path)))
            } catch (e: java.io.IOException) {
                null
            }
        },
        untrash = { entry ->
            val trashed = File(entry.handle.toString())
            val trashRoot = Trash.rootOf(entry.original.path)
            if (trashRoot != null && trashed.renameTo(entry.original)) {
                File(Trash.infoDir(trashRoot), trashed.name + Trash.INFO_SUFFIX).delete()
                true
            } else {
                false
            }
        }
    )

    /** Tells the media index about old and new places, so galleries and players stay right. */
    private fun scan(context: Context, journal: CommandRunner.Journal) {
        val paths = journal.placed.flatMap { listOf(it.from.path, it.to.path) } +
            journal.trashed.map { it.original.path }
        if (paths.isNotEmpty()) {
            android.media.MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }

    // ---- Show, screens and hints -------------------------------------------------------------

    /** Read-only: finds the files and opens them in the app's own list, sorted as asked. Nothing to confirm. */
    private suspend fun showResults(plan: CommandPlan) {
        val sources = withContext(Dispatchers.IO) { CommandCheck.showSources(plan.source, root) }
        if (sources == null) {
            showAsk(Outcome.AskFolder(plan, forDestination = false))
            return
        }
        show(binding.loadingLayout)
        binding.loadingText.setText(getString(R.string.ai_finding_files))
        // Only whether anything matches: the list searches for itself. Closing the sheet stops this walk.
        val job = currentCoroutineContext()[Job]
        val found = withContext(Dispatchers.IO) {
            CommandFiles.show(plan, sources, root, active = { job?.isActive != false }, stopAfter = 1).files
        }
        if (found.isEmpty()) {
            val where = if (sources.size == 1) display(sources[0]) else plan.source
            showMessage(R.string.ai_no_files_title, getString(R.string.ai_no_files_text, where))
            return
        }
        val activity = requireActivity()
        startActivity(
            understood(plan, sources).let { (title, detail) ->
                FileListActivity.createShowIntent(ShowFileListLiveData.Request(plan, sources, title, detail))
            }
        )
        if (plan.contentContains.isNotBlank()) {
            Toast.makeText(activity, R.string.ai_show_text_note, Toast.LENGTH_LONG).show()
        }
        dismiss()
    }

    /**
     * What Ordo understood, in words: the kind as the list's title ("Videos") and the filters for its subtitle, after
     * the count ("über 500 MB in Download · größte zuerst").
     */
    private fun understood(plan: CommandPlan, sources: List<File>): Pair<String, String> {
        val context = requireContext()
        val noun = when {
            plan.cleanExtensions.isNotEmpty() -> getString(
                R.string.ai_show_extension, plan.cleanExtensions.joinToString("/") { it.uppercase() }
            )
            plan.kinds.size == 1 -> kindName(plan.kinds[0])
            else -> getString(R.string.ai_show_files)
        }
        val parts = mutableListOf<String>()
        if (plan.nameContains.isNotBlank()) parts += getString(R.string.ai_show_named, plan.nameContains.trim())
        if (plan.contentContains.isNotBlank()) parts += getString(R.string.ai_show_word, plan.contentContains.trim())
        if (plan.largerThan > 0) parts += getString(R.string.ai_show_larger, plan.largerThan, plan.sizeUnit.uppercase())
        if (plan.today) parts += getString(R.string.ai_show_today)
        fun age(count: Int, days: Int, weeks: Int, months: Int, years: Int) = context.quantity(
            when (plan.ageUnit) { "weeks" -> weeks; "months" -> months; "years" -> years; else -> days }, count, count
        )
        if (plan.newerThan > 0) parts += age(plan.newerThan, R.plurals.ai_show_newer_days, R.plurals.ai_show_newer_weeks,
            R.plurals.ai_show_newer_months, R.plurals.ai_show_newer_years)
        if (plan.olderThan > 0) parts += age(plan.olderThan, R.plurals.ai_show_older_days, R.plurals.ai_show_older_weeks,
            R.plurals.ai_show_older_months, R.plurals.ai_show_older_years)
        if (plan.nameTerms.isNotEmpty() || plan.textTerms.isNotEmpty()) {
            parts += getString(R.string.ai_show_terms, (plan.nameTerms + plan.textTerms).map { typed(it) }.distinct().joinToString(" "))
        }
        dateRange(plan)?.let { parts += it }
        sizeRange(plan)?.let { parts += it }
        exclusion(plan)?.let { parts += it }
        if (sources.size == 1 && sources[0] != root) parts += getString(R.string.ai_show_in, sources[0].name)
        var title = parts.joinToString(" ")
        val order = when (plan.sort) {
            "size" -> getString(R.string.ai_show_largest_first)
            "newest" -> getString(R.string.ai_show_newest_first)
            "oldest" -> getString(R.string.ai_show_oldest_first)
            "smallest" -> getString(R.string.ai_show_smallest_first)
            else -> null
        }
        if (order != null) title += " · $order"
        netNote?.let { title += " · $it" }
        if (plan.limit > 0) title += " · " + context.quantity(R.plurals.ai_show_top, plan.limit, plan.limit)
        return noun to title.removePrefix(" · ")
    }

    /** The net's date range in words; the end is exclusive, so a day range ends the day before. */
    private fun dateRange(plan: CommandPlan): String? {
        fun format(value: String, end: Boolean): String = runCatching {
            if ('T' in value) {
                java.time.LocalDateTime.parse(value).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
            } else {
                java.time.LocalDate.parse(value).let { if (end) it.minusDays(1) else it }
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            }
        }.getOrDefault(value)
        val from = plan.dateFrom.trim()
        val to = plan.dateTo.trim()
        return when {
            from.isNotEmpty() && to.isNotEmpty() -> getString(R.string.ai_show_between, format(from, false), format(to, true))
            from.isNotEmpty() -> getString(R.string.ai_show_since, format(from, false))
            to.isNotEmpty() -> getString(R.string.ai_show_until, format(to, true))
            else -> null
        }
    }

    private fun sizeRange(plan: CommandPlan): String? {
        val size = { bytes: Long -> android.text.format.Formatter.formatShortFileSize(requireContext(), bytes) }
        return listOfNotNull(
            plan.minBytes.takeIf { it > 0 }?.let { getString(R.string.ai_show_min_size, size(it)) },
            plan.maxBytes.takeIf { it > 0 }?.let { getString(R.string.ai_show_max_size, size(it)) }
        ).joinToString(" ").ifEmpty { null }
    }

    private fun exclusion(plan: CommandPlan): String? {
        val what = plan.excludeKinds.map { kindName(it) } + plan.excludeExtensions.map { it.uppercase() } +
            plan.excludeTerms.map { typed(it) }
        return if (what.isEmpty()) null else getString(R.string.ai_show_without, what.joinToString(", "))
    }

    private fun onRoute(route: CommandRules.Route) {
        when (route) {
            is CommandRules.Route.Refuse -> showHint(route.reason)
            is CommandRules.Route.Screen -> {
                // Storage questions and "the biggest files" open the analysis on its largest files, duplicates
                // on its duplicates page; back from there leads to the analysis overview.
                val page = if (route.screen == CommandRules.Kind.DUPLICATES) "duplicates" else "large"
                startActivity(AnalysisActivity.createIntent(requireContext(), root.path, page))
                dismiss()
            }
        }
    }

    /** Something Ordo cannot know without a memory: a friendly hint that pops in, never a guessed plan. */
    private fun showHint(reason: CommandRules.Refusal) {
        hint(getString(when (reason) {
            CommandRules.Refusal.OPENED -> R.string.ai_hint_opened
            CommandRules.Refusal.SEARCHED -> R.string.ai_hint_searched
            CommandRules.Refusal.HABIT -> R.string.ai_hint_habit
            CommandRules.Refusal.CONVERSATION -> R.string.ai_hint_conversation
            CommandRules.Refusal.WORK -> R.string.ai_hint_work
        }))
        if (reason == CommandRules.Refusal.OPENED) {
            binding.hintActionButton.setText(R.string.ai_hint_opened_action)
            binding.hintActionButton.isVisible = true
            binding.hintActionButton.setOnClickListener {
                // Offered, never done on its own: files unchanged for a month, the closest thing Ordo can see.
                viewLifecycleOwner.lifecycleScope.launch {
                    evaluate(CommandPlan("show", "", olderThan = 1, ageUnit = "months", sort = "newest"))
                }
            }
            binding.hintActionButton.reveal(220)
        }
    }

    /** The hint card: a message that pops in with its icon; a tap anywhere goes back to the command. */
    private fun hint(message: String, titleRes: Int = R.string.ai_hint_title) {
        showMessage(titleRes, message)
        binding.messageIcon.setTintedCircleIcon(R.drawable.ai_hint_icon_white_24dp, R.color.home_assistant)
        binding.messageIcon.isVisible = true
        binding.hintDismissText.isVisible = true
        binding.messageLayout.setOnClickListener { showInput() }
        popIcon()
        binding.hintDismissText.reveal(320)
    }

    private fun popIcon() {
        if (me.zhanghai.android.files.ui.Motion.any) {
            binding.messageIcon.apply {
                scaleX = 0.4f
                scaleY = 0.4f
                rotation = -20f
                animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(80).setDuration(520)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            }
        }
    }

    /** Folder paths below the storage, relative to it, for the rules' "aus X" / "nach Y" (hidden ones never). */
    private fun folderNames(): List<String> {
        val out = mutableListOf<String>()
        val rootPath = root.path
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || out.size >= 20_000) return
            for (child in dir.listFiles() ?: return) {
                if (!child.isDirectory || child.name.startsWith(".")) continue
                val relative = child.path.removePrefix("$rootPath/")
                if (relative == "Android/data" || relative == "Android/obb") continue
                out += relative
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return out.sorted()
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
        if (!me.zhanghai.android.files.ui.Motion.any) return
        alpha = 0f
        translationY = resources.getDimension(R.dimen.phi_21)
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
        /** The limit chip's words for each sort ("Nur die 5 ältesten"); no sort means newest, as the list orders. */
        internal fun limitPlurals(sort: String): Int = when (sort) {
            "size" -> R.plurals.ai_filter_limit_size
            "oldest" -> R.plurals.ai_filter_limit_oldest
            "smallest" -> R.plurals.ai_filter_limit_smallest
            else -> R.plurals.ai_filter_limit_newest
        }

        const val TAG = "CommandSheet"
        private const val ARG_PLAN = "plan"
        private const val ARG_TEXT = "text"
        private const val ARG_MODEL_ANSWER = "model_answer"

        /** Debug builds only: [answer] stands in for the model's JSON answer to [text] (see DebugAiActivity). */
        fun withModelAnswer(text: String, answer: String) = CommandSheetFragment().apply {
            check(BuildConfig.DEBUG)
            arguments = Bundle().apply {
                putString(ARG_TEXT, text)
                putString(ARG_MODEL_ANSWER, answer)
            }
        }

        /** [text] is shown as the command and is what the delete rule reads, so it must say what [plan] does. */
        fun withPlan(text: String, plan: CommandPlan) = CommandSheetFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_TEXT, text)
                putString(ARG_PLAN, plan.toJson())
            }
        }
        private const val FILES_SHOWN = 5
        private const val UNDO_MILLIS = 8_000
        private const val STATE_TEXT = "text"
        private const val STATE_PENDING = "pending"
        private const val STATE_PLAN = "plan"
        private val KINDS = CommandFiles.KINDS.keys + "other"
        // Undo outlives the sheet: it runs after the sheet is gone, from the snackbar.
        private val UNDO_SCOPE = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }
}
