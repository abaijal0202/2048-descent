package com.digiboxx.descent2048.monetize

/**
 * The monetisation seam.
 *
 * Everything the games know about advertising and billing goes through the two gateway
 * interfaces below. No ad or billing SDK is referenced anywhere else in the codebase,
 * which matters for three reasons:
 *
 * 1. The store's SDK can be swapped, or a mediation layer added, without touching a
 *    single line of game code.
 * 2. [MonetizationPolicy] — the part with the actual rules about when a player sees an
 *    ad — stays pure Kotlin and is unit tested like the game engines are.
 * 3. The app runs completely normally with [NoOpAdGateway] in place, so nothing here is
 *    load-bearing for the game working.
 *
 * See `store/monetization.md` for what has to be filled in before release.
 */

/** Where a rewarded ad was offered. Worth reporting to analytics separately. */
enum class RewardPlacement {
    /** Game over: watch to carry on from where you were. */
    CONTINUE_RUN,

    /** A power is empty: watch for one charge. */
    POWER_CHARGE
}

enum class RewardResult {
    /** The player watched enough of it. Grant the reward. */
    EARNED,

    /** They closed it early. No reward, and no hard feelings — do not re-prompt. */
    DISMISSED,

    /** No fill, network error, SDK not ready. Never punish the player for this. */
    FAILED
}

/**
 * Ad surfaces.
 *
 * Implementations must call their callback exactly once, on the main thread. A gateway
 * that silently drops a callback strands the game in a loading state — the single most
 * common way ad integrations break a game.
 */
interface AdGateway {
    /** False when nothing is cached; the UI hides the offer rather than showing a dud. */
    val rewardedReady: Boolean

    /** Cache ahead of time. Called when a run starts and after each show. */
    fun preload()

    fun showRewarded(placement: RewardPlacement, onResult: (RewardResult) -> Unit)

    /** [onClosed] must fire whether or not an ad actually appeared. */
    fun showInterstitial(onClosed: () -> Unit)
}

/** What the player owns. Extend as more products are added. */
data class Entitlements(val adsRemoved: Boolean = false)

/** Products offered for sale. */
enum class Product { REMOVE_ADS }

enum class PurchaseResult { PURCHASED, CANCELLED, FAILED, ALREADY_OWNED }

interface BillingGateway {
    val entitlements: Entitlements

    /** Localised price to show on the button, or null when the catalogue is unavailable. */
    fun priceLabel(product: Product): String?

    fun purchase(product: Product, onResult: (PurchaseResult) -> Unit)

    /** Re-checks ownership. Must be offered somewhere or players who reinstall lose it. */
    fun restorePurchases(onDone: () -> Unit)
}

/**
 * The default: no ads exist, nothing is for sale.
 *
 * Shipping this is a perfectly valid state — the app simply has no monetisation, which
 * is exactly how it behaves today.
 */
object NoOpAdGateway : AdGateway {
    override val rewardedReady: Boolean = false
    override fun preload() = Unit
    override fun showRewarded(placement: RewardPlacement, onResult: (RewardResult) -> Unit) {
        onResult(RewardResult.FAILED)
    }
    override fun showInterstitial(onClosed: () -> Unit) = onClosed()
}

object NoOpBillingGateway : BillingGateway {
    override val entitlements = Entitlements()
    override fun priceLabel(product: Product): String? = null
    override fun purchase(product: Product, onResult: (PurchaseResult) -> Unit) {
        onResult(PurchaseResult.FAILED)
    }
    override fun restorePurchases(onDone: () -> Unit) = onDone()
}

/**
 * Tuning for how often a player is asked to watch something.
 *
 * These are the numbers that decide whether the game feels generous or grasping, so they
 * live together in one place rather than being scattered through the UI.
 */
data class AdConfig(
    /** Show an interstitial on every nth game over. */
    val interstitialEveryNGameOvers: Int = 3,

    /** Never two interstitials closer together than this. */
    val minMsBetweenInterstitials: Long = 90_000L,

    /**
     * Game overs to leave alone at the start of a session.
     *
     * A player who is interrupted by an ad before they have understood the game
     * uninstalls it. The first couple of runs are the ones that decide retention.
     */
    val graceGameOvers: Int = 2,

    /** Rewarded continues allowed within a single run. */
    val maxContinuesPerRun: Int = 1,

    /** Rewarded power charges allowed per calendar day. */
    val maxChargeRewardsPerDay: Int = 6
)

