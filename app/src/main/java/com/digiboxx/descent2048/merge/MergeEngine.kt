package com.digiboxx.descent2048.merge

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rules and physics for 2048 Merge.
 *
 * Same discipline as the Descent engine: no Android imports, no coroutines, no clock of
 * its own. [update] is handed the time. That is what lets the entire simulation — piles,
 * collisions, merges and all — run as plain JVM unit tests, and it is why the physics is
 * deterministic for a given seed.
 *
 * The solver is position-based: integrate, then push overlapping bodies apart over
 * several relaxation passes, then bleed off the energy that correction implies. It is not
 * the most physically faithful approach, but for a bowl full of touching circles it is
 * far stabler than an impulse solver, and stability is the entire game.
 */
class MergeEngine(private val rng: Random = Random.Default) {

    private val balls = ArrayList<Ball>(64)
    private var nextBallId = 1L
    private val queue = ArrayDeque<Int>()

    var score: Int = 0
        private set
    var bestValue: Int = 0
        private set
    var status: MergeStatus = MergeStatus.READY
        private set

    /** Bumped whenever the world changes, so the UI can skip rebuilding a snapshot. */
    var revision: Int = 0
        private set

    /** Horizontal aim position of the ball waiting to drop. */
    var aimX: Float = WORLD_WIDTH / 2f
        private set

    var lastComboDepth: Int = 0
        private set

    private var reachedTarget = false
    private var lastDropMs = 0L
    private var lastMergeMs = -COMBO_WINDOW_MS
    private var comboDepth = 0
    private var lastUpdateMs = 0L
    private var accumulatorMs = 0f

    private val pendingEvents = mutableListOf<MergeEvent>()

    val ballCount: Int get() = balls.size

    private fun markDirty() { revision++ }

    // ---------------------------------------------------------------- lifecycle

    fun start(nowMs: Long) {
        balls.clear()
        queue.clear()
        repeat(3) { queue.addLast(randomSpawnValue()) }
        score = 0
        bestValue = 0
        reachedTarget = false
        comboDepth = 0
        lastComboDepth = 0
        lastMergeMs = -COMBO_WINDOW_MS
        aimX = WORLD_WIDTH / 2f
        status = MergeStatus.PLAYING
        lastUpdateMs = nowMs
        lastDropMs = nowMs - DROP_COOLDOWN_MS
        accumulatorMs = 0f
        pendingEvents.clear()
        markDirty()
    }

    fun pause() {
        if (status != MergeStatus.PLAYING) return
        status = MergeStatus.PAUSED
        markDirty()
    }

    fun resume(nowMs: Long) {
        if (status != MergeStatus.PAUSED) return
        status = MergeStatus.PLAYING
        lastUpdateMs = nowMs
        accumulatorMs = 0f
        markDirty()
    }

    /** Re-anchor the clock after time away, so no catch-up burst is simulated. */
    fun resyncClock(nowMs: Long) {
        lastUpdateMs = nowMs
        accumulatorMs = 0f
    }

    private fun randomSpawnValue(): Int {
        val total = MERGE_SPAWN_TABLE.sumOf { it.second }
        var roll = rng.nextInt(total)
        for ((value, weight) in MERGE_SPAWN_TABLE) {
            if (roll < weight) return value
            roll -= weight
        }
        return MERGE_SPAWN_TABLE.first().first
    }

    // ---------------------------------------------------------------- input

    val heldValue: Int get() = queue.firstOrNull() ?: 2

    /** Aim the waiting ball, clamped so it cannot be released clipping through a wall. */
    fun aimAt(x: Float) {
        if (status != MergeStatus.PLAYING) return
        val r = radiusFor(heldValue)
        val clamped = x.coerceIn(r, WORLD_WIDTH - r)
        if (clamped != aimX) {
            aimX = clamped
            markDirty()
        }
    }

    fun canDrop(nowMs: Long): Boolean =
        status == MergeStatus.PLAYING && nowMs - lastDropMs >= DROP_COOLDOWN_MS

    fun drop(nowMs: Long): Boolean {
        if (!canDrop(nowMs)) return false
        val value = queue.removeFirst()
        queue.addLast(randomSpawnValue())
        val r = radiusFor(value)
        balls.add(
            Ball(
                id = nextBallId++,
                value = value,
                x = aimX.coerceIn(r, WORLD_WIDTH - r),
                y = DROP_Y
            )
        )
        if (value > bestValue) bestValue = value
        lastDropMs = nowMs
        pendingEvents += MergeEvent.Dropped(value)
        markDirty()
        return true
    }

    // ---------------------------------------------------------------- simulation

