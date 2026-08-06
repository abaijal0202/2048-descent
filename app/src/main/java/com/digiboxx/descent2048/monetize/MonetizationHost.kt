package com.digiboxx.descent2048.monetize

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.digiboxx.descent2048.data.GameStorage

/**
 * The single place the app reaches for advertising and billing.
 *
 * A plain object rather than injected dependencies because there is exactly one of each
 * per process and the alternative is threading two more constructor arguments through
 * three view models for no benefit.
 *
 * ## Plugging in a real store
 *
 * Everything below stays as it is. Replace the two gateways in [install] with
 * implementations backed by whichever ad network and billing library the store provides,
 * and nothing else in the codebase changes. See `store/monetization.md`.
 */
object MonetizeHost {

    var ads: AdGateway = NoOpAdGateway
        private set

    var billing: BillingGateway = NoOpBillingGateway
        private set

    val policy = MonetizationPolicy()

    private var storage: GameStorage? = null

    /**
     * Called once from the Activity.
     *
     * With no SDK configured this leaves the app entirely unmonetised, which is a valid
     * shipping state — the games behave exactly as they did before any of this existed.
     */
    fun install(context: Context, debugBuild: Boolean) {
        val store = GameStorage(context.applicationContext)
        storage = store

        // A simulated gateway in debug builds only, so the reward and continue flows can
        // be exercised on a device before a real SDK exists. Release builds get the
        // no-op, so there is no possibility of shipping free rewards by accident.
        if (debugBuild) {
            ads = SimulatedAdGateway
            billing = SimulatedBillingGateway(store)
        }

        policy.updateEntitlements(billing.entitlements)
    }

    fun refreshEntitlements() {
        policy.updateEntitlements(billing.entitlements)
    }

    /** Convenience for the UI: is the remove-ads product worth showing at all? */
    fun canOfferRemoveAds(): Boolean =
        !billing.entitlements.adsRemoved && billing.priceLabel(Product.REMOVE_ADS) != null
}

/**
 * Stands in for a real rewarded/interstitial SDK during development.
 *
 * Always "ready", and grants after a short delay so the UI's loading and reward paths
 * both get exercised. Debug builds only — see [MonetizeHost.install].
 */
object SimulatedAdGateway : AdGateway {

    private val handler = Handler(Looper.getMainLooper())

    override val rewardedReady: Boolean = true

    override fun preload() = Unit

    override fun showRewarded(placement: RewardPlacement, onResult: (RewardResult) -> Unit) {
        handler.postDelayed({ onResult(RewardResult.EARNED) }, SIMULATED_AD_MS)
    }

    override fun showInterstitial(onClosed: () -> Unit) {
        handler.postDelayed(onClosed, SIMULATED_AD_MS)
    }

    private const val SIMULATED_AD_MS = 1200L
}

/** Stands in for real billing during development, persisting the entitlement locally. */
class SimulatedBillingGateway(private val storage: GameStorage) : BillingGateway {

    override val entitlements: Entitlements
        get() = Entitlements(adsRemoved = storage.adsRemoved)

    override fun priceLabel(product: Product): String? = when (product) {
        Product.REMOVE_ADS -> "₹99"
    }

    override fun purchase(product: Product, onResult: (PurchaseResult) -> Unit) {
        when (product) {
            Product.REMOVE_ADS -> {
                if (storage.adsRemoved) {
                    onResult(PurchaseResult.ALREADY_OWNED)
                } else {
                    storage.adsRemoved = true
                    onResult(PurchaseResult.PURCHASED)
                }
            }
        }
    }

    override fun restorePurchases(onDone: () -> Unit) = onDone()
}
