/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.ai

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Google Play Billing for the one product [AiUnlock.PRODUCT_ID], after
 * developer.android.com/google/play/billing/integrate: pending one-time purchases allowed, automatic reconnection,
 * fresh product details right before every purchase. Without Google Play (sideload, emulator) every call answers
 * "not reachable" instead of crashing.
 */
class PlayBillingGateway(context: Context) : BillingGateway {
    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val updates: SharedFlow<PurchaseUpdate> = _updates.asSharedFlow()
    private val connectLock = Mutex()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases ->
            // Cancel and error are reported too: only then may a new purchase dialog start.
            _updates.tryEmit(update(result.responseCode, purchases.orEmpty().map(::toStore)))
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun connected(): Boolean = connectLock.withLock {
        if (client.isReady) return@withLock true
        // A connection that never answers must not keep the paywall at "Checking" forever.
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
                    }

                    // Lost while connecting: "not reachable" now. Automatic reconnection is on for later calls.
                    override fun onBillingServiceDisconnected() {
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
        } ?: false
    }

    override suspend fun purchases(): List<StorePurchase>? {
        if (!connected()) return null
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, list ->
                if (cont.isActive) {
                    cont.resume(if (result.responseCode == BillingResponseCode.OK) list.map(::toStore) else null)
                }
            }
        }
    }

    private suspend fun details(): ProductDetails? {
        if (!connected()) return null
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(AiUnlock.PRODUCT_ID).setProductType(ProductType.INAPP).build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, detailsResult ->
                if (cont.isActive) {
                    cont.resume(
                        if (result.responseCode == BillingResponseCode.OK) {
                            detailsResult.productDetailsList.firstOrNull { it.productId == AiUnlock.PRODUCT_ID }
                        } else null
                    )
                }
            }
        }
    }

    override suspend fun price(): String? = details()?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice

    override suspend fun launch(activity: Activity, expectedPrice: String): LaunchResult {
        // Fresh details: cached ProductDetails can make launchBillingFlow fail (Google's docs).
        val details = details() ?: return LaunchResult.Failed
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: return LaunchResult.Failed
        if (offer.formattedPrice != expectedPrice) return LaunchResult.PriceChanged(offer.formattedPrice)
        val offerToken = offer.offerToken ?: return LaunchResult.Failed
        val product = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details).setOfferToken(offerToken).build()
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build()
        return withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext LaunchResult.Failed
            when (client.launchBillingFlow(activity, params).responseCode) {
                BillingResponseCode.OK -> LaunchResult.Started
                // Answered like a dialog that reported it, so the full check runs and releases the button.
                BillingResponseCode.ITEM_ALREADY_OWNED -> LaunchResult.Started.also {
                    _updates.tryEmit(PurchaseUpdate(PurchaseUpdate.Code.ALREADY_OWNED))
                }
                else -> LaunchResult.Failed
            }
        }
    }

    override suspend fun acknowledge(token: String): Boolean {
        if (!connected()) return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        return suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { result ->
                if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
            }
        }
    }

    private fun update(code: Int, purchases: List<StorePurchase>) = when (code) {
        BillingResponseCode.OK -> PurchaseUpdate(PurchaseUpdate.Code.OK, purchases)
        BillingResponseCode.USER_CANCELED -> PurchaseUpdate(PurchaseUpdate.Code.CANCELLED)
        BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdate(PurchaseUpdate.Code.ALREADY_OWNED)
        else -> PurchaseUpdate(PurchaseUpdate.Code.ERROR)
    }

    private fun toStore(purchase: Purchase) = StorePurchase(
        products = purchase.products,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> StorePurchase.State.PURCHASED
            Purchase.PurchaseState.PENDING -> StorePurchase.State.PENDING
            else -> StorePurchase.State.OTHER
        },
        acknowledged = purchase.isAcknowledged,
        token = purchase.purchaseToken
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