    /**
     * Advance the world to [nowMs].
     *
     * Real elapsed time is accumulated and spent in whole [FIXED_DT] substeps, so the
     * simulation behaves identically at 30fps and 120fps and can be driven at arbitrary
     * granularity from a test.
     */
    fun update(nowMs: Long): List<MergeEvent> {
        if (status != MergeStatus.PLAYING) {
            lastUpdateMs = nowMs
            return drainEvents()
        }

        val elapsed = (nowMs - lastUpdateMs).coerceIn(0L, MAX_CATCHUP_MS)
        lastUpdateMs = nowMs
        accumulatorMs += elapsed.toFloat()

        val stepMs = FIXED_DT * 1000f
        var steps = 0
        while (accumulatorMs >= stepMs && steps < 32) {
            accumulatorMs -= stepMs
            substep()
            // Merging is checked every substep, not once a frame. The solver settles a
            // contact at exactly rA + rB and turns that correction into outward velocity,
            // so a pair checked only at frame boundaries can be pushed back out of reach
            // before anyone looks — merges then fire or not depending on how many
            // substeps the frame happened to contain.
            resolveMerges(nowMs)
            steps++
        }
        if (steps > 0) {
            checkGameOver(nowMs, (stepMs * steps).toLong())
            markDirty()
        }
        return drainEvents()
    }

    /**
     * One fixed step of position-based dynamics.
     *
     * Integrate, let the constraints fight it out over several passes, then read the
     * velocity back off how far each ball actually ended up moving. Deriving velocity
     * this way rather than accumulating impulses means a constraint can never add energy
     * to the system, so a deep pile settles instead of slowly boiling.
     */
    private fun substep() {
        for (ball in balls) {
            ball.prevX = ball.x
            ball.prevY = ball.y
            ball.vy += GRAVITY * FIXED_DT
            ball.x += ball.vx * FIXED_DT
            ball.y += ball.vy * FIXED_DT
        }

        repeat(SOLVER_ITERATIONS) {
            separateBalls()
            constrainToBowl()
        }

        val inv = 1f / FIXED_DT
        for (ball in balls) {
            ball.vx = (ball.x - ball.prevX) * inv * LINEAR_DAMPING
            ball.vy = (ball.y - ball.prevY) * inv * LINEAR_DAMPING
            if (abs(ball.vx) < REST_EPSILON) ball.vx = 0f
            if (abs(ball.vy) < REST_EPSILON) ball.vy = 0f

            // Resolving a deep overlap turns penetration into velocity, which for a
            // badly overlapping pair can be enormous. Terminal velocity under gravity is
            // about 5.3, so anything past this came from a correction, not from falling.
            val speed = ball.speed
            if (speed > MAX_SPEED) {
                val scale = MAX_SPEED / speed
                ball.vx *= scale
                ball.vy *= scale
            }
        }
    }

    /**
     * Push overlapping pairs apart. Positional only — no velocity is touched here.
     *
     * Separation is split by area, so a 2 bounces off a 1024 rather than shoving it. The
     * O(n^2) scan is deliberate: the bowl holds a few dozen balls at most, and a spatial
     * hash would cost more in complexity than it saves in microseconds.
     */
    private fun separateBalls() {
        for (i in balls.indices) {
            val a = balls[i]
            for (j in i + 1 until balls.size) {
                val b = balls[j]
                var dx = b.x - a.x
                var dy = b.y - a.y
                val minDist = a.radius + b.radius
                val distSq = dx * dx + dy * dy
                if (distSq >= minDist * minDist) continue

                var dist = sqrt(distSq)
                if (dist < 1e-6f) {
                    // Exactly coincident centres have no normal to push along. Nudge them
                    // apart along a fixed axis rather than dividing by zero and spraying
                    // NaN through the whole pile.
                    dx = 1e-4f
                    dy = 0f
                    dist = 1e-4f
                }

                val nx = dx / dist
                val ny = dy / dist
                val overlap = minDist - dist

                val areaA = a.radius * a.radius
                val areaB = b.radius * b.radius
                val total = areaA + areaB
                val shareA = areaB / total
                val shareB = areaA / total

                a.x -= nx * overlap * shareA
                a.y -= ny * overlap * shareA
                b.x += nx * overlap * shareB
                b.y += ny * overlap * shareB
            }
        }
    }

