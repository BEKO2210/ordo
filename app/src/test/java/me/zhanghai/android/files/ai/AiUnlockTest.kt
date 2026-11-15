/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiUnlockTest {
    private class FakeGateway : BillingGateway {
        override val updates = MutableSharedFlow<List<StorePurchase>>(extraBufferCapacity = 8)
        var owned: List<StorePurchase>? = emptyList()
        var offeredPrice: String? = "0,99 €"
        var queries = 0
        var duringQuery: suspend () -> Unit = {}
        var ackFailures = 0
        val acknowledged = mutableListOf<String>()

        override suspend fun purchases(): List<StorePurchase>? {
            queries++
            duringQuery()
            return owned
        }

        override suspend fun price() = offeredPrice

        override suspend fun launch(activity: Activity, expectedPrice: String) = LaunchResult.Failed

        override suspend fun acknowledge(token: String): Boolean {
            if (ackFailures > 0) {
                ackFailures--
                return false
            }
            acknowledged += token
            return true
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val gateway = FakeGateway()
    private var stored: Boolean? = null

    private fun unlock(storedBefore: Boolean = false) =
        AiUnlock(gateway, scope, storedBefore, { stored = it }, ackRetryMs = 1)

    private fun purchase(state: StorePurchase.State, acknowledged: Boolean = false, product: String = AiUnlock.PRODUCT_ID) =
        StorePurchase(listOf(product), state, acknowledged, "token-$state")

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aPurchaseUnlocksIsStoredAndAcknowledged() = runBlocking {
        gateway.owned = listOf(purchase(StorePurchase.State.PURCHASED))
        val unlock = unlock()
        unlock.refresh()
        assertTrue(unlock.unlocked)
        assertEquals(true, stored)
        assertEquals(listOf("token-PURCHASED"), gateway.acknowledged)
    }

    @Test
    fun anAcknowledgedPurchaseIsNotAcknowledgedAgain() = runBlocking {
        gateway.owned = listOf(purchase(StorePurchase.State.PURCHASED, acknowledged = true))
        val unlock = unlock()
        unlock.refresh()
        assertTrue(unlock.unlocked)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun aFailedAcknowledgeIsRetried() = runBlocking {
        gateway.ackFailures = 2
        gateway.owned = listOf(purchase(StorePurchase.State.PURCHASED))
        unlock().refresh()
        assertEquals(listOf("token-PURCHASED"), gateway.acknowledged)
    }

    @Test
    fun aPendingPurchaseNeverUnlocks() = runBlocking {
        gateway.owned = listOf(purchase(StorePurchase.State.PENDING))
        val unlock = unlock()
        unlock.refresh()
        assertFalse(unlock.unlocked)
        assertEquals(AiUnlockState.Pending, unlock.state.value)
        assertEquals(false, stored)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun anotherProductDoesNotUnlock() = runBlocking {
        gateway.owned = listOf(purchase(StorePurchase.State.PURCHASED, product = "something_else"))
        val unlock = unlock()
        unlock.refresh()
        assertFalse(unlock.unlocked)
        assertEquals(AiUnlockState.Offer("0,99 €"), unlock.state.value)
    }

    @Test
    fun noPurchaseShowsGooglePlaysPrice() = runBlocking {
        val unlock = unlock()
        assertEquals(AiUnlockState.Checking, unlock.state.value)
        unlock.refresh()
        assertEquals(AiUnlockState.Offer("0,99 €"), unlock.state.value)
        assertEquals(false, stored)
    }

    @Test
    fun aRefundLocksAgain() = runBlocking {
        val unlock = unlock(storedBefore = true)
        assertTrue(unlock.unlocked)
        unlock.refresh()
        assertFalse(unlock.unlocked)
        assertEquals(false, stored)
    }

    @Test
    fun offlineKeepsAnEarlierPurchase() = runBlocking {
        gateway.owned = null
        val unlock = unlock(storedBefore = true)
        unlock.refresh()
        assertTrue(unlock.unlocked)
        assertNull(stored)
    }

    @Test
    fun withoutGooglePlayItStaysLockedAndCalm() = runBlocking {
        gateway.owned = null
        val unlock = unlock()
        unlock.refresh()
        assertFalse(unlock.unlocked)
        assertEquals(AiUnlockState.Unavailable, unlock.state.value)
    }

    @Test
    fun productNotOfferedIsUnavailable() = runBlocking {
        gateway.offeredPrice = null
        val unlock = unlock()
        unlock.refresh()
        assertEquals(AiUnlockState.Unavailable, unlock.state.value)
    }

    @Test
    fun aPurchaseFromTheDialogUnlocks() = runBlocking {
        val unlock = unlock()
        gateway.updates.emit(listOf(purchase(StorePurchase.State.PURCHASED)))
        assertTrue(unlock.unlocked)
        assertEquals(true, stored)
        assertEquals(listOf("token-PURCHASED"), gateway.acknowledged)
    }

    @Test
    fun aCancelledDialogChangesNothing() = runBlocking {
        val unlock = unlock()
        gateway.updates.emit(emptyList())
        assertEquals(AiUnlockState.Checking, unlock.state.value)
        assertNull(stored)
    }

    // A full check that started before the purchase went through must not lock it again.
    @Test
    fun aStaleCheckDoesNotUndoAFreshPurchase() = runBlocking {
        val unlock = unlock()
        gateway.duringQuery = {
            gateway.duringQuery = {}
            unlock.apply(listOf(purchase(StorePurchase.State.PURCHASED)), complete = false)
            gateway.owned = listOf(purchase(StorePurchase.State.PURCHASED))
        }
        unlock.refresh()
        assertEquals(2, gateway.queries)
        assertTrue(unlock.unlocked)
    }
}
