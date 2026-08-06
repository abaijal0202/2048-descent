package com.digiboxx.descent2048.merge

import kotlin.math.sqrt

/**
 * 2048 Merge — the physics variant.
 *
 * Numbered balls drop into a bowl with a curved floor, roll toward the middle, and
 * combine when two equal ones touch. The ball gets bigger with every doubling, so the
 * bowl fills up faster than the numbers climb. Let the pile crest the top line and the
 * run is over.
 *
 * ## Coordinates
 *
 * Everything is in a normalised world one unit wide. The UI multiplies by the board's
 * pixel width to draw. That keeps every constant in here resolution independent — a
 * radius of 0.055 is 5.5% of the bowl's width on a tablet and on a cheap phone alike.
 *
 * y increases downward, matching screen space, so gravity is positive.
 */

const val WORLD_WIDTH = 1.0f
const val WORLD_HEIGHT = 1.30f

/**
 * How much lower the middle of the floor sits than its edges.
 *
 * This is the whole character of the game: a flat floor lets balls pile up in columns
 * wherever they land, while a bowl rolls everything toward the middle so the pile
 * self-organises and contact is constant. Larger values make it steeper and easier.
 */
const val FLOOR_SAG = 0.17f

/** World units per second squared. Tuned so a ball falls the full height in ~0.9s. */
const val GRAVITY = 3.2f

/**
 * Velocity retained per substep.
 *
 * Applied once per substep at the velocity-update stage, never inside the relaxation
 * loop. Damping inside the loop compounds by the iteration count — at 8 iterations even a
 * gentle-looking 0.16 removes three quarters of the tangential velocity every substep,
 * which pins every ball where it lands and kills the rolling the bowl exists to create.
 *
 * 0.995 per substep is about 0.55 per second: enough that a pile stops swilling around
 * within a couple of seconds, little enough that a ball still runs down the slope. It
 * also implies a terminal velocity near 5.3 world units per second, so a drop from the
 * top still takes well under a second.
 */
const val LINEAR_DAMPING = 0.995f

/**
 * Hard ceiling on ball speed.
 *
 * Free fall tops out near 5.3 world units per second, so this only ever bites when
 * de-penetration has converted a deep overlap into velocity. Without it a badly
 * overlapping pair can be flung hard enough to disturb the whole bowl.
 */
const val MAX_SPEED = 8.0f

/**
 * Velocities below this are snapped to zero.
 *
 * Position-based solvers leave a residue of micro-velocity in a resting stack, which
 * reads as a permanently shivering pile. This is the cheapest fix.
 */
const val REST_EPSILON = 0.004f

/**
 * The physics runs at a fixed 120Hz regardless of frame rate.
 *
 * Variable timesteps make a stacking simulation both non-deterministic and unstable —
 * one long frame drives balls into each other far enough that the solver cannot recover
 * and the pile explodes. A fixed step also means the unit tests exercise exactly the
 * arithmetic the device will.
 */
const val FIXED_DT = 1f / 120f

/** Relaxation passes per substep. More is stabler and costs linearly. */
const val SOLVER_ITERATIONS = 8

/**
 * Never simulate more than this much wall-clock time in one update.
 *
 * Without a cap, returning from the background asks for thousands of substeps at once
 * and the app freezes. Time is simply dropped instead.
 */
const val MAX_CATCHUP_MS = 250L

/** Below this speed a ball is considered at rest, for settling and game-over checks. */
const val SLEEP_SPEED = 0.035f

/** Balls resting above this line lose the game. */
const val DEATH_LINE_Y = 0.13f

/** How long a settled ball may sit above the line before the run ends. */
const val DEATH_GRACE_MS = 1200L

/** Height the next ball hangs at while being aimed. */
const val DROP_Y = 0.055f

/** Minimum gap between drops, so the button cannot be spammed into a tower. */
const val DROP_COOLDOWN_MS = 320L

/**
 * Extra reach allowed when testing whether two equal balls are touching.
 *
 * Without it, resting balls may never merge. The separation solver settles a contact at
 * exactly `dist == rA + rB`, so a strict comparison becomes a floating-point coin flip
 * and two equal balls can sit against each other in the pile forever. This slack makes
 * resting contact merge reliably.
 */
const val MERGE_CONTACT_SLACK = 0.006f

/** Merges landing within this window of each other chain into a combo. */
const val COMBO_WINDOW_MS = 450L

const val COMBO_STEP = 0.5
const val MAX_COMBO_MULTIPLIER = 4.0

/** Balls lifted out of the bowl by a rewarded continue, taken from the top. */
const val MERGE_REVIVE_BALLS = 6