    /**
     * Keep every ball inside the straight side walls and the curved floor.
     *
     * The floor test is just "stay within the bowl circle". Because the bowl's centre
     * sits above the play area this only bites along the bottom arc, and the correction
     * points toward the middle — which is precisely what rolls everything to the centre.
     */
    private fun constrainToBowl() {
        for (ball in balls) {
            if (ball.x - ball.radius < 0f) {
                ball.x = ball.radius
            } else if (ball.x + ball.radius > WORLD_WIDTH) {
                ball.x = WORLD_WIDTH - ball.radius
            }

            // Only the lower arc is floor. The bowl circle is closed, so applying it
            // everywhere puts an invisible ceiling across the top of the play area — a
            // pile would hit it and be squashed back down instead of overflowing, which
            // is exactly the situation the game is supposed to end on.
            if (ball.y <= Bowl.centerY) continue

            val dx = ball.x - Bowl.centerX
            val dy = ball.y - Bowl.centerY
            val dist = sqrt(dx * dx + dy * dy)
            val limit = Bowl.radius - ball.radius
            if (dist > limit && dist > 1e-6f) {
                ball.x = Bowl.centerX + dx / dist * limit
                ball.y = Bowl.centerY + dy / dist * limit
            }
        }
    }

    // ---------------------------------------------------------------- merging

    /**
     * Combine every touching pair of equal balls.
     *
     * A ball is consumed by at most one merge per pass, so three touching 4s make one 8
     * and leave a 4 rather than collapsing into something impossible. Whatever is left
     * touching merges on the next pass a frame later, which reads as a chain.
     */
    private fun resolveMerges(nowMs: Long) {
        var merged = false
        val consumed = HashSet<Long>()
        val created = ArrayList<Ball>()

        for (i in balls.indices) {
            val a = balls[i]
            if (a.id in consumed) continue
            for (j in i + 1 until balls.size) {
                val b = balls[j]
                if (b.id in consumed || b.value != a.value) continue
                val dx = b.x - a.x
                val dy = b.y - a.y
                val reach = a.radius + b.radius + MERGE_CONTACT_SLACK
                if (dx * dx + dy * dy > reach * reach) continue

                consumed.add(a.id)
                consumed.add(b.id)

                comboDepth = if (nowMs - lastMergeMs <= COMBO_WINDOW_MS) comboDepth + 1 else 1
                lastMergeMs = nowMs
                lastComboDepth = comboDepth

                val value = a.value * 2
                val multiplier = min(
                    1.0 + (comboDepth - 1) * COMBO_STEP,
                    MAX_COMBO_MULTIPLIER
                )
                score += (value * multiplier).toInt()
                if (value > bestValue) bestValue = value

                val ball = Ball(
                    id = nextBallId++,
                    value = value,
                    x = (a.x + b.x) / 2f,
                    y = (a.y + b.y) / 2f,
                    vx = (a.vx + b.vx) / 2f,
                    vy = (a.vy + b.vy) / 2f
                ).apply {
                    // The merged ball is bigger than either parent, so the midpoint can
                    // leave it sticking through a wall or the floor. Put it back before
                    // anything renders or reads it.
                    containAtBirth(this)
                    mergedAtMs = nowMs
                    // Inherit entry state, otherwise a merge high in the bowl would
                    // silently reset the ball's eligibility to end the run.
                    hasEnteredPlay = a.hasEnteredPlay || b.hasEnteredPlay
                }
                created.add(ball)
                pendingEvents += MergeEvent.Merged(value, ball.x, ball.y, comboDepth)

                if (value >= MERGE_TARGET && !reachedTarget) {
                    reachedTarget = true
                    pendingEvents += MergeEvent.TargetReached(value)
                }
                merged = true
                break
            }
        }

        if (merged) {
            balls.removeAll { it.id in consumed }
            balls.addAll(created)
            markDirty()
        } else if (nowMs - lastMergeMs > COMBO_WINDOW_MS) {
            comboDepth = 0
        }
    }

    /** Nudge a freshly merged ball fully inside the walls and the bowl. */
    private fun containAtBirth(ball: Ball) {
        ball.x = ball.x.coerceIn(ball.radius, WORLD_WIDTH - ball.radius)
        val dx = ball.x - Bowl.centerX
        val dy = ball.y - Bowl.centerY
        val dist = sqrt(dx * dx + dy * dy)
        val limit = Bowl.radius - ball.radius
        if (ball.y > Bowl.centerY && dist > limit && dist > 1e-6f) {
            ball.x = Bowl.centerX + dx / dist * limit
            ball.y = Bowl.centerY + dy / dist * limit
        }
        ball.prevX = ball.x
        ball.prevY = ball.y
    }

    // ---------------------------------------------------------------- losing

