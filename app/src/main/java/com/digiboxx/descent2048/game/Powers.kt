package com.digiboxx.descent2048.game

/**
 * Charge bank for one power.
 *
 * Regeneration is deliberately *not* driven by a running timer. It stores the wall-clock
 * instant at which the next charge is due and derives everything from [nowMs] on demand.
 * That means charges continue accruing while the app is closed, and a player who leaves
 * for two hours comes back to a full bank — which is the behaviour the 30-minute
 * regen rule implies.
 *
 * Because it is anchored to wall-clock time it is also trivially cheatable by moving the
 * device clock forward. See [PowerBank.detectClockRollback] for the mitigation.
 */
data class PowerBank(
    val charges: Int = POWER_MAX_CHARGES,
    /** Wall-clock ms at which the next charge lands, or null when the bank is full. */
    val nextRegenAtMs: Long? = null
) {

    fun spend(nowMs: Long): PowerBank {
        if (charges <= 0) return this
        val remaining = charges - 1
        // Arm the timer only if it was not already running, so spending two charges
        // back to back does not restart the clock on the first one.
        val nextAt = nextRegenAtMs ?: (nowMs + POWER_REGEN_MS)
        return copy(charges = remaining, nextRegenAtMs = nextAt)
    }

    /**
     * Brings the bank up to date for [nowMs], granting every charge that has come due.
     * Safe to call as often as you like; calling it once an hour or 60 times a second
     * gives the same result.
     */
    fun refresh(nowMs: Long): PowerBank {
        var current = this
        var guard = 0
        while (current.charges < POWER_MAX_CHARGES &&
            current.nextRegenAtMs != null &&
            nowMs >= current.nextRegenAtMs!! &&
            guard++ < 1000
        ) {
            val granted = current.charges + 1
            val nextAt = if (granted < POWER_MAX_CHARGES) {
                current.nextRegenAtMs!! + POWER_REGEN_MS
            } else {
                null
            }
            current = current.copy(charges = granted, nextRegenAtMs = nextAt)
        }
        // A full bank never has a pending timer.
        if (current.charges >= POWER_MAX_CHARGES && current.nextRegenAtMs != null) {
            current = current.copy(nextRegenAtMs = null)
        }
        return current
    }

    /** Milliseconds until the next charge, or 0 when full or already due. */
    fun regenRemainingMs(nowMs: Long): Long {
        val target = nextRegenAtMs ?: return 0L
        if (charges >= POWER_MAX_CHARGES) return 0L
        return (target - nowMs).coerceAtLeast(0L)
    }

    /**
     * If the stored deadline is further away than a full regen cycle, the device clock
     * has moved backwards (timezone change, manual edit, or NTP correction). Re-anchor
     * rather than leaving the player unable to ever regenerate.
     */
    fun detectClockRollback(nowMs: Long): PowerBank {
        val target = nextRegenAtMs ?: return this
        return if (target - nowMs > POWER_REGEN_MS) {
            copy(nextRegenAtMs = nowMs + POWER_REGEN_MS)
        } else {
            this
        }
    }
}