/**
 * When the player is allowed to be shown something.
 *
 * Deliberately pure: no Android, no SDK, time passed in. The rules about ad frequency are
 * the part most likely to be got wrong in a way that quietly wrecks retention, so they
 * are the part that gets tested.
 */
class MonetizationPolicy(
    private val config: AdConfig = AdConfig(),
    private var entitlements: Entitlements = Entitlements()
) {

    private var gameOversThisSession = 0

    /**
     * Null until the first interstitial, rather than a sentinel value.
     *
     * `Long.MIN_VALUE` is the obvious sentinel and it is wrong: `nowMs - Long.MIN_VALUE`
     * overflows to a negative number, which reads as "still inside the cooldown" and
     * silently suppresses every interstitial forever.
     */
    private var lastInterstitialMs: Long? = null

    private var continuesThisRun = 0
    private var chargeRewardsToday = 0

    /** Also nullable: 0L is a legitimate timestamp, so it cannot mean "unset". */
    private var chargeRewardDayStartMs: Long? = null

    fun updateEntitlements(value: Entitlements) {
        entitlements = value
    }

    val adsRemoved: Boolean get() = entitlements.adsRemoved

    /** Call when a new run begins. */
    fun onRunStarted() {
        continuesThisRun = 0
    }

    /**
     * Whether to show an interstitial now. Call exactly once per game over.
     *
     * Counting happens whether or not an ad is shown, so the cadence stays honest when a
     * player buys the removal partway through a session.
     */
    fun onGameOver(nowMs: Long): Boolean {
        gameOversThisSession++
        if (entitlements.adsRemoved) return false
        if (gameOversThisSession <= config.graceGameOvers) return false
        if (gameOversThisSession % config.interstitialEveryNGameOvers != 0) return false
        lastInterstitialMs?.let { previous ->
            if (nowMs - previous < config.minMsBetweenInterstitials) return false
        }
        lastInterstitialMs = nowMs
        return true
    }

    /**
     * Whether to offer a rewarded continue.
     *
     * Note this ignores [Entitlements.adsRemoved]. Removing ads buys freedom from
     * *interruption*, not from an optional bonus the player chose to go looking for —
     * taking rewarded video away from payers punishes them for paying.
     */
    fun canOfferContinue(rewardedReady: Boolean): Boolean =
        rewardedReady && continuesThisRun < config.maxContinuesPerRun

    fun onContinueGranted() {
        continuesThisRun++
    }

    /** Whether to offer a rewarded charge, with a daily cap so it cannot be farmed. */
    fun canOfferChargeReward(nowMs: Long, rewardedReady: Boolean): Boolean {
        rollDayIfNeeded(nowMs)
        return rewardedReady && chargeRewardsToday < config.maxChargeRewardsPerDay
    }

    fun onChargeRewardGranted(nowMs: Long) {
        rollDayIfNeeded(nowMs)
        chargeRewardsToday++
    }

    fun chargeRewardsRemaining(nowMs: Long): Int {
        rollDayIfNeeded(nowMs)
        return (config.maxChargeRewardsPerDay - chargeRewardsToday).coerceAtLeast(0)
    }

    /**
     * Rolls the daily counter.
     *
     * Uses elapsed time from the first grant rather than a calendar date on purpose:
     * a date comparison hands a free reset to anyone who nudges the device clock, which
     * is the same weakness the power regeneration already has.
     */
    private fun rollDayIfNeeded(nowMs: Long) {
        val start = chargeRewardDayStartMs
        if (start == null) {
            chargeRewardDayStartMs = nowMs
            return
        }
        if (nowMs < start) {
            // Clock wound backwards. Re-anchor so regeneration is not stalled forever,
            // but deliberately keep the count: resetting it here would make winding the
            // clock back a free refill, which is exactly the exploit being closed.
            chargeRewardDayStartMs = nowMs
            return
        }
        if (nowMs - start >= DAY_MS) {
            chargeRewardDayStartMs = nowMs
            chargeRewardsToday = 0
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
