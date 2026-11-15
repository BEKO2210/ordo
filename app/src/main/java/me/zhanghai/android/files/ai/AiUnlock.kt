/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.app.Activity
import android.util.Log
import androidx.fragment.app.FragmentManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/** A purchase, reduced to what the unlock needs; testable without the billing classes. */
data class StorePurchase(val products: List<String>, val state: State, val acknowledged: Boolean, val token: String) {
    enum class State { PURCHASED, PENDING, OTHER }
}

/** An answer of the purchase dialog; [purchases] only for [Code.OK]. */
data class PurchaseUpdate(val code: Code, val purchases: List<StorePurchase> = emptyList()) {
    enum class Code { OK, CANCELLED, ALREADY_OWNED, ERROR }
}

sealed interface LaunchResult {
    data object Started : LaunchResult

    /** Google Play's price differs from the one shown; nothing was started. */
    data class PriceChanged(val price: String) : LaunchResult

    data object Failed : LaunchResult
}

/** Thin layer over Google Play Billing for [AiUnlock.PRODUCT_ID]. */
interface BillingGateway {
    /** Every answer of the purchase dialog (bought, pending, finished later, cancelled, failed). */
    val updates: SharedFlow<PurchaseUpdate>

    /** All owned purchases; `null` when Google Play is not reachable. */
    suspend fun purchases(): List<StorePurchase>?

    /** Localised price; `null` when the product is not offered or Google Play is not reachable. */
    suspend fun price(): String?

    suspend fun launch(activity: Activity, expectedPrice: String): LaunchResult

    suspend fun acknowledge(token: String): Boolean
}

sealed interface AiUnlockState {
    data object Checking : AiUnlockState
    data class Offer(val price: String) : AiUnlockState
    /** Bought, but Google Play has not confirmed the payment yet (e.g. cash at a shop). Still locked. */
    data object Pending : AiUnlockState
    data object Unlocked : AiUnlockState
    /** No Google Play (sideload, emulator) or offline without an earlier confirmed purchase. */
    data object Unavailable : AiUnlockState
}

/**
 * The one-time purchase that unlocks the on-device AI. Only a purchase Google Play reports as PURCHASED unlocks;
 * pending ones never do. The last confirmed state is kept in [Settings.AI_UNLOCKED], so the AI works offline; a full
 * check with Google Play on every return to the app also restores after a reinstall and locks again after a refund.
 */
