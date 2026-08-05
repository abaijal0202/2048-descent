package com.digiboxx.descent2048.blocks

/**
 * 2048 Blocks — the tetromino variant.
 *
 * Four-cell pieces fall in the seven classic shapes, but every cell carries its own
 * number. The board's governing rule is that **no two equal numbers may share a
 * boundary**: whenever a placement would put two equal values orthogonally adjacent,
 * they combine into their double, cascading until the rule holds again.
 *
 * Full rows still clear like the game it borrows its shapes from, so there are two
 * competing ways to make space — merge upward, or fill across.
 *
 * Named "Blocks" rather than the obvious thing on purpose: that name is a trademark its
 * owner enforces vigorously against clones, and this has to survive a store review.
 */

/** Board size. Eight wide is about the minimum for tetrominoes to be interesting. */
const val BLOCK_COLS = 8
const val BLOCK_ROWS = 13

/** Milliseconds between gravity steps at level 1. */
const val BLOCK_BASE_INTERVAL_MS = 800L

/** Fastest permitted interval, so late levels stay playable. */
const val BLOCK_MIN_INTERVAL_MS = 90L

/** Soft drop divides the interval by this. */
const val BLOCK_SOFT_DROP_FACTOR = 8

/** Lines cleared per level. */
const val LINES_PER_LEVEL = 10

/** Each level shortens the fall interval by this fraction. */
const val LEVEL_SPEEDUP = 0.14

/** Score for clearing 1, 2, 3 or 4 rows at once, before the level multiplier. */
val LINE_SCORES = listOf(0, 100, 300, 500, 800)

/** A cascade pays progressively more, matching the other two games. */
const val BLOCK_COMBO_STEP = 0.5
const val BLOCK_MAX_COMBO = 4.0

/** Rows of clearance above the spawn zone at or below which the UI warns. */
const val BLOCK_DANGER_ROWS = 3

/** Weighted spawn table for individual cell values. */
val BLOCK_SPAWN_TABLE: List<Pair<Int, Int>> = listOf(
    2 to 40, 4 to 30, 8 to 18, 16 to 8, 32 to 4
)

/** The value that counts as beating the game. Play continues past it. */
const val BLOCK_TARGET = 2048

/** A coordinate inside a piece's bounding box, or on the board. */
data class Cell(val row: Int, val col: Int)

enum class PieceType { I, O, T, S, Z, J, L }

/**
 * Shape definitions.
 *
 * Each piece is four cells inside a square bounding box. Rotation is a plain coordinate
 * transform applied to every cell in order, which matters more than it looks: because the
 * list order is preserved, each cell's number stays attached to it through a rotation.
 * Rotating must reposition the values, never reshuffle them.
 */
data class PieceShape(val boxSize: Int, val cells: List<Cell>)

val PIECE_SHAPES: Map<PieceType, PieceShape> = mapOf(
    PieceType.I to PieceShape(4, listOf(Cell(1, 0), Cell(1, 1), Cell(1, 2), Cell(1, 3))),
    PieceType.O to PieceShape(2, listOf(Cell(0, 0), Cell(0, 1), Cell(1, 0), Cell(1, 1))),
    PieceType.T to PieceShape(3, listOf(Cell(1, 0), Cell(1, 1), Cell(1, 2), Cell(0, 1))),
    PieceType.S to PieceShape(3, listOf(Cell(1, 0), Cell(1, 1), Cell(0, 1), Cell(0, 2))),
    PieceType.Z to PieceShape(3, listOf(Cell(0, 0), Cell(0, 1), Cell(1, 1), Cell(1, 2))),
    PieceType.J to PieceShape(3, listOf(Cell(0, 0), Cell(1, 0), Cell(1, 1), Cell(1, 2))),
    PieceType.L to PieceShape(3, listOf(Cell(0, 2), Cell(1, 0), Cell(1, 1), Cell(1, 2)))
)

/**
 * Offsets tried when a rotation would collide.
 *
 * A simple kick list rather than full SRS. Without it, rotating against a wall or a
 * neighbouring stack simply fails, which feels broken to anyone who has played the
 * genre — the piece should shuffle aside and turn.
 */
val ROTATION_KICKS: List<Int> = listOf(0, -1, 1, -2, 2)

/**
 * The piece currently falling.
 *
 * [values] is parallel to the shape's cell list, so `values[i]` belongs to `cells[i]` in
 * every rotation state.
 */
data class FallingPiece(
    val type: PieceType,
    val rotation: Int,
    val row: Int,
    val col: Int,
    val values: List<Int>
) {
    /** This piece's cells in board coordinates. */
    fun boardCells(): List<Cell> {
        val shape = PIECE_SHAPES.getValue(type)
        return shape.cells.map { cell ->
            val rotated = rotate(cell, shape.boxSize, rotation)
            Cell(row + rotated.row, col + rotated.col)
        }
    }

    companion object {
        /** Clockwise quarter turns inside an [boxSize] square box. */
        fun rotate(cell: Cell, boxSize: Int, turns: Int): Cell {
            var r = cell.row
            var c = cell.col
            repeat(((turns % 4) + 4) % 4) {
                val nr = c
                val nc = boxSize - 1 - r
                r = nr
                c = nc
            }
            return Cell(r, c)
        }
    }
}

/** A settled cell on the board. */
class BlockTile(val id: Long, var value: Int) {
    /** Timestamp of the most recent merge into this tile; drives the pop animation. */
    var poppedAtMs: Long = 0L
}

enum class BlocksStatus { READY, PLAYING, PAUSED, GAME_OVER }

sealed interface BlocksEvent {
    data class Merged(val value: Int, val row: Int, val col: Int, val comboDepth: Int) : BlocksEvent
    data class LinesCleared(val count: Int, val rows: List<Int>) : BlocksEvent
    data class LevelUp(val level: Int) : BlocksEvent
    data class TargetReached(val value: Int) : BlocksEvent
    data object Locked : BlocksEvent
    data object Rotated : BlocksEvent
    data object Blocked : BlocksEvent
    data object GameOver : BlocksEvent
}

/** Immutable render model, rebuilt only when the board changes. */
data class BlocksSnapshot(
    val cells: List<BlockCellView>,
    val falling: List<FallingCellView>,
    /** Where the piece would land, for the ghost outline. */
    val ghost: List<Cell>,
    val nextPieces: List<PiecePreview>,
    val score: Int,
    val bestValue: Int,
    val lines: Int,
    val level: Int,
    val status: BlocksStatus,
    val lastComboDepth: Int,
    /** Empty rows above the highest settled tile. Low values mean danger. */
    val clearance: Int,
    /** Wall-clock ms the current gravity step began; drives fall interpolation. */
    val stepStartMs: Long,
    val stepDurationMs: Long
)

data class BlockCellView(
    val id: Long,
    val row: Int,
    val col: Int,
    val value: Int,
    val poppedAtMs: Long
)

data class FallingCellView(val row: Int, val col: Int, val value: Int)

data class PiecePreview(val type: PieceType, val cells: List<Cell>, val values: List<Int>)
