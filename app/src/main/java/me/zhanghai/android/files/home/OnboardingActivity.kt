/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.home

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.OnboardingActivityBinding
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.util.supportsExternalStorageManager
import kotlin.math.abs

/** First start: four calm pages that explain Ordo and ask for file access at the end. */
class OnboardingActivity : AppActivity() {
    private lateinit var binding: OnboardingActivityBinding
    private lateinit var pages: List<View>
    private var page = 0
    private var typing: Job? = null

    private val allFilesAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateButton() }
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateButton() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = OnboardingActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pages = listOf(binding.pageWelcome, binding.pageSafety, binding.pageAi, binding.pageAccess)
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        repeat(pages.size) {
            binding.dots.addView(View(this).apply {
                background = ContextCompat.getDrawable(context, R.drawable.onboarding_dot)
            })
        }
        binding.skipButton.setOnClickListener { finishOnboarding() }
        binding.nextButton.setOnClickListener { onNext() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page > 0) go(page - 1) else finishOnboarding()
            }
        })
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 600) return false
                // In right-to-left layouts the next page comes from the left.
                val forward = (vx < 0) != (binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                if (forward && page < pages.lastIndex) go(page + 1) else if (!forward && page > 0) go(page - 1)
                return true
            }
        })
        binding.pageContainer.setOnTouchListener { view, event ->
            swipe.onTouchEvent(event).also { if (event.action == MotionEvent.ACTION_UP) view.performClick() }
        }
        page = savedInstanceState?.getInt(STATE_PAGE) ?: 0
        pages.forEachIndexed { i, view -> view.isVisible = i == page }
        onPageShown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, page)
    }

    override fun onResume() {
        super.onResume()
        updateButton()
    }

    private fun onNext() {
        when {
            page < pages.lastIndex -> go(page + 1)
            !hasStorageAccess(this) -> requestAccess()
            else -> finishOnboarding()
        }
    }

    private fun go(target: Int) {
        val forward = target > page
        TransitionManager.beginDelayedTransition(
            binding.pageContainer, MaterialSharedAxis(MaterialSharedAxis.X, forward).setDuration(420)
        )
        pages[page].isVisible = false
        pages[target].isVisible = true
        page = target
        onPageShown()
    }

    private fun onPageShown() {
        TransitionManager.beginDelayedTransition(binding.dots, ChangeBounds().setDuration(300))
        val density = resources.displayMetrics.density
        for (i in 0 until binding.dots.childCount) {
            binding.dots.getChildAt(i).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ((if (i == page) 28 else 8) * density).toInt(), (8 * density).toInt()
                ).apply { marginStart = (4 * density).toInt(); marginEnd = (4 * density).toInt() }
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (i == page) {
                        ContextCompat.getColor(context, R.color.ai_accent)
                    } else {
                        com.google.android.material.color.MaterialColors.getColor(
                            this, android.R.attr.textColorSecondary
                        ) and 0x00FFFFFF or 0x66000000
                    }
                )
            }
        }
        binding.dots.contentDescription = getString(R.string.onboarding_page, page + 1, pages.size)
        animateShift(page.toFloat())
        updateButton()
        typing?.cancel()
        when (pages[page]) {
            binding.pageWelcome -> binding.welcomeLogo.pop()
            binding.pageSafety -> listOf(binding.featureTrash, binding.featureVault, binding.featurePrivacy)
                .forEachIndexed { i, view -> view.rise(150L + 110L * i) }
            binding.pageAi -> typing = lifecycleScope.launch { typeExample() }
            binding.pageAccess -> binding.accessIcon.pop()
        }
    }

    // The example command types itself, the sphere thinks, then it starts over.
    private suspend fun typeExample() {
        val example = getString(R.string.onboarding_ai_example)
        val animate = ValueAnimator.areAnimatorsEnabled()
        while (lifecycleScope.coroutineContext.isActive) {
            binding.aiOrb.thinking = false
            if (!animate) {
                binding.aiTyping.text = example
                return
            }
            for (i in 1..example.length) {
                binding.aiTyping.text = example.substring(0, i) + "▍"
                delay(42)
            }
            binding.aiTyping.text = example
            binding.aiOrb.thinking = true
            delay(2_200)
            binding.aiOrb.celebrate()
            delay(1_400)
        }
    }

    private fun updateButton() {
        val last = page == pages.lastIndex
        val granted = hasStorageAccess(this)
        binding.nextButton.setText(
            when {
                !last -> R.string.onboarding_next
                !granted -> R.string.onboarding_allow
                else -> R.string.onboarding_start
            }
        )
        binding.skipButton.visibility = if (last) View.INVISIBLE else View.VISIBLE
        if (last) {
            binding.accessText.setText(if (granted) R.string.onboarding_access_done else R.string.onboarding_access_text)
            binding.accessIcon.setTintedCircleIcon(
                if (granted) R.drawable.ai_check_icon_white_24dp else R.drawable.ai_storage_icon_white_24dp,
                R.color.home_assistant
            )
        }
    }

    private fun requestAccess() {
        if (Environment::class.supportsExternalStorageManager() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesAccess.launch(Environment::class.createManageAppAllFilesAccessPermissionIntent(packageName))
        } else {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        finish()
    }

    private fun animateShift(target: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            binding.aurora.shift = target
            return
        }
        ValueAnimator.ofFloat(binding.aurora.shift, target).apply {
            duration = 600
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { binding.aurora.shift = it.animatedValue as Float }
        }.start()
    }

    private fun View.pop() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        scaleX = 0.6f
        scaleY = 0.6f
        alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun View.rise(startDelay: Long) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        alpha = 0f
        translationY = 24 * resources.displayMetrics.density
        animate().alpha(1f).translationY(0f).setStartDelay(startDelay).setDuration(380)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    companion object {
        private const val PREFS = "onboarding"
        private const val KEY_DONE = "done"
        private const val STATE_PAGE = "page"

        fun isDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DONE, false)

        fun hasStorageAccess(context: Context): Boolean =
            if (Environment::class.supportsExternalStorageManager()) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
    }
}
