package com.digiboxx.descent2048.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlocksEngineTest {

    private fun engine(seed: Int = 1) = BlocksEngine(rng = Random(seed))

    private fun blankPlaying(seed: Int = 1): BlocksEngine {
        val e = engine(seed)
        e.start(0L)
        e.debugClear()
        return e
    }

    private fun piece(
        type: PieceType,
        row: Int,
        col: Int,
        values: List<Int>,
        rotation: Int = 0
    ) = FallingPiece(type, rotation, row, col, values)

    // ------------------------------------------------------------ shapes

    @Test
    fun `every piece is four cells`() {
        for (type in PieceType.entries) {
            val shape = PIECE_SHAPES.getValue(type)
            assertEquals("$type should have four cells", 4, shape.cells.size)
            assertEquals("$type cells must be distinct", 4, shape.cells.toSet().size)
            for (cell in shape.cells) {
                assertTrue(
                    "$type cell $cell escapes its ${shape.boxSize} box",
                    cell.row in 0 until shape.boxSize && cell.col in 0 until shape.boxSize
                )
            }
        }
    }

    @Test
    fun `four rotations return a piece to where it started`() {
        for (type in PieceType.entries) {
            val shape = PIECE_SHAPES.getValue(type)
            for (cell in shape.cells) {
                val turned = FallingPiece.rotate(cell, shape.boxSize, 4)
                assertEquals("$type should be unchanged after a full turn", cell, turned)
            }
        }
    }

    @Test
    fun `rotation moves values with their cells, never reshuffles them`() {
        // This is the subtle one. Each cell carries its own number, so a rotation has to
        // reposition the values, not permute which cell holds which.
        val values = listOf(2, 4, 8, 16)
        val upright = piece(PieceType.T, 4, 2, values)
        val turned = upright.copy(rotation = 1)

        assertEquals("the value list itself must not change", values, turned.values)
        assertEquals(4, turned.boardCells().size)
        // The 2 belongs to shape cell 0 in both states.
        val shape = PIECE_SHAPES.getValue(PieceType.T)
        val expected = FallingPiece.rotate(shape.cells[0], shape.boxSize, 1)
        assertEquals(
            Cell(4 + expected.row, 2 + expected.col),
            turned.boardCells()[0]
        )
    }

    @Test
    fun `the square piece is unchanged by rotation`() {
        val before = piece(PieceType.O, 3, 3, listOf(2, 4, 8, 16)).boardCells().toSet()
        val after = piece(PieceType.O, 3, 3, listOf(2, 4, 8, 16), rotation = 1)
            .boardCells().toSet()
        assertEquals(before, after)
    }

    // ------------------------------------------------------------ the governing rule

    @Test
    fun `equal numbers sharing an edge combine`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 2, 4)
        e.debugPlace(BLOCK_ROWS - 1, 3, 4)
        e.debugResolve(1000L)

        assertEquals("the pair must have become one tile", 1, e.debugTileCount())
        assertEquals(8, e.debugAt(BLOCK_ROWS - 1, 2)?.value)
        assertEquals(0, e.debugTouchingEqualPairs())
    }

    @Test
    fun `merging cascades until nothing equal touches`() {
        val e = blankPlaying()
        // Stacked 8 / 4 / 4 in one column: the pair makes an 8, which then meets the
        // 8 below it. Vertical, because gravity only ever compacts downward.
        e.debugPlace(BLOCK_ROWS - 1, 2, 8)
        e.debugPlace(BLOCK_ROWS - 2, 2, 4)
        e.debugPlace(BLOCK_ROWS - 3, 2, 4)
        e.debugResolve(1000L)

        assertEquals(1, e.debugTileCount())
        assertEquals(16, e.debugAt(BLOCK_ROWS - 1, 2)?.value)
        assertEquals(0, e.debugTouchingEqualPairs())
    }

    @Test
    fun `a horizontal merge leaves a gap rather than closing it up`() {
        // Worth pinning down: gravity is vertical only, so combining two neighbours
        // leaves a hole where the loser was and the survivor does not slide across to
        // meet an equal tile further along the row.
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 0, 4)
        e.debugPlace(BLOCK_ROWS - 1, 1, 4)
        e.debugPlace(BLOCK_ROWS - 1, 2, 8)
        e.debugResolve(1000L)

        assertEquals("two 8s, not one 16", 2, e.debugTileCount())
        assertEquals(8, e.debugAt(BLOCK_ROWS - 1, 0)?.value)
        assertNull("the loser's cell is left empty", e.debugAt(BLOCK_ROWS - 1, 1))
        assertEquals(8, e.debugAt(BLOCK_ROWS - 1, 2)?.value)
        assertEquals("and the rule still holds", 0, e.debugTouchingEqualPairs())
    }

    @Test
    fun `diagonal equals are left alone`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 0, 8)
        e.debugPlace(BLOCK_ROWS - 2, 1, 8)
        e.debugPlace(BLOCK_ROWS - 1, 1, 2)
        e.debugResolve(1000L)

        assertEquals("sharing only a corner is not sharing a boundary", 3, e.debugTileCount())
    }

    @Test
    fun `a piece whose own cells match resolves itself on landing`() {
        val e = blankPlaying()
        // A square of four 2s breaks the rule against itself the moment it lands.
        e.debugSetFalling(piece(PieceType.O, 0, 3, listOf(2, 2, 2, 2)))
        e.hardDrop(1000L)

        assertEquals("no equal pair may survive a placement", 0, e.debugTouchingEqualPairs())
        assertEquals(0, e.debugFloatingTiles())
    }

    @Test
    fun `the board never ends a placement breaking the rule`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 0, 2)
        e.debugPlace(BLOCK_ROWS - 1, 1, 4)
        e.debugPlace(BLOCK_ROWS - 1, 2, 2)
        e.debugSetFalling(piece(PieceType.I, 0, 0, listOf(2, 4, 2, 8)))
        e.hardDrop(1000L)

        assertEquals(0, e.debugTouchingEqualPairs())
        assertEquals(0, e.debugFloatingTiles())
    }

    // ------------------------------------------------------------ line clears

    @Test
    fun `a full row clears and scores`() {
        val e = blankPlaying()
        // Alternating values so the row is full but breaks no rule.
        for (col in 0 until BLOCK_COLS) {
            e.debugPlace(BLOCK_ROWS - 1, col, if (col % 2 == 0) 2 else 4)
        }
        assertEquals(1, e.debugFullRows())

        e.debugResolve(1000L)

        assertEquals("the row should be gone", 0, e.debugTileCount())
        assertTrue("clearing a row should score", e.score > 0)
        assertEquals(1, e.lines)
    }

    @Test
    fun `clearing rows raises the level and the speed`() {
        val e = blankPlaying()
        val baseInterval = e.currentIntervalMs()
        assertEquals(1, e.level)

        repeat(LINES_PER_LEVEL) {
            for (col in 0 until BLOCK_COLS) {
                e.debugPlace(BLOCK_ROWS - 1, col, if (col % 2 == 0) 2 else 4)
            }
            e.debugResolve(1000L)
        }

        assertEquals(LINES_PER_LEVEL, e.lines)
        assertEquals(2, e.level)
        assertTrue("a higher level must fall faster", e.currentIntervalMs() < baseInterval)
    }

    @Test
    fun `the fall interval never drops below the floor`() {
        val e = blankPlaying()
        repeat(LINES_PER_LEVEL * 40) {
            for (col in 0 until BLOCK_COLS) {
                e.debugPlace(BLOCK_ROWS - 1, col, if (col % 2 == 0) 2 else 4)
            }
            e.debugResolve(1000L)
        }
        e.softDrop = true
        assertTrue(e.currentIntervalMs() >= BLOCK_MIN_INTERVAL_MS)
    }

    // ------------------------------------------------------------ movement

    @Test
    fun `a piece cannot be moved off the board`() {
        val e = blankPlaying()
        e.debugSetFalling(piece(PieceType.O, 2, 0, listOf(2, 4, 8, 16)))
        assertFalse("nothing to the left of column zero", e.move(-1))
        assertEquals(0, e.falling?.col)

        e.debugSetFalling(piece(PieceType.O, 2, BLOCK_COLS - 2, listOf(2, 4, 8, 16)))
        assertFalse(e.move(1))
    }

    @Test
    fun `a piece cannot be moved into a settled tile`() {
        val e = blankPlaying()
        e.debugPlace(3, 4, 64)
        e.debugSetFalling(piece(PieceType.O, 2, 2, listOf(2, 4, 8, 16)))
        // The square occupies rows 2-3, cols 2-3; moving right would hit the 64 at (3,4).
        assertFalse(e.move(1))
    }

    @Test
    fun `rotation kicks a piece off the wall instead of refusing`() {
        val e = blankPlaying()
        // An I piece flush against the right wall has no room to turn in place.
        e.debugSetFalling(piece(PieceType.I, 0, BLOCK_COLS - 4, listOf(2, 4, 8, 16)))
        assertTrue("it should shuffle aside and turn", e.rotate())
        assertNotNull(e.falling)
        assertTrue(
            "and must still be on the board",
            e.falling!!.boardCells().all { it.col in 0 until BLOCK_COLS }
        )
    }

    @Test
    fun `rotation is refused when there is genuinely no room`() {
        val e = blankPlaying()
        // Wall the piece in on both sides and below.
        for (row in 0 until BLOCK_ROWS) {
            e.debugPlace(row, 0, 64)
            e.debugPlace(row, 3, 128)
        }
        e.debugSetFalling(piece(PieceType.I, 0, 1, listOf(2, 4, 8, 16), rotation = 1))
        val before = e.falling
        e.rotate()
        assertEquals("the piece must not end up overlapping anything", before, e.falling)
    }

    @Test
    fun `hard drop lands the piece on the stack`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 3, 64)
        e.debugSetFalling(piece(PieceType.O, 0, 3, listOf(2, 4, 8, 16)))
        e.hardDrop(1000L)

        assertEquals("the 64 should still be on the floor", 64, e.debugAt(BLOCK_ROWS - 1, 3)?.value)
        assertEquals(0, e.debugFloatingTiles())
        assertNotNull("a new piece should have spawned", e.falling)
    }

    @Test
    fun `the ghost shows where the piece will land`() {
        val e = blankPlaying()
        e.debugSetFalling(piece(PieceType.O, 0, 3, listOf(2, 4, 8, 16)))
        val ghost = e.ghostCells()

        assertEquals(4, ghost.size)
        assertEquals("the square should rest on the floor", BLOCK_ROWS - 1, ghost.maxOf { it.row })
    }

    // ------------------------------------------------------------ scoring

    @Test
    fun `a lone merge is worth its face value`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 2, 16)
        e.debugPlace(BLOCK_ROWS - 1, 3, 16)
        e.debugResolve(1000L)

        assertEquals(32, e.score)
        assertEquals(1, e.lastComboDepth)
    }

    @Test
    fun `a cascade pays a combo bonus`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 2, 8)
        e.debugPlace(BLOCK_ROWS - 2, 2, 4)
        e.debugPlace(BLOCK_ROWS - 3, 2, 4)
        e.debugResolve(1000L)

        // 8 at depth 1 (x1.0) then 16 at depth 2 (x1.5) = 8 + 24.
        assertEquals(32, e.score)
        assertEquals(2, e.lastComboDepth)
    }

    @Test
    fun `reaching the target fires once and play continues`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 2, 1024)
        e.debugPlace(BLOCK_ROWS - 1, 3, 1024)
        e.debugSetFalling(piece(PieceType.O, 0, 6, listOf(2, 4, 8, 16)))
        e.hardDrop(1000L)

        assertEquals(BLOCK_TARGET, e.bestValue)
        assertEquals(BlocksStatus.PLAYING, e.status)
    }

    // ------------------------------------------------------------ losing

    @Test
    fun `the run ends when a new piece cannot fit`() {
        val e = blankPlaying()
        // Every piece spawns inside rows 0-1 of columns 2-5, so filling exactly those
        // columns to the ceiling blocks all seven shapes. Leaving columns 0, 1, 6 and 7
        // empty means no row is ever full, so nothing clears the wall away. The
        // checkerboard values keep the no-equal-neighbours rule intact.
        for (col in 2..5) {
            for (row in 0 until BLOCK_ROWS) {
                e.debugPlace(row, col, if ((row + col) % 2 == 0) 2 else 4)
            }
        }
        assertEquals("the wall must not be clearable", 0, e.debugFullRows())

        // Land the current piece harmlessly in the empty left columns.
        e.debugSetFalling(piece(PieceType.O, 0, 0, listOf(64, 128, 256, 512)))
        e.hardDrop(1000L)

        assertEquals(BlocksStatus.GAME_OVER, e.status)
        assertNull(e.falling)
    }

    @Test
    fun `input is ignored while paused`() {
        val e = blankPlaying()
        e.debugSetFalling(piece(PieceType.T, 2, 3, listOf(2, 4, 8, 16)))
        e.pause()
        assertEquals(BlocksStatus.PAUSED, e.status)

        assertFalse(e.move(-1))
        assertFalse(e.rotate())
        assertEquals(3, e.falling?.col)

        e.tick(1_000_000L)
        assertEquals("gravity must not run while paused", 2, e.falling?.row)

        e.resume(1_000_000L)
        e.tick(1_000_000L + BLOCK_BASE_INTERVAL_MS + 1)
        assertEquals(3, e.falling?.row)
    }

    // ------------------------------------------------------------ render contract

    @Test
    fun `revision only moves when the board changes`() {
        val e = blankPlaying()
        e.debugSetFalling(piece(PieceType.T, 2, 3, listOf(2, 4, 8, 16)))
        e.debugForcePlaying()
        val before = e.revision

        e.tick(0L)
        assertEquals("an idle tick must not invalidate the render", before, e.revision)

        e.move(-1)
        assertTrue(e.revision > before)
    }

    @Test
    fun `snapshot matches the board`() {
        val e = blankPlaying()
        e.debugPlace(BLOCK_ROWS - 1, 0, 2)
        e.debugPlace(BLOCK_ROWS - 1, 1, 8)
        e.debugSetFalling(piece(PieceType.T, 1, 3, listOf(2, 4, 8, 16)))
        val snap = e.snapshot()

        assertEquals(2, snap.cells.size)
        assertEquals(4, snap.falling.size)
        assertEquals(4, snap.ghost.size)
        assertEquals(2, snap.nextPieces.size)
        assertTrue(snap.stepDurationMs > 0)
        assertEquals(BlocksStatus.PLAYING, snap.status)
    }

    // ------------------------------------------------------------ fuzz

    @Test
    fun `the no-equal-neighbours rule holds across many randomised games`() {
        var placements = 0
        var bestSeen = 0
        var totalLines = 0

        for (seed in 1..40) {
            val e = BlocksEngine(rng = Random(seed))
            e.start(0L)
            val rnd = Random(seed * 29)
            var guard = 0
            while (e.status == BlocksStatus.PLAYING && guard++ < 400) {
                repeat(rnd.nextInt(4)) { e.rotate() }
                repeat(rnd.nextInt(5)) { e.move(if (rnd.nextBoolean()) -1 else 1) }
                e.hardDrop(guard * 1000L)
                placements++

                assertEquals(
                    "seed $seed left two equal numbers sharing a boundary",
                    0, e.debugTouchingEqualPairs()
                )
                assertEquals("seed $seed left a tile floating", 0, e.debugFloatingTiles())
                assertEquals("seed $seed left a full row uncleared", 0, e.debugFullRows())
            }
            if (e.bestValue > bestSeen) bestSeen = e.bestValue
            totalLines += e.lines
        }

        assertTrue("the fuzz run should place plenty of pieces", placements > 500)
        println("Blocks fuzz: $placements pieces, $totalLines lines, best value $bestSeen")
    }
}
