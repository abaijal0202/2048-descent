package com.digiboxx.descent2048

import com.digiboxx.descent2048.blocks.BLOCK_COLS
import com.digiboxx.descent2048.blocks.BLOCK_ROWS
import com.digiboxx.descent2048.blocks.BlocksEngine
import com.digiboxx.descent2048.blocks.BlocksStatus
import com.digiboxx.descent2048.blocks.FallingPiece
import com.digiboxx.descent2048.blocks.PieceType
import com.digiboxx.descent2048.game.COLS
import com.digiboxx.descent2048.game.GameEngine
import com.digiboxx.descent2048.game.GameStatus
import com.digiboxx.descent2048.game.POWER_MAX_CHARGES
import com.digiboxx.descent2048.game.PowerBank
import com.digiboxx.descent2048.game.PowerType
import com.digiboxx.descent2048.game.ROWS
import com.digiboxx.descent2048.game.START_COL
import com.digiboxx.descent2048.merge.DEATH_LINE_Y
import com.digiboxx.descent2048.merge.MergeEngine
import com.digiboxx.descent2048.merge.MergeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The rewarded continue, across all three games.
 *
 * The thing worth guarding is that a revive actually rescues the player. A continue that
 * hands back a board which ends the run on the very next spawn has taken payment (an ad
 * view) for nothing, and that is the sort of thing players are rightly furious about.
 */
class ReviveTest {

    // ------------------------------------------------------------ descent

    private fun deadDescent(): GameEngine {
        val e = GameEngine(rng = Random(1))
        e.start(0L)
        e.debugClear()
        for (row in 0 until ROWS) {
            e.debugPlace(row, START_COL, if (row % 2 == 0) 2 else 8)
        }
        e.debugSetFalling(64, 0, 0)
        e.hardDrop(1000L)
        return e
    }

    @Test
    fun `descent revive returns the player to a playable board`() {
        val e = deadDescent()
        assertEquals(GameStatus.GAME_OVER, e.status)

        assertTrue("the rescue should succeed", e.revive(2000L))
        assertEquals(GameStatus.PLAYING, e.status)
        assertNotNull("a tile must be in play again", e.falling)
        assertEquals("and the spawn cell must be clear", null, e.debugAt(0, START_COL))
    }

    @Test
    fun `descent revive clears from the top, sparing the foundation`() {
        val e = deadDescent()
        val floorBefore = e.debugAt(ROWS - 1, START_COL)?.value
        e.revive(2000L)
        assertEquals(
            "the bottom of the stack is the player's work and should survive",
            floorBefore, e.debugAt(ROWS - 1, START_COL)?.value
        )
    }

    @Test
    fun `descent revive is refused unless the run is actually over`() {
        val e = GameEngine(rng = Random(1))
        e.start(0L)
        assertFalse("nothing to rescue mid-run", e.revive(1000L))
        assertEquals(GameStatus.PLAYING, e.status)
    }

    @Test
    fun `descent revive keeps clearing until a spawn will fit`() {
        // Every column stacked to the ceiling: three rows is not enough on its own, so
        // the revive has to keep going rather than declaring success on a doomed board.
        val e = GameEngine(rng = Random(1))
        e.start(0L)
        e.debugClear()
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                e.debugPlace(row, col, if ((row + col) % 2 == 0) 2 else 8)
            }
        }
        e.debugSetFalling(64, 0, 0)
        e.hardDrop(1000L)
        assertEquals(GameStatus.GAME_OVER, e.status)

        assertTrue(e.revive(2000L))
        assertEquals(GameStatus.PLAYING, e.status)
        assertEquals(null, e.debugAt(0, START_COL))
    }

    // ------------------------------------------------------------ power charges

    @Test
    fun `granting a charge tops the bank up without resetting the timer`() {
        val spent = PowerBank().spend(0L).spend(0L)
        val granted = spent.grant()

        assertEquals(spent.charges + 1, granted.charges)
        assertEquals(
            "a reward should not push back the charge already on its way",
            spent.nextRegenAtMs, granted.nextRegenAtMs
        )
    }

    @Test
    fun `granting cannot exceed the cap`() {
        val full = PowerBank()
        assertEquals(POWER_MAX_CHARGES, full.grant().charges)
        assertEquals("a full bank has no pending timer", null, full.grant().nextRegenAtMs)
    }

    @Test
    fun `the engine grants a charge to the right power`() {
        val e = GameEngine(rng = Random(1))
        e.start(0L)
        repeat(POWER_MAX_CHARGES) { e.useSlow(0L) }
        assertEquals(0, e.slowBank.charges)

        e.grantCharge(PowerType.SLOW, 1000L)
        assertEquals(1, e.slowBank.charges)
        assertEquals("other banks untouched", POWER_MAX_CHARGES, e.deleteBank.charges)
        assertEquals(POWER_MAX_CHARGES, e.planBank.charges)
    }

    // ------------------------------------------------------------ blocks

    @Test
    fun `blocks revive returns the player to a playable board`() {
        val e = BlocksEngine(rng = Random(1))
        e.start(0L)
        e.debugClear()
        for (col in 2..5) {
            for (row in 0 until BLOCK_ROWS) {
                e.debugPlace(row, col, if ((row + col) % 2 == 0) 2 else 4)
            }
        }
        e.debugSetFalling(FallingPiece(PieceType.O, 0, 0, 0, listOf(64, 128, 256, 512)))
        e.hardDrop(1000L)
        assertEquals(BlocksStatus.GAME_OVER, e.status)

        assertTrue(e.revive(2000L))
        assertEquals(BlocksStatus.PLAYING, e.status)
        assertNotNull(e.falling)
        assertEquals("the rule must still hold after a rescue", 0, e.debugTouchingEqualPairs())
        assertEquals(0, e.debugFloatingTiles())
    }

    @Test
    fun `blocks revive is refused mid-run`() {
        val e = BlocksEngine(rng = Random(1))
        e.start(0L)
        assertFalse(e.revive(1000L))
    }

    // ------------------------------------------------------------ merge

    @Test
    fun `merge revive lifts the highest balls and clears the overflow timer`() {
        val e = MergeEngine(rng = Random(1))
        e.start(0L)
        e.debugClear()

        // Five balls resting along the floor, spread out so they do not jostle each
        // other, plus one held above the line to end the run.
        listOf(0.12f, 0.28f, 0.44f, 0.62f, 0.80f).forEachIndexed { index, x ->
            e.debugAdd(2 shl index, x, 1.05f).hasEnteredPlay = true
        }
        val doomed = e.debugAdd(1024, 0.5f, DEATH_LINE_Y - 0.01f)
        doomed.hasEnteredPlay = true

        var t = 0L
        repeat(400) {
            t += 8L
            // Hold it in place, as a pile beneath it would.
            doomed.y = DEATH_LINE_Y - 0.01f
            doomed.vx = 0f
            doomed.vy = 0f
            e.update(t)
        }
        assertEquals(MergeStatus.GAME_OVER, e.status)

        val before = e.ballCount
        assertTrue(e.revive(t + 100L, count = 3))
        assertEquals(MergeStatus.PLAYING, e.status)
        assertTrue("the top of the pile should have gone", e.ballCount < before)
        assertTrue(
            "nothing may still be counting down toward another loss",
            e.debugBalls().all { it.overLineMs == 0L }
        )
    }

    @Test
    fun `merge revive is refused mid-run`() {
        val e = MergeEngine(rng = Random(1))
        e.start(0L)
        assertFalse(e.revive(1000L))
    }
}
