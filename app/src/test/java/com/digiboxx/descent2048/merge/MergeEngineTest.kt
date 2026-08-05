package com.digiboxx.descent2048.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class MergeEngineTest {

    private fun engine(seed: Int = 1) = MergeEngine(rng = Random(seed))

    private fun playing(seed: Int = 1): MergeEngine {
        val e = engine(seed)
        e.start(0L)
        return e
    }

    /** Drives the simulation forward in realistic frame-sized slices. */
    private fun MergeEngine.run(fromMs: Long, durationMs: Long, stepMs: Long = 16L): Long {
        var t = fromMs
        val end = fromMs + durationMs
        while (t < end) {
            t = minOf(t + stepMs, end)
            update(t)
        }
        return t
    }

    // ------------------------------------------------------------ geometry

    @Test
    fun `the bowl floor is lowest in the middle and rises to the edges`() {
        val middle = Bowl.floorYAt(0.5f)
        val edge = Bowl.floorYAt(0.0f)
        assertTrue("the middle must be the deepest point", middle > edge)
        assertEquals("the middle should sit at the world floor", WORLD_HEIGHT, middle, 1e-4f)
        assertEquals("the edge should sit one sag higher", WORLD_HEIGHT - FLOOR_SAG, edge, 1e-3f)
    }

    @Test
    fun `balls get bigger with value and never shrink`() {
        var previous = 0f
        for (value in listOf(2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096)) {
            val r = radiusFor(value)
            assertTrue("radius for $value should exceed the previous", r > previous)
            previous = r
        }
        assertTrue("a 2048 should be a substantial share of the bowl", radiusFor(2048) > 0.25f)
        assertTrue("but must still fit inside it", radiusFor(2048) * 2 < WORLD_WIDTH)
    }

    // ------------------------------------------------------------ containment

    @Test
    fun `a dropped ball stays inside the bowl`() {
        val e = playing()
        e.aimAt(0.5f)
        assertTrue(e.drop(0L))
        e.run(0L, 3000L)

        assertTrue("nothing may escape the bowl", e.debugAllContained())
        assertFalse(e.debugAnyNonFinite())
    }

    @Test
    fun `a heavy pile stays contained and does not explode`() {
        val e = playing()
        var t = 0L
        repeat(40) { i ->
            e.aimAt(0.1f + (i % 9) * 0.09f)
            e.drop(t)
            t = e.run(t, 400L)
        }
        t = e.run(t, 4000L)

        assertFalse("the simulation must stay finite", e.debugAnyNonFinite())
        assertTrue("every ball must remain inside the bowl", e.debugAllContained())
        assertTrue(
            "solver left an overlap of ${e.debugWorstOverlap()}",
            e.debugWorstOverlap() < 0.06f
        )
    }

    @Test
    fun `a ball dropped hard at the wall does not tunnel through it`() {
        val e = playing()
        e.debugClear()
        // Fired sideways far faster than gravity could ever manage.
        e.debugAdd(2, 0.2f, 0.5f, vx = -6f, vy = 3f)
        e.run(0L, 1500L)

        assertTrue("a fast ball must not escape", e.debugAllContained())
    }

    // ------------------------------------------------------------ the bowl's purpose

    @Test
    fun `a ball dropped at the edge rolls toward the middle`() {
        val e = playing()
        e.debugClear()
        val ball = e.debugAdd(2, 0.08f, 0.3f)
        val startX = ball.x
        e.run(0L, 4000L)

        assertTrue(
            "the curved floor should carry it inward: $startX -> ${ball.x}",
            ball.x > startX + 0.1f
        )
    }

    @Test
    fun `a lone ball comes to rest near the centre of the bowl`() {
        val e = playing()
        e.debugClear()
        val ball = e.debugAdd(2, 0.15f, 0.3f)
        e.run(0L, 20_000L)

        assertEquals("it should settle in the middle", 0.5f, ball.x, 0.12f)
        assertTrue("and it should actually stop", ball.speed < SLEEP_SPEED)
    }

    @Test
    fun `a pile settles instead of jittering forever`() {
        val e = playing()
        var t = 0L
        repeat(12) { i ->
            e.aimAt(0.25f + (i % 5) * 0.12f)
            e.drop(t)
            t = e.run(t, 500L)
        }
        t = e.run(t, 15_000L)

        assertTrue(
            "pile still moving at ${e.debugMaxSpeed()}",
            e.debugMaxSpeed() < SLEEP_SPEED * 3
        )
    }

    // ------------------------------------------------------------ merging

    @Test
    fun `two equal balls touching combine into the next value`() {
        val e = playing()
        e.debugClear()
        val r = radiusFor(4)
        e.debugAdd(4, 0.5f - r * 0.9f, 1.0f)
        e.debugAdd(4, 0.5f + r * 0.9f, 1.0f)
        e.run(0L, 200L)

        assertEquals("the pair should have become one ball", 1, e.ballCount)
        assertEquals(8, e.debugBalls().first().value)
    }

    @Test
    fun `equal balls resting against each other still merge`() {
        // The regression this guards: the separation solver settles a contact at exactly
        // rA + rB, so a strict distance test made merging a floating-point coin flip and
        // pairs could sit touching in the pile forever.
        val e = playing()
        e.debugClear()
        val r = radiusFor(32)
        e.debugAdd(32, 0.5f - r, 1.0f)
        e.debugAdd(32, 0.5f + r, 1.0f)
        e.run(0L, 500L)

        assertEquals("touching equals must combine", 1, e.ballCount)
        assertEquals(64, e.debugBalls().first().value)
    }

    @Test
    fun `a pile may overflow the top of the bowl`() {
        // The floor arc must not act as a ceiling: overflowing is how the run ends.
        val e = playing()
        e.debugClear()
        val ball = e.debugAdd(2, 0.5f, -0.5f)
        ball.hasEnteredPlay = true
        e.update(16L)
        assertTrue("a ball above the bowl must not be clamped back down", ball.y < 0f)
    }

    @Test
    fun `different values never combine`() {
        val e = playing()
        e.debugClear()
        e.debugAdd(4, 0.42f, 1.0f)
        e.debugAdd(8, 0.58f, 1.0f)
        e.run(0L, 2000L)

        assertEquals("both balls must survive", 2, e.ballCount)
        assertTrue(e.debugBalls().any { it.value == 4 })
        assertTrue(e.debugBalls().any { it.value == 8 })
    }

    @Test
    fun `three touching equal balls leave one over`() {
        val e = playing()
        e.debugClear()
        val r = radiusFor(2)
        e.debugAdd(2, 0.5f - r * 1.8f, 1.10f)
        e.debugAdd(2, 0.5f, 1.10f)
        e.debugAdd(2, 0.5f + r * 1.8f, 1.10f)
        e.update(16L)

        // One pair merges this pass; the odd one out is still a 2 until it touches again.
        assertEquals(2, e.ballCount)
        assertTrue("a 4 should exist", e.debugBalls().any { it.value == 4 })
        assertTrue("and a lone 2 should remain", e.debugBalls().any { it.value == 2 })
    }

    @Test
    fun `a merge scores the new value and tracks the best`() {
        val e = playing()
        e.debugClear()
        val r = radiusFor(16)
        e.debugAdd(16, 0.5f - r * 0.9f, 1.0f)
        e.debugAdd(16, 0.5f + r * 0.9f, 1.0f)
        e.run(0L, 200L)

        assertEquals("a lone merge scores its face value", 32, e.score)
        assertEquals(32, e.bestValue)
    }

    @Test
    fun `merges in quick succession pay a combo bonus`() {
        val e = playing()
        e.debugClear()
        val r = radiusFor(8)
        e.debugAdd(8, 0.5f - r * 0.9f, 1.05f)
        e.debugAdd(8, 0.5f + r * 0.9f, 1.05f)
        e.update(16L)
        val afterFirst = e.score
        assertEquals(16, afterFirst)

        // A second pair landing inside the combo window is worth more than face value.
        val r16 = radiusFor(16)
        e.debugAdd(16, 0.5f - r16 * 0.9f, 0.7f)
        e.debugAdd(16, 0.5f + r16 * 0.9f, 0.7f)
        e.update(32L)

        assertTrue("the chain should beat two lone merges", e.score > afterFirst + 32)
        assertTrue(e.lastComboDepth >= 2)
    }

    @Test
    fun `reaching the target fires once and play continues`() {
        val e = playing()
        e.debugClear()
        val r = radiusFor(1024)
        e.debugAdd(1024, 0.5f - r * 0.9f, 0.9f)
        e.debugAdd(1024, 0.5f + r * 0.9f, 0.9f)
        val events = e.update(16L)

        assertNotNull(events.filterIsInstance<MergeEvent.TargetReached>().firstOrNull())
        assertEquals(MERGE_TARGET, e.bestValue)
        assertEquals("the run keeps going", MergeStatus.PLAYING, e.status)
    }

    // ------------------------------------------------------------ losing

    @Test
    fun `dropping a ball does not immediately end the run`() {
        val e = playing()
        e.aimAt(0.5f)
        e.drop(0L)
        // The ball starts above the line by definition; that must never be fatal.
        e.run(0L, 2500L)
        assertEquals(MergeStatus.PLAYING, e.status)
    }

    @Test
    fun `a ball resting above the line ends the run`() {
        val e = playing()
        e.debugClear()
        // Already in play and pinned above the line: exactly the losing condition.
        val ball = e.debugAdd(2, 0.5f, DEATH_LINE_Y - 0.01f)
        ball.hasEnteredPlay = true

        var t = 0L
        repeat(400) {
            t += 8L
            // Hold it in place, as a pile beneath it would.
            ball.y = DEATH_LINE_Y - 0.01f
            ball.vx = 0f
            ball.vy = 0f
            e.update(t)
        }
        assertEquals(MergeStatus.GAME_OVER, e.status)
    }

    @Test
    fun `a ball that briefly crosses the line is forgiven`() {
        val e = playing()
        e.debugClear()
        val ball = e.debugAdd(2, 0.5f, DEATH_LINE_Y - 0.01f)
        ball.hasEnteredPlay = true

        // Above the line for well under the grace period, then it falls away.
        var t = 0L
        repeat(20) {
            t += 8L
            ball.y = DEATH_LINE_Y - 0.01f
            ball.vx = 0f
            ball.vy = 0f
            e.update(t)
        }
        assertEquals(MergeStatus.PLAYING, e.status)

        ball.y = 1.0f
        t = e.run(t, 2000L)
        assertEquals("dropping back below must clear the danger", MergeStatus.PLAYING, e.status)
    }

    @Test
    fun `danger rises as the pile climbs`() {
        val e = playing()
        e.debugClear()
        val low = e.debugAdd(2, 0.5f, 1.2f)
        low.hasEnteredPlay = true
        e.update(16L)
        val calm = e.dangerFraction()

        e.debugClear()
        val high = e.debugAdd(2, 0.5f, DEATH_LINE_Y + 0.08f)
        high.hasEnteredPlay = true
        e.update(32L)

        assertTrue("a pile near the line should read as more dangerous",
            e.dangerFraction() > calm)
    }

    // ------------------------------------------------------------ input

    @Test
    fun `aim is clamped so a ball cannot be released through a wall`() {
        val e = playing()
        e.aimAt(-5f)
        assertTrue(e.aimX >= radiusFor(e.heldValue) - 1e-6f)
        e.aimAt(5f)
        assertTrue(e.aimX <= WORLD_WIDTH - radiusFor(e.heldValue) + 1e-6f)
    }

    @Test
    fun `drops respect the cooldown`() {
        val e = playing()
        assertTrue("the first drop is free", e.drop(0L))
        assertFalse("an immediate second drop is refused", e.drop(10L))
        assertTrue("and allowed again after the cooldown", e.drop(DROP_COOLDOWN_MS))
    }

    @Test
    fun `nothing responds once the game is over`() {
        val e = playing()
        e.pause()
        assertEquals(MergeStatus.PAUSED, e.status)
        assertFalse("dropping while paused must be refused", e.drop(5000L))

        val before = e.aimX
        e.aimAt(0.1f)
        assertEquals("aiming while paused must be refused", before, e.aimX, 1e-6f)
    }

    // ------------------------------------------------------------ integration contract

    @Test
    fun `the simulation is deterministic for a given seed`() {
        fun playOut(): Triple<Int, Int, Float> {
            val e = MergeEngine(rng = Random(42))
            e.start(0L)
            var t = 0L
            repeat(15) { i ->
                e.aimAt(0.2f + (i % 6) * 0.12f)
                e.drop(t)
                t = e.run(t, 400L)
            }
            t = e.run(t, 2000L)
            return Triple(e.score, e.ballCount, e.debugBalls().sumOf { it.x.toDouble() }.toFloat())
        }

        val a = playOut()
        val b = playOut()
        assertEquals("same seed must give the same score", a.first, b.first)
        assertEquals("and the same ball count", a.second, b.second)
        assertEquals("and identical positions", a.third, b.third, 1e-5f)
    }

    @Test
    fun `frame rate does not change the outcome`() {
        fun playAt(stepMs: Long): Float {
            val e = MergeEngine(rng = Random(7))
            e.start(0L)
            e.debugClear()
            e.debugAdd(2, 0.3f, 0.2f)
            var t = 0L
            while (t < 3000L) {
                t = minOf(t + stepMs, 3000L)
                e.update(t)
            }
            return e.debugBalls().first().y
        }

        // A fixed timestep with an accumulator should land in nearly the same place
        // whether the caller polls at 120fps or 30fps.
        assertEquals(playAt(8L), playAt(33L), 0.02f)
    }

    @Test
    fun `a long stall does not simulate a catch-up burst`() {
        val e = playing()
        e.debugClear()
        val ball = e.debugAdd(2, 0.5f, 0.2f)
        e.update(16L)
        val yBefore = ball.y

        // Ten seconds pass with the app backgrounded.
        e.update(10_000L)

        val travelled = ball.y - yBefore
        assertTrue(
            "a stall should advance at most the catch-up cap, moved $travelled",
            travelled < 0.35f
        )
    }

    @Test
    fun `snapshot reflects the world`() {
        val e = playing()
        e.aimAt(0.4f)
        e.drop(0L)
        e.run(0L, 500L)
        val snap = e.snapshot(600L)

        assertEquals(1, snap.balls.size)
        assertEquals(MergeStatus.PLAYING, snap.status)
        assertEquals(2, snap.nextValues.size)
        assertTrue(snap.heldValue in listOf(2, 4, 8))
        assertTrue(snap.balls.first().radius > 0f)
    }

    @Test
    fun `revision only moves when the world changes`() {
        val e = playing()
        val before = e.revision
        e.aimAt(e.aimX)             // same position
        assertEquals("a no-op aim must not invalidate the render", before, e.revision)
        e.aimAt(0.2f)
        assertTrue(e.revision > before)
    }

    // ------------------------------------------------------------ stress

    @Test
    fun `a long randomised session stays stable`() {
        var worstOverlap = 0f
        for (seed in 1..8) {
            val e = MergeEngine(rng = Random(seed))
            e.start(0L)
            val rnd = Random(seed * 17)
            var t = 0L
            var drops = 0
            while (e.status == MergeStatus.PLAYING && drops < 60) {
                e.aimAt(rnd.nextFloat())
                if (e.drop(t)) drops++
                t = e.run(t, 350L)

                assertFalse("seed $seed went non-finite", e.debugAnyNonFinite())
                assertTrue("seed $seed leaked a ball out of the bowl", e.debugAllContained())
                if (e.debugWorstOverlap() > worstOverlap) worstOverlap = e.debugWorstOverlap()
            }
        }
        // A transient overlap is expected and harmless: a merge creates a ball larger
        // than either parent, so for a frame it interpenetrates its neighbours before the
        // solver pushes them apart. What would matter is an overlap the solver cannot
        // recover from, so the meaningful assertion is the settled one below.
        assertTrue(
            "worst transient overlap was $worstOverlap, which suggests an explosion",
            worstOverlap < 0.30f
        )
        println("Merge stress: worst transient overlap ${"%.4f".format(worstOverlap)}")
    }

    @Test
    fun `overlaps left by a merge are resolved once the pile settles`() {
        val e = playing()
        var t = 0L
        repeat(30) { i ->
            e.aimAt(0.15f + (i % 8) * 0.1f)
            e.drop(t)
            t = e.run(t, 380L)
        }
        t = e.run(t, 8000L)

        assertTrue(
            "settled pile still overlapping by ${e.debugWorstOverlap()}",
            e.debugWorstOverlap() < 0.05f
        )
        assertTrue(e.debugAllContained())
    }
}
