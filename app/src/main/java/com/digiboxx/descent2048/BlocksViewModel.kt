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

    fun startGame() {
        scoreCommitted = false
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
