package com.digiboxx.descent2048.data

import android.content.Context
import androidx.core.content.edit
import com.digiboxx.descent2048.game.POWER_MAX_CHARGES
import com.digiboxx.descent2048.game.PowerBank

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

    private companion object {
        const val NO_TIMER = -1L
        const val KEY_HIGH_SCORE = "high_score"
        const val KEY_BEST_TILE = "best_tile"
        const val KEY_TROPHY = "trophy_earned"
        const val KEY_DELETE_CHARGES = "delete_charges"
        const val KEY_DELETE_REGEN = "delete_regen_at"
        const val KEY_SLOW_CHARGES = "slow_charges"
        const val KEY_SLOW_REGEN = "slow_regen_at"
    }
}
