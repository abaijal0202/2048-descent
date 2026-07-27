package com.digiboxx.descent2048.game

/** Board dimensions. Changing these is safe — nothing hard-codes 7 or 13. */
const val COLS = 7
const val ROWS = 13

/** Spawn column for every new tile. */
val START_COL = COLS / 2

/** Milliseconds between gravity steps at speed multiplier 1.0. */
const val BASE_INTERVAL_MS = 750L

/** Fastest permitted interval, so high speed multipliers stay playable. */
const val MIN_INTERVAL_MS = 45L

/** Soft drop divides the interval by this. */
const val SOFT_DROP_FACTOR = 6

/** Slow power multiplies the interval by this. */
const val SLOW_FACTOR = 1.8

/** How long one Slow activation lasts. */
const val SLOW_DURATION_MS = 30_000L

/** Charges regenerate one unit per this interval. */
const val POWER_REGEN_MS = 30L * 60L * 1000L

/** Maximum stored charges per power. */
const val POWER_MAX_CHARGES = 3

/** Value that ends the "climb" phase and becomes the locked trophy. */
const val TROPHY_VALUE = 2048

/**
 * Set to false to revert to vertical-only merging (the original v1 web behaviour).
 * With true, any two equal orthogonally-adjacent tiles merge.
 */
const val MERGE_HORIZONTAL = true

/** Weighted spawn table: value to relative weight. */
val SPAWN_TABLE: List<Pair<Int, Int>> = listOf(
    2 to 35, 4 to 30, 8 to 15, 16 to 10, 32 to 6, 64 to 4
)

/**
 * A settled tile on the board.
 *
 * Identity matters: the merge resolver tracks the "active" tile by reference as it
 * cascades, and gravity moves the same instances rather than recreating them, so a
 * tile keeps its [id] across settling. That is what lets the UI animate movement.
 *
 * [locked] marks the 2048 trophy, which never merges, never moves, and blocks gravity.
 */
class Tile(
    val id: Long,
    var value: Int,
    val locked: Boolean = false
) {
    /** Timestamp of the most recent merge into this tile; drives the pop animation. */
    var poppedAtMs: Long = 0L
}

/** The tile currently descending. Not part of the grid until it lands. */
data class FallingTile(
    val value: Int,
    val row: Int,
    val col: Int
)

enum class GameStatus { READY, PLAYING, CELEBRATING, GAME_OVER }

/** One-shot things the UI reacts to (sound, haptics, overlays). */
sealed interface GameEvent {
    data class Merged(val value: Int, val row: Int, val col: Int) : GameEvent
    data class SpeedIncreased(val milestone: Int, val newMultiplier: Double) : GameEvent
    data object TrophyEarned : GameEvent
    data object Landed : GameEvent
    data object GameOver : GameEvent
    data object Blocked : GameEvent
}

/** Which of the two powers is being referenced. */
enum class PowerType { DELETE_ROW, SLOW }

/**
 * Immutable render model. The engine mutates internally for speed; the UI only ever
 * sees one of these, so Compose recomposition stays predictable.
 */
data class BoardSnapshot(
    val cells: List<CellView>,
    val falling: FallingTile?,
    val landingRow: Int,
    val willMergeOnLanding: Boolean,
    val nextValues: List<Int>,
    val score: Int,
    val bestTile: Int,
    val speedMultiplier: Double,
    val status: GameStatus,
    val deleteCharges: Int,
    val slowCharges: Int,
    val deleteRegenRemainingMs: Long,
    val slowRegenRemainingMs: Long,
    val slowActiveRemainingMs: Long
)

data class CellView(
    val id: Long,
    val row: Int,
    val col: Int,
    val value: Int,
    val locked: Boolean,
    val poppedAtMs: Long
)
