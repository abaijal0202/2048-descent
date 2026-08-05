package com.digiboxx.descent2048

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digiboxx.descent2048.data.GameStorage
import com.digiboxx.descent2048.feedback.Haptics
import com.digiboxx.descent2048.merge.MergeEngine
import com.digiboxx.descent2048.merge.MergeEvent
import com.digiboxx.descent2048.merge.MergeSnapshot
import com.digiboxx.descent2048.merge.MergeStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives 2048 Merge.
 *
 * Same shape as [GameViewModel]: a polling loop that hands the engine the wall clock and
 * only rebuilds a snapshot when the engine's revision moves. The engine self-throttles
 * with a fixed timestep, so polling faster than the simulation costs nothing.
 */
class MergeViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = GameStorage(application)
    private val haptics = Haptics(application)
    private val engine = MergeEngine()

    var snapshot: MergeSnapshot by mutableStateOf(engine.snapshot(now()))
        private set

    var highScore: Int by mutableStateOf(storage.mergeHighScore)
        private set

    var hapticsEnabled: Boolean by mutableStateOf(true)
        private set

    private var scoreCommitted = false
    private var lastRevision = -1

    init {
        viewModelScope.launch {
            while (isActive) {
                step()
                delay(if (engine.status == MergeStatus.PLAYING) FRAME_DELAY_MS else IDLE_DELAY_MS)
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun step() {
        val nowMs = now()
        val events = engine.update(nowMs)
        if (events.isNotEmpty()) handleEvents(events)
        if (engine.revision != lastRevision) {
            lastRevision = engine.revision
            snapshot = engine.snapshot(nowMs)
        }
    }

    private fun refresh() {
        val nowMs = now()
        lastRevision = engine.revision
        snapshot = engine.snapshot(nowMs)
    }

    private fun handleEvents(events: List<MergeEvent>) {
        for (event in events) {
            when (event) {
                is MergeEvent.Merged -> haptics.merge(event.comboDepth)
                is MergeEvent.Dropped -> haptics.land()
                is MergeEvent.TargetReached -> haptics.trophy()
                is MergeEvent.GameOver -> {
                    haptics.gameOver()
                    commitScore()
                }
            }
        }
    }

    private fun commitScore() {
        if (scoreCommitted) return
        scoreCommitted = true
        if (engine.score > storage.mergeHighScore) {
            storage.mergeHighScore = engine.score
            highScore = engine.score
        }
        if (engine.bestValue > storage.mergeBestValue) storage.mergeBestValue = engine.bestValue
    }

    // ------------------------------------------------------------ intents

    fun startGame() {
        scoreCommitted = false
        engine.start(now())
        refresh()
    }

    /** [x] is a fraction of the bowl's width. */
    fun aimAt(x: Float) {
        engine.aimAt(x)
        refresh()
    }

    fun drop() {
        if (engine.drop(now())) refresh()
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

    /**
     * Re-anchors the clock but does not resume play. A physics pile is not something you
     * want advancing while the player is reading a notification.
     */
    fun onResume() {
        engine.resyncClock(now())
        refresh()
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
        const val IDLE_DELAY_MS = 200L
    }
}
