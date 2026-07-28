package com.digiboxx.descent2048.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // 4 at combo depth 1 (x1.0) then 8 at depth 2 (x1.5) = 4 + 12.
        assertEquals("score should include the combo bonus", 16, e.score)
        assertEquals(2, e.lastComboDepth)
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
        e.debugSetFalling(64, 0, COLS - 1)
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

    // ------------------------------------------------------------ combo scoring

    @Test
    fun `a lone merge gets no combo bonus`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 2, 4)
        e.debugSetFalling(4, 0, 2)
        e.hardDrop(1000L)

        assertEquals("a single merge is worth exactly its face value", 8, e.score)
        assertEquals(1, e.lastComboDepth)
    }

    @Test
    fun `a deeper cascade is worth more than the same merges made apart`() {
        // Chained: 8 -> 16 -> 32 resolved as one cascade.
        val chained = blankPlaying()
        chained.debugPlace(ROWS - 1, 2, 16)
        chained.debugPlace(ROWS - 2, 2, 8)
        chained.debugSetFalling(8, 0, 2)
        chained.hardDrop(1000L)

        // Apart: the same three merges, each resolved on its own board.
        val apart = blankPlaying()
        apart.debugPlace(ROWS - 1, 2, 8)
        apart.debugSetFalling(8, 0, 2)
        apart.hardDrop(1000L)
        val single = apart.score

        assertTrue(
            "a cascade (${chained.score}) should beat isolated merges ($single each)",
            chained.score > single * 2
        )
        assertEquals(2, chained.lastComboDepth)
    }

    // ------------------------------------------------------------ movement

    @Test
    fun `sliding cannot tunnel through an occupied cell`() {
        val e = blankPlaying()
        e.debugPlace(5, 3, 64)

        e.debugSetFalling(2, 5, 0)
        e.moveTo(6)
        assertEquals("should stop just left of the wall", 2, e.falling?.col)

        e.debugSetFalling(2, 5, COLS - 1)
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

    // ------------------------------------------------------------ trophy ladder

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
    fun `the ladder moves on to 4096 and keeps both trophies in the corner`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 5, 1024)
        e.debugSetFalling(1024, 0, 5)
        e.hardDrop(1000L)

        assertEquals(1, e.trophyCount)
        assertEquals("the goal should advance", 4096, e.nextTrophyValue)
        assertEquals("one trophy is worth a 1.5x score multiplier", 1.5, e.scoreMultiplier, 1e-9)

        // Let the celebration finish so play resumes.
        e.tick(10_000L)
        assertEquals(GameStatus.PLAYING, e.status)

        // Now build a 4096 alongside the trophy already sitting in the corner.
        e.debugPlace(ROWS - 1, 5, 2048)
        e.debugSetFalling(2048, 0, 5)
        e.hardDrop(11_000L)

        assertEquals(2, e.trophyCount)
        assertEquals(TROPHY_VALUE, e.debugAt(ROWS - 1, 0)?.value)
        assertEquals(4096, e.debugAt(ROWS - 1, 1)?.value)
        assertTrue(e.debugAt(ROWS - 1, 1)?.locked == true)
        assertEquals(8192, e.nextTrophyValue)
        assertEquals(2.0, e.scoreMultiplier, 1e-9)
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
    fun `delete row clears the row the player picked, not the foundation`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 0, 512)   // the foundation the player is protecting
        e.debugPlace(ROWS - 2, 0, 2)     // the junk they actually want gone
        e.debugPlace(ROWS - 2, 1, 8)
        e.debugSetFalling(64, 0, 5)

        assertTrue(e.useDeleteRowAt(ROWS - 2, 1000L))
        assertEquals("the big tile must survive", 512, e.debugAt(ROWS - 1, 0)?.value)
        assertEquals("only the targeted row is gone", 1, e.debugTileCount())
    }

    @Test
    fun `delete row on an empty row costs nothing`() {
        val e = blankPlaying()   // board is cleared
        assertFalse("nothing to delete", e.useDeleteRow(1000L))
        assertEquals("a wasted press must not spend a charge", POWER_MAX_CHARGES, e.deleteBank.charges)

        assertFalse(e.useDeleteRowAt(0, 1000L))
        assertEquals(POWER_MAX_CHARGES, e.deleteBank.charges)
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

    // ------------------------------------------------------------ spawning and loss

    @Test
    fun `a landed tile counts toward the best tile readout`() {
        val e = blankPlaying()
        e.debugSetFalling(64, 0, 3)
        e.hardDrop(1000L)
        assertEquals("a tile that never merged still counts", 64, e.bestTile)
    }

    @Test
    fun `the run ends the moment the stack blocks the spawn cell`() {
        val e = blankPlaying()
        // Only the spawn column is stacked; every other column is wide open. Gravity
        // means a spawn cell can only be blocked by a column filled to the ceiling.
        for (row in 0 until ROWS) e.debugPlace(row, START_COL, if (row % 2 == 0) 2 else 8)
        e.debugSetFalling(64, 0, 0)
        e.hardDrop(1000L)

        assertEquals("reaching the top is how you lose", GameStatus.GAME_OVER, e.status)
        assertNull("no tile should be left in play", e.falling)

        // The whole point of the rule: the rest of the board was empty and it still ended.
        for (col in 0 until COLS) {
            if (col == START_COL || col == 0) continue
            assertNull("column $col should still be empty", e.debugAt(ROWS - 1, col))
        }
    }

    @Test
    fun `play continues while the spawn cell is still clear`() {
        val e = blankPlaying()
        // Stack a neighbouring column to the ceiling; the spawn column stays free.
        val other = if (START_COL == 0) 1 else START_COL - 1
        for (row in 0 until ROWS) e.debugPlace(row, other, if (row % 2 == 0) 2 else 8)
        e.debugSetFalling(64, 0, START_COL)
        e.hardDrop(1000L)

        assertEquals(GameStatus.PLAYING, e.status)
        assertEquals("the next tile still enters in the middle", START_COL, e.falling?.col)
    }

    @Test
    fun `spawn clearance reports how close the stack is to the ceiling`() {
        val e = blankPlaying()
        assertEquals(ROWS, e.spawnClearance())
        e.debugPlace(2, START_COL, 8)
        assertEquals(2, e.spawnClearance())
    }

    // ------------------------------------------------------------ pause

    @Test
    fun `a paused game does not advance`() {
        val e = blankPlaying()
        e.debugSetFalling(2, 0, 3)
        e.pause(0L)
        assertEquals(GameStatus.PAUSED, e.status)

        e.tick(1_000_000L)
        assertEquals("the tile must not fall while paused", 0, e.falling?.row)

        e.resume(1_000_000L)
        assertEquals(GameStatus.PLAYING, e.status)
        e.tick(1_000_000L + BASE_INTERVAL_MS + 1)
        assertEquals(1, e.falling?.row)
    }

    @Test
    fun `input is ignored while paused`() {
        val e = blankPlaying()
        e.debugSetFalling(2, 0, 3)
        e.pause(0L)
        e.moveTo(0)
        assertEquals("sliding must not work while paused", 3, e.falling?.col)
        assertFalse(e.useSlow(1000L))
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun `a run survives an export and import round trip`() {
        val e = blankPlaying()
        e.debugPlace(ROWS - 1, 2, 64)
        e.debugPlace(ROWS - 1, 3, 128)
        e.debugPlace(ROWS - 1, 0, TROPHY_VALUE, locked = true)
        e.debugSetFalling(16, 3, 4)

        val saved = e.exportState()
        assertNotNull("a live game should be exportable", saved)

        val restored = GameEngine(rng = Random(9))
        restored.importState(saved!!, 5_000L)

        assertEquals("restore should wait for the player", GameStatus.PAUSED, restored.status)
        assertEquals(64, restored.debugAt(ROWS - 1, 2)?.value)
        assertEquals(128, restored.debugAt(ROWS - 1, 3)?.value)
        assertTrue("the trophy stays locked", restored.debugAt(ROWS - 1, 0)?.locked == true)
        assertEquals(16, restored.falling?.value)
        assertEquals(4, restored.falling?.col)
        assertEquals(e.score, restored.score)
        assertEquals(3, restored.snapshot(5_000L).nextValues.size)

        restored.resume(5_000L)
        assertEquals(GameStatus.PLAYING, restored.status)
    }

    @Test
    fun `a finished game is not exportable`() {
        val e = engine()
        assertNull("nothing to save before the game starts", e.exportState())
    }

    // ------------------------------------------------------------ rendering

    @Test
    fun `revision only moves when the board actually changes`() {
        val e = blankPlaying()
        e.debugSetFalling(2, 0, 3)
        val before = e.revision

        e.tick(0L)   // far too soon for a gravity step
        assertEquals("an idle tick must not invalidate the render", before, e.revision)

        e.moveTo(3)  // already in column 3
        assertEquals("a no-op move must not invalidate the render", before, e.revision)

        e.moveTo(1)
        assertTrue("a real move must invalidate the render", e.revision > before)
    }

    @Test
    fun `hud timers are quantised to whole seconds`() {
        val e = blankPlaying()
        e.useSlow(0L)
        val a = e.hudTimers(0L)
        val b = e.hudTimers(200L)
        assertEquals("sub-second jitter must not produce a new value", a, b)
        assertEquals(SLOW_DURATION_MS / 1000, a.slowActiveRemainingSec)
    }

    // ------------------------------------------------------------ plan power

    private fun planning(seed: Int = 1): GameEngine {
        val e = blankPlaying(seed)
        assertTrue(e.usePlan(0L))
        return e
    }

    @Test
    fun `plan suspends gravity for fifteen seconds`() {
        val e = blankPlaying()
        e.debugSetFalling(2, 0, 3)
        assertTrue(e.usePlan(0L))
        assertEquals(GameStatus.PLANNING, e.status)
        assertEquals(2, e.planBank.charges)

        e.tick(PLAN_DURATION_MS - 1)
        assertEquals("the tile must not fall during Plan", 0, e.falling?.row)
        assertEquals(GameStatus.PLANNING, e.status)

        e.tick(PLAN_DURATION_MS)
        assertEquals("gravity returns when the window closes", GameStatus.PLAYING, e.status)
    }

    @Test
    fun `plan refuses to fire with no charges and cannot stack`() {
        val e = blankPlaying()
        assertTrue(e.usePlan(0L))
        assertFalse("already planning", e.usePlan(10L))

        e.tick(PLAN_DURATION_MS)
        assertTrue(e.usePlan(PLAN_DURATION_MS))
        e.tick(PLAN_DURATION_MS * 2)
        assertTrue(e.usePlan(PLAN_DURATION_MS * 2))
        e.tick(PLAN_DURATION_MS * 3)
        assertFalse("fourth use has no charge", e.usePlan(PLAN_DURATION_MS * 3))
    }

    @Test
    fun `pausing out of plan ends it rather than freezing the timer`() {
        val e = planning()
        e.pause(100L)
        assertEquals(GameStatus.PAUSED, e.status)
        assertEquals("the Plan window must not survive a pause", 0L, e.planRemainingMs(100L))
    }

    @Test
    fun `a slide compacts a row toward the swipe and merges equal neighbours`() {
        val e = planning()
        e.debugPlace(4, 1, 4)
        e.debugPlace(4, 3, 4)
        e.debugPlace(4, 5, 8)

        assertTrue(e.slide(SlideDirection.LEFT, 100L))
        assertEquals("the two 4s combine", 8, e.debugAt(4, 0)?.value)
        assertEquals("the 8 packs in behind them", 8, e.debugAt(4, 1)?.value)
        assertNull(e.debugAt(4, 2))
        assertEquals(2, e.debugTileCount())
    }

    @Test
    fun `each tile merges at most once per slide`() {
        val e = planning()
        for (col in 0 until 4) e.debugPlace(4, col, 2)

        assertTrue(e.slide(SlideDirection.LEFT, 100L))
        assertEquals("four 2s become two 4s, not one 8", 4, e.debugAt(4, 0)?.value)
        assertEquals(4, e.debugAt(4, 1)?.value)
        assertEquals(2, e.debugTileCount())
    }

    @Test
    fun `sliding up leaves tiles floating because gravity is off`() {
        val e = planning()
        e.debugPlace(ROWS - 1, 2, 16)

        assertTrue(e.slide(SlideDirection.UP, 100L))
        assertEquals("the tile is now hanging at the ceiling", 16, e.debugAt(0, 2)?.value)
        assertTrue("floating is legal mid-Plan", e.debugFloatingTiles() > 0)
    }

    @Test
    fun `gravity reapplies and cascades when the plan window closes`() {
        val e = planning()
        // Two 8s parked in mid-air in the same column, with a gap beneath them.
        e.debugPlace(0, 2, 8)
        e.debugPlace(1, 2, 8)

        e.tick(PLAN_DURATION_MS)

        assertEquals(GameStatus.PLAYING, e.status)
        assertEquals("they should have fallen and merged", 16, e.debugAt(ROWS - 1, 2)?.value)
        assertEquals(0, e.debugFloatingTiles())
        assertEquals(0, e.debugUnmergedPairs())
    }

    @Test
    fun `filling the frozen tile's column during plan ends the run`() {
        val e = blankPlaying()
        e.debugSetFalling(2, 0, START_COL)
        assertTrue(e.usePlan(0L))
        // Every cell of the spawn column occupied, so the frozen tile has nowhere to go.
        for (row in 0 until ROWS) e.debugPlace(row, START_COL, if (row % 2 == 0) 2 else 8)

        e.tick(PLAN_DURATION_MS)
        assertEquals(GameStatus.GAME_OVER, e.status)
    }

    @Test
    fun `a slide never moves a locked trophy`() {
        val e = planning()
        e.debugPlace(ROWS - 1, 0, TROPHY_VALUE, locked = true)
        e.debugPlace(ROWS - 1, 4, 32)

        assertTrue(e.slide(SlideDirection.LEFT, 100L))
        assertTrue("the trophy stays put", e.debugAt(ROWS - 1, 0)?.locked == true)
        assertEquals("the loose tile packs against it", 32, e.debugAt(ROWS - 1, 1)?.value)
    }

    @Test
    fun `a slide that changes nothing reports no movement`() {
        val e = planning()
        e.debugPlace(4, 0, 2)
        e.debugPlace(4, 1, 8)
        assertFalse("already packed left with nothing to merge", e.slide(SlideDirection.LEFT, 100L))
    }

    @Test
    fun `sliding is rejected outside a plan window`() {
        val e = blankPlaying()
        e.debugPlace(4, 3, 2)
        assertFalse(e.slide(SlideDirection.LEFT, 100L))
        assertEquals("the tile must not have moved", 2, e.debugAt(4, 3)?.value)
    }

    @Test
    fun `slide merges pay the combo bonus`() {
        val e = planning()
        for (col in 0 until 4) e.debugPlace(4, col, 4)

        e.slide(SlideDirection.LEFT, 100L)
        // 8 at depth 1 (x1.0) then 8 at depth 2 (x1.5) = 8 + 12.
        assertEquals(20, e.score)
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
        assertEquals(TROPHY_VALUE, snap.nextTrophyValue)
        assertEquals(1.0, snap.scoreMultiplier, 1e-9)
        assertTrue("interpolation needs a positive step length", snap.stepDurationMs > 0)
        assertEquals(POWER_MAX_CHARGES, snap.planCharges)
    }
}
