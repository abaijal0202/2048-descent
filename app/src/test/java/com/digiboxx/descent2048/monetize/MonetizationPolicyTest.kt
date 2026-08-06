package com.digiboxx.descent2048.monetize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ad-frequency rules.
 *
 * These are worth testing properly because getting them wrong is invisible in a code
 * review and expensive in the store: an interstitial one run too early, or one that
 * ignores its own cooldown, shows up as a retention cliff weeks later.
 */
class MonetizationPolicyTest {

    private val day = 24L * 60L * 60L * 1000L

    private fun policy(
        config: AdConfig = AdConfig(),
        adsRemoved: Boolean = false
    ) = MonetizationPolicy(config, Entitlements(adsRemoved = adsRemoved))

    // ------------------------------------------------------------ interstitials

    @Test
    fun `the first runs of a session are never interrupted`() {
        val p = policy(AdConfig(graceGameOvers = 2, interstitialEveryNGameOvers = 1))
        assertFalse("first run must be left alone", p.onGameOver(0L))
        assertFalse("second run too", p.onGameOver(200_000L))
        assertTrue("third is fair game", p.onGameOver(400_000L))
    }

    @Test
    fun `interstitials follow the every-nth cadence`() {
        val p = policy(
            AdConfig(
                graceGameOvers = 0,
                interstitialEveryNGameOvers = 3,
                minMsBetweenInterstitials = 0L
            )
        )
        assertFalse(p.onGameOver(0L))
        assertFalse(p.onGameOver(1_000L))
        assertTrue("every third", p.onGameOver(2_000L))
        assertFalse(p.onGameOver(3_000L))
        assertFalse(p.onGameOver(4_000L))
        assertTrue(p.onGameOver(5_000L))
    }

    @Test
    fun `two interstitials never land inside the cooldown`() {
        val p = policy(
            AdConfig(
                graceGameOvers = 0,
                interstitialEveryNGameOvers = 1,
                minMsBetweenInterstitials = 90_000L
            )
        )
        assertTrue(p.onGameOver(0L))
        assertFalse("far too soon", p.onGameOver(10_000L))
        assertFalse(p.onGameOver(89_000L))
        assertTrue("once the cooldown is up", p.onGameOver(95_000L))
    }

    @Test
    fun `buying the removal stops interstitials immediately`() {
        val p = policy(
            AdConfig(
                graceGameOvers = 0,
                interstitialEveryNGameOvers = 1,
                minMsBetweenInterstitials = 0L
            )
        )
        assertTrue(p.onGameOver(0L))
        p.updateEntitlements(Entitlements(adsRemoved = true))
        assertFalse("a payer must never see one again", p.onGameOver(1_000L))
        assertFalse(p.onGameOver(2_000L))
        assertTrue(p.adsRemoved)
    }

    @Test
    fun `the cadence keeps counting while ads are removed`() {
        // So that a refund, or a future entitlement change, does not suddenly produce a
        // burst of ads from a counter that stalled.
        val p = policy(
            AdConfig(
                graceGameOvers = 0,
                interstitialEveryNGameOvers = 2,
                minMsBetweenInterstitials = 0L
            ),
            adsRemoved = true
        )
        assertFalse(p.onGameOver(0L))
        assertFalse(p.onGameOver(1_000L))
        p.updateEntitlements(Entitlements(adsRemoved = false))
        assertFalse("third game over is odd-numbered", p.onGameOver(2_000L))
        assertTrue("fourth lands on the cadence", p.onGameOver(3_000L))
    }

    // ------------------------------------------------------------ rewarded continues

    @Test
    fun `a continue is offered once per run`() {
        val p = policy(AdConfig(maxContinuesPerRun = 1))
        p.onRunStarted()
        assertTrue(p.canOfferContinue(rewardedReady = true))
        p.onContinueGranted()
        assertFalse("only one rescue per run", p.canOfferContinue(rewardedReady = true))

        p.onRunStarted()
        assertTrue("the next run starts fresh", p.canOfferContinue(rewardedReady = true))
    }

    @Test
    fun `no continue is offered when nothing is cached`() {
        val p = policy()
        p.onRunStarted()
        assertFalse(
            "offering a button that cannot deliver is worse than not offering",
            p.canOfferContinue(rewardedReady = false)
        )
    }

    @Test
    fun `removing ads does not take away rewarded continues`() {
        // Paying buys freedom from interruption, not from an optional bonus the player
        // went looking for. Taking it away punishes them for paying.
        val p = policy(adsRemoved = true)
        p.onRunStarted()
        assertTrue(p.canOfferContinue(rewardedReady = true))
    }

    // ------------------------------------------------------------ rewarded charges

    @Test
    fun `charge rewards are capped per day`() {
        val p = policy(AdConfig(maxChargeRewardsPerDay = 3))
        var t = 0L
        repeat(3) {
            assertTrue(p.canOfferChargeReward(t, rewardedReady = true))
            p.onChargeRewardGranted(t)
            t += 60_000L
        }
        assertFalse("the daily cap should bite", p.canOfferChargeReward(t, rewardedReady = true))
        assertEquals(0, p.chargeRewardsRemaining(t))
    }

    @Test
    fun `the daily cap resets a day later`() {
        val p = policy(AdConfig(maxChargeRewardsPerDay = 2))
        p.onChargeRewardGranted(0L)
        p.onChargeRewardGranted(1_000L)
        assertFalse(p.canOfferChargeReward(2_000L, rewardedReady = true))
        assertTrue("a day on, the allowance is back", p.canOfferChargeReward(day + 1, true))
    }

    @Test
    fun `winding the clock backwards does not hand out a free reset`() {
        // The same weakness power regeneration has, deliberately closed here: the counter
        // keys off elapsed time from the first grant, not a calendar date.
        val p = policy(AdConfig(maxChargeRewardsPerDay = 2))
        p.onChargeRewardGranted(day)
        p.onChargeRewardGranted(day + 1_000L)
        assertFalse(p.canOfferChargeReward(day + 2_000L, rewardedReady = true))

        // Player winds the clock back. The window re-anchors so regeneration is not
        // stalled forever, but the allowance must not come back for free.
        assertFalse(
            "winding the clock back must not refill the allowance",
            p.canOfferChargeReward(0L, rewardedReady = true)
        )
        assertEquals(0, p.chargeRewardsRemaining(0L))

        // Waiting a genuine day from the new anchor does restore it.
        assertTrue(p.canOfferChargeReward(day, rewardedReady = true))
    }

    @Test
    fun `remaining count is reported for the UI`() {
        val p = policy(AdConfig(maxChargeRewardsPerDay = 6))
        assertEquals(6, p.chargeRewardsRemaining(0L))
        p.onChargeRewardGranted(0L)
        assertEquals(5, p.chargeRewardsRemaining(1_000L))
    }

    // ------------------------------------------------------------ gateways

    @Test
    fun `the no-op gateways leave the game entirely unmonetised`() {
        assertFalse(NoOpAdGateway.rewardedReady)

        var result: RewardResult? = null
        NoOpAdGateway.showRewarded(RewardPlacement.CONTINUE_RUN) { result = it }
        assertEquals("never leave the caller hanging", RewardResult.FAILED, result)

        var closed = false
        NoOpAdGateway.showInterstitial { closed = true }
        assertTrue("the callback must fire even with no ad", closed)

        assertFalse(NoOpBillingGateway.entitlements.adsRemoved)
        assertEquals(null, NoOpBillingGateway.priceLabel(Product.REMOVE_ADS))
    }
}
