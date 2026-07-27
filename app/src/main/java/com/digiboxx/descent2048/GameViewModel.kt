package com.digiboxx.descent2048

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digiboxx.descent2048.data.GameStorage
import com.digiboxx.descent2048.feedback.Haptics
import com.digiboxx.descent2048.game.BoardSnapshot
import com.digiboxx.descent2048.game.GameEngine
import com.digiboxx.descent2048.game.GameEvent
import com.digiboxx.descent2048.game.GameStatus
import com.digiboxx.descent2048.game.HudTimers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = GameStorage(application)
    private val haptics = Haptics(application)

    private var engine = newEngine()

    var snapshot: BoardSnapshot by mutableStateOf(engine.snapshot(now()))
        private set

    /**
     * Countdown readouts, held separately from [snapshot] so that a ticking "+1 in 12:34"
     * label cannot force the whole board to recompose.
     */
    var hud: HudTimers by mutableStateOf(HudTimers())
        private set

    var highScore: Int by mutableStateOf(storage.highScore)
        private set

    /** True once the player has tapped Delete Row and is choosing which row to clear. */
    var deleteArmed: Boolean by mutableStateOf(false)
        private set

    var hapticsEnabled: Boolean by mutableStateOf(true)
        private set

    private var scoreCommitted = false

    /**
     * Last engine revision rendered. The loop polls at 60fps but the board changes only a
     * few times a second, so comparing this is what keeps us from rebuilding a snapshot —
     * and allocating a CellView per tile — on every single frame.
     */
    private var lastRevision = -1

    init {
        restoreOrPrepare()
        viewModelScope.launch {
            while (isActive) {
                step()
                // Only a live board needs 60fps. Idling the menus and the game-over
                // screen at 5fps stops the app burning battery on a static picture.
                delay(if (engine.status.isLive()) FRAME_DELAY_MS else IDLE_DELAY_MS)
            }
        }
    }

    private fun GameStatus.isLive() =
        this == GameStatus.PLAYING || this == GameStatus.CELEBRATING

    private fun newEngine() = GameEngine(
        initialDeleteBank = storage.loadDeleteBank(),
        initialSlowBank = storage.loadSlowBank()
    )

    private fun now() = System.currentTimeMillis()

    /** Bring back an interrupted run if there is one, otherwise sit on the title screen. */
    private fun restoreOrPrepare() {
        val saved = storage.loadGame()
        if (saved != null) {
            engine.importState(saved, now())
            scoreCommitted = false
        }
        refreshSnapshot()
    }

    private fun step() {
        val nowMs = now()
        val events = engine.tick(nowMs)
        if (events.isNotEmpty()) handleEvents(events)
        if (engine.revision != lastRevision) {
            lastRevision = engine.revision
            snapshot = engine.snapshot(nowMs)
        }
        hud = engine.hudTimers(nowMs)
    }

    /** Force a snapshot rebuild after a direct input, without waiting for the next tick. */
    private fun refreshSnapshot() {
        val nowMs = now()
        lastRevision = engine.revision
        snapshot = engine.snapshot(nowMs)
        hud = engine.hudTimers(nowMs)
    }

    private fun handleEvents(events: List<GameEvent>) {
        for (event in events) {
            when (event) {
                is GameEvent.Merged -> haptics.merge(event.comboDepth)
                is GameEvent.Landed -> haptics.land()
                is GameEvent.Blocked -> haptics.blocked()
                is GameEvent.PowerUsed -> haptics.power()
                is GameEvent.SpeedIncreased -> haptics.speedUp()
                is GameEvent.TrophyEarned -> {
                    storage.trophyEarned = true
                    haptics.trophy()
                }
                is GameEvent.GameOver -> {
                    haptics.gameOver()
                    commitScore()
                    // The run is finished; nothing left worth restoring.
                    storage.clearSavedGame()
                }
            }
        }
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

    /** Write the run to disk so it survives the process being killed in the background. */
    private fun persistRun() {
        val state = engine.exportState()
        if (state != null) storage.saveGame(state) else storage.clearSavedGame()
    }

    // ------------------------------------------------------------ intents

    fun startGame() {
        scoreCommitted = false
        deleteArmed = false
        storage.clearSavedGame()
        engine = newEngine()
        engine.start(now())
        refreshSnapshot()
    }

    fun move(direction: Int) {
        engine.move(direction)
        refreshSnapshot()
    }

    fun moveTo(column: Int) {
        engine.moveTo(column)
        refreshSnapshot()
    }

    fun hardDrop() {
        // A drop while choosing a row would throw the tile away mid-decision.
        if (deleteArmed) return
        engine.hardDrop(now())
        refreshSnapshot()
    }

    fun setSoftDrop(enabled: Boolean) {
        engine.softDrop = enabled && !deleteArmed
    }

    fun pause() {
        engine.pause()
        deleteArmed = false
        persistRun()
        persistPowers()
        refreshSnapshot()
    }

    fun resume() {
        engine.resume(now())
        refreshSnapshot()
    }

    fun toggleHaptics() {
        hapticsEnabled = !hapticsEnabled
        haptics.enabled = hapticsEnabled
    }

    /** Enter row-picking mode. The actual clear happens on the follow-up tap. */
    fun armDeleteRow() {
        if (engine.status != GameStatus.PLAYING) return
        if (snapshot.deleteCharges <= 0) return
        deleteArmed = !deleteArmed
        engine.softDrop = false
    }

    fun deleteRowAt(row: Int) {
        if (!deleteArmed) return
        deleteArmed = false
        if (engine.useDeleteRowAt(row, now())) {
            persistPowers()
            persistRun()
        }
        refreshSnapshot()
    }

    /** True when [row] currently holds something Delete Row could remove. */
    fun canDeleteRow(row: Int): Boolean = engine.canDeleteRow(row)

    fun useSlow() {
        if (engine.useSlow(now())) {
            persistPowers()
            refreshSnapshot()
        }
    }

    /** Column of the falling tile, for anchoring drag gestures. */
    fun fallingColumn(): Int = engine.falling?.col ?: 0

    // ------------------------------------------------------------ lifecycle

    /**
     * Pausing matters for more than battery: without it the tile keeps descending
     * while the player is in another app, and they return to a lost game.
     *
     * The run is written to disk here too, because a backgrounded process can be killed
     * without any further warning.
     */
    fun onPause() {
        engine.pause()
        deleteArmed = false
        persistRun()
        persistPowers()
        refreshSnapshot()
    }

    /**
     * Deliberately does not resume play. Coming back to a tile already halfway down,
     * with no chance to read the board, loses runs — the player taps Resume when ready.
     */
    fun onResume() {
        engine.resyncClock(now())
        refreshSnapshot()
    }

    private companion object {
        const val FRAME_DELAY_MS = 16L
        const val IDLE_DELAY_MS = 200L
    }
}