    /**
     * End the run when a settled ball sits above the line for [DEATH_GRACE_MS].
     *
     * The grace period is not generosity, it is correctness: a freshly dropped ball is
     * above the line by definition, and a big merge can throw one up there for a moment.
     * Only balls that have already been fully below the line — [Ball.hasEnteredPlay] —
     * can ever end the game.
     */
    private fun checkGameOver(nowMs: Long, elapsedMs: Long) {
        for (ball in balls) {
            if (!ball.hasEnteredPlay) {
                if (ball.y - ball.radius > DEATH_LINE_Y) ball.hasEnteredPlay = true
                continue
            }
            val aboveLine = ball.y - ball.radius < DEATH_LINE_Y
            if (aboveLine && ball.speed < SLEEP_SPEED) {
                ball.overLineMs += elapsedMs
                if (ball.overLineMs >= DEATH_GRACE_MS) {
                    status = MergeStatus.GAME_OVER
                    pendingEvents += MergeEvent.GameOver
                    markDirty()
                    return
                }
            } else if (!aboveLine) {
                ball.overLineMs = 0L
            }
        }
    }

    /** 0 when safe, 1 when the run is about to end. Drives the warning in the UI. */
    fun dangerFraction(): Float {
        var worst = 0f
        for (ball in balls) {
            if (!ball.hasEnteredPlay) continue
            worst = max(worst, ball.overLineMs.toFloat() / DEATH_GRACE_MS)
        }
        if (worst > 0f) return worst.coerceAtMost(1f)

        // Nothing over the line yet: fall back to how close the pile is to reaching it.
        var highest = WORLD_HEIGHT
        for (ball in balls) {
            if (ball.hasEnteredPlay) highest = min(highest, ball.y - ball.radius)
        }
        val runway = WORLD_HEIGHT * 0.45f
        val gap = (highest - DEATH_LINE_Y).coerceAtLeast(0f)
        return ((runway - gap) / runway).coerceIn(0f, 0.85f)
    }

    private fun drainEvents(): List<MergeEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        val out = pendingEvents.toList()
        pendingEvents.clear()
        return out
    }

    // ---------------------------------------------------------------- rendering

    fun snapshot(nowMs: Long): MergeSnapshot = MergeSnapshot(
        balls = balls.map {
            BallView(it.id, it.value, it.x, it.y, it.radius, it.mergedAtMs)
        },
        aimX = aimX,
        heldValue = heldValue,
        nextValues = queue.drop(1).take(2),
        canDrop = canDrop(nowMs),
        score = score,
        bestValue = bestValue,
        status = status,
        dangerFraction = dangerFraction(),
        lastComboDepth = lastComboDepth
    )

    // ---------------------------------------------------------------- test hooks

    internal fun debugAdd(value: Int, x: Float, y: Float, vx: Float = 0f, vy: Float = 0f): Ball {
        val ball = Ball(nextBallId++, value, x, y, vx, vy)
        balls.add(ball)
        markDirty()
        return ball
    }

    internal fun debugBalls(): List<Ball> = balls

    internal fun debugForcePlaying() {
        status = MergeStatus.PLAYING
    }

    internal fun debugClear() = balls.clear()

    /** True when every ball is inside the walls and the bowl, within a small tolerance. */
    internal fun debugAllContained(tolerance: Float = 0.004f): Boolean = balls.all { ball ->
        val withinWalls = ball.x >= ball.radius - tolerance &&
            ball.x <= WORLD_WIDTH - ball.radius + tolerance
        // Overflowing the top is legal — it is how the run ends — so only balls in the
        // lower half are checked against the floor arc.
        val withinFloor = if (ball.y <= Bowl.centerY) true else {
            val dx = ball.x - Bowl.centerX
            val dy = ball.y - Bowl.centerY
            sqrt(dx * dx + dy * dy) <= Bowl.radius - ball.radius + tolerance
        }
        withinWalls && withinFloor
    }

    /** Worst overlap between any two balls, as a fraction of their combined radii. */
    internal fun debugWorstOverlap(): Float {
        var worst = 0f
        for (i in balls.indices) {
            for (j in i + 1 until balls.size) {
                val a = balls[i]
                val b = balls[j]
                val reach = a.radius + b.radius
                val dist = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                if (dist < reach) worst = max(worst, (reach - dist) / reach)
            }
        }
        return worst
    }

    internal fun debugAnyNonFinite(): Boolean = balls.any {
        !it.x.isFinite() || !it.y.isFinite() || !it.vx.isFinite() || !it.vy.isFinite()
    }

    internal fun debugMaxSpeed(): Float = balls.maxOfOrNull { it.speed } ?: 0f

    internal fun debugSettled(): Boolean = balls.all { abs(it.speed) < SLEEP_SPEED }
}
