package com.digiboxx.descent2048.game

/**
 * Board dimensions. Changing these is safe — nothing hard-codes the numbers.
 *
 * 6 x 10 rather than the original 7 x 13 because cell size is what makes a four-digit
 * tile readable, and the binding constraint is height. Dropping to 10 rows is what buys
 * the size; dropping to 6 columns is what stops the width budget capping it straight
 * back. See the sizing note in GameScreen.
 */
const val COLS = 6
const val ROWS = 10

/** Preferred spawn column. Tiles shift to the nearest free column if this one is full. */
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

/**
 * How long one Plan activation lasts.
 *
 * Plan suspends gravity entirely and hands the player a plain 2048 board for the
 * duration: slide in any of four directions, equal tiles merge on contact, nothing
 * falls. When the timer runs out gravity comes back and the whole board settles and
 * cascades at once, which is where the payoff is.
 */
const val PLAN_DURATION_MS = 15_000L

/**
 * The ladder of trophy values.
 *
 * Reaching each rung clears the board and locks a permanent trophy into the next slot
 * along the bottom-left. Having more than one rung is the point: with a single 2048 the
 * game had nowhere to go afterwards except faster-until-you-die, and the most exciting
 * moment was followed by the least interesting phase.
 */
val TROPHY_LADDER: List<Int> = listOf(2048, 4096, 8192)

/** First rung. Kept as a named constant because so much of the game refers to it. */
const val TROPHY_VALUE = 2048

/** Values that compound the fall rate when first reached. */
val SPEED_MILESTONES: List<Int> = listOf(512, 1024, 2048, 4096, 8192)

/** How much each crossed milestone compounds the fall rate. */
const val SPEED_STEP = 1.2

/** Each trophy earned adds this much to the score multiplier. */
const val TROPHY_SCORE_BONUS = 0.5

/**
 * A cascade pays progressively more: the nth merge in one chain scores at
 * `1 + (n - 1) * COMBO_STEP`, capped at [MAX_COMBO_MULTIPLIER].
 *
 * Without this a five-deep cascade paid exactly the same as five unrelated merges, so
 * the single most skilful thing in the game was mechanically invisible.
 */
const val COMBO_STEP = 0.5
const val MAX_COMBO_MULTIPLIER = 4.0

/**
 * Rows of stack removed by a rewarded continue.
 *
 * Taken from the top of the pile rather than the bottom, for the same reason Delete Row
 * is player-targeted: the floor is where the large tiles live, and a revive that razes
 * your foundation is not much of a rescue.
 */
const val REVIVE_ROWS = 3

/** At or below this many empty rows above the spawn column, the UI warns the player. */
const val DANGER_CLEARANCE = 3

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
 * [locked] marks a trophy, which never merges, never moves, and blocks gravity.
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

enum class GameStatus { READY, PLAYING, PLANNING, PAUSED, CELEBRATING, GAME_OVER }

/** The four directions a Plan slide can go. */
enum class SlideDirection { LEFT, RIGHT, UP, DOWN }

/** One-shot things the UI reacts to (sound, haptics, overlays). */
sealed interface GameEvent {
    /** [comboDepth] is 1 for the first merge of a chain, 2 for the next, and so on. */
    data class Merged(val value: Int, val row: Int, val col: Int, val comboDepth: Int) : GameEvent
    data class SpeedIncreased(val milestone: Int, val newMultiplier: Double) : GameEvent
    data class TrophyEarned(val value: Int, val totalTrophies: Int) : GameEvent
    data object Landed : GameEvent
    data object GameOver : GameEvent
    data object Blocked : GameEvent
    data object PowerUsed : GameEvent
}

/** Which power is being referenced. */
enum class PowerType { DELETE_ROW, SLOW, PLAN }

/**
 * Immutable render model. The engine mutates internally for speed; the UI only ever
 * sees one of these, so Compose recomposition stays predictable.
 *
 * Deliberately contains nothing that changes every millisecond — see [HudTimers].
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
    val scoreMultiplier: Double,
    val status: GameStatus,
    /** Trophy values earned so far, in the order they were won. */
    val trophies: List<Int>,
    /** The next rung of [TROPHY_LADDER], or null once the ladder is finished. */
    val nextTrophyValue: Int?,
    /** Empty rows above the spawn column. Low values mean the player is about to lose. */
    val spawnClearance: Int,
    /** Length of the most recent merge chain, for the combo readout. */
    val lastComboDepth: Int,
    val deleteCharges: Int,
    val slowCharges: Int,
    val planCharges: Int,
    /** Wall-clock ms the Plan window ends; drives the smooth countdown bar. */
    val planExpiresAtMs: Long,
    /** Wall-clock ms at which the current gravity step began; drives fall interpolation. */
    val stepStartMs: Long,
    /** Length of the current gravity step; drives fall interpolation. */
    val stepDurationMs: Long
)

/**
 * The countdown readouts, split out of [BoardSnapshot] on purpose.
 *
 * These change every millisecond. While they lived in the board snapshot, the snapshot
 * was never equal to the previous frame's, so the entire board recomposed at 60fps purely
 * to animate a "+1 in 12:34" label. Quantising to whole seconds is all the UI ever
 * renders, and it means this object is equal to itself for ~60 frames at a time.
 */
data class HudTimers(
    val deleteRegenRemainingSec: Long = 0,
    val slowRegenRemainingSec: Long = 0,
    val slowActiveRemainingSec: Long = 0,
    val planRegenRemainingSec: Long = 0,
    val planActiveRemainingSec: Long = 0
)

data class CellView(
    val id: Long,
    val row: Int,
    val col: Int,
    val value: Int,
    val locked: Boolean,
    val poppedAtMs: Long
)

/**
 * A complete game in a form that can be written to disk and read back.
 *
 * Runs last ten minutes or more. Without this, Android killing the process in the
 * background threw the whole run away and the player got a fresh board with no
 * explanation — the single most annoying thing a game can do on a phone.
 */
data class SavedGame(
    val cells: List<SavedCell>,
    val fallingValue: Int,
    val fallingRow: Int,
    val fallingCol: Int,
    val queue: List<Int>,
    val score: Int,
    val bestTile: Int,
    val trophies: List<Int>,
    val passedMilestones: List<Int>,
    val speedMultiplier: Double
)

data class SavedCell(
    val row: Int,
    val col: Int,
    val value: Int,
    val locked: Boolean
)
