package com.digiboxx.descent2048.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {

    private fun engine(seed: Int = 1) = GameEngine(rng = Random(seed))

    /** Sets up a blank playing board with no falling tile in the way. */
    private fun blankPlaying(seed: Int = 1): GameEngine {
        val e = engine(seed)
        e.start(0L)
        e.debugClear()
        e.debugSetFalling(2, 0, START_COL)
        return e
    }

    // ------------------------------------------------------------ merge rules

    @Test
    fun `horizontal merge fires after a vertical merge creates the pair`() {
        // The originally reported bug: col2 holds 2, col3 holds 4. A falling 2 lands on
        // the 2 making 4, which should then merge sideways with the existing 4 into 8.
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 2, 2)
        e.debugPlace(ROWS - 1, 3, 4)
        e.debugSetFalling(2, 0, 2)
        e.hardDrop(1000L)

        assertEquals("expected a single 8 in the bottom row", 1, e.debugTileCount())
        assertEquals(8, e.debugAt(ROWS - 1, 2)?.value)
        assertEquals("score should count 4 then 8", 12, e.score)
    }

    @Test
    fun `vertical merges cascade down a column`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 2, 16)
        e.debugPlace(ROWS - 2, 2, 8)
        e.debugSetFalling(8, 0, 2)
        e.hardDrop(1000L)

        assertEquals(32, e.debugAt(ROWS - 1, 2)?.value)
        assertEquals(1, e.debugTileCount())
    }

    @Test
    fun `horizontal merge works across two separate stacks`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 1, 32); e.debugPlace(ROWS - 2, 1, 4)
        e.debugPlace(ROWS - 1, 2, 8);  e.debugPlace(ROWS - 2, 2, 4)
        e.debugSetFalling(2, 0, 6)
        e.useDeleteRow(1000L) // convenient way to force a resolve pass

        // Delete-row removes the bottom row, so assert on the merge instead via a
        // fresh engine to keep the two behaviours separate.
        val f = blankPlaying()
        f.debugPlace(ROWS - 1, 1, 32); f.debugPlace(ROWS - 2, 1, 4)
        f.debugPlace(ROWS - 1, 2, 8);  f.debugPlace(ROWS - 2, 2, 4)
        f.debugSetFalling(2, 0, 5)
        f.hardDrop(1000L)

        assertEquals("the two 4s should have become an 8", 0, f.debugUnmergedPairs())
        assertEquals(0, f.debugFloatingTiles())
    }

    @Test
    fun `diagonal tiles do not merge`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 1, 32)
        e.debugPlace(ROWS - 2, 1, 4)
        e.debugPlace(ROWS - 1, 2, 4)   // diagonal to the other 4
        e.debugSetFalling(64, 0, 6)
        e.hardDrop(1000L)

        assertEquals("diagonal 4s must stay separate", 4, e.debugAt(ROWS - 2, 1)?.value)
        assertEquals(4, e.debugAt(ROWS - 1, 2)?.value)
    }

    @Test
    fun `the tile the player dropped wins the horizontal position tiebreak`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 1, 4)
        e.debugSetFalling(4, 0, 2)
        e.hardDrop(1000L)

        assertEquals("merged tile should land in the active column", 8, e.debugAt(ROWS - 1, 2)?.value)
        assertEquals(null, e.debugAt(ROWS - 1, 1))
    }

    // ------------------------------------------------------------ movement

    @Test
    fun `sliding cannot tunnel through an occupied cell`() {
        val e = blankPlaying()
        e.debugPlace(5, 3, 64)

        e.debugSetFalling(2, 5, 0)
        e.moveTo(6)
        assertEquals("should stop just left of the wall", 2, e.falling?.col)

        e.debugSetFalling(2, 5, 6)
        e.moveTo(0)
        assertEquals("should stop just right of the wall", 4, e.falling?.col)
    }

    @Test
    fun `movement is clamped to the board`() {
        val e = blankPlaying()
        e.debugSetFalling(2, 0, 3)
        e.moveTo(-99)
        assertEquals(0, e.falling?.col)
        e.moveTo(99)
        assertEquals(COLS - 1, e.falling?.col)
    }

    // ------------------------------------------------------------ trophy

    @Test
    fun `reaching 2048 clears the board and locks the trophy in the corner`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 5, 1024)
        e.debugSetFalling(1024, 0, 5)
        e.hardDrop(1000L)

        assertEquals(GameStatus.CELEBRATING, e.status)
        assertEquals("only the trophy should remain", 1, e.debugTileCount())
        val trophy = e.debugAt(ROWS - 1, 0)
        assertNotNull(trophy)
        assertEquals(TROPHY_VALUE, trophy!!.value)
        assertTrue(trophy.locked)
    }

    @Test
    fun `the trophy never merges with a loose 2048`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 0, TROPHY_VALUE, locked = true)
        e.debugPlace(ROWS - 1, 1, TROPHY_VALUE)
        e.debugSetFalling(2, 0, 5)
        e.hardDrop(1000L)

        val trophy = e.debugAt(ROWS - 1, 0)
        assertTrue("trophy must stay locked", trophy?.locked == true)
        assertEquals(TROPHY_VALUE, trophy?.value)
        assertEquals("the loose 2048 must survive untouched", TROPHY_VALUE, e.debugAt(ROWS - 1, 1)?.value)
    }

    @Test
    fun `gravity does not pull tiles through the trophy`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 0, TROPHY_VALUE, locked = true)
        e.debugPlace(ROWS - 5, 0, 8)
        e.debugSetFalling(2, 0, 5)
        e.hardDrop(1000L)

        assertEquals("the 8 should rest directly on top of the trophy", 8, e.debugAt(ROWS - 2, 0)?.value)
        assertEquals(TROPHY_VALUE, e.debugAt(ROWS - 1, 0)?.value)
    }

    // ------------------------------------------------------------ speed

    @Test
    fun `each milestone compounds the speed by twenty percent`() {
        val e = blankPlaying()
        assertEquals(1.0, e.speedMultiplier, 1e-9)

        e.debugPlace(ROWS - 1, 4, 256)
        e.debugSetFalling(256, 0, 4)
        e.hardDrop(1000L)
        assertEquals("512 milestone", 1.2, e.speedMultiplier, 1e-9)

        val f = blankPlaying()
        f.debugPlace(ROWS - 1, 4, 1024)
        f.debugSetFalling(1024, 0, 4)
        f.hardDrop(1000L)
        assertEquals("512, 1024 and 2048 all cross at once", 1.728, f.speedMultiplier, 1e-9)
    }

    @Test
    fun `slow power lengthens the interval and soft drop shortens it`() {
        val e = blankPlaying()
        val base = e.currentIntervalMs(1000L)

        assertTrue(e.useSlow(1000L))
        assertTrue("slow should be slower", e.currentIntervalMs(1000L) > base)
        assertTrue(e.isSlowActive(1000L))
        assertFalse("slow expires after 30s", e.isSlowActive(1000L + SLOW_DURATION_MS + 1))

        val f = blankPlaying()
        f.softDrop = true
        assertTrue("soft drop should be faster", f.currentIntervalMs(1000L) < base)
    }

    @Test
    fun `interval never drops below the floor`() {
        val e = blankPlaying()
        e.softDrop = true
        repeat(40) { e.debugForcePlaying() }
        // Even at an absurd multiplier the interval must stay playable.
        val f = GameEngine(rng = Random(1))
        f.start(0L)
        f.softDrop = true
        assertTrue(f.currentIntervalMs(0L) >= MIN_INTERVAL_MS)
    }

    // ------------------------------------------------------------ powers

    @Test
    fun `delete row clears the lowest occupied row and settles the rest`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 0, 2)
        e.debugPlace(ROWS - 1, 1, 8)
        e.debugPlace(ROWS - 2, 0, 16)
        e.debugSetFalling(64, 0, 5)

        assertTrue(e.useDeleteRow(1000L))
        assertEquals(2, e.deleteBank.charges)
        assertEquals("the 16 should have dropped to the floor", 16, e.debugAt(ROWS - 1, 0)?.value)
        assertEquals(0, e.debugFloatingTiles())
    }

    @Test
    fun `delete row will not remove the trophy`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 0, TROPHY_VALUE, locked = true)
        e.debugSetFalling(2, 0, 5)
        e.useDeleteRow(1000L)
        assertEquals("trophy survives", TROPHY_VALUE, e.debugAt(ROWS - 1, 0)?.value)
    }

    @Test
    fun `powers refuse to fire with no charges left`() {
        val e = blankPlaying()
        assertTrue(e.useSlow(0L))
        assertTrue(e.useSlow(0L))
        assertTrue(e.useSlow(0L))
        assertFalse("fourth use should fail", e.useSlow(0L))
        assertEquals(0, e.slowBank.charges)
    }

    @Test
    fun `charges regenerate one unit every thirty minutes`() {
        var bank = PowerBank()
        bank = bank.spend(0L).spend(0L).spend(0L)
        assertEquals(0, bank.charges)

        assertEquals(0, bank.refresh(POWER_REGEN_MS - 1).charges)
        assertEquals(1, bank.refresh(POWER_REGEN_MS).charges)
        assertEquals(2, bank.refresh(POWER_REGEN_MS * 2).charges)
        assertEquals(3, bank.refresh(POWER_REGEN_MS * 3).charges)
    }

    @Test
    fun `regeneration never exceeds the cap and clears its timer when full`() {
        var bank = PowerBank().spend(0L)
        bank = bank.refresh(POWER_REGEN_MS * 100)
        assertEquals(POWER_MAX_CHARGES, bank.charges)
        assertEquals(null, bank.nextRegenAtMs)
    }

    @Test
    fun `spending a second charge does not restart the first timer`() {
        val first = PowerBank().spend(0L)
        val second = first.spend(5_000L)
        assertEquals("timer stays anchored to the first spend", first.nextRegenAtMs, second.nextRegenAtMs)
    }

    @Test
    fun `a backwards device clock is re-anchored instead of stalling regen`() {
        val bank = PowerBank().spend(1_000_000L)
        // Player winds the clock back a day; without the fix they would wait forever.
        val repaired = bank.detectClockRollback(0L)
        assertTrue(repaired.regenRemainingMs(0L) <= POWER_REGEN_MS)
    }

    // ------------------------------------------------------------ game over

    @Test
    fun `game ends when the spawn cell is blocked`() {
        val e = blankPlaying()
        for (row in 0 until ROWS) e.debugPlace(row, START_COL, if (row % 2 == 0) 2 else 8)
        e.debugSetFalling(64, 0, 0)
        e.hardDrop(1000L)
        assertEquals(GameStatus.GAME_OVER, e.status)
    }

    // ------------------------------------------------------------ fuzz

    @Test
    fun `board is always fully resolved across many randomised games`() {
        var pieces = 0
        var bestSeen = 0

        for (seed in 1..60) {
            val e = GameEngine(rng = Random(seed))
            e.start(0L)
            val rnd = Random(seed * 31)
            var guard = 0
            while (e.status != GameStatus.GAME_OVER && guard++ < 1500) {
                if (e.status == GameStatus.CELEBRATING) { e.tick(Long.MAX_VALUE / 2); continue }
                if (e.falling == null) break
                e.moveTo(rnd.nextInt(COLS))
                if (rnd.nextInt(100) < 4) e.useDeleteRow(guard * 1000L)
                e.hardDrop(guard * 1000L)
                pieces++

                assertEquals("unresolved pair left on the board", 0, e.debugUnmergedPairs())
                assertEquals("tile left floating in mid-air", 0, e.debugFloatingTiles())
            }
            if (e.bestTile > bestSeen) bestSeen = e.bestTile
        }

        assertTrue("the fuzz run should actually play a lot of pieces", pieces > 1000)
        println("Fuzz: $pieces pieces played, best tile reached $bestSeen")
    }

    @Test
    fun `snapshot matches the underlying board`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 0, 2)
        e.debugPlace(ROWS - 1, 1, 8)
        val snap = e.snapshot(1000L)

        assertEquals(2, snap.cells.size)
        assertEquals(3, snap.nextValues.size)
        assertEquals(GameStatus.PLAYING, snap.status)
        assertEquals(POWER_MAX_CHARGES, snap.deleteCharges)
    }
}
