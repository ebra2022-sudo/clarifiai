package com.clarifiai.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.clarifiai.app.data.Tier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Subscription product IDs must match the backend's PRODUCT_TIERS and the Play Console. */
object Products {
    const val PRO = "clarity_pro"
    const val MAX = "clarity_max"
    fun tierOf(productId: String): Tier? = when (productId) { PRO -> Tier.PRO; MAX -> Tier.MAX; else -> null }
}

data class PlanOffer(val tier: Tier, val price: String, val details: ProductDetails, val offerToken: String)

class BillingManager(
    context: Context,
    private val scope: CoroutineScope,
    /** Verify with backend; return true if the entitlement was granted (then we acknowledge). */
    private val onPurchase: suspend (Purchase) -> Boolean,
    private val onMessage: (String) -> Unit,
) : PurchasesUpdatedListener {

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private val _offers = MutableStateFlow<List<PlanOffer>>(emptyList())
    val offers: StateFlow<List<PlanOffer>> = _offers.asStateFlow()

    private suspend fun ensureConnected(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    suspend fun connectAndLoad() {
        if (!ensureConnected()) return
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(Products.PRO, Products.MAX).map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            }
        ).build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        _offers.value = result.productDetailsList.orEmpty().mapNotNull { d ->
            val tier = Products.tierOf(d.productId) ?: return@mapNotNull null
            val offer = d.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
            val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@mapNotNull null
            PlanOffer(tier, price, d, offer.offerToken)
        }.sortedBy { it.tier.ordinal }
    }

    fun launch(activity: Activity, offer: PlanOffer) {
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(offer.details).setOfferToken(offer.offerToken).build()
            )
        ).build()
        val result = client.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            onMessage("Couldn't open Google Play checkout.")
        }
    }

    fun restore() {
        scope.launch {
            if (!ensureConnected()) { onMessage("Google Play is unavailable."); return@launch }
            val res = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
            val active = res.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (active.isEmpty()) onMessage("No active subscriptions found for this Google account.")
            active.forEach { process(it) }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { purchases.orEmpty().forEach { process(it) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> onMessage("Purchase failed (code ${result.responseCode}).")
        }
    }

    private suspend fun process(p: Purchase) {
        when (p.purchaseState) {
            Purchase.PurchaseState.PENDING -> onMessage("Payment pending. Your plan activates once it clears.")
            Purchase.PurchaseState.PURCHASED -> {
                val granted = onPurchase(p)
                if (granted && !p.isAcknowledged) {
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
                }
            }
        }
    }

    fun close() { if (client.isReady) client.endConnection() }
}