/** The value that counts as beating the game. Play continues past it. */
const val MERGE_TARGET = 2048

/**
 * Ball radius per value, as a fraction of bowl width.
 *
 * Hand-tuned rather than derived. The tempting rule — double the area each merge, so
 * radius scales by sqrt(2) — compounds to roughly 45x across the ladder, which no phone
 * screen can hold. These grow about 16% a step, so a 2048 ends up a little over half the
 * bowl wide: big enough to feel like an achievement, small enough to still be playable.
 */
val BALL_RADII: Map<Int, Float> = mapOf(
    2 to 0.055f,
    4 to 0.070f,
    8 to 0.088f,
    16 to 0.108f,
    32 to 0.130f,
    64 to 0.152f,
    128 to 0.175f,
    256 to 0.198f,
    512 to 0.222f,
    1024 to 0.247f,
    2048 to 0.273f
)

private val LARGEST_TABLED = BALL_RADII.keys.max()

/** Radius for any value, extrapolating gently past the table for 4096 and beyond. */
fun radiusFor(value: Int): Float {
    BALL_RADII[value]?.let { return it }
    var radius = BALL_RADII.getValue(LARGEST_TABLED)
    var v = LARGEST_TABLED
    while (v < value) {
        v *= 2
        radius *= 1.08f
    }
    return radius
}

/** Only the small values ever drop; everything bigger has to be earned. */
val MERGE_SPAWN_TABLE: List<Pair<Int, Int>> = listOf(2 to 50, 4 to 32, 8 to 18)

/**
 * The bowl's floor, as an arc of a large circle whose centre sits above the play area.
 *
 * Modelling the floor as a circle rather than a parabola makes containment a one-line
 * test — a ball is inside when it is within [radius] of [centerY] — with no iterative
 * search for the nearest point on the curve, and it is exactly stable under the solver.
 */
object Bowl {
    private const val HALF_WIDTH = WORLD_WIDTH / 2f

    /** Radius of the circle whose lower arc forms the floor. */
    val radius: Float = (HALF_WIDTH * HALF_WIDTH + FLOOR_SAG * FLOOR_SAG) / (2f * FLOOR_SAG)

    val centerX: Float = HALF_WIDTH
    val centerY: Float = WORLD_HEIGHT - radius

    /** Floor height at a given x, for drawing. */
    fun floorYAt(x: Float): Float {
        val dx = x - centerX
        val inside = radius * radius - dx * dx
        return centerY + sqrt(inside.coerceAtLeast(0f))
    }
}

/** One ball in the bowl. Mutable because the solver rewrites these every substep. */
class Ball(
    val id: Long,
    var value: Int,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f
) {
    var radius: Float = radiusFor(value)

    /**
     * Position at the start of the current substep.
     *
     * The solver derives velocity from how far a ball actually moved once every
     * constraint had its say, rather than tracking impulses. Collision response then
     * falls out for free and cannot inject energy, which is what keeps a deep pile from
     * slowly exploding.
     */
    var prevX: Float = x
    var prevY: Float = y

    /** When this ball was last created by a merge; drives the pop animation. */
    var mergedAtMs: Long = 0L

    /**
     * Set once the ball has been fully below the death line.
     *
     * Every ball starts above the line — that is where they are dropped from. Without
     * this flag the game would end the instant you released the first one.
     */
    var hasEnteredPlay: Boolean = false

    /** How long this ball has been resting above the death line. */
    var overLineMs: Long = 0L

    val speed: Float get() = sqrt(vx * vx + vy * vy)
}

enum class MergeStatus { READY, PLAYING, PAUSED, GAME_OVER }

sealed interface MergeEvent {
    data class Merged(val value: Int, val x: Float, val y: Float, val comboDepth: Int) : MergeEvent
    data class Dropped(val value: Int) : MergeEvent
    data class TargetReached(val value: Int) : MergeEvent
    data object GameOver : MergeEvent
}

/** Immutable render model, rebuilt only when the world actually changed. */
data class MergeSnapshot(
    val balls: List<BallView>,
    val aimX: Float,
    val heldValue: Int,
    val nextValues: List<Int>,
    val canDrop: Boolean,
    val score: Int,
    val bestValue: Int,
    val status: MergeStatus,
    /** 0..1, how close the tallest settled ball is to ending the run. */
    val dangerFraction: Float,
    val lastComboDepth: Int
)

data class BallView(
    val id: Long,
    val value: Int,
    val x: Float,
    val y: Float,
    val radius: Float,
    val mergedAtMs: Long
)
