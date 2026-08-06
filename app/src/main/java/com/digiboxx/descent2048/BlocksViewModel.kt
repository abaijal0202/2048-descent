package com.digiboxx.descent2048

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digiboxx.descent2048.blocks.BlocksEngine
import com.digiboxx.descent2048.blocks.BlocksEvent
import com.digiboxx.descent2048.blocks.BlocksSnapshot
import com.digiboxx.descent2048.blocks.BlocksStatus
import com.digiboxx.descent2048.data.GameStorage
import com.digiboxx.descent2048.feedback.Haptics
import com.digiboxx.descent2048.monetize.MonetizeHost
import com.digiboxx.descent2048.monetize.Product
import com.digiboxx.descent2048.monetize.PurchaseResult
import com.digiboxx.descent2048.monetize.RewardPlacement
import com.digiboxx.descent2048.monetize.RewardResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Drives 2048 Blocks. Same polling-and-revision shape as the other two view models. */
class BlocksViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = GameStorage(application)
    private val haptics = Haptics(application)
    private val engine = BlocksEngine()

    var snapshot: BlocksSnapshot by mutableStateOf(engine.snapshot())
        private set

    var highScore: Int by mutableStateOf(storage.blocksHighScore)
        private set

    var hapticsEnabled: Boolean by mutableStateOf(true)
        private set


    /** True while a rewarded ad is on screen, so the UI can disable its buttons. */
    var adInFlight: Boolean by mutableStateOf(false)
        private set

    /** True when a rewarded continue can be offered on the game-over screen. */
    var canContinue: Boolean by mutableStateOf(false)
        private set

    var adsRemoved: Boolean by mutableStateOf(MonetizeHost.billing.entitlements.adsRemoved)
        private set

    fun removeAdsPrice(): String? = MonetizeHost.billing.priceLabel(Product.REMOVE_ADS)

    fun buyRemoveAds() {
        MonetizeHost.billing.purchase(Product.REMOVE_ADS) { result ->
            if (result == PurchaseResult.PURCHASED || result == PurchaseResult.ALREADY_OWNED) {
                MonetizeHost.refreshEntitlements()
                adsRemoved = true
            }
        }
    }

    private var scoreCommitted = false
    private var lastRevision = -1

    init {
        viewModelScope.launch {
            while (isActive) {
                step()
                delay(if (engine.status == BlocksStatus.PLAYING) FRAME_DELAY_MS else IDLE_DELAY_MS)
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun step() {
        val events = engine.tick(now())
        if (events.isNotEmpty()) handleEvents(events)
        if (engine.revision != lastRevision) {
            lastRevision = engine.revision
            snapshot = engine.snapshot()
        }
    }

    private fun refresh() {
        lastRevision = engine.revision
        snapshot = engine.snapshot()
    }

    private fun handleEvents(events: List<BlocksEvent>) {
        for (event in events) {
            when (event) {
                is BlocksEvent.Merged -> haptics.merge(event.comboDepth)
                is BlocksEvent.LinesCleared -> haptics.merge(event.count + 1)
                is BlocksEvent.LevelUp -> haptics.speedUp()
                is BlocksEvent.TargetReached -> haptics.trophy()
                is BlocksEvent.Locked -> haptics.land()
                is BlocksEvent.Rotated -> haptics.land()
                is BlocksEvent.Blocked -> haptics.blocked()
                is BlocksEvent.GameOver -> {
                    haptics.gameOver()
                    commitScore()
                    canContinue =
                        MonetizeHost.policy.canOfferContinue(MonetizeHost.ads.rewardedReady)
                    if (MonetizeHost.policy.onGameOver(now())) {
                        MonetizeHost.ads.showInterstitial { MonetizeHost.ads.preload() }
                    }
                }
            }
        }
    }

    private fun commitScore() {
        if (scoreCommitted) return
        scoreCommitted = true
        if (engine.score > storage.blocksHighScore) {
            storage.blocksHighScore = engine.score
            highScore = engine.score
        }
    }

    // ------------------------------------------------------------ intents

    /** Watch a rewarded ad to clear the top of the stack and carry on. */
    fun continueWithAd() {
        if (adInFlight || !canContinue) return
        adInFlight = true
        MonetizeHost.ads.showRewarded(RewardPlacement.CONTINUE_RUN) { result ->
            adInFlight = false
            if (result == RewardResult.EARNED && engine.revive(now())) {
                MonetizeHost.policy.onContinueGranted()
                canContinue = false
                scoreCommitted = false
                refresh()
            }
            MonetizeHost.ads.preload()
        }
    }

    fun startGame() {
        scoreCommitted = false
        canContinue = false
        MonetizeHost.policy.onRunStarted()
        MonetizeHost.ads.preload()
        engine.start(now())
        refresh()
    }

    fun move(direction: Int) {
        engine.move(direction)
        refresh()
    }

    fun rotate() {
        engine.rotate()
        refresh()
    }

    fun hardDrop() {
        engine.hardDrop(now())
        refresh()
    }

    fun setSoftDrop(enabled: Boolean) {
        engine.softDrop = enabled
    }

    fun pause() {
        engine.pause()
        refresh()
    }

    fun resume() {
        engine.resume(now())
        refresh()
    }

    fun toggleHaptics() {
        hapticsEnabled = !hapticsEnabled
        haptics.enabled = hapticsEnabled
    }

    // ------------------------------------------------------------ lifecycle

    fun onPause() {
        engine.pause()
        refresh()
    }

    fun onResume() {
        engine.resyncClock(now())
        refresh()
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
        const val IDLE_DELAY_MS = 200L
    }
}
