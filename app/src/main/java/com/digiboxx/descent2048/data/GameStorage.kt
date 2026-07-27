package com.digiboxx.descent2048.data

import android.content.Context
import androidx.core.content.edit
import com.digiboxx.descent2048.game.POWER_MAX_CHARGES
import com.digiboxx.descent2048.game.PowerBank
import com.digiboxx.descent2048.game.SavedCell
import com.digiboxx.descent2048.game.SavedGame

/**
 * Local persistence.
 *
 * Power charges are stored as an absolute "next regen at" timestamp rather than a
 * countdown, so charges keep accruing while the app is closed. That is the whole point
 * of a 30-minute regen: the player should come back to a fuller bank.
 *
 * Everything here is client-side and therefore trivially editable on a rooted device.
 * That is fine while charges are earned. If charges ever become purchasable, the
 * balance must move server-side — see the note in the README.
 */
class GameStorage(context: Context) {

    private val prefs = context.getSharedPreferences("descent2048", Context.MODE_PRIVATE)

    var highScore: Int
        get() = prefs.getInt(KEY_HIGH_SCORE, 0)
        set(value) = prefs.edit { putInt(KEY_HIGH_SCORE, value) }

    var bestTile: Int
        get() = prefs.getInt(KEY_BEST_TILE, 0)
        set(value) = prefs.edit { putInt(KEY_BEST_TILE, value) }

    var trophyEarned: Boolean
        get() = prefs.getBoolean(KEY_TROPHY, false)
        set(value) = prefs.edit { putBoolean(KEY_TROPHY, value) }

    fun loadDeleteBank(): PowerBank = loadBank(KEY_DELETE_CHARGES, KEY_DELETE_REGEN)
    fun loadSlowBank(): PowerBank = loadBank(KEY_SLOW_CHARGES, KEY_SLOW_REGEN)

    fun saveDeleteBank(bank: PowerBank) = saveBank(bank, KEY_DELETE_CHARGES, KEY_DELETE_REGEN)
    fun saveSlowBank(bank: PowerBank) = saveBank(bank, KEY_SLOW_CHARGES, KEY_SLOW_REGEN)

    private fun loadBank(chargesKey: String, regenKey: String): PowerBank {
        val charges = prefs.getInt(chargesKey, POWER_MAX_CHARGES).coerceIn(0, POWER_MAX_CHARGES)
        val regenAt = prefs.getLong(regenKey, NO_TIMER)
        return PowerBank(
            charges = charges,
            nextRegenAtMs = if (regenAt == NO_TIMER || charges >= POWER_MAX_CHARGES) null else regenAt
        )
    }

    private fun saveBank(bank: PowerBank, chargesKey: String, regenKey: String) {
        prefs.edit {
            putInt(chargesKey, bank.charges)
            putLong(regenKey, bank.nextRegenAtMs ?: NO_TIMER)
        }
    }

    // ------------------------------------------------------------ run in progress

    /**
     * Store the current run so it survives the process being killed.
     *
     * Encoded as one flat string rather than pulling in a JSON dependency: the shape is
     * fixed, small, and read exactly once at startup. [SAVE_VERSION] guards against a
     * future format change being fed to this parser — anything unrecognised is dropped
     * and the player simply gets a fresh board.
     */
    fun saveGame(state: SavedGame) {
        val cells = state.cells.joinToString(";") {
            "${it.row},${it.col},${it.value},${if (it.locked) 1 else 0}"
        }
        val encoded = listOf(
            SAVE_VERSION.toString(),
            state.score.toString(),
            state.bestTile.toString(),
            state.speedMultiplier.toString(),
            state.fallingValue.toString(),
            state.fallingRow.toString(),
            state.fallingCol.toString(),
            state.queue.joinToString(","),
            state.trophies.joinToString(","),
            state.passedMilestones.joinToString(","),
            cells
        ).joinToString("|")
        prefs.edit { putString(KEY_SAVED_GAME, encoded) }
    }

    fun clearSavedGame() = prefs.edit { remove(KEY_SAVED_GAME) }

    fun loadGame(): SavedGame? {
        val raw = prefs.getString(KEY_SAVED_GAME, null) ?: return null
        return try {
            decode(raw)
        } catch (_: Exception) {
            // A corrupt save must never crash the launch path.
            clearSavedGame()
            null
        }
    }

    private fun decode(raw: String): SavedGame? {
        val parts = raw.split("|")
        if (parts.size != 11) return null
        if (parts[0].toIntOrNull() != SAVE_VERSION) return null

        val cells = parts[10].split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { chunk ->
                val f = chunk.split(",")
                if (f.size != 4) return@mapNotNull null
                SavedCell(
                    row = f[0].toIntOrNull() ?: return@mapNotNull null,
                    col = f[1].toIntOrNull() ?: return@mapNotNull null,
                    value = f[2].toIntOrNull() ?: return@mapNotNull null,
                    locked = f[3] == "1"
                )
            }

        return SavedGame(
            cells = cells,
            fallingValue = parts[4].toIntOrNull() ?: return null,
            fallingRow = parts[5].toIntOrNull() ?: return null,
            fallingCol = parts[6].toIntOrNull() ?: return null,
            queue = parts[7].splitInts(),
            score = parts[1].toIntOrNull() ?: return null,
            bestTile = parts[2].toIntOrNull() ?: return null,
            trophies = parts[8].splitInts(),
            passedMilestones = parts[9].splitInts(),
            speedMultiplier = parts[3].toDoubleOrNull() ?: 1.0
        )
    }

    private fun String.splitInts(): List<Int> =
        split(",").filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }

    private companion object {
        const val NO_TIMER = -1L
        const val SAVE_VERSION = 1
        const val KEY_HIGH_SCORE = "high_score"
        const val KEY_BEST_TILE = "best_tile"
        const val KEY_TROPHY = "trophy_earned"
        const val KEY_DELETE_CHARGES = "delete_charges"
        const val KEY_DELETE_REGEN = "delete_regen_at"
        const val KEY_SLOW_CHARGES = "slow_charges"
        const val KEY_SLOW_REGEN = "slow_regen_at"
        const val KEY_SAVED_GAME = "saved_game"
    }
}
