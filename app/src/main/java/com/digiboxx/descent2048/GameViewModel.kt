package com.digiboxx.descent2048

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digiboxx.descent2048.data.GameStorage
import com.digiboxx.descent2048.game.BoardSnapshot
import com.digiboxx.descent2048.game.GameEngine
import com.digiboxx.descent2048.game.GameEvent
import com.digiboxx.descent2048.game.GameStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = GameStorage(application)

    private var engine = GameEngine(
        initialDeleteBank = storage.loadDeleteBank(),
        initialSlowBank = storage.loadSlowBank()
    )

    var snapshot: BoardSnapshot by mutableStateOf(engine.snapshot(now()))
        private set

    var highScore: Int by mutableStateOf(storage.highScore)
        private set

    /** Set when the trophy is won so the UI can show the celebration overlay. */
    var celebrating: Boolean by mutableStateOf(false)
        private set

    private var running = true
    private var scoreCommitted = false

    init {
        viewModelScope.launch {
            while (isActive) {
                if (running) step()
                // ~60fps. The engine self-throttles, so this only polls.
                delay(FRAME_DELAY_MS)
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun step() {
        val nowMs = now()
        val events = engine.tick(nowMs)
        handleEvents(events)
        snapshot = engine.snapshot(nowMs)
    }

    private fun handleEvents(events: List<GameEvent>) {
        for (event in events) {
            when (event) {
                is GameEvent.TrophyEarned -> {
                    celebrating = true
                    storage.trophyEarned = true
                }
                is GameEvent.GameOver -> commitScore()
                else -> Unit
            }
        }
        if (engine.status != GameStatus.CELEBRATING) celebrating = false
    }

    private fun commitScore() {
        if (scoreCommitted) return
        scoreCommitted = true
        if (engine.score > storage.highScore) {
            storage.highScore = engine.score
            highScore = engine.score
        }
        if (engine.bestTile > storage.bestTile) storage.bestTile = engine.bestTile
        persistPowers()
    }

    private fun persistPowers() {
        storage.saveDeleteBank(engine.deleteBank)
        storage.saveSlowBank(engine.slowBank)
    }

    // ------------------------------------------------------------ intents

    fun startGame() {
        scoreCommitted = false
        celebrating = false
        engine = GameEngine(
            initialDeleteBank = storage.loadDeleteBank(),
            initialSlowBank = storage.loadSlowBank()
        )
        engine.start(now())
        snapshot = engine.snapshot(now())
    }

    fun move(direction: Int) {
        engine.move(direction)
        snapshot = engine.snapshot(now())
    }

    fun moveTo(column: Int) {
        engine.moveTo(column)
        snapshot = engine.snapshot(now())
    }

    fun hardDrop() {
        engine.hardDrop(now())
        snapshot = engine.snapshot(now())
    }

    fun setSoftDrop(enabled: Boolean) {
        engine.softDrop = enabled
    }

    fun useDeleteRow() {
        if (engine.useDeleteRow(now())) {
            persistPowers()
            snapshot = engine.snapshot(now())
        }
    }

    fun useSlow() {
        if (engine.useSlow(now())) {
            persistPowers()
            snapshot = engine.snapshot(now())
        }
    }

    /** Column of the falling tile, for anchoring drag gestures. */
    fun fallingColumn(): Int = engine.falling?.col ?: 0

    // ------------------------------------------------------------ lifecycle

    /**
     * Pausing matters for more than battery: without it the tile keeps descending
     * while the player is in another app, and they return to a lost game.
     */
    fun onPause() {
        running = false
        engine.softDrop = false
        persistPowers()
    }

    fun onResume() {
        running = true
        // Re-anchor the gravity clock so time spent backgrounded is not applied as one
        // enormous catch-up step.
        engine.resyncClock(now())
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
    }
}