class AiUnlock(
    private val gateway: BillingGateway,
    private val scope: CoroutineScope,
    stored: Boolean,
    private val store: (Boolean) -> Unit,
    private val ackRetryMs: Long = 2_000L
) {
    private val _state = MutableStateFlow(if (stored) AiUnlockState.Unlocked else AiUnlockState.Checking)
    val state: StateFlow<AiUnlockState> = _state.asStateFlow()
    val unlocked: Boolean get() = _state.value == AiUnlockState.Unlocked

    private val stateLock = Mutex()
    private val refreshLock = Mutex()
    // Counts purchase reports for this product; a full check that ran meanwhile is stale.
    private val updateGeneration = AtomicLong(0)
    private val _busy = MutableStateFlow(false)
    /** A purchase dialog is opening or open; at most one at a time. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    /** The last purchase attempt failed (not cancelled); cleared by the next one. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()
    private val acknowledging: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        scope.launch {
            gateway.updates.collect { update ->
                _busy.value = false
                // One failing report must not stop the handling of all later ones.
                try {
                    when (update.code) {
                        PurchaseUpdate.Code.OK -> apply(update.purchases, complete = false)
                        // Google Play's cache knew the purchase before the app did: ask for the full list.
                        PurchaseUpdate.Code.ALREADY_OWNED -> refreshLater()
                        PurchaseUpdate.Code.ERROR -> _failed.value = true
                        PurchaseUpdate.Code.CANCELLED -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Purchase report not handled: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    fun refreshLater() {
        scope.launch { refresh() }
    }

    /** Full check with Google Play. If one already runs (start, return to the app), its result counts. */
    suspend fun refresh() {
        if (!refreshLock.tryLock()) {
            refreshLock.withLock {}
            return
        }
        try {
            repeat(MAX_REFRESH_ATTEMPTS) {
                val generation = updateGeneration.get()
                val purchases = gateway.purchases()
                if (purchases == null) {
                    // Not reachable: what was confirmed last stays.
                    stateLock.withLock {
                        if (_state.value != AiUnlockState.Unlocked && _state.value != AiUnlockState.Pending) {
                            _state.value = AiUnlockState.Unavailable
                        }
                    }
                    return
                }
                // A purchase report came in during the query: the list is stale, ask again.
                if (generation != updateGeneration.get()) return@repeat
                apply(purchases, complete = true)
                return
            }
        } finally {
            refreshLock.unlock()
        }
    }

    fun buy(activity: Activity) = buy { price -> gateway.launch(activity, price) }

    // Runs in this class's scope, so closing the sheet while Google Play answers does not cancel it halfway.
    internal fun buy(launch: suspend (expectedPrice: String) -> LaunchResult) {
        val offer = _state.value as? AiUnlockState.Offer ?: return
        if (!_busy.compareAndSet(false, true)) return
        _failed.value = false
        scope.launch {
            var started = false
            try {
                when (val result = launch(offer.price)) {
                    // Then the answer of the purchase dialog releases it.
                    LaunchResult.Started -> started = true
                    // Show the new price first; buying needs another deliberate tap.
                    is LaunchResult.PriceChanged -> stateLock.withLock {
                        if (_state.value is AiUnlockState.Offer) _state.value = AiUnlockState.Offer(result.price)
                    }
                    LaunchResult.Failed -> _failed.value = true
                }
            } finally {
                // Also when cancelled: a lost release would block buying until the app restarts.
                if (!started) _busy.value = false
            }
        }
    }

    /**
     * [complete] = the list from queryPurchasesAsync; only it may count a purchase as gone (refund). Reports from the
     * purchase dialog only contain what is new, on cancel nothing.
     */
    internal suspend fun apply(purchases: List<StorePurchase>, complete: Boolean) {
        val mine = purchases.filter { PRODUCT_ID in it.products }
        if (!complete && mine.isNotEmpty()) updateGeneration.incrementAndGet()
        val toAcknowledge = stateLock.withLock {
            val owned = mine.firstOrNull { it.state == StorePurchase.State.PURCHASED }
            when {
                owned != null -> {
                    store(true)
                    _state.value = AiUnlockState.Unlocked
                    owned.token.takeUnless { owned.acknowledged }
                }
                mine.any { it.state == StorePurchase.State.PENDING } -> {
                    if (complete) store(false)
                    _state.value = AiUnlockState.Pending
                    null
                }
                complete -> {
                    store(false)
                    _state.value = gateway.price()?.let { AiUnlockState.Offer(it) } ?: AiUnlockState.Unavailable
                    null
                }
                else -> null
            }
        }
        // Unlock first, then acknowledge, outside the lock.
        toAcknowledge?.let { acknowledgeWithRetry(it) }
    }

    /**
     * Google refunds unacknowledged purchases after three days. If all tries fail, the next full check (start or
     * return to the app) tries again, because the purchase is still unacknowledged then.
     */
    private suspend fun acknowledgeWithRetry(token: String) {
        if (!acknowledging.add(token)) return
        try {
            var wait = ackRetryMs
            repeat(MAX_ACK_ATTEMPTS) { attempt ->
                if (gateway.acknowledge(token)) return
                if (attempt < MAX_ACK_ATTEMPTS - 1) {
                    delay(wait)
                    wait *= 4
                }
            }
        } finally {
            acknowledging.remove(token)
        }
    }

    companion object {
        /** One-time in-app product in the Play Console. */
        const val PRODUCT_ID = "ordo_ai_lifetime"
        private const val TAG = "AiUnlock"
        private const val MAX_REFRESH_ATTEMPTS = 3
        private const val MAX_ACK_ATTEMPTS = 4

        // Main thread: Settings are LiveData, and billing answers are handled one at a time.
        val instance: AiUnlock by lazy {
            AiUnlock(PlayBillingGateway(application), MainScope(), Settings.AI_UNLOCKED.valueCompat,
                store = { Settings.AI_UNLOCKED.putValue(it) })
        }

        val isUnlocked: Boolean
            get() = instance.unlocked || BuildConfig.DEBUG && Settings.AI_DEBUG_UNLOCK.valueCompat
    }
}

/** Every way into the AI goes through here: the command sheet when unlocked, otherwise the paywall. */
fun FragmentManager.showAiSheet(sheet: CommandSheetFragment = CommandSheetFragment()) {
    if (findFragmentByTag(CommandSheetFragment.TAG) != null || findFragmentByTag(AiPaywallSheet.TAG) != null) return
    if (AiUnlock.isUnlocked) {
        sheet.show(this, CommandSheetFragment.TAG)
    } else {
        AiPaywallSheet.newInstance(sheet.arguments).show(this, AiPaywallSheet.TAG)
    }
}
